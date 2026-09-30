package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.designsystem.res.manrope_bold
import io.github.matiyaaa.fuse.ui.designsystem.res.manrope_medium
import io.github.matiyaaa.fuse.ui.designsystem.res.manrope_regular
import io.github.matiyaaa.fuse.ui.designsystem.res.manrope_semibold
import io.github.matiyaaa.fuse.ui.designsystem.res.sora_bold
import io.github.matiyaaa.fuse.ui.designsystem.res.sora_medium
import io.github.matiyaaa.fuse.ui.designsystem.res.sora_semibold
import org.jetbrains.compose.resources.Font

/**
 * Two families: Sora for display text and numbers (wide, geometric, reads well on a TV-distance
 * handheld), Manrope for everything you read. Both are SIL OFL 1.1 (see licenses/).
 */
@Immutable
data class FuseTypography(
    /** Title of the focused game when it has no logo art. */
    val hero: TextStyle,
    /** Screen titles. */
    val display: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    /** Small uppercase section labels. */
    val overline: TextStyle,
    /** Clock and counters: tabular figures so digits don't jitter. */
    val numeric: TextStyle,
    val numericLarge: TextStyle,
)

@Composable
fun rememberFuseTypography(): FuseTypography {
    val sora = FontFamily(
        Font(Res.font.sora_medium, FontWeight.Medium),
        Font(Res.font.sora_semibold, FontWeight.SemiBold),
        Font(Res.font.sora_bold, FontWeight.Bold),
    )
    val manrope = FontFamily(
        Font(Res.font.manrope_regular, FontWeight.Normal),
        Font(Res.font.manrope_medium, FontWeight.Medium),
        Font(Res.font.manrope_semibold, FontWeight.SemiBold),
        Font(Res.font.manrope_bold, FontWeight.Bold),
    )
    val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
    fun display(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
        fontFamily = sora, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
        letterSpacing = tracking.em, lineHeightStyle = trim,
    )
    fun text(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
        fontFamily = manrope, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
        letterSpacing = tracking.em, lineHeightStyle = trim,
    )
    return FuseTypography(
        hero = display(46, 52, FontWeight.Bold, -0.025),
        display = display(32, 38, FontWeight.SemiBold, -0.02),
        title = display(22, 28, FontWeight.SemiBold, -0.01),
        titleSmall = display(17, 22, FontWeight.SemiBold, -0.005),
        body = text(15, 22, FontWeight.Medium),
        bodyStrong = text(15, 22, FontWeight.SemiBold),
        label = text(13, 18, FontWeight.SemiBold, 0.005),
        caption = text(12, 16, FontWeight.Medium, 0.01),
        overline = text(11, 14, FontWeight.Bold, 0.14),
        numeric = display(15, 20, FontWeight.SemiBold, 0.0).copy(fontFeatureSettings = "tnum"),
        numericLarge = display(40, 44, FontWeight.SemiBold, -0.02).copy(fontFeatureSettings = "tnum"),
    )
}
