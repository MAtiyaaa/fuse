package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.launch.ResolvedLaunch
import io.github.matiyaaa.fuse.library.FsAccessException
import io.github.matiyaaa.fuse.library.FsEntry
import io.github.matiyaaa.fuse.library.FuseFileSystem
import io.github.matiyaaa.fuse.model.CartridgeRoute
import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.model.ScanPhase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Test doubles for [FuseServices]: real files and database, fake launcher and system. */
internal class FakeServices(override val data: FuseData, private val cache: File) : FuseServices {
    val launched = mutableListOf<ResolvedLaunch>()
    var exit: CompletableDeferred<Unit>? = null

    override val host = Host.LINUX
    override val appVersion = "0.0.1"
    override val fs: FuseFileSystem = JavaFileSystem()
    override val secrets: SecretStore = MemorySecrets()
    override val http = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
    override val cacheDir: String = cache.absolutePath

    override val emulators = object : EmulatorDetector {
        override suspend fun detect() = listOf(
            InstalledEmulator(EmulatorId("linux.mgba"), "mGBA", Host.LINUX, "/usr/bin/mgba-qt", platforms = setOf(PlatformId("gba")), detectedVia = "PATH"),
            InstalledEmulator(EmulatorId("linux.duckstation"), "DuckStation", Host.LINUX, "/usr/bin/duckstation-qt", platforms = setOf(PlatformId("psx")), detectedVia = "PATH"),
        )
    }

    override val launcher = object : GameLauncher {
        override suspend fun run(launch: ResolvedLaunch, displayId: Int?): RunResult {
            launched += launch
            val waiter = exit
            return RunResult.Started(awaitExit = waiter?.let { w -> { w.await() } })
        }

        override suspend fun openApp(appId: String): RunResult = RunResult.Started()
    }

    override val cartridge = object : CartridgeBridge {
        override suspend fun read() = CartridgeStatus(installed = false)
        override fun open(route: CartridgeRoute, link: String) = false
    }

    override val installer = object : ReleaseInstaller {
        override val platform = ReleasePlatform.LINUX_X86_64
        override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit) = Result.failure<Unit>(UnsupportedOperationException())
    }

    override val apps: AppsProvider? = null

    override val locations = object : DeviceLocations {
        override suspend fun libraryCandidates() = emptyList<LocationHint>()
        override suspend fun biosRoots() = emptyList<String>()
    }

    override fun writeCacheFile(relativePath: String, content: String): String? {
        require(!relativePath.contains("..")) { "Cache paths never leave the cache" }
        val file = File(cache, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file.absolutePath
    }

    override fun utcOffsetMillis() = 0L
}

internal class MemorySecrets : SecretStore {
    private val values = HashMap<String, String>()
    override suspend fun get(key: String) = values[key]
    override suspend fun put(key: String, value: String) { values[key] = value }
    override suspend fun remove(key: String) { values.remove(key) }
}

internal class JavaFileSystem : FuseFileSystem {
    override suspend fun list(path: String): List<FsEntry> {
        val dir = File(path)
        val children = dir.listFiles() ?: throw FsAccessException(path, "Unreadable")
        return children.map { FsEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified()) }
    }

    override suspend fun stat(path: String): FsEntry? = File(path).takeIf { it.exists() }?.let {
        FsEntry(it.name, it.absolutePath, it.isDirectory, it.length(), it.lastModified())
    }

    override suspend fun readText(path: String, maxBytes: Int): String? = File(path).takeIf { it.isFile }?.let {
        it.inputStream().use { s -> String(s.readNBytes(maxBytes)) }
    }

    override suspend fun md5(path: String): String? = null

    override suspend fun canonical(path: String): String? = File(path).canonicalPath
}
