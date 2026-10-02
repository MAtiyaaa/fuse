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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
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
    setX11WmClass()
    val dirs = FuseDirs.fromEnvironment()
    dirs.ensure()
    CrashLog(java.io.File(dirs.data, "crash")).install()
    Log.info("Fuse ${BuildInfo.VERSION} starting")
    // Lives as long as the process; runs on the AWT event thread, which is Compose's UI thread.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    application {
        val session = remember { DesktopSession(dirs, args.toList(), scope).also { it.start() } }
        session.exit = ::exitApplication
        DisposableEffect(session) { onDispose { session.close() } }
        FuseWindow(session)
    }
}

@Composable
private fun ApplicationScope.FuseWindow(session: DesktopSession) {
    val icon = remember { FuseMarkPainter() }
    // Decorations can't change on a visible window, so a base-mode change builds a new one.
    key(session.decorated) {
        val state = rememberWindowState(
            placement = session.placement,
            position = WindowPosition(Alignment.Center),
            size = DpSize(1280.dp, 800.dp),
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
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) = session.onFocusChanged(true)
                    override fun windowLostFocus(e: WindowEvent?) = session.onFocusChanged(false)
                }
                window.addWindowFocusListener(listener)
                if (window.isFocused) session.onFocusChanged(true)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(io.github.matiyaaa.fuse.desktop.ui.FuseBrand.Ink)
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
                        FuseApp(s.store, session.platform, session.router, s.phoneLink, safeMode = s.safeMode, onSettled = session::settled, startupIntro = true)
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
