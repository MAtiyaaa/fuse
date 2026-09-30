package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.ChildContent
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.ContentSupport
import io.github.matiyaaa.fuse.model.Disc
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LocationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchResolverTest {
    private val resolver = LaunchResolver()
    private val psxInstalled = listOf(
        androidEmu("epsxe", "com.epsxe.ePSXe/com.epsxe.ePSXe.ePSXe"),
        androidEmu("retroarch", "com.retroarch.aarch64"),
        androidEmu("duckstation", "com.github.stenzek.duckstation"),
    )
    private val ff7 = game("psx", "$ROMS/psx/Final Fantasy VII.chd")

    @Test
    fun priorityOrderIsUsedWithoutChoices() {
        val r = resolver.resolve(ff7, null, psxInstalled, Host.ANDROID)
        assertEquals(EmulatorId("duckstation"), r.adapter?.id)
        assertEquals(ChoiceSource.PRIORITY, r.source)
        assertEquals(listOf("retroarch", "epsxe"), r.alternatives.map { it.adapter.id.value })
    }

    @Test
    fun platformSettingBeatsPriority() {
        val r = resolver.resolve(ff7, EmulatorId("epsxe"), psxInstalled, Host.ANDROID)
        assertEquals(EmulatorId("epsxe"), r.adapter?.id)
        assertEquals(ChoiceSource.PLATFORM, r.source)
    }

    @Test
    fun gameOverrideBeatsPlatformSetting() {
        val r = resolver.resolve(ff7.copy(emulatorOverride = EmulatorId("retroarch")), EmulatorId("epsxe"), psxInstalled, Host.ANDROID)
        assertEquals(EmulatorId("retroarch"), r.adapter?.id)
        assertEquals(ChoiceSource.GAME, r.source)
    }

    @Test
    fun missingOverrideFallsBackToPlatformWithANote() {
        val r = resolver.resolve(ff7.copy(emulatorOverride = EmulatorId("fpse")), EmulatorId("epsxe"), psxInstalled, Host.ANDROID)
        assertEquals(EmulatorId("epsxe"), r.adapter?.id)
        assertTrue(r.notes.single().contains("isn't installed"))
    }

    @Test
    fun openAppOnlyAdaptersOnlyWinWhenNothingElseCanStart() {
        // Lemuroid first in priority, but it can only open its app.
        val registry = AdapterRegistry(AdapterRegistry.Default.adapters) { _, _ -> listOf(EmulatorId("lemuroid"), EmulatorId("retroarch")) }
        val custom = LaunchResolver(registry)
        val withLemuroid = listOf(androidEmu("lemuroid", "com.swordfish.lemuroid"), androidEmu("retroarch", "com.retroarch"))
        assertEquals(EmulatorId("retroarch"), custom.resolve(ff7, null, withLemuroid, Host.ANDROID).adapter?.id)
        val only = custom.resolve(ff7, null, withLemuroid.take(1), Host.ANDROID)
        assertIs<LaunchPlan.OpenAppOnly>(only.plan)
        assertEquals("com.swordfish.lemuroid", only.plan.appId)
    }

    @Test
    fun nothingInstalledIsUnsupported() {
        val r = resolver.resolve(ff7, null, emptyList(), Host.ANDROID)
        assertNull(r.adapter)
        assertEquals(ChoiceSource.NONE, r.source)
        assertIs<LaunchPlan.Unsupported>(r.plan)
    }

    @Test
    fun folderGameDirectoryGivesTheFolder() {
        val folder = "$ROMS/ps3/Gran Turismo 5.ps3"
        val gt5 = game("ps3", folder, kind = LocationKind.FOLDER, launchPath = "$folder/PS3_GAME/USRDIR/EBOOT.BIN")
        val r = resolver.resolve(gt5, null, listOf(androidEmu("aps3e", "aenu.aps3e")), Host.ANDROID)
        assertEquals(LaunchTarget.Directory(folder), r.target)
        val intent = r.plan as LaunchPlan.AndroidIntent
        assertEquals("aenu.intent.action.APS3E", intent.action)
        assertEquals(mapOf("game_dir" to folder), intent.stringExtras)
        assertEquals("aenu.aps3e.EmulatorActivity", intent.activity)
    }

    @Test
    fun folderGameDirectoryAdapterTakesAnAcceptedFileInAMultiFileFolder() {
        val folder = "$ROMS/ps3/Demon's Souls"
        val g = game("ps3", folder, kind = LocationKind.FOLDER, launchPath = "$folder/Demon's Souls.iso", interpretation = FolderInterpretation.MULTI_FILE_GAME)
        val r = resolver.resolve(g, null, listOf(androidEmu("aps3e", "aenu.aps3e.premium")), Host.ANDROID)
        assertEquals(LaunchTarget.File("$folder/Demon's Souls.iso"), r.target)
        assertEquals(mapOf("iso_uri" to "{SAF}"), (r.plan as LaunchPlan.AndroidIntent).stringExtras)
    }

    @Test
    fun folderGameResolvesFileUsesTheLaunchFile() {
        val folder = "$ROMS/psx/Jet Moto"
        val g = game("psx", folder, kind = LocationKind.FOLDER, launchPath = "$folder/Jet Moto.cue", interpretation = FolderInterpretation.MULTI_FILE_GAME)
        val r = resolver.resolve(g, null, listOf(androidEmu("retroarch", "com.retroarch")), Host.ANDROID)
        assertEquals(LaunchTarget.File("$folder/Jet Moto.cue"), r.target)
        assertEquals("$folder/Jet Moto.cue", (r.plan as LaunchPlan.AndroidIntent).stringExtras["ROM"])
    }

    @Test
    fun folderGameWithoutAFileIsUnsupportedForFileEmulators() {
        val folder = "$ROMS/psx/Jet Moto"
        val g = game("psx", folder, kind = LocationKind.FOLDER, interpretation = FolderInterpretation.FOLDER_BROWSER)
        val r = resolver.resolve(g, null, listOf(androidEmu("duckstation", "com.github.stenzek.duckstation")), Host.ANDROID)
        val plan = assertIs<LaunchPlan.Unsupported>(r.plan)
        assertEquals("DuckStation can't open a folder game; pick a file with Folder Behaviour", plan.reason)
    }

    @Test
    fun folderSupportNoneOnlyUsesAFileInsideTheFolder() {
        val folder = "$ROMS/win/Celeste"
        val inside = game("win", folder, kind = LocationKind.FOLDER, launchPath = "$folder/Celeste.desktop", interpretation = FolderInterpretation.MULTI_FILE_GAME)
        val winlator = listOf(androidEmu("winlator-cmod", "com.winlator.cmod"))
        val ok = resolver.resolve(inside, null, winlator, Host.ANDROID)
        assertEquals("$folder/Celeste.desktop", (ok.plan as LaunchPlan.AndroidIntent).stringExtras["shortcut_path"])
        val bare = resolver.resolve(inside.copy(location = inside.location.copy(launchPath = folder)), null, winlator, Host.ANDROID)
        assertIs<LaunchPlan.Unsupported>(bare.plan)
    }

    @Test
    fun multiDiscAsksForAGeneratedPlaylistWhenTheAdapterReadsThem() {
        val discs = listOf(Disc(2, "Disc 2", "$ROMS/psx/FF7 (Disc 2).chd"), Disc(1, "Disc 1", "$ROMS/psx/FF7 (Disc 1).chd"))
        val g = game("psx", discs[1].path, discs = discs, title = "Final Fantasy VII")
        val requests = mutableListOf<PlaylistRequest>()
        val withCache = LaunchResolver(playlists = { req -> requests += req; "/cache/playlists/${req.fileName}" })
        val r = withCache.resolve(g, null, listOf(androidEmu("retroarch", "com.retroarch")), Host.ANDROID)
        assertEquals(LaunchTarget.Playlist("/cache/playlists/Final Fantasy VII.m3u", generated = true), r.target)
        assertEquals("/cache/playlists/Final Fantasy VII.m3u", (r.plan as LaunchPlan.AndroidIntent).stringExtras["ROM"])
        assertEquals("$ROMS/psx/FF7 (Disc 1).chd\n$ROMS/psx/FF7 (Disc 2).chd\n", requests.single().content)
        assertEquals(requests.single(), r.playlistRequest)

        // No playlist support (DuckStation via SAF) or no generator: disc 1.
        val duck = withCache.resolve(g, null, listOf(androidEmu("duckstation", "com.github.stenzek.duckstation")), Host.ANDROID)
        assertEquals(LaunchTarget.File("$ROMS/psx/FF7 (Disc 1).chd"), duck.target)
        val plain = resolver.resolve(g, null, listOf(androidEmu("retroarch", "com.retroarch")), Host.ANDROID)
        assertEquals(LaunchTarget.File("$ROMS/psx/FF7 (Disc 1).chd"), plain.target)
        val off = withCache.resolve(g, null, listOf(androidEmu("retroarch", "com.retroarch")), Host.ANDROID, ScopedLaunchChoice(generateM3u = false))
        assertEquals(LaunchTarget.File("$ROMS/psx/FF7 (Disc 1).chd"), off.target)
    }

    @Test
    fun ps3SerialFileUsesAps3eSerialMode() {
        val g = game("ps3", "$ROMS/ps3/Demon's Souls.ps3")
        val r = resolver.resolve(g, null, listOf(androidEmu("aps3e", "aenu.aps3e")), Host.ANDROID, ScopedLaunchChoice(injectedText = "BLUS30443\n"))
        val intent = r.plan as LaunchPlan.AndroidIntent
        assertEquals("{EXTDATA}/Android/data/aenu.aps3e/files/aps3e/config/dev_hdd0/game/BLUS30443", intent.stringExtras["game_dir"])
    }

    @Test
    fun vitaNeedsATitleId() {
        val vita = listOf(androidEmu("vita3k", "org.vita3k.emulator"))
        val idFile = resolver.resolve(game("psvita", "$ROMS/psvita/Persona 4 Golden.psvita"), null, vita, Host.ANDROID, ScopedLaunchChoice(injectedText = "PCSB00245"))
        assertEquals(listOf("-r", "PCSB00245"), (idFile.plan as LaunchPlan.AndroidIntent).arrayExtras["AppStartParameters"])

        val named = resolver.resolve(game("psvita", "$ROMS/psvita/Persona 4 Golden [PCSB00245].vpk", serial = "PCSB00245"), null, vita, Host.ANDROID)
        assertEquals(LaunchTarget.TitleId("PCSB00245"), named.target)
        assertEquals(listOf("-r", "PCSB00245"), (named.plan as LaunchPlan.AndroidIntent).arrayExtras["AppStartParameters"])

        val none = resolver.resolve(game("psvita", "$ROMS/psvita/Persona 4 Golden.vpk"), null, vita, Host.ANDROID)
        assertTrue((none.plan as LaunchPlan.Unsupported).reason.contains(".psvita"))
    }

    @Test
    fun switchEdenLaunchAndContentPlan() {
        val folder = "$ROMS/switch/Zelda TotK"
        val g = game(
            "switch", folder, kind = LocationKind.FOLDER, launchPath = "$folder/Zelda TotK.nsp", interpretation = FolderInterpretation.MULTI_FILE_GAME,
            content = listOf(
                ChildContent(ContentKind.UPDATE, "v1.2.1", "$folder/update/v1.2.1.nsp", isDirectory = false),
                ChildContent(ContentKind.DLC, "Pack 1", "$folder/dlc/pack1.nsp", isDirectory = false),
                ChildContent(ContentKind.DLC, "Pack 2", "$folder/dlc/pack2.nsp", isDirectory = false),
                ChildContent(ContentKind.MANUAL, "Manual", "$folder/manual/manual.pdf", isDirectory = false),
            ),
        )
        val r = resolver.resolve(g, null, listOf(androidEmu("eden", "dev.eden.eden_emulator")), Host.ANDROID)
        val intent = r.plan as LaunchPlan.AndroidIntent
        assertEquals("android.nfc.action.TECH_DISCOVERED", intent.action)
        assertEquals("{PROVIDER}", intent.data)
        assertTrue(r.androidIntent!!.grantReadUri)

        val content = resolver.contentPlan(g, r.adapter!!)
        assertEquals(listOf(ContentKind.DLC, ContentKind.UPDATE), content.entries.map { it.kind })
        assertTrue(content.entries.all { it.support == ContentSupport.INSTALL_IN_EMULATOR })
        assertEquals(
            "Eden: install the update from Eden's menu (Install to NAND). Fuse shows where it is.",
            content.entries.first { it.kind == ContentKind.UPDATE }.message,
        )
        assertEquals(2, content.entries.first { it.kind == ContentKind.DLC }.items.size)
    }

    @Test
    fun edenTitleIdLaunchWhenAsked() {
        val g = game("switch", "$ROMS/switch/Zelda.nsp", serial = "0100F2C0115B6000")
        val r = resolver.resolve(g, null, listOf(androidEmu("eden", "dev.eden.eden_emulator")), Host.ANDROID, ScopedLaunchChoice(preferTitleId = true))
        val intent = r.plan as LaunchPlan.AndroidIntent
        assertEquals("dev.eden.eden_emulator.LAUNCH_WITH_CUSTOM_CONFIG", intent.action)
        assertEquals(mapOf("title_id" to "0100F2C0115B6000"), intent.stringExtras)
    }

    @Test
    fun linuxDesktopShortcutFallsBackToTheBuiltInAdapter() {
        val g = game("ps3", "/games/ps3/Demon's Souls.desktop")
        val r = resolver.resolve(g, null, listOf(linuxEmu("linux.rpcs3", "/usr/bin/rpcs3"), linuxEmu("linux.desktop", "/usr/bin/gio")), Host.LINUX)
        assertEquals(EmulatorId("linux.desktop"), r.adapter?.id)
        assertEquals(listOf("/usr/bin/gio", "launch", "/games/ps3/Demon's Souls.desktop"), (r.plan as LaunchPlan.Command).argv)
    }

    @Test
    fun platformChoiceThatCannotOpenTheGameFallsThroughWithANote() {
        val g = game("ps3", "/games/ps3/Demon's Souls.desktop")
        val r = resolver.resolve(g, EmulatorId("linux.rpcs3"), listOf(linuxEmu("linux.rpcs3", "/usr/bin/rpcs3")), Host.LINUX, ScopedLaunchChoice(injectedText = "[Desktop Entry]\nExec=rpcs3 --no-gui \"%RPCS3_GAMEID%:BLES00932\"\n"))
        assertEquals(EmulatorId("linux.desktop"), r.adapter?.id)
        assertEquals(listOf("rpcs3", "--no-gui", "%RPCS3_GAMEID%:BLES00932"), (r.plan as LaunchPlan.Command).argv)
        assertTrue(r.notes.single().startsWith("RPCS3 can't open this game"))
    }
}
