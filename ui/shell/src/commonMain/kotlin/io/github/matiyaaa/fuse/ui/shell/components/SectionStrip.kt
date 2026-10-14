package io.github.matiyaaa.fuse.ui.shell.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.matiyaaa.fuse.model.ConnectionState
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.HomeMode
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.ScanPhase
import io.github.matiyaaa.fuse.model.ScanScope
import io.github.matiyaaa.fuse.model.SystemStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.BatteryGlyph
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.HintBar
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuRow
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyph
import io.github.matiyaaa.fuse.ui.designsystem.icons.ButtonGlyphDefaults
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.expandVertically
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.shrinkVertically
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.applyUpdate
import io.github.matiyaaa.fuse.ui.shell.app.formatDate
import io.github.matiyaaa.fuse.ui.shell.app.offers
import io.github.matiyaaa.fuse.ui.shell.app.rememberClockText
import io.github.matiyaaa.fuse.ui.shell.app.sections
import io.github.matiyaaa.fuse.ui.shell.capture.rememberRecordingTime
import io.github.matiyaaa.fuse.ui.shell.components.ControlTile
import io.github.matiyaaa.fuse.ui.shell.components.ROW_CONTENT_START
import io.github.matiyaaa.fuse.ui.shell.components.batteryTimeText
import io.github.matiyaaa.fuse.ui.shell.components.rememberRowHighlight
import io.github.matiyaaa.fuse.ui.shell.home.switchHomeStyle
import io.github.matiyaaa.fuse.ui.shell.music.BundledMusic
import io.github.matiyaaa.fuse.ui.shell.music.MenuMusicRemote
import io.github.matiyaaa.fuse.ui.shell.platform.WindowStyle
import io.github.matiyaaa.fuse.ui.shell.settings.next
import io.github.matiyaaa.fuse.ui.shell.settings.performanceLabel
import io.github.matiyaaa.fuse.ui.shell.settings.performanceSummary
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.store.UpdateState
import io.github.matiyaaa.fuse.ui.shell.store.transferOrder
import io.github.matiyaaa.fuse.transfer.TransferStatus
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.designsystem.input.dragVerticalScroll

/** One section in a [SectionStrip]: its name, its icon (Fuse's mark when null), and a dot for something new. */
@androidx.compose.runtime.Immutable
data class StripSection(val label: String, val icon: ImageVector?, val badge: Boolean = false)

/**
 * Sections along the top of a sheet, as the quick menu has them: each its icon, and the open one
 * its name beside it in a soft pill that glides and widens to the next, the others giving it room.
 * LB and RB sit at the ends. A dot rides a section with something new.
 */
@Composable
fun SectionStrip(sections: List<StripSection>, active: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val pages = sections
    Row(modifier.fillMaxWidth().height(SECTION_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        ButtonGlyph(HintButton.PREV, size = ButtonGlyphDefaults.SmallSize, color = c.textFaint)
        Spacer(Modifier.width(Space.s))
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val density = LocalDensity.current
            val gap = Space.xxs
            val closed = SECTION_HEIGHT
            // The open section takes what the closed ones leave.
            val open = (maxWidth - closed * (pages.size - 1) - gap * (pages.size - 1)).coerceAtLeast(closed)
            val shares = pages.mapIndexed { i, _ -> fuselineFloat(if (i == active) 1f else 0f, motion.focusSpring(), label = "section $i") }
            val tray = c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f)
            Box(Modifier.fillMaxSize().clip(PillShape).background(tray))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                pages.forEachIndexed { i, p ->
                    val t = shares[i].value.coerceIn(0f, 1f)
                    val on = i == active
                    val width = with(density) { (closed.toPx() + (open - closed).toPx() * t).toDp() }
                    val fill = c.text.copy(alpha = (if (c.isDark) 0.13f else 0.09f) * t)
                    val tint = androidx.compose.ui.graphics.lerp(c.textMuted, c.text, t)
                    Box(
                        Modifier
                            .width(width)
                            .fillMaxHeight()
                            .clip(PillShape)
                            .background(fill)
                            .fuseClickable(shape = PillShape, role = Role.Tab, onClick = { onPick(i) })
                            .semantics { contentDescription = p.label; selected = on },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center) {
                                val icon = p.icon
                                if (icon != null) {
                                    FuseIcon(icon, size = Size.iconS, tint = tint)
                                } else {
                                    io.github.matiyaaa.fuse.ui.shell.app.FuseMark(Modifier.size(Size.iconS), color = tint, spark = c.accent.copy(alpha = 0.5f + 0.5f * t))
                                }
                                if (p.badge && !on) {
                                    Box(
                                        Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-4).dp).size(8.dp).clip(CircleShape).background(c.accent),
                                    )
                                }
                            }
                            // The name widens in with its section and narrows away with it.
                            FText(
                                p.label,
                                Fuse.type.label,
                                color = tint,
                                maxLines = 1,
                                modifier = Modifier
                                    .layout { m, cons ->
                                        val placeable = m.measure(Constraints())
                                        val room = (placeable.width + Space.s.roundToPx())
                                        val w = (room * t).roundToInt().coerceAtMost(cons.maxWidth)
                                        layout(w, placeable.height) { placeable.place(Space.s.roundToPx(), 0) }
                                    }
                                    .graphicsLayer { alpha = t; clip = true },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(Space.s))
        ButtonGlyph(HintButton.NEXT, size = ButtonGlyphDefaults.SmallSize, color = c.textFaint)
    }
}


/** The strip: a touch target tall. */
private val SECTION_HEIGHT = 40.dp
