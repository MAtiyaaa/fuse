package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Platforms whose games draw on two screens: the DS family and the 3DS family (top and bottom
 * screen) and the Wii U (TV and GamePad). A host with a second display can give the emulator the
 * second screen for these; every other platform leaves it free.
 */
object DualScreenPlatforms {
    val ids: Set<PlatformId> = platforms("nds", "nintendo-dsi", "3ds", "new-nintendo-3ds", "wiiu")

    fun usesSecondScreen(id: PlatformId): Boolean = id in ids
}
