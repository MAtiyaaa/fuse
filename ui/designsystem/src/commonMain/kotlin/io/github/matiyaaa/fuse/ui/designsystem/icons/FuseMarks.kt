package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Marks of other apps Fuse works with, kept as their makers draw them (unlike [FuseIcons], which is
 * generated from Lucide and never edited by hand). Each is one flat colour, tinted like any icon.
 */
object FuseMarks {
    /**
     * Cartridge's own mark: a cartridge with its label window and its contact strip, as Cartridge
     * draws it (`src/components/Logo.vue` in github.com/MAtiyaaa/cartridge, MIT). Readable from 16 px.
     */
    val Cartridge: ImageVector by lazy {
        ImageVector.Builder(name = "Cartridge", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 48f, viewportHeight = 48f)
            .addPath(
                pathData = addPathNodes(
                    "M10 3h21.6L41 12.4V41a4 4 0 0 1-4 4H10a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z" +
                        "M13 10.5a2 2 0 0 0-2 2V26a2 2 0 0 0 2 2h19a2 2 0 0 0 2-2V12.5a2 2 0 0 0-2-2z" +
                        "M13.5 34a1.75 1.75 0 0 0 0 3.5h20a1.75 1.75 0 0 0 0-3.5z",
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
