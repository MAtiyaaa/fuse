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
    /** For a profile made for a device: how many lines its screen shows. */
    val lines: Int? = null,
)

/**
 * A kind of device a profile is fitted to: its name, how many lines its screen shows, and whether
 * it is held and runs on its battery (where drawing more than the screen shows costs play time).
 */
data class DeviceClass(val id: String, val name: String, val lines: Int, val handheld: Boolean)

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

    /** The start of a device profile's id (`device.steam-deck`). */
    const val DEVICE = "device."

    /**
     * The devices Fuse fits profiles to, by their makers' own specifications: the Steam Deck's
     * 1280 by 800 screen (LCD and OLED alike), the ROG Ally's 1920 by 1080 (Ally and Ally X), an
     * Android handheld's 1920 by 1080 (the Retroid Pocket 5 and AYN Odin 2, and those like them),
     * and a 4K TV's 3840 by 2160.
     */
    val DEVICES = listOf(
        DeviceClass("steam-deck", "Steam Deck", 800, handheld = true),
        DeviceClass("rog-ally", "ROG Ally", 1080, handheld = true),
        DeviceClass("android-handheld", "Android Handheld", 1080, handheld = true),
        DeviceClass("tv-4k", "4K TV", 2160, handheld = false),
    )

    /** The devices offered on [host]: on Android its handhelds and a TV, on a computer the PC handhelds and a TV. */
    fun devicesFor(host: Host): List<DeviceClass> =
        DEVICES.filter { d -> if (host == Host.ANDROID) d.id == "android-handheld" || !d.handheld else d.id != "android-handheld" }

    /**
     * Each emulator's internal resolution setting, the lines one step of it is (as the emulator's
     * own menu names its steps: PCSX2's "3x Native (~1080px/FHD)" and Dolphin's "3x Native
     * (1920x1584) for 1080p" make a step about 360 lines, DuckStation's 240 and PPSSPP's 272 the
     * consoles' own), and its vertical sync switch, on as each writes it.
     */
    private class Fit(val resolution: String, val linesPerStep: Int, val vsync: Pair<String, String>?, val console: String)

    private val FITS = mapOf(
        "pcsx2" to Fit("pcsx2.upscale", 360, "pcsx2.vsync" to "true", "PS2"),
        "dolphin" to Fit("dolphin.resolution", 360, "dolphin.vsync" to "True", "console"),
        "duckstation" to Fit("duckstation.scale", 240, "duckstation.vsync" to "true", "PlayStation"),
        "ppsspp" to Fit("ppsspp.resolution", 272, null, "PSP"),
    )

    /**
     * A profile per device in [DEVICES] for [family]: the internal resolution fitted to the
     * device's lines, and frames paced to its screen. In the hand, the step nearest the screen's
     * lines (the lower of two as near), since drawing what the screen can't show only spends the
     * battery; on a TV, the first step that fills it, for the sharpest picture. Each choice is said.
     */
    fun forDevices(family: String, host: Host): List<SettingProfile> {
        val fit = FITS[family] ?: return emptyList()
        val settings = NativeSettings.forEmulator(family, host)
        val resolution = settings.firstOrNull { it.id == fit.resolution && it.perGame } ?: return emptyList()
        val steps = resolution.choices.mapNotNull { c -> c.value.toIntOrNull()?.takeIf { it > 0 }?.let { it to c } }
        if (steps.isEmpty()) return emptyList()
        val vsync = fit.vsync?.takeIf { (id, v) -> settings.any { s -> s.id == id && s.perGame && s.choices.any { it.value == v } } }
        return devicesFor(host).map { d ->
            val (step, choice) = if (d.handheld) {
                steps.minWith(compareBy<Pair<Int, SettingChoice>> { kotlin.math.abs(it.first * fit.linesPerStep - d.lines) }.thenBy { it.first })
            } else {
                steps.firstOrNull { it.first * fit.linesPerStep >= d.lines } ?: steps.last()
            }
            val why = if (d.handheld) {
                "the step nearest its ${d.lines} lines, so nothing is drawn that the screen can't show and the battery lasts"
            } else {
                "the first step that fills its ${d.lines} lines, for the sharpest picture"
            }
            val about = buildString {
                append("For a ${d.name}: ${choice.label}, $why.")
                if (step == 1) append(" That is the ${fit.console}'s own resolution.")
                if (vsync != null) append(" Frames paced to the screen: no tearing, and no frames drawn that it never shows.")
            }
            SettingProfile(DEVICE + d.id, d.name, about, listOfNotNull(fit.resolution to choice.value, vsync).toMap(), lines = d.lines)
        }
    }

    fun builtIn(family: String, host: Host): List<SettingProfile> = fuseOwn(family, host) + forDevices(family, host)

    private fun fuseOwn(family: String, host: Host): List<SettingProfile> {
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
            "dolphin" -> listOfNotNull(
                profile(
                    VANILLA, "Vanilla", "As on the console: its own resolution and picture shape.",
                    "dolphin.resolution" to "1", "dolphin.aspect" to "0", "dolphin.widescreen" to "False",
                ),
                profile(
                    PERFORMANCE, "Performance", "The console's own resolution on two cores, without waiting for the screen.",
                    "dolphin.resolution" to "1", "dolphin.dualcore" to "True", "dolphin.vsync" to "False",
                ),
                profile(
                    QUALITY, "Visual Quality", "Three times the resolution (1080p), with shaders compiled without stutter. Needs a capable graphics card.",
                    "dolphin.resolution" to "3", "dolphin.shaders" to "2",
                ),
                profile(
                    BATTERY, "Battery Saver", "Twice the resolution, frames paced to the screen.",
                    "dolphin.resolution" to "2", "dolphin.vsync" to "True",
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
