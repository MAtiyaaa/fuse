package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuHeader
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import kotlinx.coroutines.launch

/** A button under a [TextPreviewSpec]: what it says, its icon, what it does. */
data class PreviewAction(val label: String, val icon: ImageVector?, val primary: Boolean = false, val run: () -> Unit)

/**
 * Text to read before it goes anywhere (a diagnostics report before it is saved): a title, a line
 * on what it is, the text itself in a scrolling well, and buttons along the bottom.
 */
data class TextPreviewSpec(
    val title: String,
    val message: String?,
    val text: String,
    val actions: List<PreviewAction>,
    val icon: ImageVector? = null,
)

/**
 * The text preview: Up and Down (and the shoulders, a page at a time) scroll the text, Left and
 * Right walk the buttons, A presses one, B closes. Touch scrolls the text and taps the buttons.
 */
@Composable
internal fun TextPreviewOverlay(app: AppState) {
    val spec = app.textPreview
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    var index by remember(spec) { mutableIntStateOf(spec?.actions?.indexOfFirst { it.primary }?.coerceAtLeast(0) ?: 0) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(spec) {
        if (spec != null) {
            scroll.scrollTo(0)
            app.platform.sounds.play(SoundCue.OPEN)
        }
    }
    fun close() {
        app.textPreview = null
        app.platform.sounds.play(SoundCue.CLOSE)
    }
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            val step = 120
            when (e.action) {
                NavAction.UP -> if (scroll.value > 0) { scope.launch { scroll.animateScrollTo(scroll.value - step) }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (scroll.value < scroll.maxValue) { scope.launch { scroll.animateScrollTo(scroll.value + step) }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.PAGE_UP -> { scope.launch { scroll.animateScrollTo(scroll.value - step * 4) }; NavResult.MOVED }
                NavAction.PAGE_DOWN -> { scope.launch { scroll.animateScrollTo(scroll.value + step * 4) }; NavResult.MOVED }
                NavAction.LEFT -> if (index > 0) { index--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < spec.actions.lastIndex) { index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { spec.actions.getOrNull(index)?.run?.invoke(); NavResult.ACTIVATED }
                NavAction.BACK -> { close(); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        val c = Fuse.colors
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            Panel(Modifier.widthIn(max = minOf(820.dp, room)).heightIn(max = maxHeight - Space.l * 2)) {
                Column(Modifier.padding(Space.xl)) {
                    MenuHeader(s.title, subtitle = s.message, icon = s.icon, subtitleMaxLines = 3)
                    Box(
                        Modifier.weight(1f, fill = false).fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control))
                            .background(c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f)),
                    ) {
                        FText(
                            s.text, Fuse.type.caption, color = c.textMuted, maxLines = Int.MAX_VALUE,
                            modifier = Modifier.fadingEdges(scroll, top = Space.l, bottom = Space.l).verticalScroll(scroll).padding(Space.l),
                        )
                    }
                    Spacer(Modifier.height(Space.l))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End)) {
                        s.actions.forEachIndexed { i, a ->
                            FuseButton(
                                a.label, selected = index == i, icon = a.icon,
                                kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                onClick = { index = i; a.run() },
                            )
                        }
                    }
                }
            }
        }
    }
}
