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
        override suspend fun writeBytes(path: String, bytes: ByteArray) = runCatching { File(path).apply { parentFile.mkdirs() }.writeBytes(bytes) }.isSuccess
        override suspend fun isLink(path: String) = Files.isSymbolicLink(File(path).toPath())
        override suspend fun remove(path: String): Boolean {
            val p = File(path).toPath()
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return true
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p)) { Files.delete(p); return true }
            // Never into a link: links are removed themselves.
            Files.walk(p).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
            return true
        }
        /** A move this says no to fails, as one onto a full or failing disk would. */
        var refuse: (from: String, to: String) -> Boolean = { _, _ -> false }

        /** The programs made runnable; none is when [refuseRunnable], as on a disk that won't. */
        val runnable = mutableListOf<String>()
        var refuseRunnable = false
        override suspend fun makeRunnable(path: String) = !refuseRunnable && runnable.add(path)

        override suspend fun move(from: String, to: String) =
            !refuse(from, to) && File(to).let { !Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) } &&
                runCatching { Files.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.ATOMIC_MOVE) }.isSuccess

        override suspend fun exists(path: String) = Files.exists(File(path).toPath(), LinkOption.NOFOLLOW_LINKS)
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

    /**
     * Updates [id] and waits for the new copy: install returns before its job shows, so "ready with no
     * job" alone is also the state before it. The build's own engine.dat, changed in the copy first
     * when it isn't already, is the build's again only once the new copy is in; [setUp] is what
     * setting it up again makes (its links), done after the job is gone.
     */
    private suspend fun FuseStore.update(id: String, root: File, setUp: () -> Boolean = { true }): PortsState {
        val engine = File(root, "build/engine.dat")
        fun fresh() = runCatching { engine.readBytes() }.getOrNull()?.let { b -> b.size == 32 && b.all { it == 1.toByte() } } == true
        if (fresh()) engine.writeText("changed in the copy")
        ports.install(id)
        return until("updated") { it.port(id)?.ready == true && it.port(id)?.job == null && fresh() && setUp() }
    }

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
        assertEquals(listOf("${root.path}/build.new/skyport"), files.runnable, "the list's program is made runnable, its zip having made it nothing")
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

        // An update carries what the port wrote, never what a link leads to: the person's folder stays whole.
        store.update("wasteport", File(files.folder, "wasteport")) { link("MASTER.DAT") && link("DATA/SAVEGAME") }
        assertEquals("the port's settings", Files.readString(copy.resolve("game.cfg")), "what it wrote beside itself is carried")
        assertTrue(link("MASTER.DAT") && link("DATA/SAVEGAME"), "the links are made again")
        assertEquals(before, game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path to it.readText() }, "nothing of the person's moved")
        assertTrue(File(files.folder, "wasteport/home/data/SAVEGAME/SLOT02.SAV").isFile)

        // Choosing again replaces exactly the links made; removing never follows one out.
        assertNull(store.ports.chooseFolder("wasteport", "game", game.absolutePath))
        assertTrue(store.ports.remove("wasteport"))
        assertEquals(before, game.walkTopDown().filter { it.isFile }.associate { it.relativeTo(game).path to it.readText() })
        assertTrue(File(files.folder, "wasteport/home/data/SAVEGAME/SLOT02.SAV").isFile, "the saves stay in its home")
    }

    @Test
    fun installedWithTheGamesOneCopyInTheLibraryItIsUsedWithoutAsking(): Unit = runBlocking {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        File(game, "SKYRACER.EXE").writeBytes(ByteArray(8))
        val (services, _) = services()
        val store = createFuseStore(services, scope)
        val dos = assertNotNull(store.library.addGameFile(File(game, "SKYRACER.EXE").absolutePath, PlatformId("dos"), title = "Sky Racer"))
        store.ports.open()
        store.until("listed") { it.loaded }

        // Nothing chosen: installing finds the one copy in the library and sets the port up with it.
        store.ports.install("skyport")
        // Set up first, then the copy recorded as the game the port plays: both, before looking.
        store.until("set up by itself") { it.port("skyport")?.ready == true }
        withTimeout(20_000) { while ("skyport" !in services.data.settings.current().ports.copies) delay(25) }
        assertEquals(dos.value, services.data.settings.current().ports.copies["skyport"])
        assertEquals(game.absolutePath.replace('\\', '/'), services.data.settings.current().ports.folders["skyport"]?.get("game"))
    }

    @Test
    fun theCopyInTheLibraryIsTakenAndItsGamePlaysWithThePortUntilItIsRemoved(): Unit = runBlocking {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        File(game, "SKYRACER.EXE").writeBytes(ByteArray(8))
        // Another game of a similar name whose folder lacks the port's file: never offered.
        val other = Files.createTempDirectory("fuse-ports-other").toFile()
        File(other, "SKYRACER2.EXE").writeBytes(ByteArray(8))
        val (services, files) = services()
        val store = createFuseStore(services, scope)
        val dos = assertNotNull(store.library.addGameFile(File(game, "SKYRACER.EXE").absolutePath, PlatformId("dos"), title = "Sky Racer"))
        assertNotNull(store.library.addGameFile(File(other, "SKYRACER2.EXE").absolutePath, PlatformId("dos"), title = "Sky Racer 2"))
        store.ports.open()
        store.until("listed") { it.loaded }

        // The person's copy is found in the library by its name and believed by its files.
        val copies = store.ports.copiesInLibrary("skyport", "game")
        assertEquals(listOf(PortCopy(dos, "Sky Racer", "MS-DOS", game.absolutePath.replace('\\', '/'))), copies)
        assertNull(store.ports.useCopy("skyport", "game", copies.single()))
        assertEquals(dos.value, services.data.settings.current().ports.copies["skyport"])
        store.ports.install("skyport")
        store.until("set up") { it.port("skyport")?.ready == true }
        assertEquals(File(game, "SKY.DAT").readBytes().toList(), ByteArray(16) { 7 }.toList(), "the copy is untouched")

        // Play With on the game's page: the port is offered, and once chosen Play starts it.
        assertEquals(listOf("skyport"), store.ports.portsFor(dos).map { it.port.id })
        assertNull(store.ports.playWith(dos))
        store.ports.setPlayWith(dos, "skyport")
        val page = withTimeout(20_000) { store.library.game(dos).first { it?.emulator?.port != null } }!!
        assertEquals("SkyPort", page.emulator.port)
        assertEquals("Starts in SkyPort", page.emulator.launchSummary)
        assertTrue(page.emulator.canLaunch)
        val launcher = PortLauncher.decode(File(files.folder, "skyport/skyport.fuseport").readText())!!
        assertIs<LaunchOutcome.Started>(store.library.launch(dos))
        assertEquals(launcher.argv, (services.launched.last().plan as LaunchPlan.Command).argv)
        // A Direct entry in Steam for the game starts the port too.
        assertEquals(launcher.argv, assertIs<DirectCommand.Ready>(store.library.directCommand(dos)).argv)

        // Removed: the game goes back to its emulator.
        assertTrue(store.ports.remove("skyport"))
        assertNull(store.ports.playWith(dos))
        assertTrue(store.ports.portsFor(dos).isEmpty())
        other.deleteRecursively()
    }

    @Test
    fun withNoCopyHereThePersonsCopyOnRommIsOfferedBroughtAndUsedOnceIn(): Unit = runBlocking {
        val (services, _) = services()
        val store = createFuseStore(services, scope)
        val asked = java.util.concurrent.CopyOnWriteArrayList<PortGameCopy>()
        val landed = kotlinx.coroutines.flow.MutableStateFlow<io.github.matiyaaa.fuse.model.GameId?>(null)
        val onRomm = PortGameCopy(io.github.matiyaaa.fuse.model.GameId(-5), "Sky Racer (1996)", PlatformId("dos"), CopyPlace.ROMM, "RomM", romId = 5)
        (store.ports as io.github.matiyaaa.fuse.ui.shell.store.impl.PortsStore).sources = object : io.github.matiyaaa.fuse.ui.shell.store.impl.PortGameSources {
            override suspend fun games(systems: Set<String>) = listOf(
                onRomm,
                PortGameCopy(io.github.matiyaaa.fuse.model.GameId(-6), "Sky Racer 2", PlatformId("dos"), CopyPlace.ROMM, "RomM", romId = 6),
                PortGameCopy(io.github.matiyaaa.fuse.model.GameId(-7), "Sky Racer", PlatformId("n64"), CopyPlace.DEVICE, "Gaming PC"),
            ).filter { it.platform.value in systems }
            override suspend fun bring(copy: PortGameCopy): String? = null.also { asked += copy }
            override fun arrived(copy: PortGameCopy) = landed
        }
        store.ports.open()
        store.until("listed") { it.loaded }

        // Not on this computer: the person's copy of its game, by its name, from a computer's systems.
        assertTrue(store.ports.copiesInLibrary("skyport", "game").isEmpty())
        assertEquals(listOf(onRomm), store.ports.copiesElsewhere("skyport", "game"))

        // Brought here; once it is in the library, the port reads its files.
        assertNull(store.ports.bringGame("skyport", onRomm))
        assertEquals(listOf(onRomm), asked.toList())
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        File(game, "SKYRACER.EXE").writeBytes(ByteArray(8))
        val dos = assertNotNull(store.library.addGameFile(File(game, "SKYRACER.EXE").absolutePath, PlatformId("dos"), title = "Sky Racer"))
        landed.value = dos
        withTimeout(20_000) { while (services.data.settings.current().ports.copies["skyport"] != dos.value) kotlinx.coroutines.delay(25) }
        assertEquals(game.absolutePath.replace('\\', '/'), services.data.settings.current().ports.folders["skyport"]?.get("game"))
    }

    @Test
    fun aPortsOwnSettingsAreChangedInItsFileAsItsCodeNamesThemAndPutBack(): Unit = runBlocking {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        // A port Fuse Tune has read (DevilutionX's settings, by its id), its settings file in its home.
        val list = """
            {"format":1,"revision":2,"ports":[
              {"id":"devilutionx","name":"DevilutionX","game":"Diablo","about":"Made up.","homepage":"https://example.org/dvx","version":"1.5.5",
               "builds":[{"os":"linux","url":"https://example.org/dvx.zip","size":${build.size},"sha256":"${sha(build)}","kind":"ZIP","run":"skyport"}],
               "data":[{"id":"game","name":"Diablo's files","about":"From the CD.","needs":[["SKY.DAT"]]}],
               "args":["--config-dir","{home}/config"],"source":"made up","settings":{"file":"{home}/config/diablo.ini"}}
            ]}
        """.trimIndent()
        val (services, files) = services(list = list)
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        assertNull(store.ports.chooseFolder("devilutionx", "game", game.absolutePath))
        store.ports.install("devilutionx")
        val id = store.until("set up") { it.port("devilutionx")?.ready == true }.port("devilutionx")!!.game!!
        // As DevilutionX wrote it before: key=value, CRLF.
        val ini = File(files.folder, "devilutionx/home/config/diablo.ini").apply { parentFile.mkdirs(); writeText("[Graphics]\r\nFullscreen=1\r\n") }
        val before = ini.readText()

        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(id, null))
        assertEquals("DevilutionX", view.emulator)
        val graphics = view.sections.single { it.id == "|Graphics" }
        val full = graphics.values.single { it.setting.key == "Fullscreen" }
        assertEquals("true", full.value, "its 1 shown as on")
        assertEquals("Fullscreen", full.setting.label)
        val zoom = graphics.values.single { it.setting.key == "Zoom" }
        assertEquals("false", zoom.value, "DevilutionX's default where the file says nothing")

        assertTrue(store.library.setEveryEmulatorSetting(id, null, full.setting.id, "false"))
        assertTrue(store.library.setEveryEmulatorSetting(id, null, zoom.setting.id, "true"))
        assertEquals("[Graphics]\r\nFullscreen=0\r\nZoom=1\r\n", ini.readText(), "written as DevilutionX writes it")
        assertTrue(store.library.restoreEmulatorSettings(id, null))
        assertEquals(before, ini.readText(), "put back exactly")
    }

    /** Installs a made-up port with Fuse Tune's [id] and its settings at [settings] in its home, and gives its game. */
    private suspend fun tunedPort(id: String, settings: String): Triple<FuseStore, File, io.github.matiyaaa.fuse.model.GameId> {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        val list = """
            {"format":1,"revision":2,"ports":[
              {"id":"$id","name":"Tuned","game":"Sky Racer","about":"Made up.","homepage":"https://example.org/tuned","version":"1.0",
               "builds":[{"os":"linux","url":"https://example.org/tuned.zip","size":${build.size},"sha256":"${sha(build)}","kind":"ZIP","run":"skyport"}],
               "data":[{"id":"game","name":"Its files","about":"From the CD.","needs":[["SKY.DAT"]]}],
               "source":"made up","settings":{"file":"{home}/$settings"}}
            ]}
        """.trimIndent()
        val (services, files) = services(list = list)
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        assertNull(store.ports.chooseFolder(id, "game", game.absolutePath))
        store.ports.install(id)
        val gameId = store.until("set up") { it.port(id)?.ready == true }.port(id)!!.game!!
        return Triple(store, File(files.folder, "$id/home"), gameId)
    }

    @Test
    fun aHarbourMastersPortsCVarsAreChangedInsideTheirObjectsUnderTheMenusOwnNames(): Unit = runBlocking {
        val (store, home, id) = tunedPort("shipwright", "shipofharkinian.json")
        // As libultraship wrote it: every CVar under CVars, its name's dots as objects.
        val json = File(home, "shipofharkinian.json").apply { parentFile.mkdirs(); writeText("{\n    \"CVars\": {\n        \"gSettings\": {\n            \"ControlNav\": 1\n        }\n    },\n    \"Window\": {\n        \"Width\": 640\n    }\n}\n") }
        val before = json.readText()
        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(id, null))
        val general = view.sections.single { it.title == "Settings / General" }
        val nav = general.values.single { it.setting.key == "ControlNav" }
        assertEquals("true", nav.value, "its 1 shown as on")
        assertEquals("Menu Controller Navigation", nav.setting.label)
        val boot = general.values.single { it.setting.key == "BootSequence" }
        assertEquals("0", boot.value, "the menu's default where the file says nothing")
        assertTrue(store.library.setEveryEmulatorSetting(id, null, nav.setting.id, "false"))
        assertTrue(store.library.setEveryEmulatorSetting(id, null, boot.setting.id, "2"))
        assertEquals(
            "{\n    \"CVars\": {\n        \"gSettings\": {\n            \"ControlNav\": 0,\n            \"BootSequence\": 2\n        }\n    },\n    \"Window\": {\n        \"Width\": 640\n    }\n}\n",
            json.readText(),
            "a switch written 1 or 0, as libultraship keeps it; everything else as it was",
        )
        assertTrue(store.library.restoreEmulatorSettings(id, null))
        assertEquals(before, json.readText(), "put back exactly")
    }

    @Test
    fun anN64RecompilationsTabsAreEachTheirOwnFileBesideTheOthers(): Unit = runBlocking {
        val (store, home, id) = tunedPort("banjorecomp", "general.json")
        val graphics = File(home, "graphics.json").apply { parentFile.mkdirs(); writeText("{\n    \"res_option\": \"Auto\"\n}") }
        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(id, null))
        val res = view.sections.single { it.title == "Graphics" }.values.single { it.setting.key == "res_option" }
        assertEquals(listOf("Auto", "Resolution"), listOf(res.value, res.setting.label))
        assertTrue(store.library.setEveryEmulatorSetting(id, null, res.setting.id, "Original2x"))
        assertEquals("{\n    \"res_option\": \"Original2x\"\n}", graphics.readText(), "in the tab's own file")
        val rumble = view.sections.single { it.title == "General" }.values.single { it.setting.key == "rumble_strength" }
        assertTrue(store.library.setEveryEmulatorSetting(id, null, rumble.setting.id, "60"))
        assertEquals("{\n  \"rumble_strength\": 60\n}\n", File(home, "general.json").readText(), "a file the game hasn't made yet, made with the one key")
    }

    @Test
    fun anIdTech4PortsConsoleVariablesAreWrittenAsItsSetaLines(): Unit = runBlocking {
        val (store, home, id) = tunedPort("dhewm3", "base/dhewm.cfg")
        val cfg = File(home, "base/dhewm.cfg").apply { parentFile.mkdirs(); writeText("unbindall\nbind \"TAB\" \"_impulse19\"\nseta r_mode \"5\"\nseta r_fullscreen \"0\"\n") }
        val view = assertIs<EverySettingView.Ready>(store.library.everyEmulatorSetting(id, null))
        val renderer = view.sections.single { it.title == "Renderer" }
        val full = renderer.values.single { it.setting.key == "r_fullscreen" }
        assertEquals("false", full.value)
        assertTrue(store.library.setEveryEmulatorSetting(id, null, full.setting.id, "true"))
        assertTrue(store.library.setEveryEmulatorSetting(id, null, renderer.values.single { it.setting.key == "r_mode" }.setting.id, "7"))
        assertEquals("unbindall\nbind \"TAB\" \"_impulse19\"\nseta r_mode \"7\"\nseta r_fullscreen \"1\"\n", cfg.readText())
    }

    @Test
    fun aRecompilationIsMadeFromThePersonsRomInTheLibraryLinkedOrTurnedNeverChanged(): Unit = runBlocking {
        // A made-up N64 ROM in its own order (.z64), and the same ROM with its pairs swapped (.v64).
        val z64 = ByteArray(4096) { (it * 7).toByte() }.also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        val v64 = ByteArray(z64.size) { z64[it xor 1] }
        val hash = io.github.matiyaaa.fuse.launch.ports.Xxh3.hex(z64)
        val roms = Files.createTempDirectory("fuse-ports-roms").toFile()
        val rom = File(roms, "Majoras Mask (USA).z64").apply { writeBytes(z64) }
        val swapped = File(roms, "Majoras Mask (USA).v64").apply { writeBytes(v64) }
        val wrong = File(roms, "Majoras Mask (Europe).z64").apply { writeBytes(z64.copyOf().also { it[100] = 1 }) }
        val list = """
            {"format":1,"revision":2,"ports":[
              {"id":"maskport","name":"MaskPort","game":"Majoras Mask","about":"Made up.","homepage":"https://example.org/mask","version":"1.2.2",
               "builds":[{"os":"linux","url":"https://example.org/mask.zip","size":${build.size},"sha256":"${sha(build)}","kind":"ZIP","run":"skyport"}],
               "data":[{"id":"rom","name":"Its ROM","about":"The North American one.","needs":[],"rom":{"system":"n64","xxh3":"$hash","place":"mask.us.z64"}}],
               "env":{"APP_FOLDER_PATH":"{home}"},"saves":"saves","source":"made up"}
            ]}
        """.trimIndent()
        val (services, files) = services(list = list)
        val store = createFuseStore(services, scope)
        val usa = assertNotNull(store.library.addGameFile(rom.absolutePath, PlatformId("n64"), title = "Majoras Mask"))
        assertNotNull(store.library.addGameFile(wrong.absolutePath, PlatformId("n64"), title = "Majoras Mask Europe"))
        store.ports.open()
        store.until("listed") { it.loaded }

        // Only the ROM it is made from is offered, found by its hash.
        val copies = store.ports.copiesInLibrary("maskport", "rom")
        assertEquals(listOf(rom.absolutePath.replace('\\', '/')), copies.map { it.folder })
        assertEquals("That isn't the ROM it is made from. The North American one.", store.ports.chooseFolder("maskport", "rom", wrong.absolutePath))
        assertNull(store.ports.useCopy("maskport", "rom", copies.single()))
        store.ports.install("maskport")
        store.until("set up") { it.port("maskport")?.ready == true }
        val placed = File(files.folder, "maskport/home/mask.us.z64").toPath()
        assertTrue(Files.isSymbolicLink(placed), "already in the form it reads: linked")
        assertEquals(rom.toPath(), Files.readSymbolicLink(placed))
        val launcher = PortLauncher.decode(File(files.folder, "maskport/maskport.fuseport").readText())!!
        assertEquals(mapOf("APP_FOLDER_PATH" to File(files.folder, "maskport/home").path), launcher.env)
        // The N64 game's page offers it.
        assertEquals(listOf("maskport"), store.ports.portsFor(usa).map { it.port.id })

        // The same ROM in the other order: a copy turned into the one it reads; the person's file as it was.
        assertNull(store.ports.chooseFolder("maskport", "rom", swapped.absolutePath))
        store.until("set up again") { it.port("maskport")?.ready == true }
        assertFalse(Files.isSymbolicLink(placed))
        assertTrue(Files.readAllBytes(placed).contentEquals(z64))
        assertTrue(swapped.readBytes().contentEquals(v64), "the person's file is untouched")
        assertTrue(rom.readBytes().contentEquals(z64))
        roms.deleteRecursively()
    }

    @Test
    fun aNesRecompilationIsHandedThePersonsOwnRomByItsPathAndStartsStraightIntoIt(): Unit = runBlocking {
        // A made-up NES ROM, its iNES header left out of the CRC32 as nesrecomp's verify_rom leaves it out.
        val data = ByteArray(40960) { (it * 13).toByte() }
        val crc = io.github.matiyaaa.fuse.launch.ports.PortRomCheck.crc32(data).toString(16).padStart(8, '0')
        val roms = Files.createTempDirectory("fuse-ports-nes").toFile()
        val rom = File(roms, "Yoshi (USA).nes").apply { writeBytes(byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + data) }
        val wrong = File(roms, "Yoshi (Europe).nes").apply { writeBytes(byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12) + data.copyOf().also { it[5] = 1 }) }
        val list = """
            {"format":1,"revision":2,"ports":[
              {"id":"eggport","name":"EggPort","game":"Yoshi","about":"Made up.","homepage":"https://example.org/egg","version":"0.1.0",
               "builds":[{"os":"linux","url":"https://example.org/egg.zip","size":${build.size},"sha256":"${sha(build)}","kind":"ZIP","run":"skyport"}],
               "data":[{"id":"rom","name":"Its ROM","about":"The USA one.","needs":[],"rom":{"system":"nes","crc32":"$crc"}}],
               "args":["{data:rom}"],"source":"made up"}
            ]}
        """.trimIndent()
        val (services, files) = services(list = list)
        val store = createFuseStore(services, scope)
        assertNotNull(store.library.addGameFile(rom.absolutePath, PlatformId("nes"), title = "Yoshi"))
        assertNotNull(store.library.addGameFile(wrong.absolutePath, PlatformId("nes"), title = "Yoshi Europe"))
        store.ports.open()
        store.until("listed") { it.loaded }

        // The one copy it is made from is the only one offered, and used without asking.
        assertEquals(listOf(rom.absolutePath.replace('\\', '/')), store.ports.copiesInLibrary("eggport", "rom").map { it.folder })
        assertEquals("That isn't the ROM it is made from. The USA one.", store.ports.chooseFolder("eggport", "rom", wrong.absolutePath))
        store.ports.install("eggport")
        store.until("set up") { it.port("eggport")?.ready == true }
        val launcher = PortLauncher.decode(File(files.folder, "eggport/eggport.fuseport").readText())!!
        assertEquals(rom.absolutePath.replace('\\', '/'), launcher.argv.last(), "its ROM by its path, so it opens no picker of its own")
        assertFalse(File(files.folder, "eggport/home").listFiles().orEmpty().any { it.name.endsWith(".nes") }, "nothing is put in its home")
        assertTrue(rom.readBytes().copyOfRange(16, rom.length().toInt()).contentEquals(data), "the person's file is untouched")
        roms.deleteRecursively()
    }

    @Test
    fun anUpdateThatCantGoInLeavesTheWorkingCopyAndAStoppedOneIsPutBack(): Unit = runBlocking {
        File(game, "SKY.DAT").writeBytes(ByteArray(16) { 7 })
        val (services, files) = services()
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        assertNull(store.ports.chooseFolder("skyport", "game", game.absolutePath))
        store.ports.install("skyport")
        store.until("installed") { it.port("skyport")?.ready == true }
        val root = File(files.folder, "skyport")
        val program = File(root, "build/skyport").readText()

        // The new build can't be moved in: the one in use stays, whole, and still plays.
        files.refuse = { from, _ -> from.endsWith("/build.new") }
        store.ports.install("skyport")
        val failed = store.until("the update failed") { it.port("skyport")?.job is PortJob.Failed }.port("skyport")!!
        assertTrue("as it was" in (failed.job as PortJob.Failed).message, (failed.job as PortJob.Failed).message)
        assertEquals(program, File(root, "build/skyport").readText())
        assertTrue(File(root, "build/engine.dat").isFile)
        assertFalse(File(root, "build.old").exists() || File(root, "build.new").exists(), "nothing left beside it")
        assertTrue(failed.installed)

        // A copy in use that couldn't be moved aside (a file in use) is never taken out either.
        store.ports.cancel("skyport")
        store.until("the failure is cleared") { it.port("skyport")?.job == null }
        files.refuse = { from, _ -> from.endsWith("/build") }
        store.ports.install("skyport")
        store.until("failed again") { it.port("skyport")?.job is PortJob.Failed }
        assertEquals(program, File(root, "build/skyport").readText())

        // Fuse stopped between the two moves: the copy waiting aside goes back the next time Ports opens.
        // When it can't yet (a file in use), it stays aside, never removed: it is the only copy there is.
        files.refuse = { _, _ -> false }
        assertTrue(files.move(File(root, "build").path, File(root, "build.old").path))
        suspend fun reopen() {
            val again = PortsStore(StoreContext(services, scope, services.data.settings.current()), files, library = { error("not used") }, write = {}, publicKey = { null })
            again.open()
            withTimeout(20_000) { again.state.first { it.loaded } }
        }
        files.refuse = { from, _ -> from.endsWith("/build.old") }
        reopen()
        assertEquals(program, File(root, "build.old/skyport").readText(), "the only copy is kept")
        assertFalse(File(root, "build").exists())
        files.refuse = { _, _ -> false }
        reopen()
        assertEquals(program, File(root, "build/skyport").readText())
        assertFalse(File(root, "build.old").exists())

        // Fuse stopped after the new copy went in whole: the one aside goes, the new one stays.
        File(root, "build.old").mkdirs()
        File(root, "build.old/skyport").writeText("the old build")
        files.recover(File(root, "build").path)
        assertEquals(program, File(root, "build/skyport").readText())
        assertFalse(File(root, "build.old").exists())

        // And an update that goes in replaces it, with nothing left aside (the failure before is still shown until it does).
        store.update("skyport", root)
        assertTrue(File(root, "build/skyport").isFile)
        assertFalse(File(root, "build.old").exists())
    }

    @Test
    fun whatAPortKeepsBesideItselfOutlivesAnUpdate(): Unit = runBlocking {
        val (services, files) = services(keepList())
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        store.ports.install("keepport")
        store.until("set up without a folder of the person's") { it.port("keepport")?.ready == true }
        val root = File(files.folder, "keepport")
        val copy = File(root, "build").toPath()
        assertTrue(Files.isSymbolicLink(copy.resolve("saves")), "its saves' folder is a link")
        assertEquals(File(root, "home/saves").toPath(), Files.readSymbolicLink(copy.resolve("saves")))
        assertFalse(Files.exists(copy.resolve("config.ini"), LinkOption.NOFOLLOW_LINKS), "a file is the port's to make")

        // It plays: its save lands in its home, its settings in its copy, and it writes more beside itself.
        Files.writeString(copy.resolve("saves/slot1.sav"), "a save")
        Files.writeString(copy.resolve("config.ini"), "fullscreen=1")
        Files.createDirectories(copy.resolve("shots"))
        Files.writeString(copy.resolve("shots/one.png"), "a screenshot")
        Files.writeString(copy.resolve("engine.dat"), "changed by the port")
        assertTrue(File(root, "home/saves/slot1.sav").isFile)

        // An update replaces the copy: the save stays in its home, the settings and what else it wrote
        // are carried into the new copy, and a file of the build's own comes new with it.
        store.update("keepport", root) { Files.isSymbolicLink(copy.resolve("saves")) }
        assertEquals("fullscreen=1", Files.readString(copy.resolve("config.ini")))
        assertEquals("a screenshot", Files.readString(copy.resolve("shots/one.png")))
        assertTrue(Files.readAllBytes(copy.resolve("engine.dat")).all { it == 1.toByte() }, "the build's own file is the new build's")
        assertTrue(Files.isSymbolicLink(copy.resolve("saves")))
        assertEquals("a save", Files.readString(copy.resolve("saves/slot1.sav")))

        // Removed: the copy and its settings go; the saves stay in its home.
        assertTrue(store.ports.remove("keepport"))
        assertTrue(File(root, "home/saves/slot1.sav").isFile)
    }

    /** A port that asks for its game itself and writes everything beside its program, as many recompilations do. */
    private fun keepList(body: ByteArray = build) = """
        {"format":1,"revision":2,"ports":[
          {"id":"keepport","name":"KeepPort","game":"Keep Racer","about":"Made up.","homepage":"https://example.org/keepport","version":"1.0",
           "builds":[{"os":"linux","url":"https://example.org/keepport-1.0.zip","size":${body.size},"sha256":"${sha(body)}","kind":"ZIP","run":"skyport"}],
           "keep":["saves/","config.ini"],"saves":"saves","source":"made up"}
        ]}
    """.trimIndent()

    /** A zip of [entries] (path to contents), as a project's build would be. */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> for ((name, bytes) in entries) { z.putNextEntry(ZipEntry(name)); z.write(bytes) } }
    }.toByteArray()

    /** KeepPort installed and played: a save through its link into its home; its settings, a screenshot and a changed file of the build's own in its copy. */
    private suspend fun played(services: FakeServices, files: TestPortFiles): Triple<FuseStore, File, java.nio.file.Path> {
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        store.ports.install("keepport")
        store.until("set up") { it.port("keepport")?.ready == true }
        val root = File(files.folder, "keepport")
        val copy = File(root, "build").toPath()
        Files.writeString(copy.resolve("saves/slot1.sav"), "a save")
        Files.writeString(copy.resolve("config.ini"), "fullscreen=1")
        Files.createDirectories(copy.resolve("shots"))
        Files.writeString(copy.resolve("shots/one.png"), "a screenshot")
        Files.writeString(copy.resolve("engine.dat"), "changed by the port")
        return Triple(store, root, copy)
    }

    /** Everything KeepPort wrote is in the copy in use, which is the one it had, and nothing is left beside it. */
    private fun assertAsItWas(root: File, copy: java.nio.file.Path) {
        assertEquals("fullscreen=1", Files.readString(copy.resolve("config.ini")), "its settings are in the copy in use")
        assertEquals("a screenshot", Files.readString(copy.resolve("shots/one.png")), "and what else it wrote")
        assertEquals("changed by the port", Files.readString(copy.resolve("engine.dat")), "the copy in use is the one it had")
        assertEquals("a save", Files.readString(copy.resolve("saves/slot1.sav")))
        assertFalse(File(root, "build.new").exists() || File(root, "build.old").exists(), "nothing is left beside it")
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("carried-") }, "nothing had to be kept aside")
    }

    @Test
    fun anUpdateThatCantGoInPutsBackEverythingThePortWroteFirst(): Unit = runBlocking {
        val (services, files) = services(keepList())
        val (store, root, copy) = played(services, files)
        // The new copy, which its settings and screenshot were carried into, can't be moved in.
        files.refuse = { from, _ -> from.endsWith("/build.new") }
        store.ports.install("keepport")
        val failed = store.until("the update failed") { it.port("keepport")?.job is PortJob.Failed }.port("keepport")!!
        assertTrue("as it was" in (failed.job as PortJob.Failed).message, (failed.job as PortJob.Failed).message)
        assertAsItWas(root, copy)
    }

    @Test
    fun aFileThatCantBeCarriedStopsTheUpdateWithWhatWasCarriedPutBack(): Unit = runBlocking {
        val (services, files) = services(keepList())
        val (store, root, copy) = played(services, files)
        // Its settings go first; then the screenshot can't be moved (a file in use): the update stops.
        files.refuse = { from, _ -> from.endsWith("/shots/one.png") }
        store.ports.install("keepport")
        val failed = store.until("the update stopped") { it.port("keepport")?.job is PortJob.Failed }.port("keepport")!!
        val message = (failed.job as PortJob.Failed).message
        assertTrue("shots/one.png" in message && "as it was" in message, message)
        assertAsItWas(root, copy)
        // Once it can be moved, the update goes in with everything.
        files.refuse = { _, _ -> false }
        store.ports.cancel("keepport")
        store.update("keepport", root) { Files.isSymbolicLink(copy.resolve("saves")) }
        assertEquals("fullscreen=1", Files.readString(copy.resolve("config.ini")))
        assertEquals("a screenshot", Files.readString(copy.resolve("shots/one.png")))
    }

    @Test
    fun whatThePortWroteIsCarriedWithoutTheListOfItsBuildsOwnFiles(): Unit = runBlocking {
        val (services, files) = services(keepList())
        val (store, root, copy) = played(services, files)
        // Installed by a Fuse that kept no list of the build's own files: what the new build doesn't have is the port's.
        assertTrue(File(root, "build.files").delete())
        store.update("keepport", root) { Files.isSymbolicLink(copy.resolve("saves")) }
        assertEquals("a screenshot", Files.readString(copy.resolve("shots/one.png")), "what it wrote is carried all the same")
        assertEquals("fullscreen=1", Files.readString(copy.resolve("config.ini")))
        assertEquals("a save", Files.readString(copy.resolve("saves/slot1.sav")))
    }

    @Test
    fun aFolderItKeepsThatTheBuildHasItselfIsTheHomesWithTheBuildsFilesInIt(): Unit = runBlocking {
        // The build ships its save folder with a file in it.
        val body = zipOf("skyport" to "#!/bin/sh\necho keepport\n".toByteArray(), "engine.dat" to ByteArray(32) { 1 }, "saves/README.txt" to "from the build".toByteArray())
        val (services, files) = services(keepList(body), body)
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        store.ports.install("keepport")
        store.until("set up") { it.port("keepport")?.ready == true }
        val root = File(files.folder, "keepport")
        val copy = File(root, "build").toPath()
        assertTrue(Files.isSymbolicLink(copy.resolve("saves")), "its save folder is a link into its home, though the build had one")
        assertEquals(File(root, "home/saves").toPath(), Files.readSymbolicLink(copy.resolve("saves")))
        assertEquals("from the build", File(root, "home/saves/README.txt").readText(), "the build's file in it went into the home")

        // A save lands in the home; the build's file, changed by the port, is never put back over at an update.
        Files.writeString(copy.resolve("saves/slot1.sav"), "a save")
        File(root, "home/saves/README.txt").writeText("the port's own now")
        store.update("keepport", root) { Files.isSymbolicLink(copy.resolve("saves")) }
        assertEquals("the port's own now", File(root, "home/saves/README.txt").readText())
        assertEquals("a save", File(root, "home/saves/slot1.sav").readText())

        // Removed: the copy goes, the saves stay.
        assertTrue(store.ports.remove("keepport"))
        assertFalse(File(root, "build").exists())
        assertEquals("a save", File(root, "home/saves/slot1.sav").readText())
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
    fun aProgramThatCantBeMadeRunnableIsNeverInstalled(): Unit = runBlocking {
        val (services, files) = services()
        files.refuseRunnable = true
        val store = createFuseStore(services, scope)
        store.ports.open()
        store.until("listed") { it.loaded }
        store.ports.install("skyport")
        val failed = store.until("failed") { it.port("skyport")?.job is PortJob.Failed }.port("skyport")!!
        assertEquals("Fuse couldn't make skyport runnable, so it wasn't installed.", (failed.job as PortJob.Failed).message)
        assertFalse(failed.installed)
        assertFalse(File(files.folder, "skyport/build").exists() || File(files.folder, "skyport/build.new").exists())
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
