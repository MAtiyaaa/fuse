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
    setX11WmClass()
    // Fuse Player plays on FFmpeg here.
    io.github.matiyaaa.fuse.ui.player.FusePlayer.engineFactory = { io.github.matiyaaa.fuse.ui.player.ffmpeg.FfmpegEngine() }
    val dirs = FuseDirs.fromEnvironment()
    dirs.eraseIfAsked()
    dirs.ensure()
    CrashLog(java.io.File(dirs.data, "crash")).install()
    Log.info("Fuse ${BuildInfo.VERSION} starting")
    goBackFromAFailedUpdate(dirs, args)
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
    application {
        val session = remember { DesktopSession(dirs, args.toList(), scope).also { it.start() } }
        session.exit = ::exitApplication
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
    val start = trial.onStart(System.getenv("APPIMAGE"), BuildInfo.VERSION)
    if (start !is io.github.matiyaaa.fuse.desktop.services.UpdateTrial.Start.WentBack) return
    Log.warn("Fuse ${start.version} didn't come up in its first starts; going back to ${start.from}")
    val before = start.from.ifEmpty { null }?.let { "Fuse $it" } ?: "the version before it"
    runCatching {
        java.io.File(dirs.data, io.github.matiyaaa.fuse.desktop.services.UpdateTrial.NOTE)
            .writeText("Fuse ${start.version} didn't start properly, so Fuse went back to $before.")
    }
    if (io.github.matiyaaa.fuse.desktop.system.Processes.spawn(listOf(start.target) + args.filter { it.startsWith("--") }) != null) {
        kotlin.system.exitProcess(0)
    }
}

/** How much larger than Java's own scale Fuse draws (see DesktopScale); 1 almost everywhere. */
private var desktopScale = 1f

/**
 * What the window is really drawn with (Skiko's render info names the GL driver), where it is shown
 * (X11, Wayland, nested gamescope or Steam's Game Mode), and whether gamescope is scaling a smaller
 * window up to the screen, which makes everything soft however sharp the art is.
 */
private fun probeRenderer(session: DesktopSession, window: java.awt.Window) {
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
    val note = if (profile.upscaled) "Shown at ${w}x$h and scaled up to ${output?.first ?: mode.width}x${output?.second ?: mode.height}, which looks soft. " +
        "In Steam, Fuse's Properties, General, set Game Resolution to Native" else null
    Log.info("Display: ${kind.name.lowercase()}, window ${w}x$h, output ${output?.first ?: mode.width}x${output?.second ?: mode.height} at ${profile.refreshHz} Hz, large ${profile.largeOutput}, upscaled ${profile.upscaled}")
    session.platform.noteRenderer(api, info, note)
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
                    .then(if (session.pointerHidden) Modifier.pointerHoverIcon(BlankPointer, overrideDescendants = true) else Modifier),
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
