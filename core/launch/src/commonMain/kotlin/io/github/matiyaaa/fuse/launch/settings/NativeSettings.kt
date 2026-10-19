package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.model.Host

/** One value a setting can take: what the emulator's file holds, and what to call it. */
data class SettingChoice(val value: String, val label: String)

/**
 * One of an emulator's own settings that Fuse can change, exactly as the emulator stores it: its
 * section and key in the emulator's settings file (and in a game's own file, when [perGame]), the
 * values the emulator accepts, and the one it uses when nothing is set ([default]).
 */
data class NativeSetting(
    val id: String,
    val category: String,
    val label: String,
    val section: String,
    val key: String,
    val choices: List<SettingChoice>,
    val default: String,
    /** The emulator honours it in a game's own settings file. */
    val perGame: Boolean = true,
    val about: String? = null,
    /** Which of the emulator's settings files holds it for every game, when it has several (Dolphin's `GFX`); null for its main one. */
    val file: String? = null,
    /** Its section in a game's own file, when that differs from [section] (Dolphin's `Video_Settings`). */
    val gameSection: String? = null,
) {
    /** The section it is kept under: in a game's own file when [game], else in the file for every game. */
    fun sectionIn(game: Boolean): String = if (game) gameSection ?: section else section

    /** What [value] is called; a value Fuse doesn't offer is shown as it is written. */
    fun labelFor(value: String): String = choices.firstOrNull { same(it.value, value) }?.label ?: value

    /** Two values the emulator reads the same: `2`, `2.0` and `2.000000`; `true` and `True`. */
    fun same(a: String, b: String): Boolean {
        if (a.equals(b, ignoreCase = true)) return true
        val x = a.trim().toDoubleOrNull() ?: return false
        val y = b.trim().toDoubleOrNull() ?: return false
        return x == y
    }
}

/**
 * The settings Fuse offers for each emulator it can change them in, each checked against the
 * emulator's own settings code: PCSX2 (pcsx2/Pcsx2Config.cpp, GSOptions::LoadSave), DuckStation
 * (src/core/settings.cpp, Settings::Load), PPSSPP (Core/Config.cpp, only those flagged PER_GAME
 * can be set for one game) and Dolphin (Core/Config/GraphicsSettings.cpp and MainSettings.cpp; a
 * game's own file maps its sections onto them in Core/ConfigLoaders/GameConfigLoader.cpp).
 */
object NativeSettings {
    private val onOff = listOf(SettingChoice("false", "Off"), SettingChoice("true", "On"))
    private val ppssppOnOff = listOf(SettingChoice("False", "Off"), SettingChoice("True", "On"))

    fun forEmulator(family: String, host: Host): List<NativeSetting> = when (family) {
        "pcsx2" -> pcsx2(host)
        "duckstation" -> duckstation(host)
        "ppsspp" -> ppsspp()
        "dolphin" -> dolphin()
        else -> emptyList()
    }

    /** The emulators Fuse changes settings in, by their family (`pcsx2`, from `linux.pcsx2`). */
    val families = setOf("pcsx2", "duckstation", "ppsspp", "dolphin")

    /**
     * The emulators whose settings Fuse changes: these, and RPCS3, RetroArch, Eden and Citron, Azahar
     * and its family, Cemu, shadPS4, Xenia, Vita3K, Flycast, MAME, Supermodel and melonDS, whose come only as Every Setting (see [Tune]).
     */
    val changeable: Set<String> = families + setOf("rpcs3", "retroarch", "eden", "citron", "azahar", "azaharplus", "lime3ds", "mandarine", "cemu", "shadps4", "xenia", "vita3k", "flycast", "mame", "supermodel", "melonds")

    /**
     * The family an emulator's id keeps its settings as (`linux.pcsx2` is `pcsx2`): Steam's RetroArch
     * is RetroArch, and Eden's nightly builds keep Eden's own folders (src/common/fs/fs_paths.h).
     */
    fun familyOf(id: String): String = id.substringAfter('.').let { f ->
        when (f) {
            "retroarch-steam" -> "retroarch"
            "eden-nightly" -> "eden"
            // PCSX2 for Android: its files laid out as PCSX2's, in the data folder chosen in it.
            "armsx2" -> "pcsx2"
            else -> f
        }
    }

    /**
     * The families Fuse changes on Android: those whose files can be in shared storage (PPSSPP's
     * memory stick, ARMSX2's data folder, RetroArch's own folder). The others keep theirs in their
     * private `Android/data` folders, which no other app can open.
     */
    val onAndroid: Set<String> = setOf("pcsx2", "ppsspp", "retroarch")

    /** Said where Android can't reach an emulator's settings. */
    const val ON_ANDROID = "On Android, Fuse changes PPSSPP's, ARMSX2's and RetroArch's settings, where their files are in your storage."

    /** Emulators of a family whose versions are their own, not the family's (ARMSX2's against PCSX2's). */
    val ownVersions: Set<String> = setOf("armsx2")

    /** Dolphin's graphics settings file (GFX.ini), beside Dolphin.ini. */
    const val DOLPHIN_GFX = "GFX"

    private fun pcsx2(host: Host): List<NativeSetting> {
        val gs = "EmuCore/GS"
        // GSRendererType (pcsx2/Config.h): Auto -1, DX11 3, OGL 12, SW 13, VK 14, DX12 15, Metal 17.
        val renderers = buildList {
            add(SettingChoice("-1", "Automatic"))
            add(SettingChoice("14", "Vulkan"))
            if (host == Host.WINDOWS) { add(SettingChoice("3", "Direct3D 11")); add(SettingChoice("15", "Direct3D 12")) }
            if (host == Host.MACOS) add(SettingChoice("17", "Metal")) else add(SettingChoice("12", "OpenGL"))
            add(SettingChoice("13", "Software"))
        }
        return listOf(
            NativeSetting(
                "pcsx2.renderer", "Graphics", "Renderer", gs, "Renderer", renderers, "-1",
                about = "The graphics interface PCSX2 draws with.",
            ),
            NativeSetting(
                "pcsx2.upscale", "Graphics", "Internal resolution", gs, "upscale_multiplier",
                listOf(
                    SettingChoice("1", "Native (PS2)"), SettingChoice("2", "2x (about 720p)"), SettingChoice("3", "3x (about 1080p)"),
                    SettingChoice("4", "4x (about 1440p)"), SettingChoice("5", "5x (about 1800p)"), SettingChoice("6", "6x (about 4K)"),
                    SettingChoice("8", "8x (about 5K)"),
                ),
                "1",
                about = "How many times the PS2's resolution games are drawn at. Higher is sharper and needs a faster graphics card.",
            ),
            NativeSetting(
                "pcsx2.aspect", "Display", "Aspect ratio", gs, "AspectRatio",
                listOf(
                    SettingChoice("Auto 4:3/3:2", "Automatic (4:3 or 3:2)"), SettingChoice("4:3", "4:3"), SettingChoice("16:9", "16:9"),
                    SettingChoice("10:7", "10:7"), SettingChoice("Stretch", "Stretch to the screen"),
                ),
                "Auto 4:3/3:2",
            ),
            NativeSetting("pcsx2.vsync", "Display", "Vertical sync", gs, "VsyncEnable", onOff, "false", about = "Waits for the screen between frames: no tearing, a little more delay."),
        )
    }

    private fun duckstation(host: Host): List<NativeSetting> {
        // s_gpu_renderer_names (src/core/settings.cpp), by what this system's build has.
        val renderers = buildList {
            add(SettingChoice("Automatic", "Automatic"))
            if (host == Host.WINDOWS) { add(SettingChoice("D3D11", "Direct3D 11")); add(SettingChoice("D3D12", "Direct3D 12")) }
            if (host == Host.MACOS) add(SettingChoice("Metal", "Metal"))
            add(SettingChoice("Vulkan", "Vulkan"))
            if (host != Host.MACOS) add(SettingChoice("OpenGL", "OpenGL"))
            add(SettingChoice("Software", "Software"))
        }
        return listOf(
            NativeSetting("duckstation.renderer", "Graphics", "Renderer", "GPU", "Renderer", renderers, "Automatic"),
            NativeSetting(
                "duckstation.scale", "Graphics", "Internal resolution", "GPU", "ResolutionScale",
                listOf(
                    SettingChoice("0", "Automatic (by window size)"), SettingChoice("1", "Native (240p)"), SettingChoice("2", "2x (480p)"),
                    SettingChoice("3", "3x (720p)"), SettingChoice("4", "4x (960p)"), SettingChoice("5", "5x (1200p)"),
                    SettingChoice("6", "6x (1440p)"), SettingChoice("8", "8x (1920p)"), SettingChoice("9", "9x (4K)"),
                ),
                "1",
            ),
            NativeSetting(
                "duckstation.filter", "Graphics", "Texture filtering", "GPU", "TextureFilter",
                listOf(
                    SettingChoice("Nearest", "Nearest-Neighbor"), SettingChoice("Bilinear", "Bilinear"),
                    SettingChoice("BilinearBinAlpha", "Bilinear (No Edge Blending)"), SettingChoice("JINC2", "JINC2 (Slow)"),
                ),
                "Nearest",
            ),
            NativeSetting(
                "duckstation.pgxp", "Graphics", "PGXP geometry correction", "GPU", "PGXPEnable", onOff, "false",
                about = "Steadier 3D: fewer wobbling polygons and warping textures. A few games misbehave with it.",
            ),
            NativeSetting(
                "duckstation.widescreen", "Display", "Widescreen rendering", "GPU", "WidescreenHack", onOff, "false",
                about = "Draws 3D scenes wider to fill a wide aspect ratio; with one, DuckStation also turns on the game's widescreen patch.",
            ),
            NativeSetting(
                "duckstation.aspect", "Display", "Aspect ratio", "Display", "AspectRatio",
                listOf(
                    SettingChoice("Auto (Game Native)", "Automatic (as the game)"), SettingChoice("4:3", "4:3"), SettingChoice("16:9", "16:9"),
                    SettingChoice("20:9", "20:9"), SettingChoice("PAR 1:1", "Square pixels"), SettingChoice("Stretch To Fill", "Stretch to the screen"),
                ),
                "Auto (Game Native)",
            ),
            NativeSetting("duckstation.vsync", "Display", "Vertical sync", "Display", "VSync", onOff, "false"),
        )
    }

    private fun ppsspp(): List<NativeSetting> = listOf(
        NativeSetting(
            "ppsspp.resolution", "Graphics", "Internal resolution", "Graphics", "InternalResolution",
            listOf(
                SettingChoice("0", "Automatic (by window size)"), SettingChoice("1", "1x PSP (272p)"), SettingChoice("2", "2x (544p)"),
                SettingChoice("3", "3x (816p)"), SettingChoice("4", "4x (1088p)"), SettingChoice("5", "5x (1360p)"),
                SettingChoice("6", "6x (1632p)"), SettingChoice("8", "8x (2176p)"), SettingChoice("10", "10x (2720p)"),
            ),
            "0",
        ),
        NativeSetting(
            "ppsspp.filtering", "Graphics", "Texture filtering", "Graphics", "TextureFiltering",
            listOf(SettingChoice("1", "Automatic"), SettingChoice("2", "Nearest"), SettingChoice("3", "Linear"), SettingChoice("4", "Automatic, best quality")),
            "1",
        ),
        NativeSetting(
            "ppsspp.anisotropy", "Graphics", "Anisotropic filtering", "Graphics", "AnisotropyLevel",
            listOf(SettingChoice("0", "Off"), SettingChoice("1", "2x"), SettingChoice("2", "4x"), SettingChoice("3", "8x"), SettingChoice("4", "16x")),
            "4",
        ),
        NativeSetting(
            "ppsspp.texscale", "Graphics", "Texture upscaling", "Graphics", "TexScalingLevel",
            listOf(SettingChoice("1", "Off"), SettingChoice("2", "2x"), SettingChoice("3", "3x"), SettingChoice("4", "4x"), SettingChoice("5", "5x")),
            "1",
        ),
        NativeSetting("ppsspp.hwtransform", "Graphics", "Hardware transform", "Graphics", "HardwareTransform", ppssppOnOff, "True"),
    )

    /** Dolphin writes its switches as `True` and `False`. */
    private val dolphinOnOff = listOf(SettingChoice("False", "Off"), SettingChoice("True", "On"))

    private fun dolphin(): List<NativeSetting> = listOf(
        NativeSetting(
            "dolphin.resolution", "Graphics", "Internal resolution", "Settings", "InternalResolution",
            listOf(
                SettingChoice("1", "Native (480p)"), SettingChoice("2", "2x (720p)"), SettingChoice("3", "3x (1080p)"),
                SettingChoice("4", "4x (1440p)"), SettingChoice("5", "5x (about 1800p)"), SettingChoice("6", "6x (4K)"),
                SettingChoice("8", "8x (5K)"),
            ),
            "1", file = DOLPHIN_GFX, gameSection = "Video_Settings",
            about = "How many times the console's resolution games are drawn at. Higher is sharper and needs a faster graphics card.",
        ),
        NativeSetting(
            "dolphin.aspect", "Display", "Aspect ratio", "Settings", "AspectRatio",
            listOf(SettingChoice("0", "Automatic"), SettingChoice("1", "Force 16:9"), SettingChoice("2", "Force 4:3"), SettingChoice("3", "Stretch to the window")),
            "0", file = DOLPHIN_GFX, gameSection = "Video_Settings",
        ),
        NativeSetting(
            "dolphin.widescreen", "Display", "Widescreen hack", "Settings", "wideScreenHack", dolphinOnOff, "False",
            file = DOLPHIN_GFX, gameSection = "Video_Settings",
            about = "Draws 3D scenes wider for a wide screen. A game's own widescreen option or patch is better where there is one.",
        ),
        NativeSetting(
            "dolphin.shaders", "Graphics", "Shader compilation", "Settings", "ShaderCompilationMode",
            listOf(
                SettingChoice("0", "Specialized (default)"), SettingChoice("1", "Exclusive ubershaders"),
                SettingChoice("2", "Hybrid ubershaders"), SettingChoice("3", "Skip drawing"),
            ),
            "0", file = DOLPHIN_GFX, gameSection = "Video_Settings",
            about = "Hybrid ubershaders avoid most stutter while shaders compile, at some cost in speed.",
        ),
        NativeSetting("dolphin.vsync", "Display", "Vertical sync", "Hardware", "VSync", dolphinOnOff, "False", file = DOLPHIN_GFX, gameSection = "Video_Hardware"),
        NativeSetting(
            "dolphin.dualcore", "System", "Dual core", "Core", "CPUThread", dolphinOnOff, "True",
            about = "Runs the graphics on a second core: much faster, and a few games are unstable with it.",
        ),
    )
}

/** Where a value comes from for one game: its own file, the emulator's for every game, or the emulator's default. */
enum class SettingSource { GAME, EVERY_GAME, DEFAULT }

/** A setting's value for one game (or for every game), and where it comes from. */
data class SettingValue(val setting: NativeSetting, val value: String, val source: SettingSource, val everyGame: String) {
    val label: String get() = setting.labelFor(value)
}

/**
 * Changing emulator settings so they can be put back: for each key Fuse changes it keeps one record
 * of the value the key had before Fuse first changed it and the value Fuse last gave it. Putting
 * back restores the first only where the key still holds the second, so a change made in the
 * emulator since then stays.
 */
object SettingsEdit {
    /** A setting's value for a game: the game's own file, else the emulator's file, else its default. */
    fun valueFor(setting: NativeSetting, game: SettingsText?, global: SettingsText?): SettingValue {
        val every = global?.values(setting.section, setting.key)?.lastOrNull()
        val own = if (setting.perGame) game?.values(setting.sectionIn(game = true), setting.key)?.lastOrNull() else null
        val everyGame = every ?: setting.default
        return when {
            own != null -> SettingValue(setting, own, SettingSource.GAME, everyGame)
            every != null -> SettingValue(setting, every, SettingSource.EVERY_GAME, everyGame)
            else -> SettingValue(setting, setting.default, SettingSource.DEFAULT, everyGame)
        }
    }

    /**
     * One key Fuse changed: what it held before ([was], null when it wasn't there), what Fuse set
     * ([now]), and [made] when Fuse made its section too (`s`, or `sb` with the blank line before it),
     * so putting it back takes that section out again.
     */
    private data class Record(val section: String, val key: String, val was: String?, val now: String?, val made: String? = null) {
        fun text() = "$section\t$key\t${was?.let { "=$it" } ?: ""}\t${now?.let { "=$it" } ?: ""}" + (made?.let { "\t$it" } ?: "")
    }

    private fun parse(text: String): Record? {
        val p = text.split('\t')
        if (p.size != 4 && p.size != 5) return null
        fun v(s: String) = if (s.startsWith("=")) s.drop(1) else null
        return Record(p[0], p[1], v(p[2]), v(p[3]), p.getOrNull(4)?.takeIf { it == "s" || it == "sb" })
    }

    /**
     * Sets (or, with a null [value], takes out) [section] [key] in [file] and returns [records] with
     * this key's record made or brought up to date.
     */
    fun change(file: SettingsText, records: Set<String>, section: String, key: String, value: String?): Set<String> {
        val all = records.mapNotNull(::parse)
        val earlier = all.firstOrNull { it.section.equals(section, ignoreCase = true) && it.key.equals(key, ignoreCase = true) }
        val was = earlier?.was ?: if (earlier == null) file.values(section, key).lastOrNull() else null
        // A section this change makes, noted once (by the first key Fuse put in it).
        val made = earlier?.made ?: if (earlier == null && value != null && !file.hasSection(section) && all.none { it.made != null && it.section.equals(section, ignoreCase = true) }) {
            if (file.separatesNewSection()) "sb" else "s"
        } else null
        if (value == null) file.removeKey(section, key) else file.set(section, key, value)
        val next = Record(earlier?.section ?: section, earlier?.key ?: key, was, value, made)
        return (all - setOfNotNull(earlier) + next).map { it.text() }.toSet()
    }

    /**
     * Puts back what [records] say each key held before Fuse; a key changed since in the emulator
     * stays, and a section Fuse made goes again once nothing is left in it. Returns how many stayed.
     */
    fun restore(file: SettingsText, records: Set<String>): Int {
        var kept = 0
        val all = records.mapNotNull(::parse)
        for (r in all) {
            val current = file.values(r.section, r.key).lastOrNull()
            if (current != r.now) {
                kept++
                continue
            }
            if (r.was != null) file.set(r.section, r.key, r.was) else file.removeKey(r.section, r.key)
        }
        for (r in all) if (r.made != null) file.removeEmptySection(r.section, blankBefore = r.made == "sb")
        return kept
    }
}

/**
 * Dolphin's folders: [config] holds Dolphin.ini and GFX.ini (Config in its user folder, or its XDG
 * config folder on Linux), and [gameSettings] each game's own file by its six-letter id.
 */
data class DolphinHome(val config: String, val gameSettings: String) {
    /** The settings file for every game called [name] (`Dolphin`, `GFX`). */
    fun file(name: String?): String = "$config/${name ?: "Dolphin"}.ini"

    fun gameFile(id: String): String = "$gameSettings/$id.ini"
}
