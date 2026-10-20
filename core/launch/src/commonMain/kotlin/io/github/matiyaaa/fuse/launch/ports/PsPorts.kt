package io.github.matiyaaa.fuse.launch.ports

/**
 * A PlayStation game rebuilt to run natively on a computer by its own project, played with the
 * person's own dump: known by the game's title ids, found by the files its release puts in its
 * folder ([marks], per system: "windows", "linux"), started as its project says ([start], with
 * `{game}` for the dump's folder and `{install}` for its own), and its saves where its code keeps
 * them ([saves], under `{install}` or `{data}`, the person's data folder; [saveFiles] when only some
 * files there are saves). Every fact is cited in [source].
 */
data class PsPort(
    val id: String,
    val name: String,
    val game: String,
    val system: String,
    val titleIds: Set<String>,
    /** Its GitHub repository (owner/name), whose releases bring new versions. */
    val repo: String,
    val marks: Map<String, List<String>>,
    val start: Map<String, List<String>>,
    val saves: Map<String, String>,
    val saveFiles: List<String> = emptyList(),
    val source: String,
)

/** The PlayStation games rebuilt to run natively that Fuse finds and plays beside their emulators. */
object PsPorts {
    const val GAME = "{game}"
    const val INSTALL = "{install}"
    const val DATA = "{data}"

    val all: List<PsPort> = listOf(
        PsPort(
            id = "bloodborne-pc",
            name = "Bloodborne PC Port",
            game = "Bloodborne",
            system = "ps4",
            titleIds = setOf("CUSA03173"),
            repo = "Mrsuss60/bloodborne_pc_windows_port",
            // Its launcher, which keeps the game folder chosen in it (run.bat, which takes the folder, is
            // documented for a build from source, so a release isn't counted on to carry it).
            marks = mapOf("windows" to listOf("BloodborneLauncher.exe")),
            start = mapOf("windows" to listOf("$INSTALL/BloodborneLauncher.exe")),
            saves = mapOf("windows" to "$INSTALL/user/savedata"),
            source = "Mrsuss60/bloodborne_pc_windows_port v0.6.3: README.md (CUSA03173 version 1.09, BloodborneLauncher.exe in the release folder and the game folder chosen in it, saves in user/, release archives with their own Python); run.bat (BB_DATA_DIR, where user/ is, defaults to its own folder); src/probe.c (the user folder, user by default); src/runtime_savedata.c (<user>/savedata/<user id>/<title id>)",
        ),
        PsPort(
            id = "spartanport",
            name = "SpartanPort",
            game = "God of War III Remastered",
            system = "ps4",
            titleIds = setOf("CUSA01623", "CUSA01715"),
            repo = "FelipePrado0/SpartanPort",
            marks = mapOf(
                "windows" to listOf("SpartanPort.exe", "Play SpartanPort.exe"),
                "linux" to listOf("spartanport-x86_64.AppImage"),
            ),
            start = mapOf(
                "windows" to listOf("$INSTALL/Play SpartanPort.exe"),
                "linux" to listOf("$INSTALL/spartanport-x86_64.AppImage", "--play"),
            ),
            saves = mapOf("windows" to "$INSTALL/user/savedata", "linux" to "$DATA/spartanport/user/savedata"),
            source = "FelipePrado0/gow3_pc (SpartanPort) windows-v2.6.0: README.md (CUSA01623 and CUSA01715, spartanport-windows.zip, Play SpartanPort.exe, spartanport-x86_64.AppImage); launcher/spartanport_launcher_win.py (--play starts the game with the saved settings once a game folder is chosen, else the launcher opens; DATA_DIR and its user folder); packaging/linux/AppRun (SPARTAN_DATA_DIR: XDG_DATA_HOME or ~/.local/share, then spartanport); run.py (--user <data>/user); src/runtime_savedata.c (<user>/savedata)",
        ),
        PsPort(
            id = "uncharted-ndc-pc",
            name = "Uncharted: The Nathan Drake Collection PC Port",
            game = "Uncharted: The Nathan Drake Collection",
            system = "ps4",
            titleIds = setOf("CUSA02344"),
            repo = "bc160201983/Uncharted-The-Nathan-Drake-Collection-PC-port",
            marks = mapOf("windows" to listOf("Uncharted Launcher.exe")),
            start = mapOf("windows" to listOf("$INSTALL/Uncharted Launcher.exe")),
            saves = mapOf("windows" to "$INSTALL/analysis/uncharted-user/savedata"),
            source = "bc160201983/Uncharted-The-Nathan-Drake-Collection-PC-port v0.4.1: README.md (the dumped CUSA02344 folder, Uncharted Launcher.exe, saves in analysis); launcher/uncharted_launcher.pyw (USER_DIR analysis/uncharted-user, its savedata folder); gow3_pc/src/runtime_savedata.c (<user>/savedata)",
        ),
        PsPort(
            id = "pt-pc",
            name = "P.T. PC Port",
            game = "P.T.",
            system = "ps4",
            titleIds = setOf("CUSA01127"),
            repo = "LoreanXavier/pt-pc",
            marks = mapOf("windows" to listOf("pt.exe", "chunk1.psarc"), "linux" to listOf("pt", "chunk1.psarc")),
            start = mapOf("windows" to listOf("$INSTALL/pt.exe"), "linux" to listOf("$INSTALL/pt")),
            saves = mapOf("windows" to "$INSTALL/data", "linux" to "$INSTALL/data"),
            saveFiles = listOf("PT_Save_Data0", "PT_Save_Data1"),
            source = "LoreanXavier/pt-pc v1.0.7: README.md (CUSA01127, the installer copies chunk1.psarc beside the port, data/ beside the program); CMakeLists.txt (the pt program); installer/Native/setup.cpp (pt.exe); src/main.cpp UserDataDir and the save path; src/game/save_data.h and save_data.cpp (two slots, PT_Save_Data0 and PT_Save_Data1)",
        ),
    )

    fun byId(id: String): PsPort? = all.firstOrNull { it.id == id }

    /** The ports that play the game with [titleId] (CUSA01623). */
    fun forTitle(titleId: String): List<PsPort> = all.filter { titleId.uppercase() in it.titleIds }

    /**
     * [port]'s start on [os] with its folder ([install]) and the dump's ([game]) in place; null when
     * it isn't made for [os]. Paths use `/`.
     */
    fun argv(port: PsPort, os: String, install: String, game: String): List<String>? =
        port.start[os]?.map { it.replace(INSTALL, install.trimEnd('/')).replace(GAME, game.trimEnd('/')) }

    /** Where [port] keeps its saves on [os]: under its folder ([install]) or the person's data folder ([data]). */
    fun saves(port: PsPort, os: String, install: String, data: String): String? =
        port.saves[os]?.replace(INSTALL, install.trimEnd('/'))?.replace(DATA, data.trimEnd('/'))

    /** The person's data folder on Linux, as an AppImage's own start script finds it: XDG_DATA_HOME, else ~/.local/share. */
    fun dataHome(home: String, env: Map<String, String>): String =
        env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "${home.trimEnd('/')}/.local/share"
}

/** What the person can install themselves and Fuse plays: recomps ([Recomps]) and PlayStation ports ([PsPorts]), by id. */
object NativePorts {
    fun name(id: String): String? = Recomps.byId(id)?.name ?: PsPorts.byId(id)?.name

    fun game(id: String): String? = Recomps.byId(id)?.game ?: PsPorts.byId(id)?.game

    /** True for a recomp: its mods, graphics and controls are in files Fuse knows. */
    fun isRecomp(id: String): Boolean = Recomps.byId(id) != null
}
