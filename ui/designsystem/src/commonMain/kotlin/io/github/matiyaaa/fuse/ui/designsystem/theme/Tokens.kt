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

    /** Small marks inside badges, chips and rows (a heart on a tile, a check, a chevron). */
    val iconXS: Dp = 14.dp

    /** Controller button glyphs in the hint line. */
    val glyph: Dp = 22.dp

    /** Smaller glyphs beside tabs and inside panels, where the hint line's would shout. */
    val glyphS: Dp = 18.dp

    /** Chips, filter pills and segmented controls. */
    val chip: Dp = 36.dp

    /** Chips inside dense rows and on small screens. */
    val chipCompact: Dp = 28.dp

    /** A round state mark on a tile (favourite, update, warning). Its icon is [badgeIcon]. */
    val badge: Dp = 24.dp
    val badgeIcon: Dp = 14.dp

    /** Status and attention dots. */
    val dot: Dp = 8.dp

    /** Art in list rows (search results, pickers); [thumbL] for rows that lead with it. */
    val thumb: Dp = 40.dp
    val thumbL: Dp = 56.dp

    /** Dividers between rows and sections. Same weight as [stroke], named for what it separates. */
    val divider: Dp = 1.dp

    /** Track thickness of progress bars and sliders. */
    val track: Dp = 4.dp

    /** Space between a focused item and its outline ring. */
    val focusGap: Dp = 3.dp

    /** Distance from a tile's bottom edge to its spark bar (before the lift scales it). */
    val sparkGap: Dp = 7.dp

    /**
     * Room to leave under a row of tiles before any label or next row starts, so the lifted tile and
     * its spark bar never touch what is below. Enough for tiles up to about 180 dp tall; taller tiles
     * need about an eighth of their height.
     */
    val sparkClearance: Dp = 20.dp
}

/**
 * One level of the elevation family. Depth reads the same way everywhere because every level pairs
 * a shadow with a light edge along its top: the room is flat, tiles and panels sit just above it,
 * raised surfaces above those, overlays highest. Pick the level for what a surface is, never a
 * shadow size by eye.
 */
@Immutable
data class ElevationLevel(
    /** Shadow elevation (0 for none). */
    val shadow: Dp,
    /** Opacity of the white light edge along the top in dark themes. */
    val edge: Float,
    /** The same in light themes, where white only shows over tinted or darker fills. */
    val edgeOnLight: Float,
) {
    /** The light edge's opacity for a dark or light theme. */
    fun edgeAlpha(dark: Boolean): Float = if (dark) edge else edgeOnLight
}

/** The elevation scale. See [ElevationLevel]. */
object Elevation {
    /** The room itself: flat, no edge. */
    val room = ElevationLevel(shadow = 0.dp, edge = 0f, edgeOnLight = 0f)

    /** A tile at rest: a contact shadow and a quiet edge. */
    val tile = ElevationLevel(shadow = 3.dp, edge = 0.14f, edgeOnLight = 0.55f)

    /** A focused (lifted) tile. */
    val tileFocused = ElevationLevel(shadow = 18.dp, edge = 0.30f, edgeOnLight = 0.85f)

    /** Panels, menus and sheets. */
    val panel = ElevationLevel(shadow = 6.dp, edge = 0.12f, edgeOnLight = 0.5f)

    /** Surfaces on panels: selected rows, cards on sheets. */
    val raised = ElevationLevel(shadow = 10.dp, edge = 0.16f, edgeOnLight = 0.6f)

    /** Overlays above everything: dialogs, context menus, the quick menu. */
    val overlay = ElevationLevel(shadow = 24.dp, edge = 0.18f, edgeOnLight = 0.7f)
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
