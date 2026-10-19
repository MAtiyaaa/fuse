package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.AppSettings
import io.github.matiyaaa.fuse.launch.ports.PortLauncher
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PortFileKind
import io.github.matiyaaa.fuse.ui.shell.store.impl.PortsStore
import io.github.matiyaaa.fuse.ui.shell.store.impl.StoreContext
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Fuse Ports end to end, with made-up ports and a made-up game: the download checked against the
 * list, the person's folder checked for what the port needs and linked in (never copied or
 * changed), the port's own folders kept in its home, the game added to the library and started as
 * its launcher says, and removing it leaving the person's files and the saves where they are.
 */
class PortsStoreTest {
    private lateinit var cache: File
    private lateinit var data: File
    private lateinit var game: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-ports-cache").toFile()
        data = Files.createTempDirectory("fuse-ports-data").toFile()
        game = Files.createTempDirectory("fuse-ports-game").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(cache, data, game).forEach { it.deleteRecursively() }
    }

    /** The build: a zip with the port's program and a file of its own. */
    private val build: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("skyport"))
            z.write("#!/bin/sh\necho skyport\n".toByteArray())
            z.putNextEntry(ZipEntry("engine.dat"))
            z.write(ByteArray(32) { 1 })
        }
    }.toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Two made-up ports: one handed its folder by path, one played from an overlay of the game's folder. */
    private fun index(digest: String = sha(build)) = """
        {"format":1,"revision":2,"ports":[
          {"id":"skyport","name":"SkyPort","game":"Sky Racer","about":"Made up.","homepage":"https://example.org/skyport","version":"2.1",
           "builds":[{"os":"linux","url":"https://example.org/skyport-2.1.zip","size":${build.size},"sha256":"$digest","kind":"ZIP","run":"skyport","verified":"2026-10-17"}],
           "data":[{"id":"game","name":"Sky Racer's files","about":"From the CD.","needs":[["SKY.DAT"]]}],
           "args":["--data","{data:game}","--home","{home}/saves"],"saves":"saves","source":"made up"},
          {"id":"wasteport","name":"WastePort","game":"Waste Land","about":"Made up.","homepage":"https://example.org/wasteport","version":"1.0",
           "builds":[{"os":"linux","url":"https://example.org/wasteport-1.0.zip","size":${build.size},"sha256":"$digest","kind":"ZIP","run":"skyport"}],
           "data":[{"id":"game","name":"Waste Land's files","about":"From the CD.","needs":[["master.dat"],["critter.dat"]],
                    "overlay":{"into":"","own":["game.cfg","data/SAVEGAME/","data/MAPS/"]}}],
           "saves":"data/SAVEGAME","source":"made up"}
        ]}
    """.trimIndent()

    private class TestPortFiles(root: File, private val downloads: File, var list: String) : PortFiles {
        override val folder: String = File(root, "ports").absolutePath
        override val os = "linux"
        override val arch = "x86_64"
        override fun bundledIndex() = list
        override fun umuRun(): String? = null
        override suspend fun newDownload(name: String): DownloadSink {
            downloads.mkdirs()
            val f = File(downloads, "${System.nanoTime()}-$name")
            return object : DownloadSink {
                val out = f.outputStream()
                val md = MessageDigest.getInstance("SHA-256")
                override val path = f.absolutePath
                override suspend fun write(bytes: ByteArray, count: Int) { out.write(bytes, 0, count); md.update(bytes, 0, count) }
                override suspend fun finish(): String { out.close(); return md.digest().joinToString("") { "%02x".format(it) } }
                override suspend fun discard() { runCatching { out.close() }; f.delete() }
            }
        }
        override fun freeBytes(): Long? = null
        override suspend fun unpack(archive: String, kind: PortFileKind, name: String, into: String) {
            check(kind == PortFileKind.ZIP)
            val to = File(into).apply { mkdirs() }
            ZipInputStream(File(archive).inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    File(to, e.name).apply { parentFile.mkdirs(); outputStream().use { z.copyTo(it) } }
                }
            }
        }
        override suspend fun link(target: String, at: String): String? {
            Files.createSymbolicLink(File(at).toPath(), File(target).toPath())
            return null
        }
        override suspend fun makeFolder(path: String) = File(path).let { it.isDirectory || it.mkdirs() }
        override suspend fun writeText(path: String, text: String) = runCatching { File(path).apply { parentFile.mkdirs() }.writeText(text) }.isSuccess
        override suspend fun isLink(path: String) = Files.isSymbolicLink(File(path).toPath())
        override suspend fun remove(path: String): Boolean {
            val p = File(path).toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) { Files.delete(p); return true }
            // Never into a link: links are removed themselves.
            Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            return true
        }
        override suspend fun move(from: String, to: String) = runCatching { Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
    }

    private fun services(list: String = index(), body: ByteArray = build): Pair<FakeServices, TestPortFiles> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache)
        val files = TestPortFiles(data, File(cache, "dl"), list)
        services.ports = files
        services.web = { request -> if (request.url.host == "example.org") respond(body, HttpStatusCode.OK) else null }
        return services to files
    }

    private suspend fun FuseStore.until(what: String, ms: Long = 20_000, check: (PortsState) -> Boolean): PortsState =
        withTimeout(ms) { while (!check(ports.state.value)) delay(25); ports.state.value }.also { assertTrue(check(it), what) }

    @Test
    fun aPortIsInstalledCheckedSetUpWithThePersonsFolderAndPlayedAsItsLauncherSays(): Unit = runBlocking {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        val (services, files) = services()
        val store = createFuseStore(services, scope)
        assertTrue(store.ports.supported)
        store.ports.open()
        val listed = store.until("the list inside Fuse is read") { it.loaded && it.ports.size == 2 }
        assertFalse(listed.signed)
        assertNull(listed.port("skyport")!!.verified, "nothing is verified on the list inside Fuse's word alone")
        assertEquals("Not chosen", listed.port("skyport")!!.missing["game"])

        // A folder without the game's file is said, by name, and not kept.
        val empty = Files.createTempDirectory("fuse-ports-empty").toFile()
        assertEquals("That folder has no SKY.DAT.", store.ports.chooseFolder("skyport", "game", empty.absolutePath))
        empty.deleteRecursively()
        assertNull(store.ports.chooseFolder("skyport", "game", game.absolutePath))

        store.ports.install("skyport")
        val ready = store.until("installed and set up") { it.port("skyport")?.ready == true }.port("skyport")!!
        assertEquals("2.1", ready.installedVersion)
        val root = File(files.folder, "skyport")
        assertTrue(File(root, "build/skyport").isFile && File(root, "build/engine.dat").isFile)
        assertTrue(File(root, "home/saves").isDirectory, "its saves' folder is made in its home")
        val launcher = PortLauncher.decode(File(root, "skyport.fuseport").readText())!!
        assertEquals(listOf("${root.path}/build/skyport", "--data", game.absolutePath.replace('\\', '/'), "--home", "${root.path}/home/saves"), launcher.argv)

        // The game is in the library under Ports, named for the game, and Play starts the launcher's command.
        val card = withTimeout(20_000) { store.library.games(GameQuery()).first { l -> l.any { it.platformId == PlatformId("ports") } } }.single { it.platformId == PlatformId("ports") }
        assertEquals("Sky Racer", card.title)
        assertIs<LaunchOutcome.Started>(store.library.launch(card.id))
        val plan = services.launched.last().plan as LaunchPlan.Command
        assertEquals(launcher.argv, plan.argv)
        assertEquals("${root.path}/build", plan.workingDir)

        // Removed: Fuse's copy and the game go; the person's file and the home stay.
        assertTrue(store.ports.remove("skyport"))
        assertFalse(File(root, "build").exists() || File(root, "skyport.fuseport").exists())
        assertTrue(File(root, "home/saves").isDirectory)
        assertTrue(File(game, "SKY.DAT").readBytes().all { it == 7.toByte() }, "the person's file is untouched")
        assertFalse(store.until("not installed") { it.port("skyport")?.installed == false }.port("skyport")!!.ready)
    }

    @Test
    fun anOverlayLinksTheGameInAndKeepsWhatThePortWritesInItsHome(): Unit = runBlocking {
        File(game, "MASTER.DAT").writeText("master")
        File(game, "critter.dat").writeText("critter")
        File(game, "game.cfg").writeText("the person's own settings")
        File(game, "DATA/SOUND").mkdirs()
        File(game, "DATA/SOUND/theme.acm").writeText("music")
        File(game, "DATA/SAVEGAME/SLOT01").mkdirs()
        File(game, "DATA/SAVEGAME/SLOT01/SAVE.DAT").writeText("an old save")
        val before = game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path to it.readText() }
        val (services, files) = services()
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        assertEquals("That folder has no master.dat, critter.dat.", store.ports.chooseFolder("wasteport", "game", File(game, "DATA").absolutePath))
        assertNull(store.ports.chooseFolder("wasteport", "game", game.absolutePath), "case is ignored where the port ignores it")
        store.ports.install("wasteport")
        store.until("set up") { it.port("wasteport")?.ready == true }

        val copy = File(files.folder, "wasteport/build").toPath()
        fun link(p: String) = Files.isSymbolicLink(copy.resolve(p))
        assertTrue(link("MASTER.DAT") && link("critter.dat"), "the game's files are links, not copies")
        assertFalse(Files.exists(copy.resolve("game.cfg"), LinkOption.NOFOLLOW_LINKS), "the person's own settings are never linked")
        assertTrue(Files.isDirectory(copy.resolve("DATA")) && !link("DATA"), "the folder holding the port's own is Fuse's")
        assertTrue(link("DATA/SOUND"))
        val names = Files.list(copy).use { l -> l.map { it.fileName.toString() }.toList() }
        assertEquals(1, names.count { it.equals("data", ignoreCase = true) }, "the own folders went into the folder already there, whatever its case")
        assertTrue(link("DATA/SAVEGAME") && link("DATA/MAPS"))
        assertEquals(File(files.folder, "wasteport/home/data/SAVEGAME").toPath(), Files.readSymbolicLink(copy.resolve("DATA/SAVEGAME")))

        // What the port writes lands in its home, never in the person's folder.
        Files.writeString(copy.resolve("DATA/SAVEGAME/SLOT02.SAV"), "a new save")
        Files.writeString(copy.resolve("game.cfg"), "the port's settings")
        assertTrue(File(files.folder, "wasteport/home/data/SAVEGAME/SLOT02.SAV").isFile)
        assertEquals(before, game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path to it.readText() }, "nothing in the person's folder changed")

        // Choosing again replaces exactly the links made; removing never follows one out.
        assertNull(store.ports.chooseFolder("wasteport", "game", game.absolutePath))
        assertTrue(store.ports.remove("wasteport"))
        assertEquals(before, game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path to it.readText() })
        assertTrue(File(files.folder, "wasteport/home/data/SAVEGAME/SLOT02.SAV").isFile, "the saves stay in its home")
    }

    @Test
    fun aDownloadThatIsntTheListsFileIsNeverInstalled(): Unit = runBlocking {
        val (services, files) = services(body = build.copyOf().also { it[it.size - 1] = 9 })
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        store.ports.install("skyport")
        val failed = store.until("failed") { it.port("skyport")?.job is PortJob.Failed }.port("skyport")!!
        assertTrue("checksum" in (failed.job as PortJob.Failed).message, (failed.job as PortJob.Failed).message)
        assertFalse(failed.installed)
        assertFalse(File(files.folder, "skyport/build").exists())
        assertTrue(File(cache, "dl").listFiles().orEmpty().isEmpty(), "the download is deleted")
    }

    @Test
    fun onlyASignedListVerifiesAndOnlyForThisComputersBuild(): Unit = runBlocking {
        val (services, files) = services(list = index())
        // A signed list kept from before (revision 5, newer than the one inside Fuse), signed with
        // the throwaway test key of core:integrations' PortsIndexTest.
        File(files.folder).mkdirs()
        File(files.folder, "index.json").writeText(SIGNED)
        File(files.folder, "index.json.sig").writeText(SIGNATURE)
        val ctx = StoreContext(services, scope, AppSettings())
        val signed = PortsStore(ctx, files, library = { error("not used") }, write = {}, publicKey = { TEST_KEY })
        signed.open()
        val state = withTimeout(20_000) { signed.state.first { it.loaded } }
        assertTrue(state.signed)
        assertEquals(5, state.revision)
        assertEquals("2026-10-17", state.port("skyport")!!.verified)
        assertEquals("2.2", state.port("skyport")!!.port.version)

        // The same files changed after signing: the list inside Fuse again, nothing verified.
        File(files.folder, "index.json").writeText(SIGNED.replace("2.2", "2.3"))
        val tampered = PortsStore(StoreContext(services, scope, AppSettings()), files, library = { error("not used") }, write = {}, publicKey = { TEST_KEY })
        tampered.open()
        val back = withTimeout(20_000) { tampered.state.first { it.loaded } }
        assertFalse(back.signed)
        assertEquals(2, back.revision)
        assertNotNull(back.port("skyport"))
        assertNull(back.port("skyport")!!.verified)
    }

    private companion object {
        const val TEST_KEY = "vGVgmIo2NSqKDoF0kzT5CgCsW/FbOuaBdXBG+AvCuPo="
        const val SIGNED = """{"format":1,"revision":5,"ports":[{"id":"skyport","name":"SkyPort","game":"Sky Racer","about":"Made up.","homepage":"https://example.org/skyport","version":"2.2","builds":[{"os":"linux","url":"https://example.org/skyport-2.2.zip","size":100,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","kind":"ZIP","run":"skyport","verified":"2026-10-17"},{"os":"windows","url":"https://example.org/skyport-2.2-win.zip","size":100,"sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","kind":"ZIP","run":"skyport.exe","verified":"2026-10-17"}],"source":"made up"}]}"""
        const val SIGNATURE = "kGlchxzunvAQw3x8OAECDeQo2P6MEwnD5xLhr/zqX9Jl5knUrYIrFwULhlsijzbEEHLkYjwXRlo9Lh/IkO7iBw=="
    }
}
