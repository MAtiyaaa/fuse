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

        override suspend fun install(name: String, file: String, fileName: String, kind: DesktopAssetKind, previous: String?): String {
            assertEquals(DesktopAssetKind.APPIMAGE, kind)
            val target = File(apps, fileName)
            File(file).copyTo(target, overwrite = true)
            return target.absolutePath
        }

        override suspend fun remove(path: String): Boolean = File(path).delete()
        override suspend fun exists(path: String): Boolean = File(path).exists()
        override fun launch(path: String): Boolean = File(path).exists()
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
        assertEquals(Availability.MANUAL, catalogue.apps.single { it.key == "dolphin" }.availability, "builds on its own site are only linked")

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
}
