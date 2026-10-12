package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Marks kept as their makers draw them: Fuse One's, and those of other apps Fuse works with (unlike
 * [FuseIcons], which is generated from Lucide and never edited by hand). Each is one flat colour,
 * tinted like any icon.
 */
object FuseMarks {
    /**
     * Fuse One's mark: Fuse's own frame and fuse, the fuse rising into a 1 with the spark lit at its
     * top (docs/assets/brand/fuse-one-mark.svg). Drawn at icon weight, so it sits with the line icons.
     */
    val FuseOne: ImageVector by lazy {
        ImageVector.Builder(name = "FuseOne", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 100f, viewportHeight = 100f)
            .addPath(
                pathData = addPathNodes(
                    // Fuse's squircle frame, as the brand file draws it.
                    "M49.8 5h.4c15.68 0 23.52 0 29.51 3.05a28 28 0 0 1 12.24 12.24C95 26.28 95 34.12 95 49.8v.4" +
                        "c0 15.68 0 23.52-3.05 29.51a28 28 0 0 1-12.24 12.24C73.72 95 65.88 95 50.2 95h-.4" +
                        "c-15.68 0-23.52 0-29.51-3.05A28 28 0 0 1 8.05 79.71C5 73.72 5 65.88 5 50.2v-.4" +
                        "c0-15.68 0-23.52 3.05-29.51A28 28 0 0 1 20.29 8.05C26.28 5 34.12 5 49.8 5Z",
                ),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = MARK_STROKE,
            )
            .addPath(
                pathData = addPathNodes("M36 46c9 0 12-12 22-12 3.2 0 5.2-1.5 6-4V72"),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = MARK_STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
            .addPath(
                pathData = addPathNodes("M56 30a8 8 0 1 0 16 0a8 8 0 1 0-16 0Z"),
                fill = SolidColor(Color.Black),
            )
            .build()
    }

    /** The marks' line, as heavy as the line icons' at 24 dp. */
    private const val MARK_STROKE = 8.5f

    /**
     * Cartridge's own mark, its newer one ("Marquee", Cartridge 0.9.53): a rounded panel with a C cut
     * out of it, as if lit from behind, as Cartridge draws it (`MARK` in `src/brand.js` of
     * github.com/abdu2304/cartridge, MIT). One flat colour, even-odd, readable from 16 px.
     */
    val Cartridge: ImageVector by lazy {
        ImageVector.Builder(name = "Cartridge", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 48f, viewportHeight = 48f)
            .addPath(
                pathData = addPathNodes(
                    "M8 2h32a6 6 0 0 1 6 6v32a6 6 0 0 1-6 6H8a6 6 0 0 1-6-6V8a6 6 0 0 1 6-6z" +
                        "M24 11a13 13 0 1 0 11.3 19.4l-5.6-3.2A6.6 6.6 0 1 1 29.7 20.8l5.6-3.2A13 13 0 0 0 24 11z",
                ),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
            .build()
    }
}

/**
 * Cartridge's colours: its orange mark on its near-black, as on its launcher icon. Fuse uses them
 * wherever it shows Cartridge, whatever Fuse's own theme is, as Cartridge does.
 */
object CartridgeBrand {
    /** The mark's orange (`#EF4B23`). */
    const val ORANGE: Long = 0xFFEF4B23L

    /** The icon's ground and Cartridge's surface (`#16171B`). */
    const val INK: Long = 0xFF16171BL

    /** Cartridge's deepest background (`#0C0D10`). */
    const val DEEP: Long = 0xFF0C0D10L
}
