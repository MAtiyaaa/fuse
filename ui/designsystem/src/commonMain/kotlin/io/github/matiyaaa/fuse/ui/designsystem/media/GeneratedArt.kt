package io.github.matiyaaa.fuse.ui.designsystem.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import kotlin.math.abs

/** Which slot generated art fills; it lays out differently for each. */
enum class ArtSlot { ICON, BOX, WIDE, HERO, SYSTEM }

/**
 * Original, deterministic placeholder art: a lit gradient in the platform's accent with the title set
 * in type. Used whenever a game has no artwork yet, so every tile still looks intentional. The same
 * title always produces the same composition.
 */
@Composable
fun GeneratedArt(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    slot: ArtSlot = ArtSlot.ICON,
    label: String? = null,
) {
    val seed = remember(title) { abs(title.hashCode()) }
    val base = lerp(Color(0xFF0B0C10), accent, 0.42f)
    val deep = lerp(Color(0xFF050608), accent, 0.12f)
    val light = lerp(accent, Color.White, 0.25f)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(base, deep), start = Offset.Zero, end = Offset.Infinite)),
    ) {
        val w = constraints.maxWidth.toFloat()
        val maxH = maxHeight.value
        // A soft light source whose position depends on the title, so tiles side by side differ.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width * (0.2f + (seed % 60) / 100f)
            val cy = size.height * (0.1f + (seed / 7 % 40) / 100f)
            drawCircle(
                Brush.radialGradient(
                    listOf(light.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.maxDimension * 0.75f,
                ),
                radius = size.maxDimension,
                center = Offset(cx, cy),
            )
            // Fine diagonal lines, very faint, for texture.
            val step = size.minDimension / 9f
            var x = -size.height
            while (x < size.width) {
                drawLine(
                    Color.White.copy(alpha = 0.035f),
                    Offset(x, size.height),
                    Offset(x + size.height, 0f),
                    strokeWidth = 1f,
                )
                x += step
            }
        }
        val pad = (w / 12f).coerceIn(6f, 28f)
        val type = Fuse.type
        when (slot) {
            ArtSlot.ICON, ArtSlot.SYSTEM -> {
                val initials = remember(title) { initialsOf(title) }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BasicText(
                        initials,
                        style = type.display.copy(
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = (maxH / 3.2f / (if (initials.length > 2) 1.3f else 1f) ).coerceAtLeast(10f).sp,
                            lineHeight = (maxH / 3f ).coerceAtLeast(10f).sp,
                        ),
                        maxLines = 1,
                    )
                }
                if (label != null) {
                    BasicText(
                        label,
                        modifier = Modifier.align(Alignment.BottomStart).padding(pad.dp / 2),
                        style = type.overline.copy(color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp),
                    )
                }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(pad.dp / 2),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                BasicText(
                    label ?: "",
                    style = type.overline.copy(color = Color.White.copy(alpha = 0.7f)),
                )
                BasicText(
                    title,
                    style = (if (slot == ArtSlot.HERO) type.hero else type.title).copy(color = Color.White),
                    maxLines = if (slot == ArtSlot.BOX) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "The Legend of Zelda" -> "LZ", "Tetris" -> "TE", "F-Zero X" -> "FZ". */
fun initialsOf(title: String): String {
    val stop = setOf("the", "a", "an", "of", "and", "to", "in", "on", "for")
    val words = title
        .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
        .split(Regex("""[\s\-:_.]+"""))
        .filter { it.isNotBlank() && it.lowercase() !in stop }
    return when {
        words.isEmpty() -> title.take(2).uppercase()
        words.size == 1 -> words[0].take(2).uppercase()
        else -> words.take(2).joinToString("") { it.take(1) }.uppercase()
    }
}
