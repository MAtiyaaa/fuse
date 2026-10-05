package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.ui.designsystem.icons.AvatarGlyphs
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon

/** One of Fuse's profile pictures: a picture on its own two-colour light. */
@Immutable
class AvatarStyle(val id: String, val name: String, private val picture: () -> ImageVector, val from: Color, val to: Color) {
    val glyph: ImageVector get() = picture()
}

/** Fuse's own profile pictures, made for it: every profile picks one, and none needs a photo. */
object FuseAvatars {
    val all: List<AvatarStyle> = listOf(
        AvatarStyle("cat", "Cat", { AvatarGlyphs.Cat }, Color(0xFFFF8A65), Color(0xFFE0457B)),
        AvatarStyle("dog", "Dog", { AvatarGlyphs.Dog }, Color(0xFFFFB74D), Color(0xFFF06A3B)),
        AvatarStyle("bird", "Bird", { AvatarGlyphs.Bird }, Color(0xFF4FC3F7), Color(0xFF3D5AFE)),
        AvatarStyle("fish", "Fish", { AvatarGlyphs.Fish }, Color(0xFF4DD0E1), Color(0xFF1E88E5)),
        AvatarStyle("rabbit", "Rabbit", { AvatarGlyphs.Rabbit }, Color(0xFFF48FB1), Color(0xFFAB47BC)),
        AvatarStyle("turtle", "Turtle", { AvatarGlyphs.Turtle }, Color(0xFF81C784), Color(0xFF00897B)),
        AvatarStyle("squirrel", "Squirrel", { AvatarGlyphs.Squirrel }, Color(0xFFFFAB40), Color(0xFFD84315)),
        AvatarStyle("snail", "Snail", { AvatarGlyphs.Snail }, Color(0xFFCE93D8), Color(0xFF7E57C2)),
        AvatarStyle("panda", "Panda", { AvatarGlyphs.Panda }, Color(0xFFB0BEC5), Color(0xFF455A64)),
        AvatarStyle("paw", "Paw", { AvatarGlyphs.PawPrint }, Color(0xFFFFD54F), Color(0xFFFF7043)),
        AvatarStyle("origami", "Origami", { AvatarGlyphs.Origami }, Color(0xFF80DEEA), Color(0xFF5C6BC0)),
        AvatarStyle("ghost", "Ghost", { AvatarGlyphs.Ghost }, Color(0xFFB39DDB), Color(0xFF512DA8)),
        AvatarStyle("rocket", "Rocket", { AvatarGlyphs.Rocket }, Color(0xFFFF7043), Color(0xFFC2185B)),
        AvatarStyle("crown", "Crown", { AvatarGlyphs.Crown }, Color(0xFFFFE082), Color(0xFFFF8F00)),
        AvatarStyle("flame", "Flame", { AvatarGlyphs.Flame }, Color(0xFFFFCA28), Color(0xFFE53935)),
        AvatarStyle("leaf", "Leaf", { AvatarGlyphs.Leaf }, Color(0xFFAED581), Color(0xFF2E7D32)),
        AvatarStyle("sparkles", "Sparkles", { AvatarGlyphs.Sparkles }, Color(0xFFF8BBD0), Color(0xFF7C4DFF)),
        AvatarStyle("moon", "Moon", { AvatarGlyphs.Moon }, Color(0xFF9FA8DA), Color(0xFF283593)),
        AvatarStyle("sun", "Sun", { AvatarGlyphs.Sun }, Color(0xFFFFE57F), Color(0xFFFB8C00)),
        AvatarStyle("bolt", "Bolt", { AvatarGlyphs.Zap }, Color(0xFFFFF176), Color(0xFFF9A825)),
        AvatarStyle("gem", "Gem", { AvatarGlyphs.Gem }, Color(0xFF84FFFF), Color(0xFF00838F)),
        AvatarStyle("mountain", "Mountain", { AvatarGlyphs.Mountain }, Color(0xFF90CAF9), Color(0xFF37474F)),
        AvatarStyle("gamepad", "Gamepad", { AvatarGlyphs.Gamepad2 }, Color(0xFFEA80FC), Color(0xFF6200EA)),
        AvatarStyle("swords", "Swords", { AvatarGlyphs.Swords }, Color(0xFFEF9A9A), Color(0xFFB71C1C)),
        AvatarStyle("skull", "Skull", { AvatarGlyphs.Skull }, Color(0xFFCFD8DC), Color(0xFF263238)),
        AvatarStyle("bot", "Bot", { AvatarGlyphs.Bot }, Color(0xFF80CBC4), Color(0xFF00695C)),
        AvatarStyle("feather", "Feather", { AvatarGlyphs.Feather }, Color(0xFFFFCCBC), Color(0xFF8D6E63)),
        AvatarStyle("anchor", "Anchor", { AvatarGlyphs.Anchor }, Color(0xFF81D4FA), Color(0xFF01579B)),
        AvatarStyle("cherry", "Cherry", { AvatarGlyphs.Cherry }, Color(0xFFFF8A80), Color(0xFFAD1457)),
        AvatarStyle("pizza", "Pizza", { AvatarGlyphs.Pizza }, Color(0xFFFFCC80), Color(0xFFE65100)),
        AvatarStyle("icecream", "Ice Cream", { AvatarGlyphs.IceCreamCone }, Color(0xFFF8BBD0), Color(0xFFEC407A)),
        AvatarStyle("clover", "Clover", { AvatarGlyphs.Clover }, Color(0xFFB9F6CA), Color(0xFF00C853)),
        AvatarStyle("snowflake", "Snowflake", { AvatarGlyphs.Snowflake }, Color(0xFFE1F5FE), Color(0xFF4FC3F7)),
        AvatarStyle("flower", "Flower", { AvatarGlyphs.Flower2 }, Color(0xFFFF80AB), Color(0xFFD500F9)),
        AvatarStyle("pine", "Pine", { AvatarGlyphs.TreePine }, Color(0xFFA5D6A7), Color(0xFF1B5E20)),
        AvatarStyle("headphones", "Headphones", { AvatarGlyphs.Headphones }, Color(0xFFB388FF), Color(0xFF304FFE)),
        AvatarStyle("rainbow", "Rainbow", { AvatarGlyphs.Rainbow }, Color(0xFFFFAB91), Color(0xFF7986CB)),
        AvatarStyle("orbit", "Orbit", { AvatarGlyphs.Orbit }, Color(0xFF8C9EFF), Color(0xFF1A237E)),
    )

    private val byId = all.associateBy { it.id }

    /** The avatar [id] names; one picked by the id itself when it is unknown (from a newer Fuse). */
    fun of(id: String): AvatarStyle = byId[id] ?: all[(id.hashCode() and 0x7fffffff) % all.size]
}

/**
 * A profile's avatar at [size]: its picture on its light, a soft sheen from the top, and a [ring]
 * (the focus or "in use" mark) drawn around it when given.
 */
@Composable
fun ProfileAvatar(id: String, size: Dp, modifier: Modifier = Modifier, ring: Color? = null, dim: Boolean = false) {
    val style = FuseAvatars.of(id)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val alpha = if (dim) 0.45f else 1f
            drawCircle(Brush.linearGradient(listOf(style.from, style.to), start = Offset.Zero, end = Offset(this.size.width, this.size.height)), r, alpha = alpha)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), center = Offset(r * 0.62f, r * 0.42f), radius = r * 1.1f),
                r,
                alpha = alpha,
            )
            drawCircle(Color.White.copy(alpha = 0.16f * alpha), r - 0.5f, style = Stroke(1f))
            if (ring != null) drawCircle(ring, r - 1.5f, style = Stroke(3f))
        }
        FuseIcon(style.glyph, size = size * 0.5f, tint = Color.White.copy(alpha = if (dim) 0.6f else 0.96f))
    }
}
