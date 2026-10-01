package io.github.matiyaaa.fuse.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.matiyaaa.fuse.desktop.input.GamepadSink
import io.github.matiyaaa.fuse.desktop.input.LinuxGamepads
import io.github.matiyaaa.fuse.desktop.platform.DesktopPlatformUi
import io.github.matiyaaa.fuse.desktop.platform.WindowActions
import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.link.PhoneLinkServer
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputRouter
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

/** Where startup is. */
sealed interface StartState {
    data object Loading : StartState
    data class Ready(val store: FuseStore, val services: DesktopFuseServices, val phoneLink: PhoneLinkServer? = null) : StartState
    data class Failed(val message: String) : StartState
}

/**
 * Everything that lives as long as the process: input routing, controllers, platform services and
 * the window's mode. The Compose window reads its state from here, so a window rebuilt for a
 * decoration change keeps the same store and input.
 */
class DesktopSession(
    val dirs: FuseDirs,
    val args: List<String>,
    val scope: CoroutineScope,
) : WindowActions {
    private val prefs = WindowPrefs(File(dirs.config, "window.properties"))

    /** Routes keyboard and controller input. Only touched on the UI (AWT event) thread. */
    val router = InputRouter(scope)

    var startState: StartState by mutableStateOf(StartState.Loading)
        private set

    /** Base mode (borderless or windowed) and whether fullscreen is on top of it. */
    var baseMode: WindowMode by mutableStateOf(WindowMode.BORDERLESS)
        private set
    var fullscreen: Boolean by mutableStateOf(true)
        private set

    /** True while the pointer should be hidden (controller or keyboard in use, mouse idle). */
    var pointerHidden: Boolean by mutableStateOf(false)

    @Volatile var window: ComposeWindow? = null
    var windowState: WindowState? = null
    var exit: () -> Unit = {}

    /** Input only reaches Fuse while its window has focus (never while a game runs in front). */
    @Volatile var focused: Boolean = false
        private set

    val platform = DesktopPlatformUi(dirs, scope, this, args)
    private val gamepads = LinuxGamepads(GamepadBridge())
    private val closed = AtomicBoolean(false)

    init {
        val saved = prefs.load()
        val flag = WindowMode.fromArgs(args)
        baseMode = saved?.base ?: WindowMode.BORDERLESS
        val start = flag ?: saved?.mode ?: WindowMode.FULLSCREEN
        if (start != WindowMode.FULLSCREEN) baseMode = start
        fullscreen = start == WindowMode.FULLSCREEN
        pointerHidden = fullscreen
    }

    val placement: WindowPlacement
        get() = when {
            fullscreen -> WindowPlacement.Fullscreen
            baseMode == WindowMode.BORDERLESS -> WindowPlacement.Maximized
            else -> WindowPlacement.Floating
        }

    val decorated: Boolean get() = baseMode == WindowMode.WINDOWED

    val windowMode: WindowMode get() = if (fullscreen) WindowMode.FULLSCREEN else baseMode

    /** Builds the services and the store off the UI thread; the splash shows until then. */
    fun start() {
        gamepads.start()
        scope.launch {
            try {
                val services = withContext(Dispatchers.IO) { DesktopFuseServices.create(dirs, scope) }
                services.launcherHooks.onGameExited = { EventQueue.invokeLater { bringToFront() } }
                val store = createFuseStore(services, scope)
                startState = StartState.Ready(store, services, startPhoneLink(store, services))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.warn("startup failed", e)
                startState = StartState.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
    }

    /** Phone Link follows its switch in Settings; a failure here never stops Fuse from starting. */
    private fun startPhoneLink(store: FuseStore, services: DesktopFuseServices): PhoneLinkServer? = try {
        val name = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Fuse"
        // Off the UI thread: requests and address checks never wait on the window.
        PhoneLinkServer(store, services.secrets, scope + Dispatchers.Default, name, BuildInfo.VERSION).also { it.start() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.warn("Phone Link could not start", e)
        null
    }

    fun toggleFullscreen() = setWindowMode(if (fullscreen) baseMode else WindowMode.FULLSCREEN)

    /**
     * Switches the window mode and remembers it. Between fullscreen and the base mode this happens in
     * place; changing the base (borderless or windowed) rebuilds the window, since decorations can't
     * change on a visible window.
     */
    fun setWindowMode(mode: WindowMode) {
        if (mode == WindowMode.FULLSCREEN) {
            fullscreen = true
        } else {
            fullscreen = false
            baseMode = mode
        }
        windowState?.placement = placement
        prefs.save(WindowPrefs.Saved(windowMode, baseMode))
    }

    /** Called when the window manager changed the placement itself (for example un-maximizing). */
    fun onPlacementChanged(p: WindowPlacement) {
        val nowFullscreen = p == WindowPlacement.Fullscreen
        if (nowFullscreen != fullscreen) fullscreen = nowFullscreen
    }

    fun onFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        val store = (startState as? StartState.Ready)?.store
        if (hasFocus) {
            platform.onForeground()
            store?.library?.onResume()
        } else {
            router.releaseAll()
            store?.library?.onPause()
        }
    }

    private fun bringToFront() {
        val w = window ?: return
        if (w.extendedState and java.awt.Frame.ICONIFIED != 0) w.extendedState = w.extendedState and java.awt.Frame.ICONIFIED.inv()
        w.toFront()
        w.requestFocus()
    }

    // WindowActions / DialogHost

    override val parent: java.awt.Window? get() = window

    override suspend fun <T> withDialog(block: suspend () -> T): T {
        val wasFullscreen = withContext(Dispatchers.Main) {
            val was = fullscreen
            if (was) {
                fullscreen = false
                windowState?.placement = placement
            }
            was
        }
        try {
            return block()
        } finally {
            if (wasFullscreen) {
                withContext(NonCancellable + Dispatchers.Main) {
                    fullscreen = true
                    windowState?.placement = placement
                    bringToFront()
                }
            }
        }
    }

    override fun exitApplication() {
        EventQueue.invokeLater { exit() }
    }

    override val currentWindowMode: WindowMode get() = windowMode

    override fun changeWindowMode(mode: WindowMode) {
        if (EventQueue.isDispatchThread()) setWindowMode(mode) else EventQueue.invokeLater { setWindowMode(mode) }
    }

    /** Releases devices, the database and the HTTP client. Safe to call twice. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        gamepads.close()
        platform.close()
        (startState as? StartState.Ready)?.phoneLink?.close()
        (startState as? StartState.Ready)?.services?.close()
    }

    /** Hands controller input to the router on the UI thread, only while Fuse has focus. */
    private inner class GamepadBridge : GamepadSink {
        private val latestStick = AtomicReference(0f to 0f)
        private val stickPosted = AtomicBoolean(false)

        override fun press(button: PadButton) = EventQueue.invokeLater {
            if (!focused) return@invokeLater
            pointerHidden = true
            router.press(button, InputSource.GAMEPAD)
        }

        override fun release(button: PadButton) = EventQueue.invokeLater { router.release(button, InputSource.GAMEPAD) }

        override fun stick(x: Float, y: Float) {
            latestStick.set(x to y)
            // Coalesce: many axis events per frame become one router update.
            if (!stickPosted.compareAndSet(false, true)) return
            EventQueue.invokeLater {
                stickPosted.set(false)
                val (sx, sy) = latestStick.get()
                if (focused || (sx == 0f && sy == 0f)) router.stick(sx, sy, InputSource.GAMEPAD)
            }
        }

        override fun trigger(button: PadButton, value: Float) = EventQueue.invokeLater {
            if (focused || value <= 0f) router.trigger(button, value, InputSource.GAMEPAD)
        }
    }
}
