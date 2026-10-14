package io.github.matiyaaa.fuse.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import io.github.matiyaaa.fuse.ui.designsystem.effects.UiRenderTrace
import io.github.matiyaaa.fuse.ui.designsystem.effects.traceUiRoot
import io.github.matiyaaa.fuse.ui.designsystem.effects.drawDither
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.compose.setSingletonImageLoaderFactory
import coil3.svg.SvgDecoder
import io.github.matiyaaa.fuse.desktop.system.DesktopOs
import io.github.matiyaaa.fuse.desktop.ui.FuseMarkPainter
import io.github.matiyaaa.fuse.desktop.ui.Splash
import io.github.matiyaaa.fuse.model.DeviceTier
import io.github.matiyaaa.fuse.ui.designsystem.input.handleKeyEvent
import io.github.matiyaaa.fuse.ui.shell.app.FuseApp
import io.github.matiyaaa.fuse.ui.shell.platform.fuseImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.image.BufferedImage

fun main(args: Array<String>) {
    if ("--self-test" in args) kotlin.system.exitProcess(SelfTest.run())
    // The helper that restarts Steam for a Fuse running inside it (see SteamApplyMain): no window.
    args.indexOf(io.github.matiyaaa.fuse.desktop.platform.SteamApplyMain.ARG).takeIf { it >= 0 }?.let { at ->
        val job = args.getOrNull(at + 1) ?: kotlin.system.exitProcess(2)
        kotlin.system.exitProcess(io.github.matiyaaa.fuse.desktop.platform.SteamApplyMain.run(java.io.File(job)))
    }
    if ("--sync-host" in args) {
        val dirs = FuseDirs.fromEnvironment().also { it.ensure() }
        kotlin.system.exitProcess(SyncHostMain.run(dirs))
    }
    UiRenderTrace.enabled = "--trace-ui" in args
    // Gradients and baked pictures behind the interface are dithered, on the graphics card or not.
    io.github.matiyaaa.fuse.desktop.system.DesktopDithering.install()
    // Compose here draws shadows at one strength whatever their colour's alpha: they stay neutral.
    io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.tintedShadows = false
    // Skia's own messages, so a fallback to drawing on the processor has its reason.
    io.github.matiyaaa.fuse.desktop.system.GraphicsReport.install()
    setX11WmClass()
    // Fuse Player plays on FFmpeg here.
    io.github.matiyaaa.fuse.ui.player.FusePlayer.engineFactory = { io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegEngine() }
    val dirs = FuseDirs.fromEnvironment()
    // One Fuse at a time: a second start brings the first to the front, or takes its place (see
    // SingleInstance); a start that plays a game (--play) takes its place. Before the log, so a
    // start that leaves doesn't turn the running one's log over.
    val guard = io.github.matiyaaa.fuse.desktop.system.SingleInstance(instanceFolder(dirs))
    val claim = guard.claim(System.getenv("SteamGameId")?.takeIf { it.isNotBlank() }, replace = io.github.matiyaaa.fuse.desktop.system.SingleInstance.REPLACE in args || "--play" in args)
    if (claim is io.github.matiyaaa.fuse.desktop.system.SingleInstance.Decision.Handed) kotlin.system.exitProcess(0)
    instance = guard
    dirs.eraseIfAsked()
    dirs.ensure()
    Log.toFile(java.io.File(dirs.data, "logs"))
    CrashLog(java.io.File(dirs.data, "crash")).install()
    Log.info("Fuse ${BuildInfo.VERSION} starting")
    when (claim) {
        is io.github.matiyaaa.fuse.desktop.system.SingleInstance.Decision.Replaced -> Log.info("took the place of the Fuse already running" + if (claim.ended) " (it had to be ended)" else "")
        is io.github.matiyaaa.fuse.desktop.system.SingleInstance.Decision.Unguarded -> Log.warn("not guarded against a second Fuse: ${claim.why}")
        else -> Unit
    }
    // A stalled interface thread writes what it was doing into the log.
    io.github.matiyaaa.fuse.desktop.system.UiWatchdog().start()
    // Without a screen to open a window on, one plain sentence instead of the toolkit's error.
    NoDisplay.why()?.let { why ->
        System.err.println(NoDisplay.message(why))
        kotlin.system.exitProcess(NoDisplay.EXIT_CODE)
    }
    goBackFromAFailedUpdate(dirs, args)
    // Skia's picture cache sized for the screen, and the drawing settings asked for, before a window opens.
    val tuning = io.github.matiyaaa.fuse.desktop.system.RenderTuning.install(io.github.matiyaaa.fuse.desktop.system.RenderTuning.screenPixels())
    Log.info("Drawing: ${tuning.notes.joinToString("; ")}")
    // The desktop's own scale where Java's toolkit can't follow it (fractional scaling on Linux).
    desktopScale = runCatching {
        val javaScale = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        val dpi = io.github.matiyaaa.fuse.desktop.system.DesktopScale.readDpi()
        io.github.matiyaaa.fuse.desktop.system.DesktopScale.extra(dpi, javaScale, System.getenv("FUSE_SCALE")).also {
            Log.info("Display scale: Java ${javaScale}x, Xft.dpi ${dpi ?: "unset"}, Fuse draws ${it}x on top")
        }
    }.getOrDefault(1f)
    // Lives as long as the process; runs on the AWT event thread, which is Compose's UI thread.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    try {
        runWindow(dirs, args, scope)
        // The window is closed: Fuse is done, whatever thread something left running, so no Fuse
        // stays behind without a window (Steam would think it still runs).
        guard.release()
        Log.info("Fuse closed")
        kotlin.system.exitProcess(0)
    } catch (e: Throwable) {
        // The display went away as the window opened (or the toolkit couldn't reach it): the same plain sentence.
        if (!NoDisplay.isDisplayError(e)) throw e
        Log.warn("the window couldn't open", e)
        System.err.println(NoDisplay.message(e.message ?: e.javaClass.simpleName))
        kotlin.system.exitProcess(NoDisplay.EXIT_CODE)
    }
}

/** The single-instance guard this Fuse holds, once it holds it. */
private var instance: io.github.matiyaaa.fuse.desktop.system.SingleInstance? = null

/**
 * Where the single-instance lock lives: outside Fuse's own folders (Erase Everything deletes those
 * while the Fuse before it may still hold the lock), one for each person and data folder.
 */
private fun instanceFolder(dirs: FuseDirs): java.io.File {
    val base = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() && java.io.File(it).isDirectory } ?: System.getProperty("java.io.tmpdir")
    val user = System.getProperty("user.name").orEmpty().filter { it.isLetterOrDigit() }.ifEmpty { "user" }
    val key = Integer.toHexString(java.io.File(dirs.data).absolutePath.hashCode())
    return java.io.File(base, "fuse-$user-$key")
}

private fun runWindow(dirs: FuseDirs, args: Array<String>, scope: CoroutineScope) {
    application {
        val session = remember { DesktopSession(dirs, args.filter { it != io.github.matiyaaa.fuse.desktop.system.SingleInstance.REPLACE }, scope).also { it.start() } }
        session.exit = ::exitApplication
        DisposableEffect(session) {
            instance?.onShow = { java.awt.EventQueue.invokeLater { session.raise() } }
            instance?.onQuit = {
                java.awt.EventQueue.invokeLater { session.exit() }
                // A Fuse that can't close in time is ended, so the one taking its place isn't kept waiting.
                Thread({ Thread.sleep(4_000); Runtime.getRuntime().halt(0) }, "fuse-quit").apply { isDaemon = true; start() }
            }
            onDispose { instance?.onShow = {}; instance?.onQuit = {} }
        }
        DisposableEffect(session) { onDispose { session.close() } }
        FuseWindow(session)
    }
}

/**
 * An update that hasn't come up in its first starts goes back to the version before it, which
 * starts in its place (see UpdateTrial). Returns only when this start carries on.
 */
private fun goBackFromAFailedUpdate(dirs: FuseDirs, args: Array<String>) {
    val trial = io.github.matiyaaa.fuse.desktop.services.UpdateTrial(java.io.File(dirs.data, io.github.matiyaaa.fuse.desktop.services.UpdateTrial.FILE))
    val start = trial.onStart(System.getenv("APPIMAGE") ?: macBundle(), BuildInfo.VERSION)
    if (start !is io.github.matiyaaa.fuse.desktop.services.UpdateTrial.Start.WentBack) return
    Log.warn("Fuse ${start.version} didn't come up in its first starts; going back to ${start.from}")
    val before = start.from.ifEmpty { null }?.let { "Fuse $it" } ?: "the version before it"
    runCatching {
        java.io.File(dirs.data, io.github.matiyaaa.fuse.desktop.services.UpdateTrial.NOTE)
            .writeText("Fuse ${start.version} didn't start properly, so Fuse went back to $before.")
    }
    val pass = args.filter { it.startsWith("--") && it != io.github.matiyaaa.fuse.desktop.system.SingleInstance.REPLACE }
    // A Mac's Fuse.app starts from the launcher inside it.
    val program = java.io.File(start.target).takeIf { it.isDirectory }
        ?.let { java.io.File(it, "Contents/MacOS").listFiles()?.firstOrNull { f -> f.isFile && f.canExecute() }?.path } ?: start.target
    if (io.github.matiyaaa.fuse.desktop.system.Processes.spawn(listOf(program) + pass + io.github.matiyaaa.fuse.desktop.system.SingleInstance.REPLACE) != null) {
        kotlin.system.exitProcess(0)
    }
}

/** The Fuse.app this Fuse runs from on a Mac, or null. */
private fun macBundle(): String? {
    if (io.github.matiyaaa.fuse.desktop.system.DesktopOs.current != io.github.matiyaaa.fuse.desktop.system.DesktopOs.MACOS) return null
    val launcher = System.getProperty("jpackage.app-path")?.let(java.io.File::new) ?: return null
    return generateSequence(launcher.absoluteFile) { it.parentFile }.firstOrNull { it.name.endsWith(".app") }?.path
}

/** Every frame Skia draws while something moves, summed up in the log each minute (see FrameWatch). */
private val frameWatch = io.github.matiyaaa.fuse.desktop.system.FrameWatch()

/**
 * Times each frame of [layer] as Skia draws it, around the drawing Compose gave it, and writes a
 * minute's frames into the log when anything moved in it.
 */
private fun watchFrames(layer: org.jetbrains.skiko.SkiaLayer) {
    val inner = layer.renderDelegate ?: return
    if (inner is TimedRender) return
    layer.renderDelegate = TimedRender(inner)
    frameReports
}

/** The minute's line, from a thread of its own; started with the first window watched, once. */
private val frameReports: Unit by lazy {
    Thread({
        while (true) {
            try {
                Thread.sleep(io.github.matiyaaa.fuse.desktop.system.FrameWatch.PERIOD_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            frameWatch.report()?.let(Log::info)
        }
    }, "fuse-frame-watch").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }
    Unit
}

private class TimedRender(private val inner: org.jetbrains.skiko.SkikoRenderDelegate) : org.jetbrains.skiko.SkikoRenderDelegate {
    override fun onRender(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, nanoTime: Long) {
        val start = System.nanoTime()
        inner.onRender(canvas, width, height, nanoTime)
        frameWatch.rendered(nanoTime, System.nanoTime() - start)
    }
}

/** How much larger than Java's own scale Fuse draws (see DesktopScale); 1 almost everywhere. */
private var desktopScale = 1f

/**
 * What the window is really drawn with (Skiko's render info names the GL driver), where it is shown
 * (X11, Wayland, nested gamescope or Steam's Game Mode), and whether gamescope is scaling a smaller
 * window up to the screen, which makes everything soft however sharp the art is.
 */
private suspend fun probeRenderer(session: DesktopSession, window: java.awt.Window) {
    fun layers(c: java.awt.Component): List<org.jetbrains.skiko.SkiaLayer> =
        (if (c is org.jetbrains.skiko.SkiaLayer) listOf(c) else emptyList()) + ((c as? java.awt.Container)?.components?.flatMap(::layers) ?: emptyList())
    val layer = layers(window).firstOrNull() ?: return
    val api = layer.renderApi.name
    val info = runCatching { layer.renderInfo }.getOrNull()
    val device = window.graphicsConfiguration.device
    val mode = device.displayMode
    val scale = window.graphicsConfiguration.defaultTransform.scaleX
    val w = (window.width * scale).toInt()
    val h = (window.height * scale).toInt()
    val output = io.github.matiyaaa.fuse.desktop.system.ActiveOutput.currentMode()
    val kind = io.github.matiyaaa.fuse.desktop.system.FramePacing.session(System::getenv)
    val gpu = io.github.matiyaaa.fuse.desktop.system.Renderer.classify(api, info).gpu
    val profile = io.github.matiyaaa.fuse.desktop.system.FramePacing.profile(kind, w, h, output?.first ?: mode.width, output?.second ?: mode.height,
        mode.refreshRate.toDouble(), gpu)
    if (profile.refreshHz > 0) UiRenderTrace.expectedFrameNanos = profile.budgetNanos
    if (profile.refreshHz > 0) frameWatch.budgetNanos = profile.budgetNanos
    watchFrames(layer)
    // A TV's picture processing smooths a faint dither away and leaves the bands: a stronger one there.
    io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.largeOutput = profile.largeOutput || kind == io.github.matiyaaa.fuse.desktop.system.DisplaySession.STEAM_GAME_MODE
    // In Game Mode gamescope composes Fuse in 10 bits instead of scanning its 8-bit picture out as is (see GamescopeComposite).
    io.github.matiyaaa.fuse.desktop.system.GamescopeComposite.enable(kind)
    val report = io.github.matiyaaa.fuse.desktop.system.GraphicsReport
    // A 4K screen at 120 Hz over HDMI on Linux gets reduced colour, whose bands nothing in Fuse can hide.
    val screen = io.github.matiyaaa.fuse.desktop.system.RenderScale.outputs().singleOrNull()
    val colour = io.github.matiyaaa.fuse.desktop.system.HdmiColour.note(screen?.connector, screen?.width ?: 0, screen?.height ?: 0, profile.refreshHz, report.driver(info))
    val note = if (profile.upscaled) "Shown at ${w}x$h and scaled up to ${output?.first ?: mode.width}x${output?.second ?: mode.height}, which looks soft. " +
        "In Steam, Fuse's Properties, General, set Game Resolution to Native" else colour
    val verdict = io.github.matiyaaa.fuse.desktop.system.Renderer.classify(api, info)
    // Asked only when drawing on the processor: whether the computer's own OpenGL uses the card.
    val system = if (gpu) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { report.systemRenderer() }
    val details = buildList {
        add("Drawn by" to if (gpu) "The graphics card" else "The processor")
        add("Graphics API" to api.lowercase().replaceFirstChar { it.uppercase() }.replace("Opengl", "OpenGL").replace("Direct3d", "Direct3D"))
        report.driver(info)?.let { add("Driver" to it) }
        add("Display" to report.sessionName(kind))
        val ow = output?.first ?: mode.width
        val oh = output?.second ?: mode.height
        add("Window" to if (profile.upscaled) "${w}x$h, enlarged to ${ow}x$oh" else "${w}x$h on a ${ow}x$oh screen")
        add("Refresh" to "${profile.refreshHz.toInt()} Hz")
        addAll(io.github.matiyaaa.fuse.desktop.system.RenderTuning.described())
        if (!gpu) {
            verdict.why?.let { add("Why" to it) }
            report.skiaFailures.lastOrNull()?.let { add("Skia said" to it) }
            for (cause in report.environmentCauses(System::getenv)) add("Environment" to cause)
            add("This computer's OpenGL" to (system ?: "Not known (glxinfo isn't installed)"))
            val systemGpu = system?.let { io.github.matiyaaa.fuse.desktop.system.Renderer.classify("OPENGL", it).gpu }
            when (systemGpu) {
                true -> add("What to try" to "This computer's OpenGL uses its graphics card, so the cause is how Fuse was started: start it from its AppImage, without launch options that change the graphics driver")
                false -> add("What to try" to "This computer's own OpenGL draws in software too: its graphics driver isn't working. Updating the system usually fixes it")
                null -> Unit
            }
        }
        note?.let { add("Note" to it) }
        if (colour != null && colour != note) add("Colour" to colour)
    }
    Log.info("Display: ${kind.name.lowercase()}, window ${w}x$h, output ${output?.first ?: mode.width}x${output?.second ?: mode.height} at ${profile.refreshHz} Hz, large ${profile.largeOutput}, upscaled ${profile.upscaled}, connector ${screen?.connector ?: "unknown"}" + if (colour != null) ", reduced colour likely" else "")
    session.platform.noteRenderer(api, info, note, details)
    UiRenderTrace.renderer = api
}

/**
 * A comfortable first window: three quarters of the screen's width at 16:10, never smaller than
 * 960 by 600 nor larger than the screen leaves room for.
 */
private fun firstWindowSize(): DpSize = try {
    val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val w = (bounds.width * 0.76f).coerceIn(960f, maxOf(960f, bounds.width - 48f))
    val h = (w / 1.6f).coerceIn(600f, maxOf(600f, bounds.height - 48f))
    DpSize(w.dp, h.dp)
} catch (e: Exception) {
    DpSize(1280.dp, 800.dp)
}

@Composable
private fun ApplicationScope.FuseWindow(session: DesktopSession) {
    val icon = remember { FuseMarkPainter() }
    // Decorations can't change on a visible window, so a base-mode change builds a new one.
    key(session.decorated) {
        val state = rememberWindowState(
            placement = session.placement,
            position = WindowPosition(Alignment.Center),
            size = remember { firstWindowSize() },
        )
        session.windowState = state
        LaunchedEffect(state) {
            snapshotFlow { state.placement }.collect(session::onPlacementChanged)
        }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Fuse",
            icon = icon,
            undecorated = !session.decorated,
            onPreviewKeyEvent = { e -> onKey(session, e) },
        ) {
            DisposableEffect(window) {
                session.window = window
                // How the window is drawn: on the processor, Fuse keeps motion light.
                runCatching {
                    val renderer = window.renderApi.name
                    session.platform.noteRenderer(renderer)
                    UiRenderTrace.renderer = renderer
                    val rate = window.graphicsConfiguration.device.displayMode.refreshRate
                    if (rate > 0) UiRenderTrace.expectedFrameNanos = (1e9 / rate).toLong()
                }
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) = session.onFocusChanged(true)
                    override fun windowLostFocus(e: WindowEvent?) = session.onFocusChanged(false)
                }
                window.addWindowFocusListener(listener)
                if (window.isFocused) session.onFocusChanged(true)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            // Skiko settles on its renderer with the first frames: then the real driver is known.
            LaunchedEffect(window) {
                kotlinx.coroutines.delay(1_500)
                runCatching { probeRenderer(session, window) }.onFailure { Log.warn("Renderer probe", it) }
            }
            SideEffect { UiRenderTrace.composition() }
            if (UiRenderTrace.enabled) {
                LaunchedEffect(window) {
                    try {
                        while (true) withFrameNanos { UiRenderTrace.frame(it) }
                    } finally {
                        Log.info(UiRenderTrace.report())
                    }
                }
            }
            val gpu = session.platform.drawing.collectAsState().value?.gpu != false
            val outer = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides if (desktopScale == 1f) outer else androidx.compose.ui.unit.Density(outer.density * desktopScale, outer.fontScale),
            ) {
            Box(
                Modifier
                    .traceUiRoot()
                    .fillMaxSize()
                    .background(io.github.matiyaaa.fuse.desktop.ui.FuseBrand.Ink)
                    // Skia on a desktop draws gradients undithered, so a soft light Fuse doesn't
                    // dither at the source bands in 8-bit steps; one dither over the whole window
                    // breaks those up, at the cost of a single textured rectangle on the graphics card
                    // (not drawn on the processor). Not where gamescope enlarges Fuse's picture:
                    // there each of its pixels is shown as a square of several, and they are seen.
                    .then(
                        if (gpu) {
                            Modifier.drawWithContent {
                                drawContent()
                                if (io.github.matiyaaa.fuse.ui.designsystem.effects.Drawing.upscale <= 1.05f) drawDither()
                            }
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) {
                                    if (session.pointerHidden) session.pointerHidden = false
                                }
                            }
                        }
                    }
                    .then(if (session.pointerHidden && !io.github.matiyaaa.fuse.desktop.system.RenderTuning.keepCursor) Modifier.pointerHoverIcon(BlankPointer, overrideDescendants = true) else Modifier),
            ) {
                when (val s = session.startState) {
                    StartState.Loading -> Splash()
                    is StartState.Failed -> Splash(error = s.message, detail = s.detail)
                    is StartState.Ready -> {
                        val lowMemory = session.platform.device.tier == DeviceTier.LOW
                        setSingletonImageLoaderFactory { context ->
                            fuseImageLoader(context, s.services.cacheDir, s.services.http, lowMemory) {
                                if (DesktopOs.isWindows) add(WindowsImagePaths)
                                add(SvgDecoder.Factory())
                            }
                        }
                        FuseApp(s.store, session.platform, session.router, s.phoneLink, safeMode = s.safeMode, onSettled = session::settled, startupIntro = true, keepPlace = true)
                    }
                }
            }
            }
        }
    }
}

/** F11 and Alt+Enter toggle fullscreen; everything else goes to the input router. */
private fun onKey(session: DesktopSession, e: KeyEvent): Boolean {
    val fullscreenKey = e.key == Key.F11 || (e.key == Key.Enter && e.isAltPressed)
    if (fullscreenKey) {
        if (e.type == KeyEventType.KeyDown) session.toggleFullscreen()
        return true
    }
    // F12 takes a screenshot of Fuse's window; Shift+F12 starts or stops a recording (as L3 + R3 on a pad).
    if (e.key == Key.F12) {
        if (e.type == KeyEventType.KeyDown) {
            session.router.onCaptureCombo?.invoke(
                if (e.isShiftPressed) io.github.matiyaaa.fuse.ui.designsystem.input.ComboGesture.HOLD else io.github.matiyaaa.fuse.ui.designsystem.input.ComboGesture.TAP,
            )
        }
        return true
    }
    val handled = session.router.handleKeyEvent(e)
    if (handled && e.type == KeyEventType.KeyDown) session.pointerHidden = true
    return handled
}

/** An invisible cursor, shown while a controller or the keyboard is in use. */
private val BlankPointer: PointerIcon by lazy {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "fuse-blank"))
}

/**
 * Names the X11 window class "fuse" (instead of the main class name) so docks and task switchers
 * match the window to fuse.desktop (`StartupWMClass=fuse`). Needs `--add-opens
 * java.desktop/sun.awt.X11=ALL-UNNAMED`, which the packaged launcher passes; skipped quietly otherwise.
 */
private fun setX11WmClass() {
    try {
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
        val field = toolkit.javaClass.getDeclaredField("awtAppClassName")
        field.isAccessible = true
        field.set(null, "fuse")
    } catch (e: Throwable) {
        // Not X11 or not opened to us: the default class name stays.
    }
}
