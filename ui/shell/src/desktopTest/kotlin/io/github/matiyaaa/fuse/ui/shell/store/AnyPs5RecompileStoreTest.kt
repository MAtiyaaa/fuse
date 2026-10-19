package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.ports.PortLauncher
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PortFileKind
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A PlayStation 5 dump recompiled with AnyPS5 end to end, with a made-up release and a stand-in
 * for its relinker: AnyPS5 fetched and checked against GitHub's SHA-256, the build made in Fuse's
 * folder beside the dump (linked, never changed), Play starting it natively, and taking it out
 * leaving the saves.
 */
class AnyPs5RecompileStoreTest {
    private lateinit var cache: File
    private lateinit var data: File
    private lateinit var roms: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-anyps5-cache").toFile()
        data = Files.createTempDirectory("fuse-anyps5-data").toFile()
        roms = Files.createTempDirectory("fuse-anyps5-roms").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(cache, data, roms).forEach { it.deleteRecursively() }
    }

    private val relinker = "#!/bin/sh\necho relinker\n".toByteArray()

    /** AnyPS5's system libraries, every file under libs/ as its release packs them. */
    private val libraries: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for (name in listOf("libs/libkernel.prx", "libs/libc.prx")) {
                z.putNextEntry(ZipEntry(name))
                z.write(name.toByteArray())
            }
        }
    }.toByteArray()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun release(digests: Boolean = true) = """
        {"tag_name":"v0.1.3","name":"v0.1.3","html_url":"https://github.com/boykopovar/AnyPS5/releases/tag/v0.1.3","assets":[
          {"name":"relinker-v0.1.3","browser_download_url":"https://github.com/boykopovar/AnyPS5/releases/download/v0.1.3/relinker-v0.1.3","size":${relinker.size}${if (digests) ",\"digest\":\"sha256:${sha(relinker)}\"" else ""}},
          {"name":"relinker-v0.1.3.exe","browser_download_url":"https://github.com/boykopovar/AnyPS5/releases/download/v0.1.3/relinker-v0.1.3.exe","size":3},
          {"name":"prx-linux-v0.1.3.zip","browser_download_url":"https://github.com/boykopovar/AnyPS5/releases/download/v0.1.3/prx-linux-v0.1.3.zip","size":${libraries.size}${if (digests) ",\"digest\":\"sha256:${sha(libraries)}\"" else ""}}
        ]}
    """.trimIndent()

    /** Fuse's folder of ports, as the computer's own does it: links made, nothing removed through one. */
    private class Files_(root: File, private val downloads: File, override val os: String = "linux") : PortFiles {
        override val folder: String = File(root, "ports").absolutePath
        override val arch = "x86_64"
        override fun bundledIndex(): String? = null
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
            val to = File(into)
            check(!to.exists()) { "not a new folder" }
            to.mkdirs()
            when (kind) {
                PortFileKind.FILE -> File(to, name).also { File(archive).copyTo(it) }.setExecutable(true)
                PortFileKind.ZIP -> ZipInputStream(File(archive).inputStream()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        File(to, e.name).apply { parentFile.mkdirs(); outputStream().use { z.copyTo(it) } }
                    }
                }
                else -> error("not used")
            }
        }
        override suspend fun link(target: String, at: String): String? {
            File(at).parentFile.mkdirs()
            Files.createSymbolicLink(File(at).toPath(), File(target).toPath())
            return null
        }
        override suspend fun makeFolder(path: String) = File(path).let { it.isDirectory || it.mkdirs() }
        override suspend fun writeText(path: String, text: String) = runCatching { File(path).apply { parentFile.mkdirs() }.writeText(text) }.isSuccess
        override suspend fun writeBytes(path: String, bytes: ByteArray) = runCatching { File(path).apply { parentFile.mkdirs() }.writeBytes(bytes) }.isSuccess
        override suspend fun isLink(path: String) = Files.isSymbolicLink(File(path).toPath())
        override suspend fun remove(path: String): Boolean {
            val p = File(path).toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) { Files.delete(p); return true }
            Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            return true
        }
        override suspend fun move(from: String, to: String) = runCatching { Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess
    }

    /** The person's dump: a plain ELF eboot.bin, its params, a module and a file of its own. */
    private fun dump(eboot: ByteArray = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1)): File {
        val d = File(roms, "ps5/Astro Bot [PPSA01234]").apply { mkdirs() }
        File(d, "eboot.bin").writeBytes(eboot)
        File(d, "sce_sys").mkdirs()
        File(d, "sce_sys/param.json").writeText("""{"titleId":"PPSA01234","localizedParameters":{"defaultLanguage":"en-US","en-US":{"titleName":"Astro Bot"}}}""")
        File(d, "sce_module").mkdirs()
        File(d, "sce_module/libSceFios2.prx").writeText("module")
        File(d, "assets.pak").writeBytes(ByteArray(32) { 3 })
        return d
    }

    private fun services(release: String = release(), os: String = "linux"): Pair<FakeServices, Files_> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse-${System.nanoTime()}.db").absolutePath)), cache)
        val files = Files_(data, File(cache, "dl"), os)
        services.ports = files
        services.web = { request ->
            when {
                request.url.host == "api.github.com" && request.url.encodedPath == "/repos/boykopovar/AnyPS5/releases/latest" ->
                    respond(release, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.host == "github.com" && request.url.encodedPath.endsWith("/relinker-v0.1.3") -> respond(relinker, HttpStatusCode.OK)
                request.url.host == "github.com" && request.url.encodedPath.endsWith("/prx-linux-v0.1.3.zip") -> respond(libraries, HttpStatusCode.OK)
                else -> null
            }
        }
        // AnyPS5's relinker, standing in: the program written where it was asked, and the game's module converted into app0.
        services.contentInstaller = { run ->
            val out = File(run.argv.last())
            out.writeText("#!/bin/sh\necho native\n")
            File(out.parentFile, "app0/sce_module").mkdirs()
            File(out.parentFile, "app0/sce_module/libSceFios2.prx").writeText("converted")
            InstallerResult(0, "Guest module: app0/sce_module/libSceFios2.prx")
        }
        return services to files
    }

    private suspend fun FuseStore.ps5Game(): GameCard {
        sources.add(roms.absolutePath, LibrarySourceKind.ROMS_ROOT)
        return withTimeout(20_000) { library.games(GameQuery()).first { l -> l.any { it.platformId == PlatformId("ps5") } } }.single { it.platformId == PlatformId("ps5") }
    }

    @Test
    fun aDumpIsRecompiledBesideItselfAndPlayedNatively(): Unit = runBlocking {
        val d = dump()
        val before = d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).path to it.readBytes().toList() }
        val (services, files) = services()
        val store = createFuseStore(services, scope)
        val game = store.ps5Game()
        val offer = assertNotNull(store.ports.recompileOffer(game.id))
        assertNull(offer.whyNot, offer.whyNot)
        assertNull(offer.builtWith)

        val steps = mutableListOf<String>()
        assertNull(store.ports.recompile(game.id) { steps += it })
        assertTrue(steps.any { it.startsWith("Downloading AnyPS5 v0.1.3") }, "$steps")
        // The relinker was given the dump's eboot.bin and the build's program, Linux's own.
        val root = File(files.folder, "anyps5-ppsa01234")
        val run = services.installerRuns.single()
        assertEquals(listOf("${files.folder}/anyps5/v0.1.3/bin/relinker", "${d.absolutePath}/eboot.bin", "${root.path}/build.new/app.elf"), run.argv)
        val build = File(root, "build")
        assertTrue(File(build, "app.elf").isFile)
        // AnyPS5's libraries linked in, the dump's entries linked into app0 (but its modules, which are converted ones), the saves in the home.
        assertEquals(File(files.folder, "anyps5/v0.1.3/prx/libs").toPath(), Files.readSymbolicLink(File(build, "libs").toPath()))
        for (name in listOf("eboot.bin", "sce_sys", "assets.pak")) assertEquals(File(d, name).toPath(), Files.readSymbolicLink(File(build, "app0/$name").toPath()), name)
        assertFalse(Files.isSymbolicLink(File(build, "app0/sce_module").toPath()))
        assertEquals("converted", File(build, "app0/sce_module/libSceFios2.prx").readText())
        assertEquals(File(root, "home/_sd").toPath(), Files.readSymbolicLink(File(build, "_sd").toPath()))
        // The dump is just as it was.
        assertEquals(before, d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).path to it.readBytes().toList() })

        // Play With has it, and Play starts the build from its folder.
        assertEquals("v0.1.3", store.ports.recompileOffer(game.id)!!.builtWith)
        assertEquals(listOf("AnyPS5"), store.ports.portsFor(game.id).map { it.port.name })
        assertEquals("anyps5-ppsa01234", store.ports.playWith(game.id))
        val launcher = PortLauncher.decode(File(root, "anyps5-ppsa01234.fuseport").readText())!!
        assertIs<LaunchOutcome.Started>(store.library.launch(game.id))
        val plan = assertIs<LaunchPlan.Command>(services.launched.last().plan)
        assertEquals(listOf("${build.path}/app.elf"), plan.argv)
        assertEquals(launcher.cwd, plan.workingDir)

        // A save made, then the build taken out: the save stays, Play goes back to the emulator.
        File(root, "home/_sd/SAVEDATA00").apply { mkdirs() }.resolve("sdimg").writeText("progress")
        assertTrue(store.ports.removeRecompiled(game.id))
        assertFalse(build.exists())
        assertEquals("progress", File(root, "home/_sd/SAVEDATA00/sdimg").readText())
        assertNull(store.ports.playWith(game.id))
        assertNull(store.ports.recompileOffer(game.id)!!.builtWith)
        assertEquals(before, d.walkTopDown().filter { it.isFile }.associate { it.relativeTo(d).path to it.readBytes().toList() })
    }

    @Test
    fun whatAnyPs5CantRecompileIsSaidAndNothingIsDownloaded(): Unit = runBlocking {
        // Still in its signed container.
        dump(eboot = byteArrayOf(0x4F, 0x15, 0x3D, 0x1D, 0, 0))
        val (services, _) = services()
        val store = createFuseStore(services, scope)
        val game = store.ps5Game()
        assertTrue("SELF" in store.ports.recompileOffer(game.id)!!.whyNot.orEmpty())
        assertTrue("SELF" in store.ports.recompile(game.id).orEmpty())
        assertTrue(services.installerRuns.isEmpty())
    }

    @Test
    fun withoutGitHubsChecksumAnyPs5IsNotDownloaded(): Unit = runBlocking {
        val d = dump()
        val (services, files) = services(release = release(digests = false))
        val store = createFuseStore(services, scope)
        val game = store.ps5Game()
        assertTrue("checksum" in store.ports.recompile(game.id).orEmpty())
        assertFalse(File(files.folder, "anyps5").exists())
        assertTrue(services.installerRuns.isEmpty())
        assertTrue(File(d, "eboot.bin").isFile)
    }

    @Test
    fun aFailedRecompileSaysAnyPs5sReasonAndLeavesNoBuild(): Unit = runBlocking {
        dump()
        val (services, files) = services()
        services.contentInstaller = { InstallerResult(2, "Patches: none\nerror: unsupported syscall in sce_module/libSceFios2.prx") }
        val store = createFuseStore(services, scope)
        val game = store.ps5Game()
        val problem = store.ports.recompile(game.id).orEmpty()
        assertTrue("unsupported syscall" in problem, problem)
        val root = File(files.folder, "anyps5-ppsa01234")
        assertFalse(File(root, "build").exists() || File(root, "build.new").exists())
        assertNull(store.ports.playWith(game.id))
    }

    @Test
    fun onAMacItSaysWhyItIsNotOffered(): Unit = runBlocking {
        dump()
        val (mac, _) = services(os = "macos")
        val onMac = createFuseStore(mac, scope)
        assertTrue("Linux and Windows" in onMac.ports.recompileOffer(onMac.ps5Game().id)!!.whyNot.orEmpty())
    }
}
