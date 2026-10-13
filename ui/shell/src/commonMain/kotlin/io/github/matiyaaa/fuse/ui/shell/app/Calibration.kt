package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.DisplayInfo
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ScreenCalibration
import io.github.matiyaaa.fuse.model.calibrationKey
import io.github.matiyaaa.fuse.model.mainScreen
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs

/** The steps of calibrating a screen, in order. */
enum class CalibrationStep(val title: String, val guide: String) {
    SIZE("How large everything is", "Pick the size that reads comfortably from where you sit. The whole interface follows as you choose."),
    EDGES("Where your screen ends", "Move the corners in until all four sit just inside your screen, with nothing cut off."),
    TEXT("Text you can read", "Pick the size at which the lines below read easily, without leaning in."),
}

/**
 * Calibrating the screen Fuse is on: kept under [key] for [name] only. [before] is the calibration
 * it had (null for none), put back if calibrating is left without saving. Each change shows at once,
 * all over the interface, because it is the screen's calibration being changed.
 */
class CalibrationSession(val key: String, val name: String, val before: ScreenCalibration?) {
    var step by mutableStateOf(CalibrationStep.SIZE)
}

/** The calibration of the screen Fuse's main window is on, if it has one. */
fun UiPrefs.calibration(displays: List<DisplayInfo>): ScreenCalibration? = display.calibrations[screenKey(displays)]

/** What the screen Fuse is on is calibrated under; a platform that names no screen has just the one. */
private fun screenKey(displays: List<DisplayInfo>): String = displays.mainScreen()?.calibrationKey() ?: UNNAMED_SCREEN

/** Changes the calibration of the screen Fuse is on, when it has one (the Text size and Screen edges rows there). */
fun AppState.changeScreenCalibration(change: (ScreenCalibration) -> ScreenCalibration) {
    val key = screenKey(platform.displays.value)
    store.updatePrefs { p ->
        val cal = p.display.calibrations[key] ?: return@updatePrefs p
        p.copy(display = p.display.copy(calibrations = p.display.calibrations + (key to change(cal))))
    }
}

/** Starts calibrating the screen Fuse is on. */
fun AppState.calibrateScreen() {
    val screen = platform.displays.value.mainScreen()
    val key = screenKey(platform.displays.value)
    val name = screen?.name?.trim()?.takeIf { it.isNotEmpty() } ?: "This screen"
    val prefs = store.prefs.value
    val before = prefs.display.calibrations[key]
    // Starts from what this screen shows now: its calibration, or the settings every screen shares.
    val start = before ?: ScreenCalibration(margin = prefs.screenMargin, textScale = prefs.textScale, interfaceSize = prefs.display.interfaceSize)
    store.updatePrefs { it.copy(display = it.display.copy(calibrations = it.display.calibrations + (key to start))) }
    calibrating = CalibrationSession(key, name, before)
}

/** Forgets the calibration of the screen Fuse is on: it goes back to the settings every screen shares. */
fun AppState.forgetScreenCalibration() {
    val key = screenKey(platform.displays.value)
    store.updatePrefs { it.copy(display = it.display.copy(calibrations = it.display.calibrations - key)) }
    toasts.show("This screen uses the settings every screen shares again")
}

/** The text sizes calibration offers. */
private val TextScales = listOf(1f to "Default", 1.15f to "Large", 1.3f to "Extra large")

/**
 * The calibration screen, over everything inside the screen's edges: as the edges move in, its
 * corner marks move with them, so where they sit is exactly where Fuse will draw. A card in the middle
 * says which step this is and offers its choice; the D-pad (or the arrows) changes it, A goes on and
 * saves at the end, B goes back and, from the first step, leaves without changing anything.
 */
@Composable
fun CalibrationOverlay(app: AppState) {
    val session = app.calibrating ?: return
    val prefs by app.store.prefs.collectAsState()
    val now = prefs.display.calibrations[session.key] ?: ScreenCalibration()
    val c = Fuse.colors
    val automatic = LocalAutomaticInterfaceSize.current

    fun set(change: (ScreenCalibration) -> ScreenCalibration) = app.store.updatePrefs { p ->
        val cal = p.display.calibrations[session.key] ?: ScreenCalibration()
        p.copy(display = p.display.copy(calibrations = p.display.calibrations + (session.key to change(cal))))
    }
    fun leave(save: Boolean) {
        if (!save) {
            app.store.updatePrefs { p ->
                val rest = p.display.calibrations - session.key
                p.copy(display = p.display.copy(calibrations = session.before?.let { rest + (session.key to it) } ?: rest))
            }
        }
        app.calibrating = null
        app.toasts.show(if (save) "${session.name} is calibrated. Other screens keep their own" else "Nothing changed")
    }
    fun change(by: Int): Boolean = when (session.step) {
        CalibrationStep.SIZE -> {
            val i = InterfaceSizes.indexOf(now.interfaceSize).coerceAtLeast(0)
            val next = (i + by).coerceIn(0, InterfaceSizes.lastIndex)
            if (next == i) false else { set { it.copy(interfaceSize = InterfaceSizes[next]) }; true }
        }
        CalibrationStep.EDGES -> {
            val next = (now.margin + by).coerceIn(0, MAX_MARGIN)
            if (next == now.margin) false else { set { it.copy(margin = next) }; true }
        }
        CalibrationStep.TEXT -> {
            val i = TextScales.indexOfFirst { it.first == now.textScale }.coerceAtLeast(0)
            val next = (i + by).coerceIn(0, TextScales.lastIndex)
            if (next == i) false else { set { it.copy(textScale = TextScales[next].first) }; true }
        }
    }
    fun forward() {
        val next = CalibrationStep.entries.getOrNull(session.step.ordinal + 1)
        if (next == null) leave(save = true) else session.step = next
    }
    fun back() {
        val previous = CalibrationStep.entries.getOrNull(session.step.ordinal - 1)
        if (previous == null) leave(save = false) else session.step = previous
    }

    InputLayer(priority = LayerPriority.OVERLAY + 1, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.DOWN -> if (change(-1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.RIGHT, NavAction.UP -> if (change(1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.SELECT -> { forward(); NavResult.ACTIVATED }
            NavAction.BACK -> { back(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }

    val motion = Fuse.motion
    Box(Modifier.fillMaxSize().background(c.ink)) {
        // The corners and the middle of each edge, where Fuse's drawing now ends.
        val lit by fuselineFloat(if (session.step == CalibrationStep.EDGES) 1f else 0.45f, Fuse.motion.fade(Durations.BASE), label = "calibration marks")
        val accent = c.accent
        Canvas(Modifier.fillMaxSize()) {
            val arm = 56.dp.toPx()
            val w = 5.dp.toPx()
            val inset = w / 2
            val color = accent.copy(alpha = lit)
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawLine(color, Offset(x, y), Offset(x + arm * dx, y), w, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + arm * dy), w, StrokeCap.Round)
            }
            corner(inset, inset, 1f, 1f)
            corner(size.width - inset, inset, -1f, 1f)
            corner(inset, size.height - inset, 1f, -1f)
            corner(size.width - inset, size.height - inset, -1f, -1f)
            val tick = 22.dp.toPx()
            drawLine(color, Offset(size.width / 2 - tick, inset), Offset(size.width / 2 + tick, inset), w, StrokeCap.Round)
            drawLine(color, Offset(size.width / 2 - tick, size.height - inset), Offset(size.width / 2 + tick, size.height - inset), w, StrokeCap.Round)
            drawLine(color, Offset(inset, size.height / 2 - tick), Offset(inset, size.height / 2 + tick), w, StrokeCap.Round)
            drawLine(color, Offset(size.width - inset, size.height / 2 - tick), Offset(size.width - inset, size.height / 2 + tick), w, StrokeCap.Round)
        }
        Panel(Modifier.align(Alignment.Center).padding(Space.l).widthIn(max = 640.dp)) {
            Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                FText("CALIBRATING ${session.name.uppercase()}  ·  STEP ${session.step.ordinal + 1} OF ${CalibrationStep.entries.size}", Fuse.type.overline, color = c.accent, maxLines = 1)
                Spacer(Modifier.height(Space.s))
                Swap(
                    targetState = session.step,
                    transitionSpec = { fadeIn(motion.fade(Durations.BASE)) togetherWith fadeOut(motion.fade(Durations.FAST)) },
                    label = "calibration step",
                ) { step ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FText(step.title, Fuse.type.title, maxLines = 1)
                        Spacer(Modifier.height(Space.xs))
                        FText(step.guide, Fuse.type.body, color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(Space.l))
                        val value = when (step) {
                            CalibrationStep.SIZE -> if (now.interfaceSize == 0) "Automatic (${(automatic * 100).toInt()}%)" else "${now.interfaceSize}%"
                            CalibrationStep.EDGES -> if (now.margin == 0) "All of the screen" else "${now.margin}% in from each edge"
                            CalibrationStep.TEXT -> TextScales.firstOrNull { it.first == now.textScale }?.second ?: "Default"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Arrow(FuseIcons.ChevronLeft, "Less") { change(-1) }
                            Spacer(Modifier.width(Space.l))
                            FText(value, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.widthIn(min = 220.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                            Spacer(Modifier.width(Space.l))
                            Arrow(FuseIcons.ChevronRight, "More") { change(1) }
                        }
                        if (step == CalibrationStep.TEXT) {
                            Spacer(Modifier.height(Space.l))
                            // Sample lines at the size chosen (the interface's text follows it too).
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                FText("Continue playing", Fuse.type.bodyStrong, maxLines = 1)
                                FText("Velvet Orbit  ·  Played 2 hours ago  ·  2 discs", Fuse.type.body, color = c.textMuted, maxLines = 1)
                                FText("Hints like these sit along the bottom of every page", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    FuseButton(if (session.step.ordinal == 0) "Cancel" else "Back", selected = false, onClick = ::back)
                    FuseButton(
                        if (session.step == CalibrationStep.entries.last()) "Save" else "Next", selected = true, onClick = ::forward,
                        kind = ButtonKind.PRIMARY, icon = if (session.step == CalibrationStep.entries.last()) FuseIcons.Check else FuseIcons.ArrowRight,
                    )
                }
            }
        }
        HintBar(
            listOf(
                Hint(HintButton.DPAD, "Change"),
                Hint(HintButton.CONFIRM, if (session.step == CalibrationStep.entries.last()) "Save" else "Next"),
                Hint(HintButton.BACK, if (session.step.ordinal == 0) "Cancel" else "Back"),
            ),
            Modifier.align(Alignment.BottomEnd).padding(Space.xl),
        )
    }
}

@Composable
private fun Arrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Fuse.colors
    Box(
        Modifier
            .size(Size.touch)
            .clip(CircleShape)
            .background(c.text.copy(alpha = if (c.isDark) 0.08f else 0.06f))
            .fuseClickable(shape = CircleShape, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = Size.iconM, tint = c.text)
    }
}

/** The key of the one screen of a platform that names none. */
private const val UNNAMED_SCREEN = "Screen"

/** The most room a screen's edges keep clear, in percent. */
private const val MAX_MARGIN = 10
