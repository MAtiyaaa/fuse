package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.Confidence.UNVERIFIED
import io.github.matiyaaa.fuse.launch.Confidence.VERIFIED_ESDE
import io.github.matiyaaa.fuse.launch.Confidence.VERIFIED_SOURCE
import io.github.matiyaaa.fuse.launch.IdFiles
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.FolderSupport

/**
 * Android PC/Windows game launchers: Winlator builds that take an exported `.desktop` shortcut, and the
 * Steam/store clients that take an id file. Sources per entry; see docs/research/emulators.md section 4.
 */
internal object AndroidPcDefs {
    private val WIN = platforms("win")
    private val PC = platforms("steam", "win")
    private val noFolders = caps(folders = FolderSupport.NONE)

    /** `-e shortcut_path <abs .desktop>` with CLEAR_TASK + CLEAR_TOP, as in ES-DE's Winlator commands. */
    private val shortcut = listOf(fileExt(intent(extras = listOf(str("shortcut_path", PATH)), task = true), "desktop", label = "Shortcut"))

    private val shortcutNotes = listOf(
        "Create the .desktop file with Winlator's Export for Frontend.",
        "Fuse passes the real file path; the launcher reads it itself (it holds All files access).",
    )

    private const val GAMEHUB_ACTIVITY = "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity"
    private val gameHubPackages = arrayOf(
        "emuready.gamehub.lite", "gamehub.lite", "com.antutu.ABenchMark", "com.tencent.ig", "com.ludashi.aibench",
        "com.antutu.benchmark.full",
    )

    val all: List<AndroidEmulatorDef> = listOf(
        AndroidEmulatorDef(
            id = "winlator-cmod", name = "Winlator Cmod",
            apps = listOf(
                app("com.winlator.cmod", ".XServerDisplayActivity"),
                app("com.winlator.vanilla", "com.winlator.cmod.XServerDisplayActivity"),
                // Winlator-Ludashi: a Cmod fork whose releases install as their own package.
                app("com.winlator.ludashi", "com.winlator.cmod.XServerDisplayActivity"),
                app("com.ludashi.benchmark", "com.winlator.cmod.XServerDisplayActivity"),
                app("com.tencent.ig", "com.winlator.cmod.XServerDisplayActivity"),
            ),
            platforms = WIN, modes = shortcut,
            source = esde("WINLATOR-CMOD") + "; coffincolors/winlator XServerDisplayActivity.java, ShortcutsFragment.java",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/coffincolors/winlator",
            capabilities = noFolders, limitations = shortcutNotes,
        ),
        AndroidEmulatorDef(
            id = "winlator-glibc", name = "Winlator Cmod Glibc",
            apps = listOf(app("com.winlator", ".XServerDisplayActivity")),
            platforms = WIN, modes = shortcut,
            source = esde("WINLATOR-GLIBC"), confidence = VERIFIED_ESDE, homepage = "https://github.com/coffincolors/winlator",
            capabilities = noFolders,
            limitations = shortcutNotes + "Shares the com.winlator package with mainline Winlator, whose game activity is not " +
                "exported, so Fuse checks the activity before using this.",
        ),
        AndroidEmulatorDef(
            id = "winlator-proot", name = "Winlator Cmod PRoot",
            apps = listOf(app("com.cmodded.winlator", "com.winlator.XServerDisplayActivity")),
            platforms = WIN, modes = shortcut,
            source = esde("WINLATOR-PROOT"), confidence = VERIFIED_ESDE, homepage = "https://github.com/coffincolors/winlator",
            capabilities = noFolders, limitations = shortcutNotes,
        ),
        AndroidEmulatorDef(
            id = "winnative", name = "WinNative",
            apps = apps(
                "com.winlator.cmod.runtime.display.XServerDisplayActivity",
                "com.winnative.cmod", "com.antutu.ABenchMark", "com.ludashi.benchmark", "com.tencent.ig",
            ),
            platforms = PC, modes = shortcut,
            source = esde("WINNATIVE") + "; WinNative source (GPL-3.0)", confidence = VERIFIED_ESDE,
            capabilities = noFolders,
            limitations = listOf("Store games exported by WinNative are .desktop files too."),
        ),
        AndroidEmulatorDef(
            id = "bannerlator", name = "Bannerlator",
            apps = apps("com.winlator.star.XServerDisplayActivity", "com.winlator.banner", "com.ludashi.benchmark", "com.tencent.ig"),
            platforms = WIN, modes = shortcut,
            source = "Bannerlator marcescence-frontends.md (vendor doc): -e shortcut_path, CLEAR_TASK, CLEAR_TOP",
            confidence = VERIFIED_SOURCE, homepage = "https://github.com/The412Banner/Bannerlator",
            capabilities = noFolders,
            limitations = shortcutNotes + "Not in ES-DE; the launch follows Bannerlator's own frontend guide.",
        ),
        AndroidEmulatorDef(
            id = "winlator", name = "Winlator",
            apps = listOf(app("com.winlator", "com.winlator.MainActivity")),
            platforms = WIN, modes = emptyList(),
            source = "brunodev85/winlator-app AndroidManifest.xml (XServerDisplayActivity exported=\"false\"); ES-DE: no frontend support",
            confidence = VERIFIED_SOURCE, homepage = "https://github.com/brunodev85/winlator",
            capabilities = noFolders,
            openAppOnlyReason = "Mainline Winlator does not export its game activity, so frontends can only open the app. " +
                "Winlator Cmod and its forks can start shortcuts directly.",
            supersededBy = setOf("winlator-glibc"),
        ),
        AndroidEmulatorDef(
            id = "winlator-frost", name = "Winlator Frost",
            apps = emptyList(), platforms = WIN, modes = emptyList(),
            source = "No repository or documentation found", confidence = UNVERIFIED,
            capabilities = noFolders,
            openAppOnlyReason = "Winlator Frost's launch intent is unknown, so Fuse only opens the app.",
        ),
        AndroidEmulatorDef(
            id = "gamenative", name = "GameNative",
            apps = listOf(app("app.gamenative", ".MainActivity"), app("app.gamenative.gold", "app.gamenative.MainActivity")),
            platforms = PC,
            modes = IdFiles.storeSources.map { (ext, source) ->
                idFile(
                    intent(action = "app.gamenative.LAUNCH_GAME", extras = listOf(str("game_source", source), int("app_id", INJECT))),
                    ext, label = source, pattern = INT_ID,
                )
            },
            source = esde("GAMENATIVE, commands GameNative Steam/Epic/GOG/Amazon/Custom Game") +
                "; GameNative IntentLaunchManager.kt (int app_id), build.gradle.kts (.gold suffix)",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/utkarshdalal/GameNative",
            capabilities = noFolders,
            limitations = listOf("GameNative's Export for frontend writes the id files (.steam, .epic, .gog, .amazon, .pcgame)."),
        ),
        AndroidEmulatorDef(
            id = "gamehub-lite", name = "GameHub Lite",
            apps = apps(GAMEHUB_ACTIVITY, *gameHubPackages),
            platforms = PC,
            modes = listOf(
                idFile(
                    intent(
                        action = "gamehub.lite.LAUNCH_GAME",
                        extras = listOf(bool("autoStartGame", true), str("steamAppId", INJECT), str("localGameId", INJECT)),
                    ),
                    "steam", label = "Steam", pattern = STEAM_ID, platforms = platforms("steam"),
                ),
                idFile(
                    intent(action = "gamehub.lite.LAUNCH_GAME", extras = listOf(bool("autoStartGame", true), str("steamAppId", INJECT))),
                    "steam", label = "Steam", pattern = STEAM_ID, platforms = WIN,
                ),
            ),
            source = esde("GAMEHUB-LITE (steam and windows systems)") + "; gamehub-lite AndroidManifest.xml.patch",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/Producdevity/gamehub-lite",
            capabilities = noFolders,
        ),
        AndroidEmulatorDef(
            id = "gamehub-lite-local", name = "GameHub Lite (local id)",
            apps = apps(GAMEHUB_ACTIVITY, *gameHubPackages),
            platforms = PC,
            modes = listOf(
                idFile(
                    intent(action = "gamehub.lite.LAUNCH_GAME", extras = listOf(bool("autoStartGame", true), str("localGameId", INJECT))),
                    "steam", "pcgame", label = "Local game id",
                ),
            ),
            source = esde("GAMEHUB-LITE, command GameHub Lite Local"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/Producdevity/gamehub-lite",
            capabilities = noFolders,
            limitations = listOf("The file must contain GameHub's local game id (copy it inside GameHub)."),
        ),
        AndroidEmulatorDef(
            id = "gamehub", name = "GameHub",
            apps = emptyList(), platforms = PC, modes = emptyList(),
            source = "Closed source; GameHub Lite's patch has to add the exported launch activity",
            confidence = UNVERIFIED, capabilities = noFolders,
            openAppOnlyReason = "The original GameHub has no documented way to start a game from another app. Fuse opens it instead.",
        ),
    )
}
