package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.online.Rpcn
import io.github.matiyaaa.fuse.launch.online.TogetherWay
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Play Together end to end: each emulator's way, its own files changed and put back, and how a game starts hosting or joining. */
class PlayTogetherStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var emu: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-together").toFile()
        cache = Files.createTempDirectory("fuse-together-cache").toFile()
        emu = Files.createTempDirectory("fuse-together-emu").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, emu).forEach { it.deleteRecursively() }
    }

    private suspend fun storeWith(installed: InstalledEmulator, system: String, file: String, setUp: (FakeServices) -> Unit): Triple<FuseStore, FakeServices, GameCard> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache, installedEmulators = listOf(installed))
        setUp(services)
        File(root, system).mkdirs()
        File(root, "$system/$file").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.single()
        return Triple(store, services, game)
    }

    @Test
    fun retroArchHostsAndJoinsFromItsCommandLine(): Unit = runBlocking {
        val retroArch = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("snes")), detectedVia = "PATH")
        val (store, services, game) = storeWith(retroArch, "snes", "Kart Racer (USA).sfc") { it.retroArchCores = setOf("snes9x") }
        val view = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals(TogetherWay.NETPLAY, view.way)
        assertTrue(view.canHost)
        assertTrue(view.missing.isEmpty(), "RetroArch needs nothing set")
        assertFalse(view.household, "no Fuse One here")

        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, together = Together.Host))
        val hosting = assertIs<LaunchPlan.Command>(services.launched.last().plan).argv
        assertEquals(listOf("/usr/bin/retroarch", "--host", "-L", "/usr/lib/libretro/snes9x_libretro.so"), hosting.dropLast(1))

        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, together = Together.JoinAddress("192.168.1.31")))
        val joining = assertIs<LaunchPlan.Command>(services.launched.last().plan).argv
        assertEquals(listOf("/usr/bin/retroarch", "--connect=192.168.1.31", "-L"), joining.take(3))
        // An ordinary Play is as it always was.
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id))
        assertEquals("-L", assertIs<LaunchPlan.Command>(services.launched.last().plan).argv[1])
    }

    @Test
    fun ppssppHostsItsOwnAdHocServerAndJoinsTheHostsThenPutsItBack(): Unit = runBlocking {
        val ppsspp = InstalledEmulator(EmulatorId("linux.ppsspp"), "PPSSPP", Host.LINUX, "/usr/bin/PPSSPPSDL", platforms = setOf(PlatformId("psp")), detectedVia = "PATH")
        val memstick = File(emu, "ppsspp").apply { File(this, "PSP/SYSTEM").mkdirs() }
        val ini = File(memstick, "PSP/SYSTEM/ppsspp.ini")
        val before = "[Graphics]\nInternalResolution = 2\n[Network]\nEnableWlan = False\nproAdhocServer = socom.cc\n"
        ini.writeText(before)
        val (store, services, game) = storeWith(ppsspp, "psp", "Monster Quest (USA).iso") {
            it.ppssppHome = io.github.matiyaaa.fuse.launch.patches.PpssppHome(memstick.absolutePath)
            it.homeNetwork = listOf("192.168.1.20")
        }
        val view = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals(TogetherWay.AD_HOC, view.way)
        assertEquals(listOf("Its wireless on"), view.missing)
        assertFalse(view.canPutBack)

        // Hosting: PPSSPP's own server, at this computer's address.
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, together = Together.Host))
        val hosted = ini.readText()
        assertTrue("EnableWlan = True" in hosted && "EnableAdhocServer = True" in hosted && "proAdhocServer = 192.168.1.20" in hosted, hosted)
        assertTrue("[Graphics]\nInternalResolution = 2\n" in hosted, "the rest stays")

        // Joining someone else's: their server, not one here.
        val ana = TogetherHost("deck", "Steam Deck", "Ana", 0, "192.168.1.31", 27312)
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id, together = Together.Join(ana)))
        val joined = ini.readText()
        assertTrue("EnableAdhocServer = False" in joined && "proAdhocServer = 192.168.1.31" in joined, joined)
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).missing.isEmpty())

        // Put Back: exactly what was there, and nothing else of Fuse's touched.
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, ini.readText())
        assertTrue(services.launched.isNotEmpty())
    }

    @Test
    fun ppssppIsGivenANameForGamesOnlineServersOnlyWhenItHasNone(): Unit = runBlocking {
        val ppsspp = InstalledEmulator(EmulatorId("linux.ppsspp"), "PPSSPP", Host.LINUX, "/usr/bin/PPSSPPSDL", platforms = setOf(PlatformId("psp")), detectedVia = "PATH")
        val memstick = File(emu, "ppsspp").apply { File(this, "PSP/SYSTEM").mkdirs() }
        val ini = File(memstick, "PSP/SYSTEM/ppsspp.ini")
        // As PPSSPP saves it when its nickname isn't one a server takes: no name for them.
        ini.writeText("[SystemParam]\nNickName = PPSSPP\n[Network]\nEnableWlan = False\nInfrastructureUsername = \n")
        // Fuse One on this computer alone, as Ana Lee: the name Fuse has to give.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse-one.db").absolutePath))
        data.settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = "PC")) }
        lateinit var services: FakeServices
        services = FakeServices(data, cache, installedEmulators = listOf(ppsspp), sync = { p, s ->
            io.github.matiyaaa.fuse.sync.JvmSyncService(File(cache, "sync"), data.settings, services.secrets, p, "LINUX", "PC", "test", io.github.matiyaaa.fuse.sync.NoHostLifetime("test"), s)
        })
        services.ppssppHome = io.github.matiyaaa.fuse.launch.patches.PpssppHome(memstick.absolutePath)
        File(root, "psp").mkdirs()
        File(root, "psp/Monster Quest (USA).iso").writeBytes(ByteArray(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }.single()
        val sync = kotlin.test.assertNotNull(store.sync.service)
        sync.hostHere("PC", installService = false).getOrThrow()
        sync.switchTo(sync.createProfile("Ana Lee", "cat", null).getOrThrow().id).getOrThrow()

        assertEquals(listOf("Its wireless on", "Your name for games' online servers"), assertIs<TogetherView.Ready>(store.library.together(game.id)).missing)
        assertTrue(store.library.setUpTogether(game.id))
        val set = ini.readText()
        assertTrue("NickName = Ana Lee" in set && "InfrastructureUsername = AnaLee" in set && "EnableWlan = True" in set, set)
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).missing.isEmpty())

        // A name PPSSPP has is the person's own: never replaced.
        ini.writeText("[Network]\nEnableWlan = True\nInfrastructureUsername = ana_psp\n")
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).missing.isEmpty())
        assertTrue(store.library.setUpTogether(game.id))
        assertTrue("InfrastructureUsername = ana_psp" in ini.readText(), ini.readText())
    }

    @Test
    fun rpcs3SignsInToRpcnAsItsOwnDialogDoesAndGoesOnline(): Unit = runBlocking {
        val rpcs3 = InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", version = "0.0.43-17000", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH")
        val config = File(emu, "rpcs3").apply { mkdirs() }
        val yml = File(config, "config.yml").apply { writeText("Net:\n  Internet enabled: Disconnected\n  PSN status: Disconnected\n") }
        val (store, _, game) = storeWith(rpcs3, "ps3", "Racing Day [BLUS30001].iso") { it.rpcs3ConfigDir = config.absolutePath }
        val view = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals(TogetherWay.ONLINE_SERVICE, view.way)
        assertEquals(listOf("Its network on, through RPCN", "An RPCN account"), view.missing)
        assertNull(view.rpcn)

        assertTrue("16" in store.library.signInRpcn(game.id, "Ana Lee", "pw", "TOKEN123").orEmpty(), "a name RPCN wouldn't take")
        assertNull(store.library.signInRpcn(game.id, "Ana_99", "correct horse", "TOKEN123"))
        val rpcn = File(config, Rpcn.FILE).readText()
        assertTrue("Password: E4F60B0FE5A32FF53523ED7C9009869E6624E9C243FDA987DB171B5AA71E94A6" in rpcn, rpcn)
        assertFalse("correct horse" in rpcn)
        assertEquals("Net:\n  Internet enabled: Connected\n  PSN status: RPCN\n", yml.readText())
        val after = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals("Ana_99", after.rpcn)
        assertTrue(after.missing.isEmpty())
        // No copy of the file holding the sign-in is kept anywhere of Fuse's.
        assertTrue(cache.walkTopDown().none { it.isFile && it.name == Rpcn.FILE })

        assertTrue(store.library.signOutRpcn(game.id))
        assertNull(assertIs<TogetherView.Ready>(store.library.together(game.id)).rpcn)
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals("Net:\n  Internet enabled: Disconnected\n  PSN status: Disconnected\n", yml.readText())
    }

    @Test
    fun shadPs4GoesOnlineThroughShadNetWithoutFuseEverWritingItsPassword(): Unit = runBlocking {
        val shadPs4 = InstalledEmulator(EmulatorId("linux.shadps4"), "shadPS4", Host.LINUX, "/opt/shadps4/Shadps4-sdl.AppImage", version = "0.20.0", platforms = setOf(PlatformId("ps4")), detectedVia = "AppImage")
        val user = File(emu, "shadps4").apply { mkdirs() }
        val config = File(user, "config.json")
        // As shadPS4 0.20 saves it: every group, network off.
        val before = "{\n  \"General\": {\n    \"volume_slider\": 100\n  },\n  \"Network\": {\n    \"connected_to_network\": false,\n    \"shad_net_enabled\": false\n  }\n}\n"
        config.writeText(before)
        val (store, _, game) = storeWith(shadPs4, "ps4", "Racing Day [CUSA00001]/eboot.bin") {
            it.shadPs4UserDir = user.absolutePath
            File(root, "ps4/Racing Day [CUSA00001]").mkdirs()
        }
        val view = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals(TogetherWay.ONLINE_SERVICE, view.way)
        assertEquals(listOf("Its network on, through ShadNet"), view.missing, "no RPCN account asked for")
        assertNull(view.rpcn)
        assertTrue(store.library.setUpTogether(game.id))
        val set = config.readText()
        assertTrue("\"connected_to_network\": true" in set && "\"shad_net_enabled\": true" in set, set)
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).missing.isEmpty())
        assertTrue("RPCS3" in store.library.signInRpcn(game.id, "Ana_99", "pw", "TOKEN123").orEmpty(), "RPCN is RPCS3's")
        assertFalse(File(user, "users.json").exists(), "its users, and their passwords, are shadPS4's own")
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, config.readText())
    }

    @Test
    fun pcsx2sNetworkAdapterIsTurnedOnAndLookedAtAgainAfterAnUpdate(): Unit = runBlocking {
        val pcsx2 = InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")
        val home = File(emu, "pcsx2").apply { File(this, "inis").mkdirs(); File(this, "gamesettings").mkdirs() }
        val ini = File(home, "inis/PCSX2.ini").apply { writeText("[EmuCore]\nEnableCheats = false\n") }
        val (store, _, game) = storeWith(pcsx2, "ps2", "Street Racer (USA).iso") {
            it.pcsx2Home = io.github.matiyaaa.fuse.launch.patches.Pcsx2Home(home.absolutePath, "${home.absolutePath}/gamesettings", "${home.absolutePath}/patches", null)
        }
        assertEquals(listOf("Its network adapter on"), assertIs<TogetherView.Ready>(store.library.together(game.id)).missing)
        assertFalse(assertIs<TogetherView.Ready>(store.library.together(game.id)).canHost, "online through the game's own service, not hosted by Fuse")
        assertTrue(store.library.setUpTogether(game.id))
        val set = ini.readText()
        assertTrue("[DEV9/Eth]" in set && "EthEnable = true" in set && "EthApi = Sockets" in set && "EthDevice = Auto" in set, set)
        assertNull(store.library.recheckTogether("pcsx2"), "still there")
        // A portable copy's settings go with the version an update replaced.
        ini.writeText("[EmuCore]\nEnableCheats = false\n")
        assertTrue("PCSX2" in store.library.recheckTogether("pcsx2").orEmpty())
        assertNull(store.library.recheckTogether("ppsspp"), "nothing set up there")
    }

    @Test
    fun azaharsRoomsAreLeftUnlistedAndJoinedByAddressThenPutBack(): Unit = runBlocking {
        val azahar = InstalledEmulator(EmulatorId("linux.azahar"), "Azahar", Host.LINUX, "/usr/bin/azahar", platforms = setOf(PlatformId("3ds")), detectedVia = "PATH")
        val config = File(emu, "azahar").apply { mkdirs() }
        val ini = File(config, "qt-config.ini")
        // As Azahar writes it: each value with whether it is the default beside it.
        val before = "[Multiplayer]\nhost_type\\default=true\nhost_type=0\nip\\default=true\nip=\n[Renderer]\nresolution_factor\\default=false\nresolution_factor=2\n"
        ini.writeText(before)
        val (store, _, game) = storeWith(azahar, "3ds", "Kart Racer 7 [0004000000030800].3ds") { it.azaharConfigDir = config.absolutePath }
        val view = assertIs<TogetherView.Ready>(store.library.together(game.id))
        assertEquals(TogetherWay.ROOMS, view.way)
        assertEquals("azahar", view.family)
        assertEquals(listOf("Its rooms set up for your household"), view.missing)
        assertFalse(view.canHost, "a room is made in Azahar's own window")

        assertTrue(store.library.setUpTogether(game.id))
        val set = ini.readText()
        assertTrue("host_type\\default=false\nhost_type=1\n" in set, set)
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).missing.isEmpty(), "read back as Azahar reads it")

        // Joining: the address waits in Direct Connect to Room.
        assertNull(store.library.readyTogether(game.id, Together.JoinAddress("192.168.1.31")))
        val joined = ini.readText()
        assertTrue("ip\\default=false\nip=192.168.1.31\n" in joined, joined)
        assertTrue("[Renderer]\nresolution_factor\\default=false\nresolution_factor=2\n" in joined, "the rest stays")

        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, ini.readText())
    }

    @Test
    fun edensRoomsAreSetInItsOwnFileAndPutBack(): Unit = runBlocking {
        val eden = InstalledEmulator(EmulatorId("linux.eden"), "Eden", Host.LINUX, "/usr/bin/eden", platforms = setOf(PlatformId("switch")), detectedVia = "PATH")
        val config = File(emu, "eden").apply { mkdirs() }
        val ini = File(config, "qt-config.ini")
        val before = "[Multiplayer]\nhost_type\\default=true\nhost_type=0\n"
        ini.writeText(before)
        val (store, _, game) = storeWith(eden, "switch", "Party Game [0100F2C0115B6000].nsp") { it.edenConfigDir = config.absolutePath }
        assertEquals(TogetherWay.ROOMS, assertIs<TogetherView.Ready>(store.library.together(game.id)).way)
        assertTrue(store.library.setUpTogether(game.id))
        assertTrue("host_type\\default=false\nhost_type=1\n" in ini.readText(), ini.readText())
        assertTrue(assertIs<TogetherView.Ready>(store.library.together(game.id)).canPutBack)
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, ini.readText())
    }

    @Test
    fun dolphinJoinsByAddressOrByHostCodeOnItsConnectTab(): Unit = runBlocking {
        val dolphin = InstalledEmulator(EmulatorId("linux.dolphin"), "Dolphin", Host.LINUX, "/usr/bin/dolphin-emu", platforms = setOf(PlatformId("ngc")), detectedVia = "PATH")
        val config = File(emu, "dolphin").apply { mkdirs() }
        val ini = File(config, "Dolphin.ini")
        val before = "[NetPlay]\nTraversalChoice = direct\nAddress = 127.0.0.1\n"
        ini.writeText(before)
        val (store, services, game) = storeWith(dolphin, "ngc", "Party Game [GALE01].iso") {
            it.dolphinHome = io.github.matiyaaa.fuse.launch.settings.DolphinHome(config.absolutePath, "${config.absolutePath}/GameSettings")
        }
        assertEquals(TogetherWay.NETPLAY_WINDOW, assertIs<TogetherView.Ready>(store.library.together(game.id)).way)
        assertNull(store.library.readyTogether(game.id, Together.JoinAddress("192.168.1.31")))
        assertTrue("TraversalChoice = direct" in ini.readText() && "Address = 192.168.1.31" in ini.readText(), ini.readText())
        // Hosted over the internet: Dolphin reaches it through its traversal server, by the code.
        assertNull(store.library.readyTogether(game.id, Together.JoinCode("1a2b3c4d")))
        val coded = ini.readText()
        assertTrue("TraversalChoice = traversal" in coded && "HostCode = 1a2b3c4d" in coded, coded)
        assertTrue(services.launched.isEmpty(), "Dolphin's own window joins it")
        assertNull(assertIs<TogetherView.Ready>(store.library.together(game.id)).wiiLink, "a GameCube game has no WiiConnect24")
        assertFalse(store.library.setWiiLink(game.id, true))
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, ini.readText())
    }

    @Test
    fun aWiiGamesWiiConnect24GoesThroughWiiLinkOnlyWhenTurnedOn(): Unit = runBlocking {
        val dolphin = InstalledEmulator(EmulatorId("linux.dolphin"), "Dolphin", Host.LINUX, "/usr/bin/dolphin-emu", platforms = setOf(PlatformId("wii")), detectedVia = "PATH")
        val config = File(emu, "dolphin").apply { mkdirs() }
        val ini = File(config, "Dolphin.ini")
        val before = "[Core]\nCPUThread = True\n"
        ini.writeText(before)
        val (store, _, game) = storeWith(dolphin, "wii", "Forecast Fun [RFNE01].iso") {
            it.dolphinHome = io.github.matiyaaa.fuse.launch.settings.DolphinHome(config.absolutePath, "${config.absolutePath}/GameSettings")
        }
        assertEquals(false, assertIs<TogetherView.Ready>(store.library.together(game.id)).wiiLink, "off until the person turns it on")
        assertTrue(store.library.setWiiLink(game.id, true))
        assertEquals("[Core]\nCPUThread = True\nEnableWiiLink = True\n", ini.readText())
        assertEquals(true, assertIs<TogetherView.Ready>(store.library.together(game.id)).wiiLink)
        assertTrue(store.library.setWiiLink(game.id, false))
        assertEquals(false, assertIs<TogetherView.Ready>(store.library.together(game.id)).wiiLink)
        assertTrue(store.library.putBackTogether(game.id))
        assertEquals(before, ini.readText())
    }

    @Test
    fun onlyDolphinJoinsByHostCode(): Unit = runBlocking {
        val retroArch = InstalledEmulator(EmulatorId("linux.retroarch"), "RetroArch", Host.LINUX, "/usr/bin/retroarch", platforms = setOf(PlatformId("snes")), detectedVia = "PATH")
        val (store, services, game) = storeWith(retroArch, "snes", "Kart Racer (USA).sfc") { it.retroArchCores = setOf("snes9x") }
        assertTrue("host code" in store.library.readyTogether(game.id, Together.JoinCode("1a2b3c4d")).orEmpty())
        assertIs<LaunchOutcome.Problem>(store.library.launch(game.id, together = Together.JoinCode("1a2b3c4d")))
        assertTrue(services.launched.isEmpty(), "never started as if hosting")
    }

    @Test
    fun anEmulatorFuseDoesntKnowAWayInSaysSo(): Unit = runBlocking {
        val xemu = InstalledEmulator(EmulatorId("linux.xemu"), "xemu", Host.LINUX, "/usr/bin/xemu", platforms = setOf(PlatformId("xbox")), detectedVia = "PATH")
        val (store, services, game) = storeWith(xemu, "xbox", "Halo.iso") {}
        val no = assertIs<TogetherView.Unavailable>(store.library.together(game.id))
        assertEquals("Not with xemu", no.title)
        assertIs<LaunchOutcome.Problem>(store.library.launch(game.id, together = Together.Host))
        assertTrue(services.launched.isEmpty(), "nothing started")
    }
}
