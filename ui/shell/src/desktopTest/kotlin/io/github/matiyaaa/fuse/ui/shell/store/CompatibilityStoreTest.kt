package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** RPCS3's compatibility list, asked only when the user asks and only with the title id. */
class CompatibilityStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-compat").toFile()
        cache = Files.createTempDirectory("fuse-compat-cache").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "ps3").mkdirs()
        File(root, "ps3/Demon's Souls [BLUS30443].iso").writeBytes(ByteArray(64))
        File(root, "ps3/Unknown Racer [BLUS99999].iso").writeBytes(ByteArray(64))
        File(root, "ps3/No Id At All.iso").writeBytes(ByteArray(64))
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
        cache.deleteRecursively()
    }

    @Test
    fun answersComeFromTheExactTitleIdAndAreKept(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache)
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 3 } }.associateBy { it.title }
        services.requestHosts.clear()

        val listed = assertIs<CompatibilityAnswer.Listed>(store.library.rpcs3Compatibility(games.getValue("Demon's Souls").id))
        assertEquals(Rpcs3Status.PLAYABLE, listed.entry.status)
        assertEquals(1, services.requestHosts.count { it == "rpcs3.net" })
        // Asked again: answered from what Fuse kept.
        assertIs<CompatibilityAnswer.Listed>(store.library.rpcs3Compatibility(games.getValue("Demon's Souls").id))
        assertEquals(1, services.requestHosts.count { it == "rpcs3.net" })

        assertEquals(CompatibilityAnswer.NotListed("BLUS99999"), store.library.rpcs3Compatibility(games.getValue("Unknown Racer").id))
        // Without a title id nothing is asked.
        assertEquals(CompatibilityAnswer.NoTitleId, store.library.rpcs3Compatibility(games.getValue("No Id At All").id))
        assertEquals(2, services.requestHosts.count { it == "rpcs3.net" })
    }
}
