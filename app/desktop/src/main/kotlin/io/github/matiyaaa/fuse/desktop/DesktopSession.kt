package io.github.matiyaaa.fuse.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.matiyaaa.fuse.desktop.input.GamepadSink
import io.github.matiyaaa.fuse.desktop.input.Gamepads
import io.github.matiyaaa.fuse.desktop.platform.DesktopPlatformUi
import io.github.matiyaaa.fuse.desktop.platform.WindowActions
import io.github.matiyaaa.fuse.desktop.services.DesktopFuseServices
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
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
    data class Ready(
        val store: FuseStore,
        val services: DesktopFuseServices,
        val phoneLink: PhoneLinkServer? = null,
        val safeMode: io.github.matiyaaa.fuse.ui.shell.app.SafeMode? = null,
    ) : StartState
    /** Startup failed: [message] says what it means for the user, [detail] is the system's own words. */
    data class Failed(val message: String, val detail: String? = null) : StartState
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

    /** Controllers: the kernel's joystick devices on Linux, SDL on Windows and macOS. */
    private val gamepads: Gamepads = Gamepads.forOs(DesktopOs.current, GamepadBridge())
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

    /** Counts starts that never settled, in Fuse's data folder (see [StartupGuard]). */
    private val guardFile = File(dirs.data, "startup-guard")
    private val guard = io.github.matiyaaa.fuse.ui.shell.app.StartupGuard(
        load = { guardFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0 },
        save = { n -> guardFile.writeText(n.toString()) },
    )

    /**
     * Safe mode when asked (`--safe-mode`, or a file named `safe-mode` in Fuse's data folder, used
     * once) or after the last starts never settled.
     */
    private fun safeMode(): io.github.matiyaaa.fuse.ui.shell.app.SafeMode? {
        val failing = guard.begin()
        val recovery = File(dirs.data, "safe-mode")
        val asked = "--safe-mode" in args || recovery.exists().also { if (it) recovery.delete() }
        return when {
            asked -> io.github.matiyaaa.fuse.ui.shell.app.SafeMode(io.github.matiyaaa.fuse.ui.shell.app.SafeMode.Reason.REQUESTED)
            failing -> io.github.matiyaaa.fuse.ui.shell.app.SafeMode(io.github.matiyaaa.fuse.ui.shell.app.SafeMode.Reason.REPEATED_FAILURES, guard.failedBefore)
            else -> null
        }
    }

    /** This start ran long enough: the next one starts with a clean count. */
    fun settled() = guard.settle()

    /** Builds the services and the store off the UI thread; the splash shows until then. */
    fun start() {
        gamepads.start()
        scope.launch {
            try {
                val safe = withContext(Dispatchers.IO) { safeMode() }
                if (safe != null) Log.info("starting in safe mode (${safe.reason})")
                val services = withContext(Dispatchers.IO) { DesktopFuseServices.create(dirs, scope) }
                services.launcherHooks.onGameExited = { EventQueue.invokeLater { bringToFront() } }
                val store = createFuseStore(services, scope, safeMode = safe != null)
                // Phone Link waits for a normal start: safe mode runs nothing that listens on the network.
                startState = StartState.Ready(store, services, if (safe == null) startPhoneLink(store, services) else null, safe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.warn("startup failed", e)
                startState = StartState.Failed(StartupFailure.explain(e, dirs.data), e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
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

/** Words for a start that failed, by what failed: never a bare exception. */
internal object StartupFailure {
    fun explain(e: Throwable, dataDir: String): String {
        val text = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return when {
            "newer than this Fuse" in text ->
                "Your library was last opened by a newer version of Fuse, and this one can't read it without risking it. " +
                    "Install that version again. Nothing was changed or deleted."
            "locked" in text.lowercase() || "busy" in text.lowercase() ->
                "Another copy of Fuse seems to have your library open. Close it, then start Fuse again. Nothing was changed."
            "readonly" in text.lowercase() || "read-only" in text.lowercase() || "permission" in text.lowercase() ->
                "Fuse can't write to its data folder ($dataDir). Check that your user can write there, then start Fuse again."
            "disk" in text.lowercase() && "full" in text.lowercase() ->
                "The drive Fuse keeps its data on is full. Free some space, then start Fuse again. Nothing was deleted."
            else ->
                "Something stopped Fuse from opening your library. Nothing was deleted: your library and settings are in $dataDir. " +
                    "Start Fuse with --safe-mode to try with everything optional turned off."
        }
    }
}
