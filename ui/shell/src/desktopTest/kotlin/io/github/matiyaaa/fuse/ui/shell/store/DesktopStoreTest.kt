package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.obtainium.DesktopAssetKind
import io.github.matiyaaa.fuse.model.Host
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Store on a Linux computer: its own list, each program's newest release from GitHub with the
 * AppImage picked, the download put in place, removed again, and apps added by their address (only
 * when their releases have a build for this computer).
 */
class DesktopStoreTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-desktop-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    private class FakeInstaller(dir: File) : DesktopInstaller {
        override val host = Host.LINUX
        override val arch = "x86_64"
        val apps = File(dir, "Applications").apply { mkdirs() }
        override val folder: String = apps.absolutePath
        private val downloads = File(dir, "downloads").apply { mkdirs() }

        override suspend fun newDownload(fileName: String): DownloadSink {
            val file = File(downloads, "${System.nanoTime()}-$fileName")
            val out = file.outputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            return object : DownloadSink {
                override val path: String = file.absolutePath
                override suspend fun write(bytes: ByteArray, count: Int) { out.write(bytes, 0, count); digest.update(bytes, 0, count) }
                override suspend fun finish(): String { out.close(); return digest.digest().joinToString("") { "%02x".format(it) } }
                override suspend fun discard() { runCatching { out.close() }; file.delete() }
            }
        }

        override fun freeBytes(): Long = 1L shl 34

        override var handheld: String? = null
        val flatpaks = mutableListOf<String>()
        override suspend fun installFlatpak(appId: String, name: String) { flatpaks += appId }

        /** The next install fails, as one whose download turns out broken would. */
        @Volatile var failNext = false
        private val kept = File(dir, "kept").apply { mkdirs() }

        private var shelves = 0
        val forgotten = mutableListOf<String>()

        override suspend fun keepPrevious(name: String, previous: String): String? {
            // A shelf each, as the real installer keeps them.
            val to = File(kept, "${++shelves}/${File(previous).name}").apply { parentFile.mkdirs() }
            return if (File(previous).renameTo(to)) to.absolutePath else null
        }

        override suspend fun restorePrevious(kept: String, current: String?): String {
            current?.let { File(it).delete() }
            val back = File(apps, File(kept).name)
            check(File(kept).renameTo(back))
            return back.absolutePath
        }

        override suspend fun forgetKept(kept: String): Boolean {
            forgotten += kept
            return File(kept).delete()
        }

        override suspend fun install(name: String, file: String, fileName: String, kind: DesktopAssetKind, previous: String?): String {
            if (failNext) {
                failNext = false
                throw java.io.IOException("The download was broken.")
            }
            assertEquals(DesktopAssetKind.APPIMAGE, kind)
            val target = File(apps, fileName)
            File(file).copyTo(target, overwrite = true)
            return target.absolutePath
        }

        override suspend fun remove(path: String): Boolean = File(path).delete()
        override suspend fun exists(path: String): Boolean = File(path).exists()
        override fun launch(path: String): Boolean = File(path).exists()

        /** Programs found here but not installed by Fuse: removed only with the right password. */
        val removedOthers = mutableListOf<String>()
        val passwordsTried = mutableListOf<String?>()

        /** Programs found here that Flatpak updated for Fuse. */
        val updatedOthers = mutableListOf<String>()

        override suspend fun updateOther(appId: String, name: String, password: String?): UpdateOutcome {
            updatedOthers += appId
            return UpdateOutcome.Updated
        }

        override suspend fun removeOther(appId: String, name: String, password: String?): RemoveOutcome {
            passwordsTried += password
            return when (password) {
                null -> RemoveOutcome.NeedsPassword()
                "right" -> { removedOthers += appId; RemoveOutcome.Removed }
                else -> RemoveOutcome.NeedsPassword(wrong = true)
            }
        }
    }

    private fun release(repo: String, vararg assets: String) =
        """[{"tag_name":"v2.0","name":"2.0","published_at":"2026-09-01T00:00:00Z","html_url":"https://github.com/$repo/releases/tag/v2.0","draft":false,"prerelease":false,"body":"Faster","assets":[""" +
            assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/$repo/releases/download/v2.0/$it","size":0}""" } + "]}]"

    private fun services(installed: List<io.github.matiyaaa.fuse.model.InstalledEmulator>? = null): Pair<FakeServices, FakeInstaller> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache, installedEmulators = installed)
        val installer = FakeInstaller(cache)
        services.desktop = installer
        services.web = web@{ request ->
            val url = request.url.toString()
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                url.startsWith("https://api.github.com/repos/stenzek/duckstation/releases") ->
                    respond(release("stenzek/duckstation", "DuckStation-x64.AppImage", "duckstation-windows-x64-release.zip", "duckstation-mac-release.zip"), HttpStatusCode.OK, json)
                url.startsWith("https://api.github.com/repos/someone/handy/releases") ->
                    respond(release("someone/handy", "Handy-x86_64.AppImage"), HttpStatusCode.OK, json)
                url.startsWith("https://git.eden-emu.dev/api/v1/repos/eden-emu/eden/releases") -> respond(
                    """[{"tag_name":"v0.2.1","name":"v0.2.1","published_at":"2026-07-01T00:00:00Z","html_url":"https://git.eden-emu.dev/eden-emu/eden/releases/tag/v0.2.1","draft":false,"prerelease":false,"assets":[""" +
                        listOf("Eden-Linux-amd64-clang-pgo.AppImage.zsync", "Eden-Linux-v0.2.1-amd64-clang-pgo.AppImage", "Eden-Linux-v0.2.1-amd64-gcc-standard.AppImage", "Eden-Linux-v0.2.1-steamdeck-clang-pgo.AppImage", "Eden-Linux-v0.2.1-legacy-clang-pgo.AppImage")
                            .joinToString(",") { """{"name":"$it","browser_download_url":"https://stable.eden-emu.dev/v0.2.1/$it","size":0}""" } + "]}]",
                    HttpStatusCode.OK, json,
                )
                request.url.host == "stable.eden-emu.dev" -> respond("binary of ${url.substringAfterLast('/')}", HttpStatusCode.OK)
                url.startsWith("https://flathub.org/api/v2/appstream/org.mamedev.MAME") ->
                    respond("""{"name":"MAME","releases":[{"version":"0.289","type":"stable"}]}""", HttpStatusCode.OK, json)
                // A Forgejo server's release list (Codeberg's API answers in GitHub's shape).
                url.startsWith("https://codeberg.org/api/v1/repos/someone/tool/releases") -> respond(
                    """[{"tag_name":"v1.4","name":"1.4","published_at":"2026-08-02T00:00:00Z","html_url":"https://codeberg.org/someone/tool/releases/tag/v1.4","draft":false,"prerelease":false,"body":"Fixes","assets":[""" +
                        """{"name":"Tool-1.4-x86_64.AppImage","browser_download_url":"https://codeberg.org/someone/tool/releases/download/v1.4/Tool-1.4-x86_64.AppImage","size":${"binary of Tool-1.4-x86_64.AppImage".length}}]}]""",
                    HttpStatusCode.OK, json,
                )
                request.url.host == "codeberg.org" && "/releases/download/" in url -> respond("binary of ${url.substringAfterLast('/')}", HttpStatusCode.OK)
                url.startsWith("https://api.github.com/repos/someone/library/releases") ->
                    respond(release("someone/library", "library-src.tar.gz", "library_2.0_amd64.deb"), HttpStatusCode.OK, json)
                request.url.host == "github.com" && "/releases/download/" in url -> respond("binary of ${url.substringAfterLast('/')}", HttpStatusCode.OK)
                else -> null
            }
        }
        return services to installer
    }

    /** The file's text, or null while it is moved away (an update or a roll back between steps). */
    private fun File.text(): String? = runCatching { readText() }.getOrNull()

    private suspend fun <T> eventually(what: String, read: () -> T?): T = withTimeout(20_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            kotlinx.coroutines.delay(25)
        }
        @Suppress("UNREACHABLE_CODE")
        error(what)
    }

    @Test
    fun aProgramIsFetchedAsPublishedAndPutInPlace(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        assertTrue(ops.supported)
        ops.open()
        val catalogue = eventually("catalogue") { ops.state.value.catalogue }
        assertTrue(ops.state.value.desktop)
        val duck = catalogue.apps.single { it.key == "duckstation" }
        assertEquals(Availability.INSTALLABLE, duck.availability)
        // On Linux every emulator is installed by Fuse: Dolphin's AppImage, Eden from its own server, MAME from Flathub.
        assertEquals(Availability.INSTALLABLE, catalogue.apps.single { it.key == "dolphin" }.availability)
        assertEquals(Availability.INSTALLABLE, catalogue.apps.single { it.key == "eden" }.availability)
        assertEquals(Availability.INSTALLABLE, catalogue.apps.single { it.key == "mame" }.availability)
        // Builds carrying one emulator on offer each other: yuzu's, Citra's.
        assertEquals(listOf("citron"), catalogue.apps.single { it.key == "eden" }.otherBuilds)
        assertEquals(listOf("azaharplus", "mandarine"), catalogue.apps.single { it.key == "azahar" }.otherBuilds)
        assertEquals(emptyList(), catalogue.apps.single { it.key == "cemu" }.otherBuilds)

        ops.install("duckstation")
        // DuckStation is already on this computer's PATH, so it can show as installed (found, with no
        // record) before Fuse's own install lands: wait for the install Fuse made.
        val installed = eventually("installed") { ops.state.value.installed["duckstation"]?.takeIf { it.record != null } }
        assertEquals("v2.0", installed.versionName)
        val placed = File(installer.apps, "DuckStation-x64.AppImage")
        assertTrue(placed.isFile)
        assertEquals("binary of DuckStation-x64.AppImage", placed.readText())
        assertNull(ops.state.value.jobs["duckstation"])
        assertTrue(ops.launch("duckstation"))

        ops.uninstall("duckstation")
        // Gone as Fuse's install; the copy on PATH may still be listed as found.
        eventually("removed") { true.takeIf { ops.state.value.installed["duckstation"]?.record == null } }
        assertFalse(placed.exists())
    }

    @Test
    fun edenComesFromItsOwnServerInItsBuildForThisComputer(): Unit = runBlocking {
        val (services, installer) = services()
        installer.handheld = "steamdeck"
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        ops.install("eden")
        val installed = eventually("installed") { ops.state.value.installed["eden"]?.takeIf { it.record != null } }
        assertEquals("v0.2.1", installed.versionName)
        // A Steam Deck takes Eden's build for the Deck.
        assertTrue(File(installer.apps, "Eden-Linux-v0.2.1-steamdeck-clang-pgo.AppImage").isFile)
    }

    @Test
    fun aProgramWithNoBuildOfItsOwnComesFromFlathub(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        ops.install("mame")
        eventually("flatpak") { installer.flatpaks.takeIf { "org.mamedev.MAME" in it } }
        eventually("done") { true.takeIf { ops.state.value.jobs["mame"] == null } }
        Unit
    }

    @Test
    fun anAppAddedByItsAddressMustHaveABuildForThisComputer(): Unit = runBlocking {
        val (services, _) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        assertNotNull(ops.addCustom("not a link"))
        val why = assertNotNull(ops.addCustom("github.com/someone/library"))
        assertTrue("AppImage" in why, why)
        assertNull(ops.addCustom("https://github.com/someone/handy/"))
        val added = eventually("added") { ops.state.value.catalogue?.apps?.firstOrNull { it.custom } }
        assertEquals("handy", added.name)
        assertEquals(listOf(AppStoreOps.OTHER), added.categories)
        assertTrue(ops.state.value.catalogue!!.categories.any { it.name == AppStoreOps.OTHER })
        assertNotNull(ops.addCustom("https://github.com/someone/handy"), "added once only")
        ops.removeCustom(added.key)
        assertTrue(ops.state.value.catalogue!!.apps.none { it.custom })
    }

    /**
     * From a Forgejo or Gitea link: what would install is shown first (the release, the file, the
     * room), nothing is downloaded until it is installed, and then it installs like any other.
     */
    @Test
    fun anAppFromAForgejoLinkShowsWhatWouldInstallFirst(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        assertTrue(ops.previewCustom("http://codeberg.org/someone/tool").isFailure, "plain http is refused")
        assertTrue(ops.previewCustom("https://git.eden-emu.dev/eden-emu/eden").exceptionOrNull()?.message.orEmpty().contains("already"))

        val p = ops.previewCustom("https://codeberg.org/someone/tool/releases").getOrThrow()
        assertEquals("tool", p.name)
        assertEquals("codeberg.org/someone/tool", p.source)
        assertEquals("v1.4", p.version)
        assertEquals("Tool-1.4-x86_64.AppImage", p.file.name)
        assertEquals("binary of Tool-1.4-x86_64.AppImage".length.toLong(), p.file.sizeBytes, "the size its release says, before any download")
        assertTrue(p.room.orEmpty().startsWith("Needs about"), p.room)
        assertTrue(ops.state.value.catalogue!!.apps.none { it.custom }, "not added by looking")
        val text = io.github.matiyaaa.fuse.ui.shell.addons.previewText(p)
        assertTrue("From codeberg.org/someone/tool" in text && "1.4" in text && "Tool-1.4-x86_64.AppImage" in text, text)

        assertNull(ops.addCustom(p.url, "Tools"))
        val added = eventually("added") { ops.state.value.catalogue?.apps?.firstOrNull { it.custom } }
        assertEquals("https://codeberg.org/someone/tool", added.sourceUrl)
        ops.install(added.key)
        eventually("installed") { ops.state.value.installed[added.key]?.takeIf { it.record != null } }
        assertEquals("binary of Tool-1.4-x86_64.AppImage", File(installer.apps, "Tool-1.4-x86_64.AppImage").readText())
        ops.removeCustom(added.key)
        assertTrue(ops.state.value.catalogue!!.apps.none { it.custom })
    }

    /**
     * DuckStation here came with the system (on the PATH under /usr): the Store never offers to
     * update it with a copy of its own beside it, whatever GitHub has. Leaving an app out of Update
     * All is kept, and a channel is offered only where a project publishes stable releases.
     */
    @Test
    fun aProgramTheSystemInstalledIsNeverDoubledAndChoicesAreKept(): Unit = runBlocking {
        val (services, _) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("found") { ops.state.value.installed["duckstation"] }
        assertEquals(Managed.SYSTEM, ops.state.value.managedBy("duckstation"))
        ops.check("duckstation", force = true)
        eventually("release") { ops.state.value.releases["duckstation"] as? ReleaseCheck.Ready }
        assertTrue(ops.state.value.updates.none { it.key == "duckstation" })

        ops.setExcluded("duckstation", true)
        eventually("left out") { true.takeIf { "duckstation" in ops.state.value.excluded } }
        assertTrue("duckstation" in services.data.settings.current().store.excluded, "kept in settings")
        ops.setExcluded("duckstation", false)
        eventually("back") { true.takeIf { "duckstation" !in ops.state.value.excluded } }

        // DuckStation only publishes rolling pre-releases; PCSX2 has stable ones to choose against.
        assertFalse("duckstation" in ops.state.value.channels)
        assertTrue("pcsx2" in ops.state.value.channels)
    }

    /**
     * A program found on this computer that Fuse didn't install (DuckStation on the PATH here) is
     * removed too: the account's password is asked for when only an administrator can, asked again
     * when it was wrong, and Back gives up without removing anything.
     */
    @Test
    fun aProgramFuseDidntInstallIsRemovedWithThePassword(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("found") { ops.state.value.installed["duckstation"] }

        ops.uninstall("duckstation")
        val ask = eventually("asked") { ops.state.value.password }
        assertEquals("duckstation", ask.key)
        assertFalse(ask.wrong)

        ops.uninstallWith("duckstation", "wrong")
        eventually("asked again") { ops.state.value.password?.takeIf { it.wrong } }

        // Given up: nothing removed, nothing waiting.
        ops.uninstallWith("duckstation", null)
        assertNull(ops.state.value.password)
        assertNull(ops.state.value.jobs["duckstation"])
        assertTrue(installer.removedOthers.isEmpty())

        ops.uninstall("duckstation")
        eventually("asked once more") { ops.state.value.password }
        ops.uninstallWith("duckstation", "right")
        // Gone from the list, and its Uninstalling job cleared just after.
        eventually("removed") { ops.state.value.takeIf { "duckstation" !in it.installed && "duckstation" !in it.jobs } }
        assertEquals(1, installer.removedOthers.size)
    }

    @Test
    fun anUpdateKeepsTheVersionItReplacesToGoBackToAndAFailedOnePutsItBack(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        ops.install("duckstation")
        eventually("installed") { ops.state.value.installed["duckstation"]?.takeIf { it.record != null } }
        val placed = File(installer.apps, "DuckStation-x64.AppImage")
        placed.writeText("the version that worked")

        // A failed update: the version that worked is back in place, and says so.
        installer.failNext = true
        ops.install("duckstation")
        val failed = eventually("failed") { ops.state.value.jobs["duckstation"] as? StoreJob.Failed }
        assertTrue("back in place" in failed.message, failed.message)
        assertEquals("the version that worked", placed.readText())

        // An update that works: the earlier version is kept, and can be gone back to.
        ops.install("duckstation")
        val updated = eventually("kept") { ops.state.value.installed["duckstation"]?.record?.takeIf { it.previousVersion != null } }
        assertEquals("v2.0", updated.previousVersion)
        assertEquals("binary of DuckStation-x64.AppImage", placed.readText())
        ops.rollBack("duckstation")
        // Back to the version that worked; the one it replaced is kept in turn, to come back to.
        val back = eventually("back") {
            ops.state.value.installed["duckstation"]?.record?.takeIf { placed.text() == "the version that worked" && ops.state.value.jobs["duckstation"] == null && it.earlier.size == 1 }
        }
        assertEquals("v2.0", back.earlier.single().version)
        assertEquals("binary of DuckStation-x64.AppImage", File(back.earlier.single().id).readText())
    }

    /**
     * What EmuDeck installed is EmuDeck's to update: never in Update All, never doubled. RetroDECK's
     * own emulators are said, and the room an install needs is checked before a byte is fetched.
     */
    @Test
    fun emuDecksInstallsAreLeftToItAndRoomIsCheckedFirst(): Unit = runBlocking {
        val db = FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath))
        val services = FakeServices(
            db, cache,
            installedEmulators = listOf(
                io.github.matiyaaa.fuse.model.InstalledEmulator(
                    io.github.matiyaaa.fuse.model.EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/home/deck/Applications/DuckStation.AppImage",
                    platforms = setOf(io.github.matiyaaa.fuse.model.PlatformId("psx")), detectedVia = "AppImage", installedBy = "EmuDeck",
                ),
            ),
        )
        services.suites = setOf("EmuDeck", "RetroDECK")
        val installer = FakeInstaller(cache)
        services.desktop = installer
        services.web = { request ->
            when {
                request.url.toString().startsWith("https://api.github.com/repos/stenzek/duckstation/releases") ->
                    respond(release("stenzek/duckstation", "DuckStation-x64.AppImage"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.toString().startsWith("https://api.github.com/repos/PCSX2/pcsx2/releases") -> respond(
                    """[{"tag_name":"v2.4.0","name":"2.4.0","published_at":"2026-09-01T00:00:00Z","html_url":"https://github.com/PCSX2/pcsx2/releases/tag/v2.4.0","draft":false,"prerelease":false,"assets":[""" +
                        """{"name":"pcsx2-v2.4.0-linux-appimage-x64-Qt.AppImage","browser_download_url":"https://github.com/PCSX2/pcsx2/releases/download/v2.4.0/pcsx2-v2.4.0-linux-appimage-x64-Qt.AppImage","size":${3L shl 34}}]}]""",
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> null
            }
        }
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("found") { ops.state.value.installed["duckstation"] }
        assertEquals(Managed.EMUDECK, ops.state.value.managedBy("duckstation"))
        assertEquals(setOf("EmuDeck", "RetroDECK"), ops.state.value.suites)
        ops.check("duckstation", force = true)
        eventually("release") { ops.state.value.releases["duckstation"] as? ReleaseCheck.Ready }
        assertTrue(ops.state.value.updates.none { it.key == "duckstation" }, "EmuDeck updates it")

        // PCSX2's build is bigger than the room there is (16 GB free here): said on its page, and refused before downloading.
        ops.check("pcsx2", force = true)
        val room = eventually("room") { ops.state.value.room["pcsx2"] }
        assertTrue("more than" in room, room)
        val requests = services.requestHosts.size
        ops.install("pcsx2")
        val failed = eventually("refused") { ops.state.value.jobs["pcsx2"] as? StoreJob.Failed }
        assertTrue("free to install" in failed.message, failed.message)
        assertTrue(services.requestHosts.drop(requests).none { it == "github.com" }, "nothing downloaded")
    }

    /**
     * Every version an update replaces is kept (the newest three), and any of them can be gone back
     * to: the one in place is kept in turn, the others stay as they were, and the oldest beyond three
     * is let go from the disk.
     */
    @Test
    fun everyReplacedVersionIsKeptAndAnyOfThemCanBeGoneBackTo(): Unit = runBlocking {
        val (services, installer) = services()
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        ops.open()
        eventually("catalogue") { ops.state.value.catalogue }
        ops.install("duckstation")
        eventually("installed") { ops.state.value.installed["duckstation"]?.takeIf { it.record != null } }
        val placed = File(installer.apps, "DuckStation-x64.AppImage")
        // Four updates over versions told apart by what they hold.
        for (n in 1..4) {
            placed.writeText("build $n")
            ops.install("duckstation")
            eventually("update $n") { ops.state.value.installed["duckstation"]?.record?.takeIf { it.earlier.size == minOf(n, 3) && ops.state.value.jobs["duckstation"] == null && placed.text()?.startsWith("binary") == true } }
        }
        val earlier = ops.state.value.installed.getValue("duckstation").record!!.earlier
        assertEquals(listOf("build 4", "build 3", "build 2"), earlier.map { File(it.id).readText() }, "newest first, three kept")
        assertEquals(1, installer.forgotten.size, "build 1 let go")

        // Straight back to build 2, past the two since.
        ops.rollBackTo("duckstation", earlier[2].id)
        val after = eventually("back") { ops.state.value.installed["duckstation"]?.record?.takeIf { placed.text() == "build 2" && ops.state.value.jobs["duckstation"] == null } }
        assertEquals(listOf("binary of DuckStation-x64.AppImage", "build 4", "build 3"), after.earlier.map { File(it.id).readText() })
    }

    /**
     * Update All takes Fuse's own updates and Flathub's through Flatpak: each is listed before it
     * starts, and what came of them is said once, together, at the end (not one notice an app).
     */
    @Test
    fun updateAllListsEachUpdateFirstAndSaysTheRoundOnce(): Unit = runBlocking {
        val mame = io.github.matiyaaa.fuse.model.InstalledEmulator(
            io.github.matiyaaa.fuse.model.EmulatorId("linux.mame"), "MAME", Host.LINUX, "org.mamedev.MAME", version = "0.288",
            platforms = setOf(io.github.matiyaaa.fuse.model.PlatformId("arcade")), detectedVia = "Flatpak",
        )
        val (services, installer) = services(installed = listOf(mame))
        // DuckStation 1.0, installed by Fuse before: 2.0 is out.
        val old = File(installer.apps, "DuckStation-x64.AppImage").apply { writeText("1.0") }
        services.data.settings.update { s ->
            s.copy(store = s.store.copy(installs = s.store.installs + ("duckstation" to io.github.matiyaaa.fuse.data.settings.StoreInstall(old.absolutePath, "v1.0", installedAt = 1))))
        }
        val store = createFuseStore(services, scope)
        val ops = store.appStore
        val notices = java.util.Collections.synchronizedList(mutableListOf<String>())
        scope.launch { ops.notices.collect { notices += it } }
        ops.open()
        eventually("found") { ops.state.value.takeIf { it.installed["duckstation"]?.record != null && "mame" in it.installed } }
        assertEquals(Managed.FLATPAK, ops.state.value.managedBy("mame"))
        ops.checkInstalled(force = true)
        val all = eventually("updates") { ops.state.value.updateAllList.takeIf { it.size == 2 } }
        assertEquals(listOf("duckstation", "mame"), all.map { it.key })

        val text = io.github.matiyaaa.fuse.ui.shell.addons.updateAllText(ops.state.value, all)
        assertTrue("DuckStation  ·  1.0 to 2.0" in text, text)
        assertTrue("MAME  ·  0.288 to 0.289  ·  with Flatpak" in text, text)

        ops.updateAll()
        val said = eventually("round") { notices.firstOrNull { "are updated" in it } }
        assertEquals("DuckStation and MAME are updated.", said)
        assertEquals(listOf("org.mamedev.MAME"), installer.updatedOthers)
        assertEquals("binary of DuckStation-x64.AppImage", old.readText())
        assertTrue(notices.none { it == "DuckStation is updated." || it == "MAME is up to date." }, notices.toString())
    }
}
