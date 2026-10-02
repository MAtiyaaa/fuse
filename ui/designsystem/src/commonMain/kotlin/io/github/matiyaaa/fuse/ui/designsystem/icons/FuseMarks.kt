package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Marks drawn for Fuse itself, in the same 24 x 24 line style as [FuseIcons] (which is generated
 * from Lucide and never edited by hand). Original drawings, not taken from any app or brand.
 */
object FuseMarks {
    /**
     * Cartridge: a game cartridge seen from the front, its label window and the contacts along its
     * bottom edge. Stands for the Cartridge app wherever Fuse names it.
     */
    val Cartridge: ImageVector by lazy {
        lineIcon(
            "Cartridge",
            "M6.5 2.5h11a2 2 0 0 1 2 2v12.2l-1.5 1.5V21.5H6V18.2l-1.5-1.5V4.5a2 2 0 0 1 2-2Z",
            "M9 6h6a1 1 0 0 1 1 1v5a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-5a1 1 0 0 1 1 -1Z",
            "M9 21.5v-2",
            "M12 21.5v-2",
            "M15 21.5v-2",
        )
    }
}
