package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.floor

/**
 * The size [InterfaceSize] would pick by itself for this screen (1 is the normal size), for the
 * setting's Automatic choice to say what it is.
 */
val LocalAutomaticInterfaceSize = compositionLocalOf { 1f }

/** The long edge of Fuse's window in pixels (0 when not known), so art is decoded for the screen it is on now. */
val LocalWindowPx = compositionLocalOf { 0 }

/**
 * Draws [content] at a size that suits the screen. A computer counts a 4K TV's every pixel as one,
 * so there Fuse would be drawn at a quarter of the size it has on a 1080p screen; automatic ([percent]
 * 0) makes it as large as on that 1080p screen instead. Otherwise [percent] of the normal size.
 * Where the system already says how large things should be (phones, tablets, Android TVs, scaled
 * computer screens) automatic leaves it alone.
 */
@Composable
internal fun InterfaceSize(percent: Int, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Measured in the system's own size, so a change of ours never feeds back into it.
        val automatic = automaticInterfaceScale(maxWidth.value, maxHeight.value)
        val scale = if (percent > 0) percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f else automatic
        val density = remember(base, scale) { Density(base.density * scale, base.fontScale) }
        // In steps of a quarter of 1080p's width, so resizing a window doesn't decode the art again at every size.
        val windowPx = (maxOf(constraints.maxWidth, constraints.maxHeight).takeIf { it < Int.MAX_VALUE / 2 } ?: 0).let { (it + 479) / 480 * 480 }
        // Always provided, so a change of size keeps every screen where it is.
        CompositionLocalProvider(LocalDensity provides density, LocalAutomaticInterfaceSize provides automatic, LocalWindowPx provides windowPx) {
            content()
        }
    }
}

/**
 * Automatic interface size for a screen [widthDp] by [heightDp]: normal up to about a 1080p screen
 * (a little over still reads as one), then growing with it in quarter steps: 1.25 at 1440p, 2 at 4K.
 */
fun automaticInterfaceScale(widthDp: Float, heightDp: Float): Float {
    if (widthDp <= 0f || heightDp <= 0f) return 1f
    val fit = minOf(widthDp / REFERENCE_WIDTH, heightDp / REFERENCE_HEIGHT)
    if (fit < START) return 1f
    return (floor(fit * STEPS) / STEPS).coerceIn(1f, MAX_PERCENT / 100f)
}

/** The choices of Settings, Display: Automatic (0) and these percentages. */
val InterfaceSizes = listOf(0, 100, 125, 150, 175, 200, 250, 300)

private const val REFERENCE_WIDTH = 1920f
private const val REFERENCE_HEIGHT = 1080f
private const val START = 1.2f
private const val STEPS = 4f
private const val MIN_PERCENT = 50
private const val MAX_PERCENT = 300
