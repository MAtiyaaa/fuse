package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space

/**
 * Text in a Fuse style. [color] defaults to the style's role colour (primary text). Long text
 * ends in an ellipsis rather than being cut. [minLines] keeps room for text that may wrap, so a
 * card's layout doesn't change between one line and two.
 */
@Composable
fun FText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Fuse.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    minLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        minLines = minLines.coerceAtMost(maxLines),
        overflow = overflow,
    )
}

/**
 * Small uppercase section label ("CONTINUE PLAYING"). A quiet [count] may follow it in tabular
 * figures, an [icon] may lead it, and with [rule] a hairline runs on to the end of the row, for
 * sections in long pages that want a clear edge. Screen readers hear it as a heading.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Fuse.colors.textMuted,
    /** A quiet count after the title ("12"). */
    count: String? = null,
    icon: ImageVector? = null,
    rule: Boolean = false,
) {
    val heading = modifier.semantics { heading() }
    if (count == null && icon == null && !rule) {
        FText(text.uppercase(), Fuse.type.overline, heading, color, maxLines = 1)
        return
    }
    Row(heading, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            FuseIcon(icon, size = SECTION_ICON, tint = color)
            Spacer(Modifier.width(Space.s - Space.xxs))
        }
        FText(text.uppercase(), Fuse.type.overline, color = color, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(Space.s))
            FText(count, Fuse.type.overline.copy(fontFeatureSettings = "tnum"), color = Fuse.colors.textFaint, maxLines = 1)
        }
        if (rule) {
            Spacer(Modifier.width(Space.m))
            Box(Modifier.weight(1f).height(1.dp).background(Fuse.colors.hairline))
        }
    }
}

private val SECTION_ICON = 14.dp
