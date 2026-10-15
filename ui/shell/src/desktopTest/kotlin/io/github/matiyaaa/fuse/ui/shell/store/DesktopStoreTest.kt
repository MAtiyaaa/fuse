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

        override suspend fun keepPrevious(name: String, previous: String): String? {
            val to = File(kept, File(previous).name)
            return if (File(previous).renameTo(to)) to.absolutePath else null
        }

        override suspend fun restorePrevious(kept: String, current: String?): String {
            current?.let { File(it).delete() }
            val back = File(apps, File(kept).name)
            check(File(kept).renameTo(back))
            return back.absolutePath
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

    private fun services(): Pair<FakeServices, FakeInstaller> {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
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
                url.startsWith("https://api.github.com/repos/someone/library/releases") ->
                    respond(release("someone/library", "library-src.tar.gz", "library_2.0_amd64.deb"), HttpStatusCode.OK, json)
                request.url.host == "github.com" && "/releases/download/" in url -> respond("binary of ${url.substringAfterLast('/')}", HttpStatusCode.OK)
                else -> null
            }
        }
        return services to installer
    }

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
        val back = eventually("back") { true.takeIf { ops.state.value.installed["duckstation"]?.record?.previousVersion == null && placed.readText() == "the version that worked" } }
        assertTrue(back)
    }
}
