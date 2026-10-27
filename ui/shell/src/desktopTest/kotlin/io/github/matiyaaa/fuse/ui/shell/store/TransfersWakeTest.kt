package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferHandler
import io.github.matiyaaa.fuse.transfer.TransferInterrupted
import io.github.matiyaaa.fuse.transfer.TransferIo
import io.github.matiyaaa.fuse.transfer.TransferItem
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.WaitReason
import io.github.matiyaaa.fuse.ui.shell.store.impl.DefaultTransfersOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A download the connection dropped as the device went to sleep: Fuse stays running while it waits
 * to try again (letting go, the device slept through every try), and the screen coming back sends it
 * on at once instead of after its wait.
 */
class TransfersWakeTest {
    private val root = Files.createTempDirectory("fuse-wake").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    /** Fails with a dropped connection until [connected], then finishes. */
    private class Flaky : TransferHandler {
        override val source = "flaky"
        @Volatile var connected = false
        val runs = AtomicInteger()
        override suspend fun run(item: TransferItem, io: TransferIo) {
            runs.incrementAndGet()
            io.progress(0, 10)
            if (!connected) throw TransferInterrupted("The connection dropped.")
            io.progress(10, 10)
        }
    }

    @Test
    fun aDownloadTheNetworkDroppedKeepsFuseRunningAndGoesOnWhenTheScreenComesBack(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(root, "fuse.db").absolutePath)), File(root, "cache").apply { mkdirs() })
        val store = createFuseStore(services, scope)
        val engine = (store.transfers as DefaultTransfersOps).engine
        val flaky = Flaky()
        engine.register(flaky)
        val id = engine.enqueue(
            TransferItem(id = "", key = "flaky:1", source = "flaky", direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME, title = "A game", totalBytes = 10),
        )
        withTimeout(10_000) { engine.items.first { l -> l.any { it.id == id && it.status == TransferStatus.WAITING && it.waiting == WaitReason.NETWORK } } }
        // Waiting to try again is still on its way: Fuse stays running, and says what it waits for.
        val kept = withTimeout(5_000) { services.keptAlive.first { it != null && "waiting for the connection" in it } }
        assertTrue(kept!!.isNotBlank())
        delay(500)
        assertEquals("1 waiting for the connection", services.keptAlive.value?.lowercase())

        // The device sleeps, and the connection is back when it wakes.
        services.interactive.value = false
        flaky.connected = true
        delay(300)
        val woke = System.currentTimeMillis()
        services.interactive.value = true
        withTimeout(10_000) { engine.items.first { l -> l.any { it.id == id && it.status == TransferStatus.DONE } } }
        assertTrue(System.currentTimeMillis() - woke < 4_000, "it went on when the screen came back, not after its wait")
        // Nothing left to move: Fuse lets the device go again.
        withTimeout(5_000) { services.keptAlive.first { it == null } }
    }
}
