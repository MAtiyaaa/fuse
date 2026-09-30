package io.github.matiyaaa.fuse.launch.android

import io.github.matiyaaa.fuse.launch.Confidence.COMMUNITY
import io.github.matiyaaa.fuse.launch.Confidence.UNVERIFIED
import io.github.matiyaaa.fuse.launch.Confidence.VERIFIED_ESDE
import io.github.matiyaaa.fuse.launch.Confidence.VERIFIED_SOURCE
import io.github.matiyaaa.fuse.launch.RetroArchCores
import io.github.matiyaaa.fuse.launch.Sources
import io.github.matiyaaa.fuse.launch.TitleIdMode
import io.github.matiyaaa.fuse.launch.platforms
import io.github.matiyaaa.fuse.model.FolderSupport
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Android console emulators. Packages, activities, actions, extras and flags are copied from ES-DE's
 * Android find rules and commands (master [Sources.ESDE_COMMIT], MIT) unless the entry says otherwise;
 * see docs/research/emulators.md for the per-emulator source notes.
 */
internal object AndroidConsoleDefs {
    private const val RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
    private const val CITRA_ACTIVITY = "org.citra.citra_emu.activities.EmulationActivity"
    private const val YUZU_ACTIVITY = "org.yuzu.yuzu_emu.activities.EmulationActivity"
    private const val M64_ACTIVITY = "paulscode.android.mupen64plusae.SplashActivity"
    private const val MELON_ACTIVITY = "me.magnum.melonds.ui.emulator.EmulatorActivity"

    /** Eden's EmulationFragment only loads intent data with these extensions (Game.extensions). */
    private val SWITCH_FILES = arrayOf("xci", "nsp", "nca", "nro")

    private val PSX = platforms("psx")
    private val PS2 = platforms("ps2")
    private val PS3 = platforms("ps3")
    private val N3DS = platforms("3ds", "new-nintendo-3ds")
    private val NDS = platforms("nds", "nintendo-dsi")
    private val SWITCH = platforms("switch")
    private val GB = platforms("gb", "gbc")
    private val NES = platforms("nes", "famicom", "fds")

    private val saf = intent(data = SAF)
    private val safTask = intent(data = SAF, task = true)
    private val viewSaf = intent(action = VIEW, data = SAF)
    private val viewSafTask = intent(action = VIEW, data = SAF, task = true)
    private val provider = intent(data = PROVIDER)
    private val viewProvider = intent(action = VIEW, data = PROVIDER)

    private fun yuzuStyle(title: Boolean) = buildList {
        add(fileExt(intent(action = "android.nfc.action.TECH_DISCOVERED", data = PROVIDER), *SWITCH_FILES))
        if (title) {
            add(
                titleId(
                    intent(action = "dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG", extras = listOf(str("title_id", SERIAL))),
                    pattern = SWITCH_TITLE_ID,
                ),
            )
        }
    }

    private val vitaModes = intent(extras = listOf(array("AppStartParameters", "-r", SERIAL))).let {
        listOf(idFile(it, "psvita", pattern = PS_TITLE_ID), titleId(it, pattern = PS_TITLE_ID))
    }

    val all: List<AndroidEmulatorDef> = listOf(
        // RetroArch: one adapter for every libretro platform, core picked per platform or game.
        AndroidEmulatorDef(
            id = "retroarch", name = "RetroArch",
            apps = apps(RA_ACTIVITY, "com.retroarch.aarch64", "com.retroarch.ra32", "com.retroarch"),
            platforms = RetroArchCores.android.keys,
            modes = listOf(
                anyFile(
                    intent(
                        extras = listOf(
                            str("CONFIGFILE", "{EXTDATA}/Android/data/{PKG}/files/retroarch.cfg"),
                            str("LIBRETRO", "{INTDATA}/{PKG}/cores/{CORE}_libretro_android.so"),
                            str("ROM", PATH),
                        ),
                    ),
                ),
            ),
            source = esde("RETROARCH rule and per-system RetroArch commands") +
                "; RetroActivityFuture.java, frontend/drivers/platform_unix.c",
            confidence = VERIFIED_ESDE,
            homepage = "https://www.retroarch.com/",
            capabilities = caps(playlists = true),
            limitations = listOf(
                "ES-DE recommends the 64-bit build from retroarch.com; the Play Store build is known to cause problems.",
                "The core must be installed in RetroArch (Online Updater). A missing core or BIOS gives a black screen.",
                "Generated disc playlists must be stored where RetroArch can read them (shared storage).",
            ),
            usesRetroArchCores = true,
        ),

        // PlayStation
        AndroidEmulatorDef(
            id = "duckstation", name = "DuckStation",
            apps = listOf(app("com.github.stenzek.duckstation", ".EmulationActivity")),
            platforms = PSX,
            modes = listOf(anyFile(intent(extras = listOf(bool("resumeState", false), str("bootPath", SAF)), task = true))),
            source = esde("DUCKSTATION"), confidence = VERIFIED_ESDE,
            homepage = "https://www.duckstation.org/",
            limitations = listOf(
                "The Android source is not public; the launch comes from ES-DE's config.",
                "For multi-disc games keep an .m3u in the psx folder so DuckStation can read it through its folder grant.",
            ),
        ),
        AndroidEmulatorDef(
            id = "epsxe", name = "ePSXe",
            apps = listOf(app("com.epsxe.ePSXe", ".ePSXe")),
            platforms = PSX,
            modes = listOf(anyFile(intent(action = MAIN, extras = listOf(str("com.epsxe.ePSXe.isoName", PATH))))),
            source = esde("EPSXE"), confidence = VERIFIED_ESDE, homepage = play("com.epsxe.ePSXe"),
        ),
        AndroidEmulatorDef(
            id = "fpse-ng", name = "FPseNG",
            apps = listOf(app("com.emulator.fpse64", ".Main")),
            platforms = PSX, modes = listOf(anyFile(viewProvider)),
            source = esde("FPSE-NG"), confidence = VERIFIED_ESDE, homepage = play("com.emulator.fpse64"),
            limitations = listOf("Scoped storage must also be set up inside FPseNG.", "No .chd support."),
        ),
        AndroidEmulatorDef(
            id = "fpse", name = "FPse",
            apps = listOf(app("com.emulator.fpse", ".Main")),
            platforms = PSX, modes = listOf(anyFile(viewProvider)),
            source = esde("FPSE"), confidence = VERIFIED_ESDE, homepage = play("com.emulator.fpse"),
            limitations = listOf("Scoped storage must also be set up inside FPse.", "No .chd support."),
        ),
        AndroidEmulatorDef(
            id = "armsx1", name = "ARMSX1",
            apps = listOf(app("com.nanodata.armsx", "com.armsx2.Main")),
            platforms = PSX, modes = listOf(anyFile(saf)),
            source = esde("ARMSX1") + ". Conflict: the public ARMSX1 manifest only declares com.nanodata.armsx.EmulatorActivity",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/ARMSX2/ARMSX1",
            limitations = listOf(
                "ES-DE and Daijisho start com.armsx2.Main, but the public ARMSX1 repository only declares " +
                    "com.nanodata.armsx.EmulatorActivity. Release builds may differ; test on your device.",
            ),
        ),

        // PlayStation 2
        AndroidEmulatorDef(
            id = "nethersx2", name = "NetherSX2 / AetherSX2",
            apps = listOf(app("xyz.aethersx2.android", ".EmulationActivity")),
            platforms = PS2,
            modes = listOf(anyFile(intent(action = MAIN, extras = listOf(str("bootPath", SAF)), task = true))),
            source = esde("NETHERSX2 (renamed from AETHERSX2)"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/Trixarian/NetherSX2-patch",
            limitations = listOf(
                "Closed source. ES-DE recommends the patched NetherSX2 build (15210-v1.5-4248-noads); the Play Store " +
                    "AetherSX2 probably can't be started from frontends.",
            ),
        ),
        AndroidEmulatorDef(
            id = "nethersx2-turnip", name = "NetherSX2-Turnip",
            apps = listOf(app("xyz.aethersx2.tturnip", "xyz.aethersx2.android.EmulationActivity")),
            platforms = PS2,
            modes = listOf(anyFile(intent(action = MAIN, extras = listOf(str("bootPath", SAF)), task = true))),
            source = esde("NETHERSX2-TURNIP"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/Trixarian/NetherSX2-patch",
        ),
        AndroidEmulatorDef(
            id = "nethersx2-turnip-classic", name = "NetherSX2-Turnip Classic",
            apps = listOf(app("xyz.aethersx2.cturnip", "xyz.aethersx2.android.EmulationActivity")),
            platforms = PS2,
            modes = listOf(anyFile(intent(action = MAIN, extras = listOf(str("bootPath", SAF)), task = true))),
            source = esde("NETHERSX2-TURNIP-CLASSIC"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/Trixarian/NetherSX2-patch",
        ),
        AndroidEmulatorDef(
            id = "armsx2", name = "ARMSX2",
            apps = listOf(
                app("come.nanodata.armsx2", "com.armsx2.MainActivity"),
                app("com.armsx2", ".MainActivity"),
                app("com.armsx2.nightly", "com.armsx2.MainActivity"),
                app("come.nanodata.armsx2", "kr.co.iefriends.pcsx2.MainActivity"),
                app("come.nanodata.armsx2.debug", "kr.co.iefriends.pcsx2.MainActivity"),
            ),
            platforms = PS2, modes = listOf(anyFile(viewSaf)),
            source = esde("ARMSX2 (including the nightly entry)"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/ARMSX2/ARMSX2",
            limitations = listOf("\"come.nanodata.armsx2\" is ARMSX2's real package name, not a typo."),
        ),
        AndroidEmulatorDef(
            id = "emucorex", name = "EmuCoreX",
            apps = listOf(app("com.sbro.emucorex", ".MainActivity")),
            platforms = PS2, modes = listOf(anyFile(intent(action = VIEW, data = SAF, clearTask = true))),
            source = esde("EMUCOREX"), confidence = VERIFIED_ESDE, homepage = "https://github.com/sbro-dev/EmuCoreX",
        ),
        AndroidEmulatorDef(
            id = "play", name = "Play!",
            apps = listOf(app("com.virtualapplications.play", ".MainActivity")),
            platforms = PS2, modes = listOf(anyFile(viewSaf)),
            source = esde("PLAY!"), confidence = VERIFIED_ESDE, homepage = "https://purei.org/",
        ),

        // PlayStation 3 (folder games supported)
        AndroidEmulatorDef(
            id = "aps3e", name = "aPS3e",
            apps = apps("aenu.aps3e.EmulatorActivity", "aenu.aps3e.premium", "aenu.aps3e"),
            platforms = PS3,
            modes = run {
                val action = "aenu.intent.action.APS3E"
                val serial = intent(
                    action = action,
                    extras = listOf(str("game_dir", "{EXTDATA}/Android/data/{PKG}/files/aps3e/config/dev_hdd0/game/$SERIAL")),
                )
                listOf(
                    idFile(serial, "ps3", label = "Game Serial", pattern = PS_TITLE_ID),
                    titleId(serial, label = "Game Serial", pattern = PS_TITLE_ID),
                    fileExt(intent(action = action, extras = listOf(str("iso_uri", SAF))), "iso", label = "ISO"),
                    directory(intent(action = action, extras = listOf(str("game_dir", PATH)))),
                )
            },
            source = esde("APS3E, commands aPS3e Game Serial / Directory / ISO") + "; aenu/aps3e EmulatorActivity.java",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/aenu1/aps3e",
            capabilities = installedContent(FolderSupport.DIRECTORY),
            titleIdMode = TitleIdMode.OPTIONAL,
            limitations = listOf(
                "Folder games (disc structure) are passed as a raw folder path, so aPS3e needs All files access.",
                "A .ps3 file with a serial starts a game installed to aPS3e's HDD (long-press a game, Show Game Info).",
            ),
        ),
        AndroidEmulatorDef(
            id = "armsx3", name = "ARMSX3",
            apps = apps("com.armsx2.Main", "com.armsx3", "com.armsx3.play"),
            platforms = PS3,
            modes = run {
                val serial = intent(action = VIEW, extras = listOf(str("title_id", SERIAL)))
                val path = intent(extras = listOf(str("path", PATH)))
                listOf(
                    idFile(serial, "ps3", label = "Game Serial", pattern = PS_TITLE_ID),
                    titleId(serial, label = "Game Serial", pattern = PS_TITLE_ID),
                    fileExt(path, "iso", label = "ISO"),
                    directory(path),
                )
            },
            source = esde("ARMSX3, commands ARMSX3 Game Serial / Directory or ISO") +
                "; ARMSX3 MainActivityRuntime.kt (com.armsx3.play from the ARMSX3 source per ${Sources.RESEARCH})",
            confidence = VERIFIED_ESDE, homepage = "https://armsx2.net/",
            capabilities = installedContent(FolderSupport.DIRECTORY),
            titleIdMode = TitleIdMode.OPTIONAL,
        ),
        AndroidEmulatorDef(
            id = "emucorec", name = "EmuCoreC",
            apps = listOf(app("com.sbro.emucorec", ".core.ps3.Emulator")),
            platforms = PS3,
            modes = intent(extras = listOf(str("gamePath", PATH))).let { listOf(fileExt(it, "iso", label = "ISO"), directory(it)) },
            source = esde("EMUCOREC"), confidence = VERIFIED_ESDE, homepage = "https://github.com/sbro-dev/EmuCoreC",
            capabilities = installedContent(FolderSupport.DIRECTORY),
        ),
        AndroidEmulatorDef(
            id = "rpcsx", name = "RPCSX",
            apps = listOf(app("net.rpcsx")), platforms = PS3, modes = emptyList(),
            source = "RPCSX/rpcsx-ui-android AndroidManifest.xml (RPCSXActivity exported=\"false\")",
            confidence = VERIFIED_SOURCE, homepage = "https://github.com/RPCSX/rpcsx-ui-android",
            openAppOnlyReason = "RPCSX does not export its emulator activity, so no app can start a game in it. Fuse opens RPCSX instead.",
        ),
        AndroidEmulatorDef(
            id = "rpcs3-android", name = "RPCS3 (Android)",
            apps = listOf(app("net.rpcs3")), platforms = PS3, modes = emptyList(),
            source = "RPCS3-Android/rpcs3-android AndroidManifest.xml (RPCS3Activity exported=\"false\"); archived",
            confidence = VERIFIED_SOURCE, homepage = "https://github.com/RPCS3-Android/rpcs3-android",
            openAppOnlyReason = "RPCS3 for Android is discontinued and does not export its emulator activity. Fuse opens the app instead.",
        ),

        // PlayStation 4
        AndroidEmulatorDef(
            id = "bachatas4", name = "BachataS4",
            apps = listOf(
                app("com.bachatas4.android", ".DirectLaunchActivity"),
                app("com.bachatas4.android.github", "com.bachatas4.android.DirectLaunchActivity"),
            ),
            platforms = platforms("ps4"),
            modes = intent(extras = listOf(str("game_id", SERIAL))).let {
                listOf(idFile(it, "ps4", pattern = PS_TITLE_ID), titleId(it, pattern = PS_TITLE_ID))
            },
            source = esde("BACHATAS4"), confidence = VERIFIED_ESDE,
            capabilities = installedContent(FolderSupport.NONE),
            titleIdMode = TitleIdMode.REQUIRED,
            limitations = listOf("Starts games installed in BachataS4 by title id (a .ps4 file containing e.g. CUSA00001)."),
        ),
        AndroidEmulatorDef(
            id = "shadps4", name = "shadPS4",
            apps = emptyList(), platforms = platforms("ps4"), modes = emptyList(),
            source = "No launch documentation found; matched by name only (Cartridge emulators.js families, MIT)",
            confidence = UNVERIFIED, homepage = "https://shadps4.net/",
            openAppOnlyReason = "No documented way to start a game in shadPS4 for Android from another app. Fuse opens it instead.",
        ),

        // PSP
        AndroidEmulatorDef(
            id = "ppsspp", name = "PPSSPP",
            apps = listOf(
                app("org.ppsspp.ppssppgold", "org.ppsspp.ppsspp.PpssppActivity"),
                app("org.ppsspp.ppsspp", ".PpssppActivity"),
            ),
            platforms = platforms("psp"),
            modes = listOf(anyFile(intent(action = VIEW, category = CATEGORY_DEFAULT, data = SAF))),
            source = esde("PPSSPP") + "; PpssppActivity.java parseIntent",
            confidence = VERIFIED_ESDE, homepage = "https://www.ppsspp.org/",
            capabilities = installedContent(),
            limitations = listOf("When granting folder access inside PPSSPP, press Browse and pick the psp folder, or launches fail (ES-DE)."),
        ),

        // GameCube / Wii / Wii U
        AndroidEmulatorDef(
            id = "dolphin", name = "Dolphin",
            apps = listOf(app("org.dolphinemu.dolphinemu", ".ui.main.TvMainActivity")),
            platforms = platforms("ngc", "wii"),
            modes = listOf(anyFile(intent(action = MAIN, category = CATEGORY_LEANBACK, extras = listOf(str("AutoStartFile", SAF))))),
            source = esde("DOLPHIN") + "; Dolphin StartupHandler.kt",
            confidence = VERIFIED_ESDE, homepage = "https://dolphin-emu.org/",
        ),
        AndroidEmulatorDef(
            id = "dolphin-mmjr", name = "Dolphin MMJR",
            apps = listOf(app("org.mm.jr", "org.dolphinemu.dolphinemu.ui.main.MainActivity")),
            platforms = platforms("ngc", "wii"),
            modes = listOf(anyFile(intent(action = VIEW, extras = listOf(str("AutoStartFile", SAF))))),
            source = esde("DOLPHIN-MMJR"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/MoonPower/Dolphin-MMJR",
            limitations = listOf("ES-DE supports only Dolphin.MMJR.v11505.apk."),
        ),
        AndroidEmulatorDef(
            id = "dolphin-mmjr2", name = "Dolphin MMJR2",
            apps = listOf(app("org.dolphinemu.mmjr", "org.dolphinemu.dolphinemu.ui.main.MainActivity")),
            platforms = platforms("ngc", "wii"),
            modes = listOf(anyFile(intent(action = VIEW, extras = listOf(str("AutoStartFile", SAF))))),
            source = esde("DOLPHIN-MMJR2"), confidence = VERIFIED_ESDE,
            limitations = listOf("ES-DE supports MMJR.v2.0-17878.apk (archive.org)."),
        ),
        AndroidEmulatorDef(
            id = "cemu", name = "Cemu",
            apps = listOf(
                app("info.cemu.cemu", "info.cemu.cemu.emulation.EmulationActivity"),
                app("info.cemu.Cemu", "info.cemu.Cemu.emulation.EmulationActivity"),
            ),
            platforms = platforms("wiiu"), modes = listOf(anyFile(saf)),
            source = esde("CEMU"), confidence = VERIFIED_ESDE, homepage = "https://cemu.info/",
            capabilities = installedContent(),
        ),

        // Nintendo DS
        AndroidEmulatorDef(
            id = "melonds", name = "melonDS",
            apps = listOf(app("me.magnum.melonds", ".ui.emulator.EmulatorActivity")),
            platforms = NDS,
            modes = listOf(anyFile(intent(action = "me.magnum.melonds.LAUNCH_ROM", extras = listOf(str("uri", SAF))))),
            source = esde("MELONDS (its me.magnum.melondualds entry is the WatermelonDS adapter)") + "; melonDS-android LaunchArgs.kt",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/rafaelvcaetano/melonDS-android",
        ),
        AndroidEmulatorDef(
            id = "melonds-nightly", name = "melonDS Nightly",
            apps = listOf(app("me.magnum.melonds.nightly", MELON_ACTIVITY)),
            platforms = NDS,
            modes = listOf(anyFile(intent(action = "me.magnum.melonds.nightly.LAUNCH_ROM", extras = listOf(str("uri", SAF))))),
            source = esde("MELONDS-NIGHTLY"), confidence = VERIFIED_ESDE,
            homepage = "https://github.com/rafaelvcaetano/melonDS-android",
        ),
        AndroidEmulatorDef(
            id = "watermelonds", name = "WatermelonDS",
            apps = listOf(app("me.magnum.melondualds", MELON_ACTIVITY)),
            platforms = NDS,
            modes = listOf(anyFile(intent(action = "me.magnum.melonds.LAUNCH_ROM", extras = listOf(str("uri", SAF))))),
            source = esde("WATERMELONDS"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "drastic", name = "DraStic",
            apps = listOf(app("com.dsemu.drastic", ".DraSticActivity")),
            platforms = platforms("nds"), modes = listOf(anyFile(safTask)),
            source = esde("DRASTIC"), confidence = VERIFIED_ESDE,
            limitations = listOf("Closed source and removed from the Play Store. Zipped ROMs are not supported."),
        ),
        AndroidEmulatorDef(
            id = "noods", name = "NooDS",
            apps = listOf(app("com.hydra.noods", ".FileBrowser")),
            platforms = platforms("nds", "gba"),
            modes = listOf(anyFile(intent(extras = listOf(str("LaunchPath", PATH)), task = true))),
            source = esde("NOODS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/Hydr8gon/NooDS",
            limitations = listOf("Only the GitHub build can be started from frontends. Zipped ROMs are not supported."),
        ),
        AndroidEmulatorDef(
            id = "seedlessds", name = "SeedlessDS",
            apps = listOf(app("com.seedlessds.app", ".LaunchGame")),
            platforms = NDS, modes = listOf(anyFile(intent(extras = listOf(str("rom_path", PATH))))),
            source = esde("SEEDLESSDS"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "skyemu", name = "SkyEmu",
            apps = listOf(app("com.sky.SkyEmu", ".EnhancedNativeActivity")),
            platforms = platforms("gb", "gbc", "gba", "nds"),
            modes = listOf(anyFile(intent(action = VIEW, data = PROVIDER, task = true))),
            source = esde("SKYEMU"), confidence = VERIFIED_ESDE, homepage = "https://github.com/skylersaleh/SkyEmu",
        ),

        // Nintendo 3DS
        AndroidEmulatorDef(
            id = "azahar", name = "Azahar",
            apps = apps(CITRA_ACTIVITY, "org.azahar_emu.azahar", "io.github.lime3ds.android"),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("AZAHAR") + "; Azahar EmulationFragment.kt, build.gradle.kts (Play flavor keeps io.github.lime3ds.android)",
            confidence = VERIFIED_ESDE, homepage = "https://azahar-emu.org/",
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "azaharplus", name = "AzaharPlus",
            apps = listOf(app("io.github.azaharplus.android", CITRA_ACTIVITY)),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("AZAHARPLUS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/AzaharPlus/AzaharPlus",
            capabilities = installedContent(),
            limitations = listOf("Use the APK marked coexists_with_azahar."),
        ),
        AndroidEmulatorDef(
            id = "citra", name = "Citra",
            apps = listOf(app("org.citra.citra_emu", ".activities.EmulationActivity"), app("org.citra.citra_emu", ".ui.main.MainActivity")),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("CITRA"), confidence = VERIFIED_ESDE, homepage = "https://github.com/PabloMK7/citra",
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "citra-canary", name = "Citra Canary",
            apps = listOf(app("org.citra.citra_emu.canary", CITRA_ACTIVITY)),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("CITRA-CANARY"), confidence = VERIFIED_ESDE,
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "citra-mmj", name = "Citra MMJ",
            apps = listOf(app("org.citra.emu", ".ui.EmulationActivity"), app("com.antutu.ABenchMark", "org.citra.emu.ui.EmulationActivity")),
            platforms = N3DS, modes = listOf(anyFile(intent(extras = listOf(str("GamePath", PATH))))),
            source = esde("CITRA-MMJ"), confidence = VERIFIED_ESDE, homepage = "https://github.com/weihuoya/citra",
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "mandarine", name = "Mandarine",
            apps = listOf(app("io.github.mandarine3ds.mandarine", ".activities.EmulationActivity")),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("MANDARINE"), confidence = VERIFIED_ESDE, homepage = "https://github.com/mandarine3ds/mandarine",
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "lime3ds", name = "Lime3DS",
            apps = listOf(app("io.github.lime3ds.android", ".activities.EmulationActivity")),
            platforms = N3DS, modes = listOf(anyFile(safTask)),
            source = esde("LIME3DS"), confidence = VERIFIED_ESDE, homepage = "https://github.com/Lime3DS/lime3ds-archive",
            capabilities = installedContent(),
            limitations = listOf("Discontinued and merged into Azahar."),
        ),
        AndroidEmulatorDef(
            id = "panda3ds", name = "Panda3DS",
            apps = listOf(app("com.panda3ds.pandroid", ".app.MainActivity")),
            platforms = N3DS, modes = emptyList(),
            source = esde("PANDA3DS") + "; ES-DE ANDROID.md section Panda3DS (pandroid)",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/wheremyfoodat/Panda3DS",
            openAppOnlyReason = "ES-DE notes Panda3DS has no way to run a game directly; its own interface opens and you pick the game there.",
        ),

        // Nintendo Switch: updates and DLC are always installed inside the emulator.
        AndroidEmulatorDef(
            id = "eden", name = "Eden",
            apps = apps(YUZU_ACTIVITY, "dev.eden.eden_emulator", "dev.legacy.eden_emulator", "com.miHoYo.Yuanshen"),
            platforms = SWITCH, modes = yuzuStyle(title = true),
            source = esde("EDEN") + "; Eden AndroidManifest.xml, EmulationFragment.kt, CustomSettingsHandler.kt " +
                "(git.eden-emu.dev master: LAUNCH_WITH_CUSTOM_CONFIG + title_id)",
            confidence = VERIFIED_ESDE, homepage = "https://eden-emu.dev/",
            capabilities = installedContent(),
            titleIdMode = TitleIdMode.OPTIONAL,
            installHint = "Install to NAND",
            limitations = listOf(
                "Updates and DLC must be installed inside Eden; they can't be passed at launch.",
                "A title id launch only finds games already in Eden's library.",
            ),
        ),
        AndroidEmulatorDef(
            id = "eden-nightly", name = "Eden Nightly",
            apps = apps(
                YUZU_ACTIVITY,
                "dev.eden.eden_emulator.nightly", "dev.legacy.eden_emulator.nightly", "com.miHoYo.Yuanshen.nightly",
            ),
            platforms = SWITCH, modes = yuzuStyle(title = true),
            source = esde("EDEN-NIGHTLY") + "; Eden source (same activity and intent filters)",
            confidence = VERIFIED_ESDE, homepage = "https://eden-emu.dev/",
            capabilities = installedContent(),
            titleIdMode = TitleIdMode.OPTIONAL,
            installHint = "Install to NAND",
        ),
        AndroidEmulatorDef(
            id = "kenji-nx", name = "Kenji-NX",
            apps = listOf(app("org.kenjinx.android", ".MainActivity")),
            platforms = SWITCH,
            modes = listOf(anyFile(intent(action = "org.kenjinx.android.LAUNCH_GAME", extras = listOf(str("bootPath", SAF))))),
            source = esde("KENJI-NX") + " (source host unreachable during research)",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/KenjiNX/Kenji-NX",
            capabilities = installedContent(),
        ),
        AndroidEmulatorDef(
            id = "skyline", name = "Skyline",
            apps = listOf(app("skyline.emu", "emu.skyline.EmulationActivity")),
            platforms = SWITCH, modes = listOf(anyFile(viewProvider)),
            source = esde("SKYLINE"), confidence = VERIFIED_ESDE, homepage = "https://github.com/skyline-emu/skyline",
            capabilities = installedContent(),
            limitations = listOf("Discontinued; the last build is on archive.org."),
        ),
        AndroidEmulatorDef(
            id = "strato", name = "Strato",
            apps = listOf(app("org.stratoemu.strato", "org.stratoemu.strato.EmulationActivity")),
            platforms = SWITCH, modes = emptyList(),
            source = "strato-emu/strato AndroidManifest.xml (exported EmulationActivity); launch behaviour not verified",
            confidence = UNVERIFIED, homepage = "https://github.com/strato-emu/strato",
            openAppOnlyReason = "Strato's launch intent is not documented or verified, so Fuse only opens the app.",
        ),
        AndroidEmulatorDef(
            id = "citron", name = "Citron",
            apps = apps(
                "org.citron.citron_emu.activities.EmulationActivity",
                "org.citron.citron_emu", "org.citron.citron_emu.ea", "com.antutu.ABenchMark", "com.miHoYo.Yuanshen",
            ),
            platforms = SWITCH, modes = yuzuStyle(title = false),
            source = "Manifest from the citron-neo/emulator GitHub mirror; packages and yuzu-style TECH_DISCOVERED + " +
                "FileProvider launch from community ES-DE configs (GlazedBelmont/es-de-android-custom-systems, reference only)",
            confidence = COMMUNITY,
            capabilities = installedContent(),
            limitations = listOf("Not in ES-DE; the launch mirrors Eden/yuzu and is only confirmed by community configs."),
        ),
        AndroidEmulatorDef(
            id = "sudachi", name = "Sudachi",
            apps = listOf(app("org.sudachi.sudachi_emu", "org.sudachi.sudachi_emu.activities.EmulationActivity")),
            platforms = SWITCH, modes = yuzuStyle(title = false),
            source = "applicationId from source mirrors (p-yukusai/sudachi-emu, Synoptikon/Sudachi); yuzu-style launch per community configs",
            confidence = COMMUNITY,
            capabilities = installedContent(),
            limitations = listOf("Not in ES-DE; the launch mirrors Eden/yuzu and is only confirmed by community configs."),
        ),
        AndroidEmulatorDef(
            id = "yuzu", name = "yuzu",
            apps = apps(YUZU_ACTIVITY, "org.yuzu.yuzu_emu", "org.yuzu.yuzu_emu.ea"),
            platforms = SWITCH, modes = yuzuStyle(title = false),
            source = "Community ES-DE configs (yuzu-style TECH_DISCOVERED + FileProvider); discontinued upstream",
            confidence = COMMUNITY,
            capabilities = installedContent(),
            limitations = listOf("Discontinued. Not in ES-DE; the launch is only confirmed by community configs."),
        ),

        // PS Vita: games are installed in the emulator and started by title id.
        AndroidEmulatorDef(
            id = "vita3k", name = "Vita3K",
            apps = listOf(app("org.vita3k.emulator", ".Emulator"), app("org.vita3k.emulator.ikhoeyZX", "org.vita3k.emulator.Emulator")),
            platforms = platforms("psvita"), modes = vitaModes,
            source = esde("VITA3K") + "; Vita3K Emulator.java getArguments",
            confidence = VERIFIED_ESDE, homepage = "https://vita3k.org/",
            capabilities = installedContent(FolderSupport.NONE),
            titleIdMode = TitleIdMode.REQUIRED,
            limitations = listOf(
                "Games must be installed in Vita3K first. Fuse starts them by title id: a .psvita file containing e.g. " +
                    "PCSF00007, or the id in the file name.",
            ),
        ),
        AndroidEmulatorDef(
            id = "emucorev", name = "EmuCoreV",
            apps = listOf(app("com.sbro.emucorev", ".core.vita.Emulator")),
            platforms = platforms("psvita"), modes = vitaModes,
            source = esde("EMUCOREV"), confidence = VERIFIED_ESDE, homepage = "https://github.com/sbro-dev/EmuCoreV",
            capabilities = installedContent(FolderSupport.NONE),
            titleIdMode = TitleIdMode.REQUIRED,
        ),

        // Dreamcast / Saturn
        AndroidEmulatorDef(
            id = "flycast", name = "Flycast",
            apps = listOf(app("com.flycast.emulator", "com.flycast.emulator.MainActivity"), app("com.flycast.emulator", "com.reicast.emulator.MainActivity")),
            platforms = platforms("dc", "arcade"), modes = listOf(anyFile(viewSaf)),
            source = esde("FLYCAST") + "; Flycast BaseGLActivity.java (reads data only for ACTION_VIEW)",
            confidence = VERIFIED_ESDE, homepage = "https://github.com/flyinghead/flycast",
            limitations = listOf("Arcade support covers NAOMI and Atomiswave sets only."),
        ),
        AndroidEmulatorDef(
            id = "redream", name = "Redream",
            apps = listOf(app("io.recompiled.redream", ".MainActivity")),
            platforms = platforms("dc"), modes = listOf(anyFile(viewSaf)),
            source = esde("REDREAM"), confidence = VERIFIED_ESDE, homepage = "https://redream.io/",
        ),
        AndroidEmulatorDef(
            id = "yabasanshiro-2", name = "Yaba Sanshiro 2",
            apps = apps("org.uoyabause.android.Yabause", "org.devmiyax.yabasanshioro2.pro", "org.devmiyax.yabasanshioro2"),
            platforms = platforms("saturn"),
            modes = listOf(anyFile(intent(action = VIEW, extras = listOf(str("org.uoyabause.android.FileNameUri", SAF)), task = true))),
            source = esde("YABASANSHIRO-2"), confidence = VERIFIED_ESDE, homepage = play("org.devmiyax.yabasanshioro2.pro"),
            limitations = listOf("Only the Pro build starts games from frontends. .bin/.cue does not work, .chd does."),
        ),
        AndroidEmulatorDef(
            id = "saturn-emu", name = "Saturn.emu",
            apps = listOf(app("com.explusalpha.SaturnEmu", IMAGINE)),
            platforms = platforms("saturn"), modes = listOf(anyFile(saf)),
            source = esde("SATURN-EMU"), confidence = VERIFIED_ESDE, homepage = EXPLUS_HOME,
        ),

        // Nintendo 64
        AndroidEmulatorDef(
            id = "m64plus-fz", name = "M64Plus FZ",
            apps = apps(M64_ACTIVITY, "org.mupen64plusae.v3.fzurita.amazon", "org.mupen64plusae.v3.fzurita.pro", "org.mupen64plusae.v3.fzurita"),
            platforms = platforms("n64"), modes = listOf(anyFile(viewSaf)),
            source = esde("M64PLUS-FZ") + "; SplashActivity forwards the intent data", confidence = VERIFIED_ESDE,
            homepage = play("org.mupen64plusae.v3.fzurita"),
        ),
        AndroidEmulatorDef(
            id = "mupen64plus-ae", name = "Mupen64Plus AE",
            apps = listOf(app("org.mupen64plusae.v3.alpha", M64_ACTIVITY)),
            platforms = platforms("n64"), modes = listOf(anyFile(viewSaf)),
            source = esde("MUPEN64PLUS-AE"), confidence = VERIFIED_ESDE, homepage = "https://github.com/mupen64plus-ae/mupen64plus-ae",
        ),

        // Game Boy family
        AndroidEmulatorDef(
            id = "pizza-boy-gba", name = "Pizza Boy GBA",
            apps = listOf(
                app("it.dbtecno.pizzaboygbapro", "it.dbtecno.pizzaboygbapro.MainActivity"),
                app("it.dbtecno.pizzaboygba", "it.dbtecno.pizzaboygba.MainActivity"),
            ),
            platforms = platforms("gba"),
            modes = listOf(anyFile(intent(extras = listOf(str("rom_uri", SAF)), task = true))),
            source = esde("PIZZA-BOY-GBA"), confidence = VERIFIED_ESDE, homepage = play("it.dbtecno.pizzaboygbapro"),
        ),
        AndroidEmulatorDef(
            id = "pizza-boy-gbc", name = "Pizza Boy GBC",
            apps = listOf(
                app("it.dbtecno.pizzaboypro", "it.dbtecno.pizzaboypro.MainActivity"),
                app("it.dbtecno.pizzaboy", "it.dbtecno.pizzaboy.MainActivity"),
            ),
            platforms = GB,
            modes = listOf(anyFile(intent(extras = listOf(str("rom_uri", SAF)), task = true))),
            source = esde("PIZZA-BOY-GBC"), confidence = VERIFIED_ESDE, homepage = play("it.dbtecno.pizzaboypro"),
        ),
        AndroidEmulatorDef(
            id = "my-boy", name = "My Boy!",
            apps = listOf(app("com.fastemulator.gba", ".EmulatorActivity")),
            platforms = platforms("gba"), modes = listOf(anyFile(viewSaf)),
            source = esde("MY-BOY"), confidence = VERIFIED_ESDE, homepage = play("com.fastemulator.gba"),
            limitations = listOf("The free Lite build is not supported."),
        ),
        AndroidEmulatorDef(
            id = "my-oldboy", name = "My OldBoy!",
            apps = listOf(app("com.fastemulator.gbc", ".EmulatorActivity")),
            platforms = GB, modes = listOf(anyFile(viewSaf)),
            source = esde("MY-OLDBOY"), confidence = VERIFIED_ESDE, homepage = play("com.fastemulator.gbc"),
        ),
        AndroidEmulatorDef(
            id = "linkboy", name = "Linkboy",
            apps = listOf(app("com.pixelrespawn.linkboy", ".EmulatorActivity")),
            platforms = platforms("gb", "gbc", "gba"), modes = listOf(anyFile(viewSaf)),
            source = esde("LINKBOY"), confidence = VERIFIED_ESDE, homepage = play("com.pixelrespawn.linkboy"),
        ),
        explus("gba-emu", "GBA.emu", "com.explusalpha.GbaEmu", platforms("gba"), PROVIDER, "GBA-EMU"),
        explus("gbc-emu", "GBC.emu", "com.explusalpha.GbcEmu", GB, PROVIDER, "GBC-EMU"),

        // NES / SNES
        explus("nes-emu", "NES.emu", "com.explusalpha.NesEmu", NES, PROVIDER, "NES-EMU"),
        AndroidEmulatorDef(
            id = "ines", name = "iNES",
            apps = listOf(app("com.fms.ines.free", FMS_ACTIVITY)),
            platforms = NES, modes = listOf(anyFile(viewSafTask)),
            source = esde("INES"), confidence = VERIFIED_ESDE, homepage = FMS_HOME,
        ),
        AndroidEmulatorDef(
            id = "nesoid", name = "Nesoid",
            apps = listOf(app("com.androidemu.nes", ".EmulatorActivity")),
            platforms = NES, modes = listOf(anyFile(intent(action = VIEW, data = PATH))),
            source = esde("NESOID"), confidence = VERIFIED_ESDE,
        ),
        explus("snes9x-explus", "Snes9x EX+", "com.explusalpha.Snes9xPlus", platforms("snes", "sfam"), SAF, "SNES9X-EXPLUS"),

        // Sega
        AndroidEmulatorDef(
            id = "md-emu", name = "MD.emu",
            apps = listOf(app("com.explusalpha.MdEmu", IMAGINE)),
            platforms = platforms("genesis", "segacd", "sms"),
            modes = listOf(
                anyFile(intent(data = SAF), label = "CD", platforms = platforms("segacd")),
                anyFile(intent(data = PROVIDER), label = "Cartridge", platforms = platforms("genesis", "sms")),
            ),
            source = esde("MD-EMU (FileProvider for cartridge systems, SAF for Sega CD)"),
            confidence = VERIFIED_ESDE, homepage = EXPLUS_HOME,
        ),
        AndroidEmulatorDef(
            id = "pizza-boy-sc", name = "Pizza Boy SC",
            apps = listOf(app("it.dbtecno.pizzaboyscpro", ".MainActivity"), app("it.dbtecno.pizzaboyscbasic", ".MainActivity")),
            platforms = platforms("genesis", "segacd", "sms", "gamegear"),
            modes = listOf(anyFile(intent(extras = listOf(str("rom_uri", SAF)), task = true))),
            source = esde("PIZZA-BOY-SC"), confidence = VERIFIED_ESDE, homepage = play("it.dbtecno.pizzaboyscpro"),
        ),
        AndroidEmulatorDef(
            id = "mastergear", name = "MasterGear",
            apps = listOf(app("com.fms.mg", FMS_ACTIVITY)),
            platforms = platforms("sms", "gamegear", "sg1000"), modes = listOf(anyFile(viewSafTask)),
            source = esde("MASTERGEAR"), confidence = VERIFIED_ESDE, homepage = FMS_HOME,
        ),

        // Arcade and SNK
        AndroidEmulatorDef(
            id = "mame4droid", name = "MAME4droid",
            apps = listOf(app("com.seleuco.mame4droid", ".MAME4droid")),
            platforms = platforms("arcade", "neogeoaes"), modes = listOf(anyFile(viewProvider)),
            source = esde("MAME4DROID"), confidence = VERIFIED_ESDE, homepage = play("com.seleuco.mame4droid"),
        ),
        AndroidEmulatorDef(
            id = "mame4droid-current", name = "MAME4droid Current",
            apps = listOf(app("com.seleuco.mame4d2024", "com.seleuco.mame4droid.MAME4droid")),
            platforms = platforms("arcade", "neogeoaes", "vectrex", "intellivision", "atari7800", "jaguar"),
            modes = listOf(
                anyFile(
                    intent(action = VIEW, data = PROVIDER, extras = listOf(str("cli_params", "-rompath '{ROMDIR}'"))),
                    label = "Arcade", platforms = platforms("arcade", "neogeoaes"),
                ),
            ) + listOf("vectrex" to "vectrex", "intellivision" to "intv", "atari7800" to "a7800", "jaguar" to "jaguar").map { (p, machine) ->
                anyFile(
                    intent(action = VIEW, data = machine, extras = listOf(str("cli_params", "-rompath '{ROMDIR}' -cart '$PATH'"))),
                    label = "Cartridge", platforms = platforms(p),
                )
            },
            source = esde("MAME4DROID-CURRENT (cli_params -rompath uses the game's own folder; ES-DE also appends <ROMs>/<system>)"),
            confidence = VERIFIED_ESDE, homepage = play("com.seleuco.mame4d2024"),
        ),
        explus("neo-emu", "NEO.emu", "com.explusalpha.NeoEmu", platforms("neogeoaes", "arcade"), SAF, "NEO-EMU"),
        explus("ngp-emu", "NGP.emu", "com.explusalpha.NgpEmu", platforms("neo-geo-pocket", "neo-geo-pocket-color"), PROVIDER, "NGP-EMU"),

        // NEC, Atari, Bandai and the rest
        AndroidEmulatorDef(
            id = "pce-emu", name = "PCE.emu",
            apps = listOf(app("com.PceEmu", IMAGINE)),
            platforms = platforms("tg16", "turbografx-cd"),
            modes = listOf(
                anyFile(intent(data = SAF), label = "CD", platforms = platforms("turbografx-cd")),
                anyFile(intent(data = PROVIDER), label = "HuCard", platforms = platforms("tg16")),
            ),
            source = esde("PCE-EMU (FileProvider for HuCards, SAF for CD)"), confidence = VERIFIED_ESDE, homepage = EXPLUS_HOME,
        ),
        explus("2600-emu", "2600.emu", "com.explusalpha.A2600Emu", platforms("atari2600"), PROVIDER, "2600-EMU"),
        explus("lynx-emu", "Lynx.emu", "com.explusalpha.LynxEmu", platforms("lynx"), PROVIDER, "LYNX-EMU"),
        explus("swan-emu", "Swan.emu", "com.explusalpha.SwanEmu", platforms("wonderswan", "wonderswan-color"), PROVIDER, "SWAN-EMU"),
        explus("c64-emu", "C64.emu", "com.explusalpha.C64Emu", platforms("c64"), SAF, "C64-EMU"),
        explus("msx-emu", "MSX.emu", "com.explusalpha.MsxEmu", platforms("msx", "colecovision"), SAF, "MSX-EMU"),
        AndroidEmulatorDef(
            id = "fmsx", name = "fMSX",
            apps = apps(FMS_ACTIVITY, "com.fms.fmsx.deluxe", "com.fms.fmsx"),
            platforms = platforms("msx"), modes = listOf(anyFile(viewSafTask)),
            source = esde("FMSX"), confidence = VERIFIED_ESDE, homepage = FMS_HOME,
        ),
        AndroidEmulatorDef(
            id = "colem", name = "ColEm",
            apps = apps(FMS_ACTIVITY, "com.fms.colem.deluxe", "com.fms.colem"),
            platforms = platforms("colecovision"), modes = listOf(anyFile(viewSafTask)),
            source = esde("COLEM"), confidence = VERIFIED_ESDE, homepage = FMS_HOME,
        ),
        AndroidEmulatorDef(
            id = "virtual-virtual-boy", name = "Virtual Virtual Boy",
            apps = listOf(app("com.simongellis.vvb", ".MainActivity")),
            platforms = platforms("virtualboy"),
            modes = listOf(anyFile(intent(action = VIEW, data = PROVIDER, task = true))),
            source = esde("VIRTUAL-VIRTUAL-BOY"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "iratajaguar", name = "IrataJaguar",
            apps = listOf(app("ru.vastness.altmer.iratajaguar", ".EmulatorActivity")),
            platforms = platforms("jaguar"), modes = listOf(anyFile(saf)),
            source = esde("IRATAJAGUAR"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "real3doplayer", name = "Real3DOPlayer",
            apps = listOf(app("ru.vastness.altmer.real3doplayer", ".EmulatorActivity")),
            platforms = platforms("3do"), modes = listOf(anyFile(saf)),
            source = esde("REAL3DOPLAYER"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "scummvm", name = "ScummVM",
            apps = apps("org.scummvm.scummvm.SplashActivity", "org.scummvm.scummvm.debug", "org.scummvm.scummvm"),
            platforms = platforms("scummvm"),
            // ES-DE really sets the category to android.intent.action.MAIN here.
            modes = listOf(idFile(intent(action = MAIN, category = MAIN, data = INJECT), "scummvm", "svm")),
            source = esde("SCUMMVM") + "; ES-DE USERGUIDE section ScummVM", confidence = VERIFIED_ESDE,
            homepage = "https://www.scummvm.org/",
            capabilities = caps(folders = FolderSupport.NONE),
            limitations = listOf(
                "On Android, games must be added in ScummVM first. The .scummvm file is named after, and contains, " +
                    "the game's ScummVM short name.",
            ),
        ),

        // Xbox / Xbox 360
        AndroidEmulatorDef(
            id = "x1-box", name = "X1 BOX",
            apps = listOf(app("com.izzy2lost.x1box", ".LauncherActivity")),
            platforms = platforms("xbox"), modes = listOf(anyFile(viewSaf)),
            source = esde("X1-BOX"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "hakux", name = "hakuX",
            apps = listOf(app("com.rfandango.haku_x", ".LauncherActivity")),
            platforms = platforms("xbox"), modes = listOf(anyFile(viewSaf)),
            source = esde("HAKUX"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "xenra", name = "Xenra",
            apps = apps("Ali.Xanite.LauncherActivity", "Ali.Xanite.green", "Ali.Xanite"),
            platforms = platforms("xbox", "xbox360"), modes = listOf(anyFile(saf)),
            source = esde("XENRA"), confidence = VERIFIED_ESDE,
        ),
        AndroidEmulatorDef(
            id = "ax360e", name = "aX360e",
            apps = apps("aenu.ax360e.EmulatorActivity", "aenu.ax360e", "aenu.ax360e.free"),
            platforms = platforms("xbox360"),
            modes = listOf(anyFile(intent(action = "aenu.intent.action.AX360E", extras = listOf(str("game_uri", SAF))))),
            source = esde("AX360E"), confidence = VERIFIED_ESDE, homepage = "https://github.com/aenu1/ax360e",
        ),
        AndroidEmulatorDef(
            id = "xendroid", name = "XenDroid",
            apps = listOf(app("xendroid.compose", ".EmulatorHostActivity")),
            platforms = platforms("xbox360"), modes = listOf(anyFile(saf)),
            source = esde("XENDROID"), confidence = VERIFIED_ESDE,
        ),

        // Multi-system front ends without an external per-game launch
        AndroidEmulatorDef(
            id = "lemuroid", name = "Lemuroid",
            apps = listOf(app("com.swordfish.lemuroid")),
            platforms = platforms(
                "atari2600", "atari7800", "lynx", "nes", "snes", "gb", "gbc", "gba", "genesis", "segacd", "sms",
                "gamegear", "n64", "psx", "psp", "arcade", "nds", "tg16", "neo-geo-pocket", "neo-geo-pocket-color",
                "wonderswan", "wonderswan-color",
            ),
            modes = emptyList(),
            source = "Lemuroid AndroidManifest.xml and ExternalGameLauncherActivity.kt (lemuroid://<pkg>/play-game/id/<internal id>)",
            confidence = VERIFIED_SOURCE, homepage = "https://github.com/Swordfish90/Lemuroid",
            openAppOnlyReason = "Lemuroid only starts games by its own internal database id, which other apps can't know. Fuse opens Lemuroid instead.",
        ),
    )

    /** An EX+ Alpha emulator: one package, `com.imagine.BaseActivity`, data only. */
    private fun explus(id: String, name: String, pkg: String, platforms: Set<PlatformId>, data: String, rule: String) =
        AndroidEmulatorDef(
            id = id, name = name,
            apps = listOf(app(pkg, IMAGINE)),
            platforms = platforms,
            modes = listOf(anyFile(intent(data = data))),
            source = esde(rule) + "; EX+ Alpha BaseActivity.java intentDataPath()",
            confidence = VERIFIED_ESDE, homepage = EXPLUS_HOME,
        )
}
