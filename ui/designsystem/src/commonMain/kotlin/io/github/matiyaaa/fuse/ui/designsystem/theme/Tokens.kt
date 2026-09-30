package io.github.matiyaaa.fuse.ui.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Every gap, padding and offset in Fuse comes from here, so screens line up with
 * each other. Steps grow roughly by 1.5x so neighbouring values are always visibly different.
 */
object Space {
    val hair: Dp = 1.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val x3: Dp = 48.dp
    val x4: Dp = 64.dp
    val x5: Dp = 96.dp

    /** Horizontal page margin: content never touches the screen edge closer than this. */
    val gutter: Dp = 40.dp

    /** Compact gutter for narrow or short screens (phones in portrait, small second screens). */
    val gutterCompact: Dp = 20.dp
}

/** Corner radii. Tiles use continuous (squircle) corners; everything else uses these. */
object Radius {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 18.dp
    val xl: Dp = 26.dp
    val pill: Dp = 999.dp
}

/** Fixed sizes that recur: icons, hit targets, bars. */
object Size {
    val iconS: Dp = 16.dp
    val iconM: Dp = 20.dp
    val iconL: Dp = 24.dp
    val iconXL: Dp = 32.dp

    /** Minimum touch target. */
    val touch: Dp = 48.dp

    /** Top HUD line (tabs + status). */
    val hudHeight: Dp = 64.dp

    /** Bottom hint line. */
    val hintHeight: Dp = 44.dp

    /** Row height in menus and settings lists. */
    val row: Dp = 56.dp
    val rowCompact: Dp = 44.dp

    /** The spark bar under a focused item. */
    val sparkWidth: Dp = 28.dp
    val sparkHeight: Dp = 3.dp

    /** Hairline stroke used for panel edges. */
    val stroke: Dp = 1.dp
    val focusStroke: Dp = 2.dp
}

/**
 * Artwork aspect ratios (width / height). Layouts size tiles from these so the same art always
 * crops the same way.
 */
object Aspect {
    const val ICON = 1f
    const val BOX = 0.72f
    const val CAPSULE = 2f / 3f
    const val GRID_WIDE = 460f / 215f
    const val HERO = 1920f / 620f
    const val SCREENSHOT = 16f / 9f
    const val SYSTEM_CARD = 1.45f
}

/** Tile sizes for each library presentation, derived from screen height at runtime. */
@Immutable
data class TileMetrics(
    val icon: Dp,
    val capsuleWidth: Dp,
    val coverWidth: Dp,
    val systemCardWidth: Dp,
    val gap: Dp,
) {
    companion object {
        /**
         * One 1x1 icon is about 18% of the usable height, so a 720dp-tall handheld shows three rows
         * under the hero band and a tall phone still gets thumb-sized tiles.
         */
        fun forHeight(height: Dp, width: Dp): TileMetrics {
            val icon = (height * 0.19f).coerceIn(84.dp, 168.dp)
            val capsule = (height * 0.30f).coerceIn(120.dp, 260.dp)
            val cover = ((width - Space.gutter * 2) / 7f).coerceIn(96.dp, 180.dp)
            val system = (width * 0.26f).coerceIn(200.dp, 380.dp)
            return TileMetrics(icon, capsule, cover, system, gap = (icon * 0.18f).coerceIn(12.dp, 24.dp))
        }
    }
}
