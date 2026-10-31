package io.github.matiyaaa.fuse.ui.designsystem.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The kinds of device a household holds that Lucide has no picture for, drawn by hand on its grid
 * (24 x 24, the same rounded line), so they sit beside its monitor, laptop, phone and television as
 * one family. Each reads at 14 dp: a body, its screens, and a dot for each hand's controls.
 */
object DeviceIcons {
    /** A handheld with its screen between its controls: a Steam Deck, an Ally, a Legion Go. */
    val Handheld: ImageVector by lazy {
        lineIcon(
            "Handheld",
            // The body, round at both ends where the hands hold it.
            "M6.5 6.5h11a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5h-11a5.5 5.5 0 0 1-5.5-5.5a5.5 5.5 0 0 1 5.5-5.5Z",
            // The screen in the middle.
            "M9.5 9.25h5a1.25 1.25 0 0 1 1.25 1.25v3a1.25 1.25 0 0 1-1.25 1.25h-5a1.25 1.25 0 0 1-1.25-1.25v-3a1.25 1.25 0 0 1 1.25-1.25Z",
            // A stick on the left, the buttons on the right.
            "M4.75 12h.01",
            "M19.25 12h.01",
        )
    }

    /** A handheld that opens like a book, a screen in each half: an AYN Thor, a 3DS. */
    val DualScreenHandheld: ImageVector by lazy {
        lineIcon(
            "DualScreenHandheld",
            // The lid: its screen fills it.
            "M6.5 2h11a2 2 0 0 1 2 2v6h-15v-6a2 2 0 0 1 2-2Z",
            // The base, rounder where it sits in the hands, a hinge's gap above it.
            "M4.5 12.5h15v5a4 4 0 0 1-4 4h-7a4 4 0 0 1-4-4Z",
            // The second screen in the base, between the controls.
            "M10 15h4v3.5h-4Z",
            "M7.25 16.75h.01",
            "M16.75 16.75h.01",
        )
    }
}
