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
    fun anEmulatorFuseDoesntKnowAWayInSaysSo(): Unit = runBlocking {
        val xemu = InstalledEmulator(EmulatorId("linux.xemu"), "xemu", Host.LINUX, "/usr/bin/xemu", platforms = setOf(PlatformId("xbox")), detectedVia = "PATH")
        val (store, services, game) = storeWith(xemu, "xbox", "Halo.iso") {}
        val no = assertIs<TogetherView.Unavailable>(store.library.together(game.id))
        assertEquals("Not with xemu", no.title)
        assertIs<LaunchOutcome.Problem>(store.library.launch(game.id, together = Together.Host))
        assertTrue(services.launched.isEmpty(), "nothing started")
    }
}
