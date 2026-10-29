package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.transfer.TransferDirection
import io.github.matiyaaa.fuse.transfer.TransferHandler
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Downloads kept for later: they wait for a charger and Wi-Fi without keeping the device awake,
 * a device that hasn't read its battery yet is never taken for one without, and Download Now leads.
 */
class TransfersLaterTest {
    private val root = Files.createTempDirectory("fuse-later").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        root.deleteRecursively()
    }

    private class Quick : TransferHandler {
        override val source = "quick"
        val runs = AtomicInteger()
        override suspend fun run(item: TransferItem, io: TransferIo) {
            runs.incrementAndGet()
            io.progress(10, 10)
        }
    }

    @Test
    fun aDownloadForLaterWaitsForTheChargerWithoutKeepingTheDeviceAwake(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(root, "fuse.db").absolutePath)), File(root, "cache").apply { mkdirs() })
        val store = createFuseStore(services, scope)
        val ops = store.transfers as DefaultTransfersOps
        val engine = ops.engine
        val quick = Quick()
        engine.register(quick)
        // The first look at the power says nothing of a battery: it isn't believed yet.
        ops.power(null, false)
        val id = engine.enqueue(
            TransferItem(id = "", key = "quick:1", source = "quick", direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME, title = "A game", totalBytes = 10, later = true),
        )
        withTimeout(5_000) { engine.items.first { l -> l.any { it.id == id && it.waiting == WaitReason.LATER } } }
        // The real reading: on its battery.
        ops.power(64, false)
        delay(300)
        assertEquals(0, quick.runs.get())
        assertNull(services.keptAlive.value, "waiting for later never keeps the device awake")
        val row = ops.rows.value.single { it.item.id == id }
        assertEquals(TransferAction.NOW, row.actions.first(), "sent now is what confirming it does")
        assertEquals(1, ops.summary.value.later)

        // Plugged in: it goes by itself.
        ops.power(64, true)
        withTimeout(10_000) { engine.items.first { l -> l.any { it.id == id && it.status == TransferStatus.DONE } } }
        assertEquals(1, quick.runs.get())
    }

    @Test
    fun aComputerWithoutABatteryIsBelievedAfterAMomentAndAnyDownloadCanBeKeptForLater(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(root, "fuse.db").absolutePath)), File(root, "cache").apply { mkdirs() })
        val store = createFuseStore(services, scope)
        val ops = store.transfers as DefaultTransfersOps
        val engine = ops.engine
        ops.power(null, false)
        // No handler yet: it stays in the queue, so its actions can be looked at.
        val id = engine.enqueue(TransferItem(id = "", key = "quick:2", source = "quick", direction = TransferDirection.DOWNLOAD, kind = TransferKind.GAME, title = "B game", totalBytes = 10))
        val queued = withTimeout(5_000) { ops.rows.first { r -> r.any { it.item.id == id } } }.single { it.item.id == id }
        assertTrue(TransferAction.LATER in queued.actions)
        ops.act(id, TransferAction.LATER)
        withTimeout(5_000) { engine.items.first { l -> l.any { it.id == id && it.later && it.waiting == WaitReason.LATER } } }
        val quick = Quick()
        engine.register(quick)
        // Nothing else came: a computer on its power, so it goes.
        withTimeout(15_000) { engine.items.first { l -> l.any { it.id == id && it.status == TransferStatus.DONE } } }
        assertEquals(1, quick.runs.get())
    }
}
