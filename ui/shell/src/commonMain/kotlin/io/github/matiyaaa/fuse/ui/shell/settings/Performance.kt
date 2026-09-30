package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.model.CapabilityProfile
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.PerformanceProfile
import io.github.matiyaaa.fuse.model.RenderQuality

/**
 * One line saying what the current performance settings do, for Settings and the Quick Menu, so a
 * profile change is visible even before its effects are: refresh rate (Android), video previews,
 * blur, moving backgrounds and how large background art is decoded.
 */
fun performanceSummary(profile: PerformanceProfile, lowPower: Boolean, device: CapabilityProfile, host: Host): String {
    val q = RenderQuality.of(profile, device, lowPower)
    val highest = q.heroMaxPx > 1920
    val parts = buildList {
        if (host == Host.ANDROID && device.maxRefreshRate > 0f) {
            add(if (highest && device.maxRefreshRate > 61f) "${device.maxRefreshRate.toInt()} Hz" else "60 Hz")
        }
        add(if (q.backgroundVideo) "Video previews" else "No video previews")
        if (q.blur) add("Blur")
        add(if (q.animatedBackground) "Moving backgrounds" else "Still backgrounds")
        add("Art up to ${q.heroMaxPx} px")
        add("Preloads ${q.prefetchDepth} ahead")
    }
    return parts.joinToString("  ·  ")
}
