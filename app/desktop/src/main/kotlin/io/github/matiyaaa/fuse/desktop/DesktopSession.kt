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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    var baseMode: WindowMode by mutableStateOf(WindowMode.WINDOWED)
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

    /** Whose a controller press is: Fuse's with focus, or in Game Mode whenever no game is in front (see [ControllerGate]). */
    private val gate = io.github.matiyaaa.fuse.desktop.input.ControllerGate(io.github.matiyaaa.fuse.desktop.system.GameMode.active)
    private var steamWatch: kotlinx.coroutines.Job? = null
    @Volatile private var reclaimedAt = 0L
    private val closed = AtomicBoolean(false)

    /** Fuse is quitting: its window never comes back (a game ending meanwhile doesn't show it again). */
    @Volatile private var quitting = false

    init {
        val saved = prefs.load()
        val flag = WindowMode.fromArgs(args)
        baseMode = saved?.base ?: WindowMode.WINDOWED
        // A first start opens as an ordinary window, like any other program on a computer, except
        // under SteamOS's Game Mode (or another gamescope session), where the screen is Fuse's.
        val start = flag ?: saved?.mode ?: if (io.github.matiyaaa.fuse.desktop.system.GameMode.active) WindowMode.FULLSCREEN else WindowMode.WINDOWED
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
        // Fuse's haptics rumble the controller in use, where its motors can be driven.
        platform.haptics.rumble = gamepads::rumble
        scope.launch {
            try {
                io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.mark(io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.WINDOW)
                val safe = withContext(Dispatchers.IO) { safeMode() }
                if (safe != null) Log.info("starting in safe mode (${safe.reason})")
                val services = withContext(Dispatchers.IO) { DesktopFuseServices.create(dirs, scope) }
                io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.mark("services")
                services.launcherHooks.onGameExited = { gate.gameEnded(); EventQueue.invokeLater { bringToFront() } }
                // Game Mode shows one window of the app at a time: Fuse steps aside for the game it started.
                services.launcherHooks.onGameStarted = {
                    gate.playing = true
                    if (io.github.matiyaaa.fuse.desktop.system.GameMode.active) EventQueue.invokeLater { stepAside() }
                }
                // Fuseline 4.2: work the first screen doesn't need waits until it has settled.
                io.github.matiyaaa.fuse.ui.designsystem.effects.StartupGate.hold()
                val store = createFuseStore(services, scope, safeMode = safe != null)
                io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.mark(io.github.matiyaaa.fuse.ui.designsystem.effects.StartupTrace.STORE)
                // Phone Link waits for a normal start: safe mode runs nothing that listens on the network.
                startState = StartState.Ready(store, services, if (safe == null) startPhoneLink(store, services) else null, safe)
                // An update on trial has come up: once it has stayed up a while, it is the one kept.
                scope.launch {
                    delay(UPDATE_PROVEN_MS)
                    withContext(Dispatchers.IO) { io.github.matiyaaa.fuse.desktop.services.UpdateTrial(File(dirs.data, io.github.matiyaaa.fuse.desktop.services.UpdateTrial.FILE)).healthy() }
                }
                // The Fuse Decky Bridge follows its switch (Steam integration, Linux), and never runs in safe mode.
                if (safe == null && io.github.matiyaaa.fuse.desktop.system.DesktopOs.current == io.github.matiyaaa.fuse.desktop.system.DesktopOs.LINUX) followDecky(store)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.warn("startup failed", e)
                startState = StartState.Failed(StartupFailure.explain(e, dirs.data), e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
    }

    /** The Fuse Decky Bridge while it is switched on; closed with the session. */
    @Volatile private var decky: io.github.matiyaaa.fuse.link.DeckyBridge? = null

    private fun followDecky(store: FuseStore) {
        // The plugin is installed (however it got there) and nobody chose for the bridge: it is on.
        scope.launch(Dispatchers.IO) {
            val steam = store.prefs.value.steam
            if (steam.decky || steam.deckyChosen) return@launch
            val installed = runCatching { platform.decky?.state()?.plugin }.getOrNull() == true
            if (installed) {
                Log.info("the Fuse Decky plugin is installed: the Decky bridge is switched on for it")
                store.updatePrefs { it.copy(steam = it.steam.copy(decky = true)) }
            }
        }
        scope.launch(Dispatchers.IO) {
            store.prefs.map { it.steam.decky }.distinctUntilChanged().collect { on ->
                decky?.close()
                decky = null
                if (on) {
                    val bridge = io.github.matiyaaa.fuse.link.DeckyBridge(store, BuildInfo.VERSION, io.github.matiyaaa.fuse.link.DeckyBridge.discoveryFile())
                    if (bridge.start() > 0) decky = bridge else Log.warn("the Fuse Decky Bridge could not start")
                }
            }
        }
    }

    /** Phone Link follows its switch in Settings; a failure here never stops Fuse from starting. */
    private fun startPhoneLink(store: FuseStore, services: DesktopFuseServices): PhoneLinkServer? = try {
        val name = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Fuse"
        // Off the UI thread: requests and address checks never wait on the window.
        PhoneLinkServer(
            store, services.secrets, scope + Dispatchers.Default, name, BuildInfo.VERSION,
            inbox = io.github.matiyaaa.fuse.link.FileLinkInbox(File(dirs.data, "phone-inbox")),
        ).also { it.start() }
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
        gate.focused = hasFocus
        if (!hasFocus) watchSteamGames()
        val store = (startState as? StartState.Ready)?.store
        if (hasFocus) {
            platform.onForeground()
            store?.library?.onResume()
        } else {
            router.releaseAll()
            store?.library?.onPause()
        }
    }

    private fun stepAside() {
        gate.showing = false
        io.github.matiyaaa.fuse.desktop.system.GamescopeComposite.hidden()
        val w = window ?: return
        // gamescope doesn't minimise windows: Fuse's stayed mapped, in front of the game (or of
        // Cartridge) and still drawing. Hidden, it is out of gamescope's choice and draws nothing.
        if (io.github.matiyaaa.fuse.desktop.system.GameMode.active) w.isVisible = false
        else w.extendedState = w.extendedState or java.awt.Frame.ICONIFIED
    }

    /** Another start of Fuse asked for this one: it comes to the front. */
    fun raise() = bringToFront()

    private fun bringToFront() {
        gate.showing = true
        // Whether Steam runs a game is asked again from here, not carried over from before.
        gate.steamGame = false
        io.github.matiyaaa.fuse.desktop.system.GamescopeComposite.shown()
        val w = window ?: return
        if (quitting || closed.get()) return
        if (!w.isVisible) w.isVisible = true
        if (w.extendedState and java.awt.Frame.ICONIFIED != 0) w.extendedState = w.extendedState and java.awt.Frame.ICONIFIED.inv()
        w.toFront()
        // Window managers that refuse a background window the focus still raise one that is on top
        // for a moment; it goes back to an ordinary window at once (not under gamescope, which shows one window).
        if (!w.isFocused && !io.github.matiyaaa.fuse.desktop.system.GameMode.active && !w.isAlwaysOnTop) {
            runCatching { w.isAlwaysOnTop = true; w.toFront(); w.isAlwaysOnTop = false }
        }
        w.requestFocus()
        w.requestFocusInWindow()
    }

    /**
     * While Fuse is without focus in Game Mode: whether Steam runs a game of its own, every couple
     * of seconds. Until the first answer a game is assumed, so a press never reaches Fuse mid-game.
     */
    private fun watchSteamGames() {
        if (!gate.watchesSteam || steamWatch?.isActive == true) return
        gate.steamGame = true
        steamWatch = scope.launch(Dispatchers.IO) {
            while (gate.watchesSteam) {
                gate.steamGame = runCatching { platform.steam.gameRunning() }.getOrDefault(true)
                delay(STEAM_WATCH_MS)
            }
        }
    }

    /** A press was taken without focus: the window asks for it back (at most once a second). */
    private fun reclaimFocus() {
        val now = System.currentTimeMillis()
        if (now - reclaimedAt < 1_000L) return
        reclaimedAt = now
        Log.info("controller press without window focus; taking the focus back")
        bringToFront()
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
            withContext(NonCancellable + Dispatchers.Main) {
                if (wasFullscreen) {
                    fullscreen = true
                    windowState?.placement = placement
                }
                // The keyboard and the controller come back to Fuse when the dialog closes: without
                // this, the folder just chosen was shown but Enter did nothing until a click.
                bringToFront()
            }
        }
    }

    override fun exitApplication() {
        // Gone within a few seconds whatever happens, so Steam's Exit Game never waits on Fuse.
        quitting = true
        io.github.matiyaaa.fuse.desktop.system.ExitGuard.deadline()
        EventQueue.invokeLater {
            // Gone from the screen at once (Steam's Game Mode shows the next thing straight away);
            // what is left to close happens out of sight.
            window?.isVisible = false
            exit()
        }
    }

    override val currentWindowMode: WindowMode get() = windowMode

    override fun changeWindowMode(mode: WindowMode) {
        if (EventQueue.isDispatchThread()) setWindowMode(mode) else EventQueue.invokeLater { setWindowMode(mode) }
    }

    /** Releases devices, the database and the HTTP client. Safe to call twice. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.github.matiyaaa.fuse.desktop.system.GamescopeComposite.hidden()
        window?.isVisible = false
        // Each part closes on its own at once (servers give their open calls a moment each), and
        // Fuse waits for them together, never longer than [CLOSE_WAIT_MS]: quitting is quick.
        val parts = listOf<() -> Unit>(
            { gamepads.close() },
            { platform.close() },
            { (startState as? StartState.Ready)?.phoneLink?.close() },
            { decky?.close() },
        )
        val threads = parts.map { part -> Thread({ runCatching { part() } }, "fuse-close").apply { isDaemon = true; start() } }
        val deadline = System.currentTimeMillis() + CLOSE_WAIT_MS
        for (t in threads) t.join(maxOf(1L, deadline - System.currentTimeMillis()))
        // The database last, once nothing above writes to it.
        (startState as? StartState.Ready)?.services?.close()
    }

    /** Hands controller input to the router on the UI thread, only while Fuse has focus. */
    private inner class GamepadBridge : GamepadSink {
        private val latestStick = AtomicReference(0f to 0f)
        private val stickPosted = AtomicBoolean(false)

        /**
         * The Guide button (the one in the middle) does nothing in Fuse on Linux: Steam and the
         * desktop already answer it, and Fuse going Home underneath them only got in the way.
         */
        private val guideIgnored = io.github.matiyaaa.fuse.desktop.system.DesktopOs.current == io.github.matiyaaa.fuse.desktop.system.DesktopOs.LINUX

        override fun press(button: PadButton, device: String?) = EventQueue.invokeLater {
            if (guideIgnored && button == PadButton.MODE) return@invokeLater
            if (!gate.admits()) return@invokeLater
            if (gate.reclaims()) reclaimFocus()
            // Only the chosen controller moves around Fuse, when one was chosen and is here.
            if (!router.fromController(device)) return@invokeLater
            pointerHidden = true
            router.press(button, InputSource.GAMEPAD)
        }

        override fun release(button: PadButton, device: String?) = EventQueue.invokeLater {
            if (guideIgnored && button == PadButton.MODE) return@invokeLater
            router.release(button, InputSource.GAMEPAD)
        }

        override fun identified(name: String?) {
            val family = io.github.matiyaaa.fuse.model.PadFamily.of(name)
            EventQueue.invokeLater { router.padIdentified(family) }
        }

        override fun connected(devices: List<String>) = EventQueue.invokeLater { router.controllersChanged(devices) }

        override fun stick(x: Float, y: Float, device: String?) {
            // A controller that doesn't navigate has its stick ignored; letting go is always passed on.
            if (!router.allows(device) && (x != 0f || y != 0f)) return
            latestStick.set(x to y)
            // Coalesce: many axis events per frame become one router update.
            if (!stickPosted.compareAndSet(false, true)) return
            EventQueue.invokeLater {
                stickPosted.set(false)
                val (sx, sy) = latestStick.get()
                if (gate.admits() || (sx == 0f && sy == 0f)) router.stick(sx, sy, InputSource.GAMEPAD)
            }
        }

        override fun trigger(button: PadButton, value: Float, device: String?) = EventQueue.invokeLater {
            if (!router.allows(device) && value > 0f) return@invokeLater
            if (gate.admits() || value <= 0f) router.trigger(button, value, InputSource.GAMEPAD)
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

/** The longest quitting waits for Fuse's servers and devices to close (they carry on closing as the process ends). */
private const val CLOSE_WAIT_MS = 1_200L

/** How often Steam is asked whether a game of its own runs, while Fuse is without focus in Game Mode. */
private const val STEAM_WATCH_MS = 2_000L

/** How long a newly updated Fuse stays up before it is the version kept (its trial ends). */
private const val UPDATE_PROVEN_MS = 20_000L
