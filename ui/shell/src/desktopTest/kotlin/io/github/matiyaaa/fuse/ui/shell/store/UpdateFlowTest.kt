package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.github.ReleasePlatform
import io.github.matiyaaa.fuse.model.ReleaseAsset
import io.github.matiyaaa.fuse.model.ReleaseInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Updates download in the background with progress, then install only when asked. */
class UpdateFlowTest {
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    private val release = ReleaseInfo(
        tag = "v9.9.9", name = "Fuse 9.9.9", notes = "", publishedAt = null, htmlUrl = "",
        assets = listOf(ReleaseAsset("Fuse-9.9.9-x86_64.AppImage", "https://example.invalid/fuse", 100, null)),
    )

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        cache.deleteRecursively()
    }

    private class StepInstaller : ReleaseInstaller {
        override val platform = ReleasePlatform.LINUX_X86_64
        val gate = CompletableDeferred<Unit>()
        var downloads = 0
        var applied: String? = null
        override suspend fun install(asset: ReleaseAsset, onProgress: (Float) -> Unit) = Result.success(Unit)
        override suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): Result<String> {
            downloads++
            onProgress(0.5f)
            gate.await()
            return Result.success("/tmp/fuse-new.AppImage")
        }
        override suspend fun applyUpdate(downloaded: String): Result<Boolean> {
            applied = downloaded
            return Result.success(true)
        }
    }

    @Test
    fun downloadsWithProgressThenAppliesOnRequest() = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "u.db").absolutePath)), cache)
        val installer = StepInstaller()
        services.installer = installer
        val store = createFuseStore(services, scope)

        store.updates.download(release)
        val downloading = withTimeout(5_000) { store.updates.state.first { it is UpdateState.Downloading && it.progress == 0.5f } }
        assertIs<UpdateState.Downloading>(downloading)
        // A second tap while it runs doesn't start another download.
        store.updates.download(release)
        installer.gate.complete(Unit)
        val ready = withTimeout(5_000) { store.updates.state.first { it is UpdateState.Ready } }
        assertEquals("/tmp/fuse-new.AppImage", (ready as UpdateState.Ready).file)
        assertEquals(1, installer.downloads)
        assertEquals(null, installer.applied)

        val result = store.updates.apply()
        assertTrue(result.getOrThrow())
        assertEquals("/tmp/fuse-new.AppImage", installer.applied)
    }
}
