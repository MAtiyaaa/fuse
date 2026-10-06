package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable

/**
 * What the device can comfortably do, measured once from legitimate device information (never from a
 * device-name list). Drives [PerformanceProfile.AUTOMATIC].
 */
@Serializable
data class CapabilityProfile(
    val cpuCores: Int,
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val maxRefreshRate: Float,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val displayCount: Int,
    /** Android media performance class (0 when not declared). */
    val performanceClass: Int = 0,
    val supportsBlur: Boolean = true,
    val gpuRenderer: String? = null,
) {
    val tier: DeviceTier
        get() = when {
            isLowRamDevice || totalRamMb < 3_000 || cpuCores <= 4 -> DeviceTier.LOW
            totalRamMb >= 7_500 && cpuCores >= 8 && (performanceClass == 0 || performanceClass >= 33) -> DeviceTier.HIGH
            else -> DeviceTier.MID
        }
}

@Serializable
enum class DeviceTier { LOW, MID, HIGH }

@Serializable
enum class PerformanceProfile { AUTOMATIC, LOW_POWER, BALANCED, HIGH_QUALITY }

/** Concrete switches the UI reads; derived from profile + capability + Low Power Mode. */
@Serializable
data class RenderQuality(
    val backgroundVideo: Boolean,
    val blur: Boolean,
    val animatedBackground: Boolean,
    val particles: Boolean,
    val crtShader: Boolean,
    /** How many neighbours on each side to prefetch artwork for. */
    val prefetchDepth: Int,
    /** Largest decode size for hero art, in pixels on the long edge. */
    val heroMaxPx: Int,
    /** How often non-essential values (clock seconds, stats) refresh. */
    val statusRefreshMs: Long,
) {
    companion object {
        /** The profile in effect: the person's choice, or under Automatic the one the device's tier recommends. */
        fun effective(profile: PerformanceProfile, capability: CapabilityProfile?, lowPower: Boolean): PerformanceProfile = when {
            lowPower -> PerformanceProfile.LOW_POWER
            profile != PerformanceProfile.AUTOMATIC -> profile
            capability == null -> PerformanceProfile.BALANCED
            else -> when (capability.tier) {
                DeviceTier.LOW -> PerformanceProfile.LOW_POWER
                DeviceTier.MID -> PerformanceProfile.BALANCED
                DeviceTier.HIGH -> PerformanceProfile.HIGH_QUALITY
            }
        }

        fun of(profile: PerformanceProfile, capability: CapabilityProfile?, lowPower: Boolean): RenderQuality {
            val effective = effective(profile, capability, lowPower)
            val longEdge = capability?.let { maxOf(it.screenWidthPx, it.screenHeightPx) } ?: 1920
            return when (effective) {
                PerformanceProfile.LOW_POWER -> RenderQuality(
                    backgroundVideo = false, blur = false, animatedBackground = false, particles = false,
                    crtShader = false, prefetchDepth = 2, heroMaxPx = minOf(longEdge, 1280), statusRefreshMs = 60_000,
                )
                PerformanceProfile.BALANCED, PerformanceProfile.AUTOMATIC -> RenderQuality(
                    backgroundVideo = true, blur = capability?.supportsBlur ?: true, animatedBackground = true,
                    particles = false, crtShader = true, prefetchDepth = 4, heroMaxPx = minOf(longEdge, 1920),
                    statusRefreshMs = 30_000,
                )
                PerformanceProfile.HIGH_QUALITY -> RenderQuality(
                    backgroundVideo = true, blur = capability?.supportsBlur ?: true, animatedBackground = true,
                    particles = true, crtShader = true, prefetchDepth = 6, heroMaxPx = minOf(longEdge, 2560),
                    statusRefreshMs = 15_000,
                )
            }
        }
    }
}

/** A connected screen as Fuse sees it. */
@Serializable
data class DisplayInfo(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRate: Float,
    val isPrimary: Boolean,
    val isPresentation: Boolean,
    val isOn: Boolean,
    /** Whether Android allows starting another app's activity on this display. Unknown until checked. */
    val canLaunchActivities: Support = Support.UNKNOWN,
)

@Serializable
enum class DualScreenMode {
    OFF,
    /** Library on the main screen, the selected game's art and details on the second. */
    LIBRARY_COMPANION,
    /** The game on the main screen, Fuse's companion on the second. */
    GAME_COMPANION,
    /** The game on the second screen, the library stays on the main one. */
    REVERSE,
}

@Serializable
data class DisplayProfile(
    val mode: DualScreenMode = DualScreenMode.LIBRARY_COMPANION,
    val companionShowsPerformance: Boolean = false,
    val companionTouchControls: Boolean = true,
    /** Where apps open on a device with two screens, unless [appScreens] names one. */
    val appScreen: LaunchDisplay = LaunchDisplay.ASK,
    /** Screens chosen for single apps, by app id ("package/activity"). */
    val appScreens: Map<String, LaunchDisplay> = emptyMap(),
    /** The second screen's page: 0 what the main screen shows, 1 status, 2 controls. */
    val companionPage: Int = 0,
    /**
     * Flipped, the way a 3DS has it: Fuse's menus on the second screen (the one you touch), and the
     * main screen a showcase of what is chosen. Only on a device with a second screen.
     */
    val flipped: Boolean = false,
    /** The second screen shows the main screen's background (its scene or picture) behind what it shows. */
    val companionFollowsBackground: Boolean = true,
    /** Which way round Fuse turns on a device that rotates (Android). */
    val rotation: ScreenRotation = ScreenRotation.AUTO,
    /** The second screen is put away: dark, showing nothing, until it is shown again. */
    val secondScreenHidden: Boolean = false,
)

/**
 * How Fuse follows the device's rotation. [AUTO] keeps a handheld (or any device whose screen is
 * wide when upright, or that has two screens) landscape either way up by its sensor, even while
 * Android's rotation lock is on, so turning it over and back never leaves Fuse stuck; elsewhere it
 * follows Android.
 */
@Serializable
enum class ScreenRotation { AUTO, SYSTEM, LANDSCAPE, PORTRAIT, ANY }

/** Live system status for the status area. Fields are null when the platform doesn't report them. */
@Serializable
data class SystemStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** Minutes until full while [charging], until empty otherwise; null while there is no estimate. */
    val batteryMinutes: Int? = null,
    /** Plugged in and charged. */
    val batteryFull: Boolean = false,
    val wifi: ConnectionState = ConnectionState.UNKNOWN,
    val wifiStrength: Int? = null,
    val bluetooth: ConnectionState = ConnectionState.UNKNOWN,
    val network: ConnectionState = ConnectionState.UNKNOWN,
    /** A cable is plugged in and its network is up. Shown in Wi-Fi's place when Wi-Fi isn't connected. */
    val ethernet: Boolean = false,
)

@Serializable
enum class ConnectionState { CONNECTED, ON, OFF, UNKNOWN }

/**
 * One live metric for the performance panel. Providers only emit metrics they can really measure; a
 * metric Fuse cannot read is absent, never zero.
 */
@Serializable
data class PerformanceMetric(
    val key: String,
    val label: String,
    val value: String,
    /** 0..1 for a bar, when meaningful. */
    val fraction: Float? = null,
    val source: String,
)

/**
 * The motion that goes with the effects Fuse uses on this device (setup's "Recommended: High
 * effects", and Settings, Performance): Minimal with light effects, Standard with balanced ones,
 * Enhanced with high quality. Reduced is only ever the person's own choice.
 */
fun recommendedMotion(profile: PerformanceProfile, capability: CapabilityProfile?, lowPower: Boolean): MotionProfile =
    when (RenderQuality.effective(profile, capability, lowPower)) {
        PerformanceProfile.LOW_POWER -> MotionProfile.MINIMAL
        PerformanceProfile.HIGH_QUALITY -> MotionProfile.ENHANCED
        PerformanceProfile.BALANCED, PerformanceProfile.AUTOMATIC -> MotionProfile.STANDARD
    }

/**
 * Motion left on Automatic: what the device's effects call for, except that a theme made to be
 * calm ([theme] Minimal or Reduced) stays as calm when the device could do more.
 */
fun automaticMotion(theme: MotionProfile, recommended: MotionProfile): MotionProfile =
    if (theme < MotionProfile.STANDARD) minOf(theme, recommended) else recommended
