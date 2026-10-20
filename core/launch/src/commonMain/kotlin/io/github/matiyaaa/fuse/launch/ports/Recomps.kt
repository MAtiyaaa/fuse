package io.github.matiyaaa.fuse.launch.ports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A game recompiled from its cartridge's own code to run natively (N64: Recompiled's runtime), as
 * Fuse knows it: the one ROM it is made from (by the XXH3-64 of its big-endian form, as the runtime
 * checks it), the program and the folder it keeps its files in (both named by its program id), its
 * Flatpak, its releases, and where its mods are shared. [id] is the port's id where Fuse's list of
 * ports has it, so a recomp installed by the person and Fuse's own keep their saves in step.
 * [frontend] when its settings menu is RecompFrontend's (newer projects), whose values differ a
 * little from the older ones' (see [RecompSettings]).
 */
data class Recomp(
    val id: String,
    val name: String,
    val game: String,
    val system: String,
    val xxh3: String,
    /** The runtime's game id: its ROM is kept as `<gameId>.z64` and its save as `saves/<gameId>.bin`. */
    val gameId: String,
    /** The cartridge [xxh3] is, as people name it ("North American 1.0"), read from [gameId]. */
    val rom: String,
    /** The program's name, and its folder's in the person's own folders. */
    val program: String,
    val flatpak: String?,
    /** Its GitHub repository (owner/name), whose releases bring new versions. */
    val repo: String,
    /** Where its mods are shared, as its README names the place; null when it names none. */
    val modSite: String?,
    val frontend: Boolean,
    val source: String,
)

/** What one recomp keeps in its folder, and what Fuse does with it. */
object Recomps {
    /** Saves, as `<gameId>.bin` (N64ModernRuntime librecomp/src/pi.cpp, save_folder and set_save_file_path). */
    const val SAVES = "saves"

    /** Mods and texture packs, read from this folder alone (librecomp mods.hpp, mods_directory). */
    const val MODS = "mods"

    /** The settings files each recomp writes in its folder (src/game/config.cpp, or RecompFrontend's config ids). */
    const val GENERAL = "general.json"
    const val GRAPHICS = "graphics.json"
    const val CONTROLS = "controls.json"
    const val SOUND = "sound.json"

    /** A file beside the program that keeps its files there instead (each README, "portable application"). */
    const val PORTABLE = "portable.txt"

    /** A mod (`.nrm`, with a mod.json inside) or a texture pack (`.rtz`), as the recomp's own installer takes them (src/ui/ui_mod_installer.cpp). */
    val MOD_EXTENSIONS = setOf("nrm", "rtz")

    val all: List<Recomp> = listOf(
        Recomp(
            id = "zelda64recomp",
            name = "Zelda 64: Recompiled",
            game = "The Legend of Zelda: Majora's Mask",
            system = "n64",
            xxh3 = "ef18b4a9e2386169",
            gameId = "mm.n64.us.1.0",
            rom = "North American 1.0",
            program = "Zelda64Recompiled",
            flatpak = "io.github.zelda64recomp.zelda64recomp",
            repo = "Zelda64Recomp/Zelda64Recomp",
            modSite = "https://thunderstore.io/c/zelda-64-recompiled/",
            frontend = false,
            source = "Zelda64Recomp v1.2.2: src/main/main.cpp supported_games; include/zelda_config.h program_id; src/game/config.cpp get_app_folder_path and the settings files; flatpak/io.github.zelda64recomp.zelda64recomp.json; README.md (saves, portable mode, Thunderstore)",
        ),
        Recomp(
            id = "banjorecomp",
            name = "Banjo: Recompiled",
            game = "Banjo-Kazooie",
            system = "n64",
            xxh3 = "1b67585d56e07f8c",
            gameId = "bk.n64.us.1.0",
            rom = "North American 1.0",
            program = "BanjoRecompiled",
            flatpak = "io.github.banjorecomp.banjorecomp",
            repo = "BanjoRecomp/BanjoRecomp",
            modSite = "https://thunderstore.io/c/banjo-recompiled/",
            frontend = true,
            source = "BanjoRecomp v1.0.2: src/main/main.cpp supported_games; include/banjo_config.h program_id; RecompFrontend d0d90ba recompui/src/util/file.cpp get_app_folder_path and recompui/include/recompui/config.h; flatpak/io.github.banjorecomp.banjorecomp.json; README.md (saves, portable mode, Thunderstore)",
        ),
        Recomp(
            id = "starfox64recomp",
            name = "Starfox 64: Recompiled",
            game = "Star Fox 64",
            system = "n64",
            xxh3 = "163fd3fc3813f54e",
            gameId = "sf64.n64.us.1.1",
            rom = "North American 1.1",
            program = "Starfox64Recompiled",
            flatpak = "io.github.starfox64recomp.starfox64recomp",
            repo = "sonicdcer/Starfox64Recomp",
            modSite = null,
            frontend = false,
            source = "Starfox64Recomp 1.0.3: src/main/main.cpp supported_games; include/zelda_config.h program_id; src/game/config.cpp get_app_folder_path and the settings files; flatpak/io.github.starfox64recomp.starfox64recomp.json; README.md (the 1.1 North American ROM, portable mode)",
        ),
        Recomp(
            id = "goemon64recomp",
            name = "Goemon 64: Recompiled",
            game = "Mystical Ninja Starring Goemon",
            system = "n64",
            xxh3 = "db1bc7ee0e6beba1",
            gameId = "mnsg.us",
            rom = "North American",
            program = "Goemon64Recompiled",
            flatpak = "io.github.klorfmorf.goemon64recomp",
            repo = "klorfmorf/Goemon64Recomp",
            modSite = null,
            frontend = false,
            source = "Goemon64Recomp v0.2.0-dev: src/main/main.cpp supported_games; include/goemon_config.h program_id; src/game/config.cpp get_app_folder_path and the settings files; flatpak/io.github.klorfmorf.goemon64recomp.json; README.md (portable mode)",
        ),
    )

    fun byId(id: String): Recomp? = all.firstOrNull { it.id == id }

    /** The recomp made from a ROM with this XXH3-64 (16 hex digits, big-endian form). */
    fun byHash(xxh3: String): Recomp? = all.firstOrNull { it.xxh3.equals(xxh3, ignoreCase = true) }

    /** The recomp made from the ROM [bytes] (in any byte order, as the runtime takes it), or null. */
    fun of(bytes: ByteArray): Recomp? = PortPlan.bigEndianRom(bytes)?.let { byHash(Xxh3.hex(it)) }

    /**
     * Whether the release [tag] ("v1.2.3", "1.0.3", "v0.2.0-dev") is newer than [have], number by
     * number; null when either has no version number in it.
     */
    fun newer(tag: String, have: String): Boolean? {
        fun parts(s: String) = Regex("""\d+(?:\.\d+)*""").find(s)?.value?.split('.')?.map { it.toIntOrNull() ?: 0 }
        val a = parts(tag) ?: return null
        val b = parts(have) ?: return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The folder [recomp] keeps its files in, as its get_app_folder_path finds it: beside the
     * program when [portable] (a portable.txt there, [programDir] the folder it starts in); else
     * `APP_FOLDER_PATH` on Linux and macOS; else `%LOCALAPPDATA%\<program>` on Windows,
     * `~/Library/Application Support/<program>` on macOS and `~/.config/<program>` on Linux (the
     * Flatpak too: it is given the whole home folder). Paths use `/`; null when it can't be told.
     */
    fun folder(recomp: Recomp, os: String, home: String, env: Map<String, String>, programDir: String? = null, portable: Boolean = false): String? {
        if (portable && programDir != null) return programDir.trimEnd('/')
        val h = home.replace('\\', '/').trimEnd('/')
        return when (os) {
            "windows" -> env["LOCALAPPDATA"]?.replace('\\', '/')?.trimEnd('/')?.let { "$it/${recomp.program}" }
                ?: h.takeIf { it.isNotEmpty() }?.let { "$it/AppData/Local/${recomp.program}" }
            "macos" -> env["APP_FOLDER_PATH"]?.trimEnd('/') ?: h.takeIf { it.isNotEmpty() }?.let { "$it/Library/Application Support/${recomp.program}" }
            "linux" -> env["APP_FOLDER_PATH"]?.trimEnd('/') ?: h.takeIf { it.isNotEmpty() }?.let { "$it/.config/${recomp.program}" }
            else -> null
        }
    }

    /** Its save, relative to its folder. */
    fun saveFile(recomp: Recomp): String = "$SAVES/${recomp.gameId}.bin"

    /** True for a file the recomp reads as a mod or a texture pack. */
    fun isMod(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MOD_EXTENSIONS

    /** A file (or a zip of them) the person picked to add: a mod or texture pack itself, or a package to look inside. */
    fun canAdd(name: String): Boolean = isMod(name) || name.lowercase().endsWith(".zip")

    /** The name a mod in a package is kept by: its own file name, never a path inside the package. */
    fun modName(entry: String): String? = entry.replace('\\', '/').substringAfterLast('/').takeIf { it.isNotEmpty() && it != "." && it != ".." && isMod(it) }
}

/** One setting of a recomp's own graphics file, and the values it takes ([RecompSettings]). */
data class RecompSetting(val key: String, val label: String, val choices: List<RecompChoice>)

/** One value: [value] as the file holds it (a word, or a number), and how Fuse names it. */
data class RecompChoice(val value: JsonPrimitive, val label: String)

/**
 * The graphics settings every recomp reads from graphics.json, with the values each takes, as its
 * code writes them: the older ones through ultramodern's JSON names (N64ModernRuntime
 * ultramodern/include/ultramodern/config.hpp, and each src/game/config.cpp, where downsampling is
 * a number with 1 for none), the newer through RecompFrontend's options (ui_config_tab_graphics.cpp,
 * where the HUD's full width is "Expand", 8x antialiasing is left out and downsampling 0 is none).
 */
object RecompSettings {
    private fun word(v: String, label: String = v) = RecompChoice(JsonPrimitive(v), label)
    private fun number(v: Int, label: String) = RecompChoice(JsonPrimitive(v), label)

    fun graphics(recomp: Recomp): List<RecompSetting> = listOf(
        RecompSetting("res_option", "Resolution", listOf(word("Original", "Original (240p)"), word("Original2x", "Original 2x (480p)"), word("Auto", "Auto (the window's)"))),
        RecompSetting(
            "ds_option", "Downsampling",
            if (recomp.frontend) listOf(number(0, "Off"), number(2, "2x"), number(4, "4x")) else listOf(number(1, "Off"), number(2, "2x"), number(4, "4x")),
        ),
        RecompSetting("ar_option", "Aspect Ratio", listOf(word("Original", "Original (4:3)"), word("Expand", "Expand to the screen"))),
        RecompSetting(
            "hr_option", "HUD",
            listOf(word("Original", "Original (4:3)"), word("Clamp16x9", "16:9"), if (recomp.frontend) word("Expand", "Full width") else word("Full", "Full width")),
        ),
        RecompSetting(
            "msaa_option", "Antialiasing",
            listOf(word("None", "Off"), word("MSAA2X", "2x"), word("MSAA4X", "4x")) + if (recomp.frontend) emptyList() else listOf(word("MSAA8X", "8x")),
        ),
        RecompSetting("rr_option", "Frame Rate", listOf(word("Original", "Original"), word("Display", "The display's"), word("Manual", "Manual"))),
        RecompSetting("wm_option", "Window", listOf(word("Windowed"), word("Fullscreen"))),
        RecompSetting("api_option", "Graphics API", listOf(word("Auto"), word("Vulkan"), word("D3D12", "Direct3D 12"), word("Metal"))),
        RecompSetting("hpfb_option", "High Precision Framebuffer", listOf(word("Auto"), word("On"), word("Off"))),
    )

    private val json = Json { prettyPrint = true; prettyPrintIndent = "    " }

    /** The value [key] has in [text] (graphics.json), or null when it isn't there or the file isn't JSON. */
    fun value(text: String?, key: String): JsonPrimitive? =
        runCatching { (Json.parseToJsonElement(text ?: return null) as? JsonObject)?.get(key) as? JsonPrimitive }.getOrNull()

    /** The choice [setting] has in [text], or null when it holds none of them. */
    fun current(text: String?, setting: RecompSetting): RecompChoice? {
        val v = value(text, setting.key) ?: return null
        return setting.choices.firstOrNull { c -> if (c.value.isString) v.isString && v.content == c.value.content else !v.isString && v.intOrNull == c.value.intOrNull }
    }

    /**
     * [text] with [key] set to [value] and everything else as it was; a file that isn't there yet
     * becomes one holding just this. Null when [text] isn't a JSON object (Fuse never overwrites a
     * file it can't read).
     */
    fun with(text: String?, key: String, value: JsonPrimitive): String? {
        val obj: JsonObject = if (text.isNullOrBlank()) JsonObject(emptyMap()) else runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val out = LinkedHashMap<String, JsonElement>(obj)
        out[key] = value
        return json.encodeToString(JsonObject.serializer(), JsonObject(out))
    }

    /** True when [text] reads as a recomp's controls file: a JSON object (a profile Fuse keeps is one of these, whole). */
    fun isControls(text: String?): Boolean = runCatching { Json.parseToJsonElement(text ?: return false) is JsonObject }.getOrDefault(false)
}
