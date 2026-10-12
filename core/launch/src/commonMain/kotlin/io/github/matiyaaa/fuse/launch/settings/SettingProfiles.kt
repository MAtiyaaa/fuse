package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host

/**
 * A set of values for an emulator's settings ([NativeSetting.id] to value) that a game can be given
 * at once. Built-in ones are made only of settings the emulator has and values it accepts; a
 * person's own are the values a game had when they were saved.
 */
data class SettingProfile(
    val id: String,
    val name: String,
    val about: String,
    val values: Map<String, String>,
    val own: Boolean = false,
)

/**
 * The profiles Fuse offers for each emulator it changes settings in. They are starting points, not
 * promises: how a game runs depends on the computer, and the emulators' own game databases (PCSX2's
 * GameIndex, DuckStation's gamedb, PPSSPP's compat.ini) still apply their fixes for a game whichever
 * profile it has.
 */
object SettingProfiles {
    const val VANILLA = "vanilla"
    const val PERFORMANCE = "performance"
    const val QUALITY = "quality"
    const val BATTERY = "battery"

    fun builtIn(family: String, host: Host): List<SettingProfile> {
        val offered = NativeSettings.forEmulator(family, host).filter { it.perGame }
        fun profile(id: String, name: String, about: String, vararg values: Pair<String, String>): SettingProfile? {
            // Only what this emulator has and accepts.
            val kept = values.filter { (k, v) -> offered.any { s -> s.id == k && s.choices.any { it.value == v } } }.toMap()
            return if (kept.isEmpty()) null else SettingProfile(id, name, about, kept)
        }
        return when (family) {
            "pcsx2" -> listOfNotNull(
                profile(VANILLA, "Vanilla", "As on a PS2: its own resolution and picture shape.", "pcsx2.upscale" to "1", "pcsx2.aspect" to "Auto 4:3/3:2"),
                profile(PERFORMANCE, "Performance", "The PS2's own resolution, without waiting for the screen: the least work for the graphics card.", "pcsx2.upscale" to "1", "pcsx2.vsync" to "false"),
                profile(QUALITY, "Visual Quality", "Drawn at three times the PS2's resolution (about 1080p). Needs a capable graphics card.", "pcsx2.upscale" to "3", "pcsx2.vsync" to "true"),
                profile(BATTERY, "Battery Saver", "The PS2's own resolution, frames paced to the screen so no work is wasted.", "pcsx2.upscale" to "1", "pcsx2.vsync" to "true"),
            )
            "duckstation" -> listOfNotNull(
                profile(
                    VANILLA, "Vanilla", "As on a PlayStation: its own resolution, sharp pixels, the original wobble.",
                    "duckstation.scale" to "1", "duckstation.filter" to "Nearest", "duckstation.pgxp" to "false", "duckstation.widescreen" to "false",
                ),
                profile(
                    PERFORMANCE, "Performance", "The PlayStation's own resolution without geometry correction: the least work.",
                    "duckstation.scale" to "1", "duckstation.pgxp" to "false", "duckstation.vsync" to "false",
                ),
                profile(
                    QUALITY, "Visual Quality", "Four times the resolution (960p) with steadier 3D (PGXP) and smoothed textures.",
                    "duckstation.scale" to "4", "duckstation.pgxp" to "true", "duckstation.filter" to "Bilinear",
                ),
                profile(
                    BATTERY, "Battery Saver", "Twice the resolution, frames paced to the screen.",
                    "duckstation.scale" to "2", "duckstation.pgxp" to "false", "duckstation.vsync" to "true",
                ),
            )
            "ppsspp" -> listOfNotNull(
                profile(
                    VANILLA, "Vanilla", "As on a PSP: its own resolution, no upscaled or filtered textures.",
                    "ppsspp.resolution" to "1", "ppsspp.texscale" to "1", "ppsspp.anisotropy" to "0", "ppsspp.filtering" to "1",
                ),
                profile(
                    PERFORMANCE, "Performance", "Twice the PSP's resolution with no texture upscaling, which is the costly part.",
                    "ppsspp.resolution" to "2", "ppsspp.texscale" to "1", "ppsspp.hwtransform" to "True",
                ),
                profile(
                    QUALITY, "Visual Quality", "Four times the PSP's resolution (1088p) with 16x anisotropic filtering.",
                    "ppsspp.resolution" to "4", "ppsspp.anisotropy" to "4", "ppsspp.filtering" to "4", "ppsspp.hwtransform" to "True",
                ),
                profile(
                    BATTERY, "Battery Saver", "Twice the PSP's resolution, nothing extra.",
                    "ppsspp.resolution" to "2", "ppsspp.texscale" to "1", "ppsspp.anisotropy" to "0",
                ),
            )
            else -> emptyList()
        }
    }

    /** Whether a game whose values are [current] (setting id to value) has [profile]. */
    fun matches(profile: SettingProfile, current: Map<String, String>, settings: List<NativeSetting>): Boolean =
        profile.values.all { (k, v) ->
            val s = settings.firstOrNull { it.id == k } ?: return@all false
            current[k]?.let { s.same(it, v) } == true
        }
}
