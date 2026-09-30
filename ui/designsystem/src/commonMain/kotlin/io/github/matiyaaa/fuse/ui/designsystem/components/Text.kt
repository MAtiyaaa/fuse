package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse

/** Text in a Fuse style. [color] defaults to the style's role colour (primary text). */
@Composable
fun FText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Fuse.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Small uppercase section label ("CONTINUE PLAYING"). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Fuse.colors.textMuted) {
    FText(text.uppercase(), Fuse.type.overline, modifier, color, maxLines = 1)
}
