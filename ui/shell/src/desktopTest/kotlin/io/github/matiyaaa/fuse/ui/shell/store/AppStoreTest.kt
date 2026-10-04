package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.StoreVariant
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The Store against a fake Android: the catalogue and releases come from fake web answers in the
 * real formats, downloads land in real files, and the "installer" installs what the file says it
 * is. Everything between (catalogue, cache, releases, downloads, checks, jobs, records) is real.
 */
class AppStoreTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-store").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    /** A fake device: what is installed, whether Fuse may install, and what the installer answers. */
    private class FakeBridge(private val dir: File) : PackageBridge {
        override val abis = listOf("arm64-v8a", "armeabi-v7a")
        override val hasSecondScreen = false
        override val changes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val installedApps = java.util.concurrent.ConcurrentHashMap<String, InstalledPackage>()
        @Volatile var allowed = true
        @Volatile var outcome: PackageOutcome = PackageOutcome.Done
        val installs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val permissionAsked = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun installed(packageName: String) = installedApps[packageName]
        override fun canInstall() = allowed
        override fun requestInstallPermission() { permissionAsked.incrementAndGet() }
        override fun freeBytes(): Long = 1L shl 34

        override suspend fun newDownload(fileName: String): DownloadSink {
            val file = File(dir, "dl-${System.nanoTime()}-$fileName")
            val out = file.outputStream()
            val digest = MessageDigest.getInstance("SHA-256")
            return object : DownloadSink {
                override val path: String = file.absolutePath
                override suspend fun write(bytes: ByteArray, count: Int) { out.write(bytes, 0, count); digest.update(bytes, 0, count) }
                override suspend fun finish(): String { out.close(); return digest.digest().joinToString("") { "%02x".format(it) } }
                override suspend fun discard() { runCatching { out.close() }; file.delete() }
            }
        }

        /** The fake APK's text is "package:versionCode:versionName". */
        override suspend fun inspect(path: String): ArchiveInfo? {
            val parts = File(path).takeIf { it.isFile }?.readText()?.split(':') ?: return null
            if (parts.size != 3) return null
            return ArchiveInfo(parts[0], parts[2], parts[1].toLong())
        }

        override suspend fun install(path: String, onTurn: () -> Unit): PackageOutcome {
            installs += path
            val a = inspect(path) ?: return PackageOutcome.Failed("Not an app")
            val o = outcome
            if (o == PackageOutcome.Done) {
                installedApps[a.packageName] = InstalledPackage(a.packageName, a.versionName, a.versionCode, a.packageName)
                changes.tryEmit(a.packageName)
            }
            return o
        }

        override suspend fun uninstall(packageName: String): PackageOutcome {
            installedApps.remove(packageName)
            changes.tryEmit(packageName)
            return PackageOutcome.Done
        }

        override fun launch(packageName: String) = installedApps.containsKey(packageName)
        override fun iconModel(packageName: String): Any? = null
    }

    private val apps = listOf("alpha", "beta", "gamma", "delta", "epsilon")

    /** A small pack in the real format: five GitHub apps, one only followed. */
    private fun packJson(): String = apps.joinToString(",", prefix = """{"apps":[""", postfix = """],"settings":{"categories":"{\"Emulator\":4292386421,\"Track Only\":4293103815}"}}""") { a ->
        val track = a == "epsilon"
        """{"id":"com.example.$a","url":"https://github.com/example/$a","author":"example","name":"${a.replaceFirstChar { it.uppercase() }}","preferredApkIndex":0,
           "additionalSettings":"{\"about\":\"The $a emulator\",\"trackOnly\":$track,\"fallbackToOlderReleases\":true,\"sortMethodChoice\":\"date\",\"autoApkFilterByArch\":true,\"versionExtractionRegEx\":\"v?(.+)\",\"matchGroupToUse\":\"$1\"}",
           "categories":["${if (track) "Track Only" else "Emulator"}"],"allowIdChange":false,"overrideSource":"GitHub"}"""
    }

    /** What each app's newest APK contains (package:versionCode:versionName); a test can change it. */
    private val apk = java.util.concurrent.ConcurrentHashMap(apps.associate { "$it.apk" to "com.example.$it:12:1.2" })
    private val downloads = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var online = true
    /** Where an APK download redirects first. */
    @Volatile private var apkRedirect: String? = null

    private fun services(): FakeServices {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        services.packages = FakeBridge(cache)
        services.web = web@{ request ->
            val url = request.url.toString()
            if (!online && (request.url.host.endsWith("github.com") || request.url.host.endsWith("githubusercontent.com"))) {
                return@web respond("", HttpStatusCode.ServiceUnavailable)
            }
            when {
                url == "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/latest" ->
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://github.com/RJNY/Obtainium-Emulation-Pack/releases/tag/v7.18.0"))
                url.startsWith("https://github.com/RJNY/Obtainium-Emulation-Pack/releases/download/v7.18.0/") ->
                    respond(packJson(), HttpStatusCode.OK)
                request.url.host == "api.github.com" && url.contains("/repos/example/") -> {
                    val name = request.url.encodedPath.split('/')[3]
                    respond(
                        """[{"tag_name":"v1.2","name":"$name 1.2","published_at":"2026-09-01T00:00:00Z","html_url":"https://github.com/example/$name/releases/tag/v1.2","draft":false,"prerelease":false,"body":"## Fixes\n- Faster","assets":[
                           {"name":"$name.apk","browser_download_url":"https://github.com/example/$name/releases/download/v1.2/$name.apk","size":0}]}]""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.url.host == "github.com" && url.contains("/releases/download/v1.2/") -> {
                    val file = url.substringAfterLast('/')
                    val redirect = apkRedirect
                    if (redirect != null && "hop" !in url) {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, redirect))
                    } else {
                        downloads.incrementAndGet()
                        respond(apk.getValue(file), HttpStatusCode.OK)
                    }
                }
                else -> null
            }
        }
        return services
    }

    private suspend fun <T> eventually(what: String, read: () -> T?): T = withTimeout(20_000) {
        while (true) {
            read()?.let { return@withTimeout it }
            kotlinx.coroutines.delay(25)
        }
        @Suppress("UNREACHABLE_CODE")
        error(what)
    }

    private suspend fun ready(): Pair<FuseStore, FakeBridge> {
        val services = services()
        val store = createFuseStore(services, scope)
        assertTrue(store.appStore.supported)
        store.appStore.chooseVariant(StoreVariant.STANDARD)
        eventually("catalogue") { store.appStore.state.value.catalogue }
        return store to (services.packages as FakeBridge)
    }

    private val alpha = "com.example.alpha@github.com/example/alpha"

    @Test
    fun theCatalogueLoadsAndIsKeptForOffline(): Unit = runBlocking {
        val (store, _) = ready()
        val c = store.appStore.state.value.catalogue!!
        assertEquals(5, c.apps.size)
        assertEquals("v7.18.0", c.packVersion)
        assertEquals(Availability.TRACK_ONLY, c.apps.first { it.id == "com.example.epsilon" }.availability)
        val data = FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath))
        eventually("edition saved") { runBlocking { data.settings.current().store.variant } }
        scope.cancel()

        // Later, offline: the saved catalogue opens, with a note that it couldn't refresh.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        online = false
        val again = createFuseStore(services(), scope)
        val offline = eventually("cached catalogue") { again.appStore.state.value.catalogue }
        assertEquals(5, offline.apps.size)
        again.appStore.refresh()
        eventually("refresh problem") { again.appStore.state.value.refreshProblem }
        assertNotNull(again.appStore.state.value.catalogue)
    }

    @Test
    fun installingGoesThroughTheInstallerAndIsRemembered(): Unit = runBlocking {
        val (store, bridge) = ready()
        val notice = scope.async(start = CoroutineStart.UNDISPATCHED) { store.appStore.notices.first() }
        store.appStore.install(alpha)
        store.appStore.install(alpha) // A second press while it runs does nothing more.
        eventually("installed") { store.appStore.state.value.installed[alpha] }
        assertEquals("Alpha is installed.", withTimeout(5_000) { notice.await() })
        assertEquals(1, bridge.installs.size)
        assertEquals(1, downloads.get())
        val s = eventually("record") { store.appStore.state.value.installed[alpha]?.record?.let { store.appStore.state.value } }
        assertEquals("v1.2".removePrefix("v"), s.installed[alpha]!!.record!!.version?.removePrefix("v"))
        assertEquals(Standing.CURRENT, s.standing(alpha))
        assertNull(s.jobs[alpha])
        // The download is gone once Android has it (deleted just after the install is reported).
        eventually("download deleted") { Unit.takeIf { bridge.installs.none { File(it).exists() } } }
    }

    @Test
    fun anAppInstalledElsewhereShowsItsUpdate(): Unit = runBlocking {
        val (store, bridge) = ready()
        bridge.installedApps["com.example.beta"] = InstalledPackage("com.example.beta", "1.1", 11, "Beta")
        bridge.changes.emit("com.example.beta")
        val beta = "com.example.beta@github.com/example/beta"
        eventually("installed") { store.appStore.state.value.installed[beta] }
        store.appStore.check(beta)
        val s = eventually("update") { store.appStore.state.value.takeIf { it.standing(beta) == Standing.UPDATE } }
        assertEquals(listOf(beta), s.updates.map { it.key })
    }

    @Test
    fun aDownloadThatIsAnotherAppIsNeverInstalled(): Unit = runBlocking {
        val (store, bridge) = ready()
        apk["alpha.apk"] = "com.evil.app:99:6.6"
        store.appStore.install(alpha)
        val failed = eventually("failure") { store.appStore.state.value.jobs[alpha] as? StoreJob.Failed }
        assertTrue("com.evil.app" in failed.message, failed.message)
        assertFalse(failed.retry)
        assertTrue(bridge.installs.isEmpty())
        assertTrue(bridge.installedApps.isEmpty())
    }

    @Test
    fun aRedirectAwayFromHttpsStopsTheDownload(): Unit = runBlocking {
        val (store, bridge) = ready()
        apkRedirect = "http://mirror.example.org/alpha.apk"
        store.appStore.install(alpha)
        val failed = eventually("failure") { store.appStore.state.value.jobs[alpha] as? StoreJob.Failed }
        assertTrue("HTTPS" in failed.message, failed.message)
        assertTrue(bridge.installs.isEmpty())
        assertEquals(0, downloads.get())
    }

    @Test
    fun saying_no_in_Android_leaves_nothing_behind(): Unit = runBlocking {
        val (store, bridge) = ready()
        bridge.outcome = PackageOutcome.Cancelled
        store.appStore.install(alpha)
        eventually("installer asked") { bridge.installs.firstOrNull() }
        eventually("job cleared") { store.appStore.state.value.takeIf { alpha !in it.jobs } }
        assertNull(store.appStore.state.value.installed[alpha])
    }

    @Test
    fun aSignatureConflictOffersUninstallingFirst(): Unit = runBlocking {
        val (store, bridge) = ready()
        bridge.outcome = PackageOutcome.Failed("Conflict", conflict = true)
        store.appStore.install(alpha)
        val failed = eventually("failure") { store.appStore.state.value.jobs[alpha] as? StoreJob.Failed }
        assertTrue(failed.uninstallFirst)
    }

    @Test
    fun withoutPermissionTheInstallWaitsForIt(): Unit = runBlocking {
        val (store, bridge) = ready()
        bridge.allowed = false
        store.appStore.onResume()
        store.appStore.install(alpha)
        eventually("asks") { store.appStore.state.value.jobs[alpha] as? StoreJob.NeedsPermission }
        assertEquals(1, bridge.permissionAsked.get())
        assertEquals(0, downloads.get())
        bridge.allowed = true
        store.appStore.onResume()
        eventually<Any>("installed") { store.appStore.state.value.installed[alpha] }
    }

    @Test
    fun cancellingStopsAQueuedInstall(): Unit = runBlocking {
        val (store, bridge) = ready()
        bridge.allowed = false
        store.appStore.onResume()
        store.appStore.install(alpha)
        eventually("waiting") { store.appStore.state.value.jobs[alpha] as? StoreJob.NeedsPermission }
        store.appStore.cancel(alpha)
        assertNull(store.appStore.state.value.jobs[alpha])
        bridge.allowed = true
        store.appStore.onResume()
        kotlinx.coroutines.delay(300)
        assertTrue(bridge.installs.isEmpty())
    }

    @Test
    fun uninstallingForgetsWhatFuseInstalled(): Unit = runBlocking {
        val (store, _) = ready()
        store.appStore.install(alpha)
        eventually("installed") { store.appStore.state.value.installed[alpha]?.record }
        store.appStore.uninstall(alpha)
        eventually<Any>("gone") { store.appStore.state.value.takeIf { alpha !in it.installed && alpha !in it.jobs } }
    }

    @Test
    fun trackOnlyAppsHaveNothingToInstall(): Unit = runBlocking {
        val (store, _) = ready()
        val epsilon = "com.example.epsilon@github.com/example/epsilon"
        store.appStore.check(epsilon)
        val ready = eventually("release") { store.appStore.state.value.releases[epsilon] as? ReleaseCheck.Ready }
        assertNull(ready.release.file)
        assertEquals("v1.2", ready.release.version?.let { if (it.startsWith("v")) it else "v$it" })
    }

    @Test
    fun withoutABridgeThereIsNoStore(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "plain.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        assertIs<AppStoreOps.None>(store.appStore)
        assertFalse(store.appStore.supported)
    }

    @Test
    fun anAppAddedByItsAddressJoinsOtherAndInstalls(): Unit = runBlocking {
        apk["zeta.apk"] = "com.example.zeta:3:0.3"
        val (store, bridge) = ready()
        val ops = store.appStore
        assertNotNull(ops.addCustom("not a link"))
        assertNull(ops.addCustom("github.com/example/zeta"))
        val zeta = eventually("added") { ops.state.value.catalogue?.apps?.firstOrNull { it.custom } }
        assertEquals("zeta", zeta.name)
        assertEquals(listOf(AppStoreOps.OTHER), zeta.categories)
        assertTrue(ops.state.value.catalogue!!.categories.any { it.name == AppStoreOps.OTHER })
        assertNotNull(ops.addCustom("https://github.com/example/zeta"), "added once only")
        ops.install(zeta.key)
        eventually("installed") { ops.state.value.installed[zeta.key] }
        assertTrue(bridge.installedApps.containsKey("com.example.zeta"))
        ops.removeCustom(zeta.key)
        assertTrue(ops.state.value.catalogue!!.apps.none { it.custom })
    }
}
