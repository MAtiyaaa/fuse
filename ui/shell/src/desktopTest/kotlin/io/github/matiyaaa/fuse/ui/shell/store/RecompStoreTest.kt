package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.ports.PortLauncher
import io.github.matiyaaa.fuse.launch.ports.Recomps
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PortFileKind
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A recomp the person installed themselves, end to end: offered for the game it plays beside its
 * emulator, chosen, played as one entry exactly as they installed it, its saves named for Fuse Sync
 * as every copy of that recomp's, and its mods, graphics, controls profiles and updates in its own
 * folder.
 */
class RecompStoreTest {
    private lateinit var cache: File
    private lateinit var data: File
    private lateinit var roms: File
    private lateinit var home: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-recomp-cache").toFile()
        data = Files.createTempDirectory("fuse-recomp-data").toFile()
        roms = Files.createTempDirectory("fuse-recomp-roms").toFile()
        home = Files.createTempDirectory("fuse-recomp-home").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(cache, data, roms, home).forEach { it.deleteRecursively() }
    }

    /** Fuse's folder of ports: only what writing a launcher needs. */
    private class Ports(root: File) : PortFiles {
        override val folder: String = File(root, "ports").absolutePath
        override val os = "linux"
        override val arch = "x86_64"
        override fun bundledIndex(): String? = null
        override fun umuRun(): String? = null
        override suspend fun newDownload(name: String): DownloadSink? = null
        override fun freeBytes(): Long? = null
        override suspend fun unpack(archive: String, kind: PortFileKind, name: String, into: String) = error("not used")
        override suspend fun link(target: String, at: String): String? = "not used"
        override suspend fun makeFolder(path: String) = File(path).let { it.isDirectory || it.mkdirs() }
        override suspend fun writeText(path: String, text: String) = runCatching { File(path).apply { parentFile.mkdirs() }.writeText(text) }.isSuccess
        override suspend fun isLink(path: String) = false
        override suspend fun remove(path: String) = File(path).deleteRecursively()
        override suspend fun move(from: String, to: String) = File(from).renameTo(File(to))
        override suspend fun exists(path: String) = File(path).exists()
    }

    /** The person's Banjo: a Flatpak, keeping its files in ~/.config, as it does. */
    private inner class Yours : RecompFiles {
        val folder = "${home.absolutePath}/.config/BanjoRecompiled"
        override val profiles = File(data, "recomp-profiles").absolutePath
        override suspend fun find() = listOf(
            FoundRecomp("banjorecomp", "Flatpak", "io.github.banjorecomp.banjorecomp", listOf("flatpak", "run", "io.github.banjorecomp.banjorecomp"), home.absolutePath, folder, "1.0.1"),
        )
        override suspend fun readText(path: String) = File(path).takeIf { it.isFile }?.readText()
        override suspend fun writeText(path: String, text: String) = runCatching { File(path).apply { parentFile.mkdirs() }.writeText(text) }.isSuccess
        override suspend fun list(folder: String) = File(folder).listFiles()?.filter { it.isFile }?.map { it.name to it.length() } ?: emptyList()
        override suspend fun addMods(file: String, mods: String) = runCatching {
            File(mods).mkdirs()
            Files.copy(File(file).toPath(), File(mods, File(file).name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            listOf(File(file).name)
        }
        override suspend fun remove(path: String) = File(path).delete()
        override suspend fun copy(from: String, to: String) = runCatching { File(from).copyTo(File(to), overwrite = true) }.isSuccess
    }

    private fun services(): Pair<FakeServices, Yours> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache)
        services.ports = Ports(data)
        val yours = Yours()
        services.recomps = yours
        services.web = { request ->
            if (request.url.host == "api.github.com" && request.url.encodedPath == "/repos/BanjoRecomp/BanjoRecomp/releases/latest") {
                respond("""{"tag_name":"v1.0.2","html_url":"https://github.com/BanjoRecomp/BanjoRecomp/releases/tag/v1.0.2"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                null
            }
        }
        return services to yours
    }

    private suspend fun FuseStore.banjo(): GameCard {
        // An N64 ROM by its first bytes, named as the game: not the very ROM, so it is known by its name.
        File(roms, "n64").mkdirs()
        File(roms, "n64/Banjo-Kazooie (USA).z64").writeBytes(ByteArray(4096).also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 })
        sources.add(roms.absolutePath, LibrarySourceKind.ROMS_ROOT)
        return withTimeout(20_000) { library.games(GameQuery()).first { l -> l.any { it.platformId == PlatformId("n64") } } }.single { it.platformId == PlatformId("n64") }
    }

    @Test
    fun yourOwnRecompPlaysTheGameAsOneEntryWithItsSavesModsAndSettingsInItsOwnFolder(): Unit = runBlocking {
        val (services, yours) = services()
        val store = createFuseStore(services, scope)
        val card = store.banjo()

        val offered = store.ports.yourRecompsFor(card.id)
        assertEquals(listOf("banjorecomp"), offered.map { it.id })
        assertNull(store.ports.playWithYours(card.id, offered.single()))
        assertEquals("yours:banjorecomp", store.ports.playWith(card.id))

        // One entry: the launcher's own is hidden, and Play starts the Flatpak as it was installed.
        val shown = store.library.games(GameQuery()).first()
        assertEquals(listOf(card.id), shown.map { it.id }, "only the game itself shows")
        assertIs<LaunchOutcome.Started>(store.library.launch(card.id))
        val plan = services.launched.last().plan as LaunchPlan.Command
        assertEquals(listOf("flatpak", "run", "io.github.banjorecomp.banjorecomp"), plan.argv)

        // Its saves are the recomp's own, named as every copy of Banjo: Recompiled's (port.banjorecomp).
        val launcher = PortLauncher.decode(File(data, "ports/yours-banjorecomp/yours-banjorecomp.fuseport").readText())!!
        assertEquals("banjorecomp", launcher.port)
        assertEquals("${yours.folder}/saves", launcher.saves)

        val place = assertNotNull(store.ports.recompFor(card.id))
        assertEquals(yours.folder, place.folder)
        assertEquals("1.0.1", place.version)

        // Graphics: one setting changed in its own file, as its newer settings menu writes it.
        File(yours.folder).mkdirs()
        File(yours.folder, Recomps.GRAPHICS).writeText("""{"res_option":"Auto","developer_mode":false}""")
        val ds = store.ports.recompGraphics(place).single { it.first.key == "ds_option" }
        assertNull(ds.second, "its default, when its file doesn't say")
        assertTrue(store.ports.setRecompGraphic(place, "ds_option", ds.first.choices.single { it.label == "2x" }))
        val graphics = File(yours.folder, Recomps.GRAPHICS).readText()
        assertTrue("\"ds_option\": 2" in graphics && "\"res_option\": \"Auto\"" in graphics && "\"developer_mode\": false" in graphics, graphics)

        // Controls: kept as a profile, changed, and put back.
        File(yours.folder, Recomps.CONTROLS).writeText("""{"profile":"deck"}""")
        assertNull(store.ports.saveRecompProfile(place, "Steam Deck"))
        File(yours.folder, Recomps.CONTROLS).writeText("""{"profile":"pad"}""")
        assertEquals(listOf("Steam Deck"), store.ports.recompProfiles(place))
        assertNull(store.ports.useRecompProfile(place, "Steam Deck"))
        assertEquals("""{"profile":"deck"}""", File(yours.folder, Recomps.CONTROLS).readText())
        assertEquals("Give it a name of letters, numbers and spaces.", store.ports.saveRecompProfile(place, "../escape"))

        // Mods: a mod in, listed, and out; anything else is refused by name.
        val mod = File(home, "Banjo Widescreen.nrm").apply { writeText("nrm") }
        assertNull(store.ports.addRecompMod(place, mod.absolutePath))
        assertEquals(listOf("Banjo Widescreen.nrm"), store.ports.recompMods(place).map { it.name })
        assertTrue(store.ports.addRecompMod(place, File(home, "notes.txt").absolutePath)!!.startsWith("That isn't a mod"))
        assertTrue(store.ports.removeRecompMod(place, "Banjo Widescreen.nrm"))
        assertEquals(emptyList(), store.ports.recompMods(place))

        // Updates: its project's newest release, newer than the one here.
        val release = assertNotNull(store.ports.recompRelease(place))
        assertEquals("v1.0.2", release.tag)
        assertEquals(true, release.newer)

        // Back to the emulator: Play With forgets it.
        store.ports.setPlayWith(card.id, null)
        assertNull(store.ports.recompFor(card.id))
    }
}
