package io.github.matiyaaa.fuse.sync

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Backup hosts as Fuse sets them up: a computer of the household offers itself in Settings, the
 * host accepts it, its copy follows the host, and when the host is gone a person makes it the host;
 * devices follow, and the choice turned off takes the copy with it.
 */
class BackupServiceTest {
    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private class Library : ProfileDataPort {
        override suspend fun read(device: String, clock: HlcClock): ProfileMeta = ProfileMeta()
        override suspend fun write(meta: ProfileMeta) = Unit
        override suspend fun keyOf(gameId: Long): GameKey? = null
    }

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest fun setUp() { root = Files.createTempDirectory("backup-svc").toFile() }

    @AfterTest fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private class Computer(val svc: JvmSyncService, val settings: SettingsStore, val secrets: Secrets)

    private fun computer(name: String, port: Int): Computer {
        val settings = SettingsStore(DesktopDatabase.open(null))
        runBlocking { settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) } }
        val secrets = Secrets()
        val svc = JvmSyncService(File(root, name), settings, secrets, Library(), "LINUX", name, "test", NoHostLifetime("test"), scope, liveLookMs = 300)
        return Computer(svc, settings, secrets)
    }

    @Test
    fun aComputerOffersTheHostAcceptsAndAPersonMakesItTheHost(): Unit = runBlocking {
        val hostPort = freePort()
        val pc = computer("Gaming PC", hostPort)
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        val desk = computer("Desk PC", freePort())
        desk.svc.connect("127.0.0.1:$hostPort", code).getOrThrow()
        val deskId = desk.settings.current().sync.deviceId

        // The desk offers itself; it waits for the host.
        desk.svc.setBackupHost(true).getOrThrow()
        assertTrue(desk.svc.hosts.value?.waiting == true)
        assertTrue(desk.settings.current().sync.backupHost)
        pc.svc.keepOnce()
        val offer = withTimeout(10_000) { pc.svc.hosts.first { it?.offers?.isNotEmpty() == true } }!!.offers.single()
        assertEquals("Desk PC", offer.name)
        assertEquals(deskId, offer.deviceId)

        // Accepted on the host: the desk copies the household and waits as a backup.
        pc.svc.acceptBackup(deskId).getOrThrow()
        desk.svc.keepOnce()
        val view = assertNotNull(desk.svc.hosts.value)
        assertEquals(Leadership.BACKUP, view.thisRole)
        assertFalse(view.waiting)
        assertEquals(2, view.hosts.size)
        assertTrue(view.copiedAt > 0)
        assertTrue(File(root, "Desk PC/${JvmSyncService.BACKUP_DIR}/host.json").isFile)

        // The host is gone: a person makes the desk the host.
        pc.svc.stop()
        desk.svc.takeOverAsHost().getOrThrow()
        assertEquals("HOST", desk.settings.current().sync.role)
        assertEquals(Leadership.PRIMARY, desk.svc.hosts.value?.thisRole)
        assertEquals(1, desk.svc.hosts.value?.generation)
        // Its own device follows it.
        assertTrue(desk.svc.status.value !is SyncStatus.NotSetUp)
        desk.svc.stop()
    }

    @Test
    fun turningItOffTakesTheCopyWithIt(): Unit = runBlocking {
        val hostPort = freePort()
        val pc = computer("Gaming PC", hostPort)
        val code = assertNotNull(pc.svc.hostHere("Gaming PC", installService = false).getOrThrow().pairingCode)
        val desk = computer("Desk PC", freePort())
        desk.svc.connect("127.0.0.1:$hostPort", code).getOrThrow()
        val deskId = desk.settings.current().sync.deviceId
        desk.svc.setBackupHost(true).getOrThrow()
        pc.svc.acceptBackup(deskId).getOrThrow()
        desk.svc.keepOnce()
        assertTrue(File(root, "Desk PC/${JvmSyncService.BACKUP_DIR}/devices.json").isFile)
        desk.svc.setBackupHost(false).getOrThrow()
        assertFalse(File(root, "Desk PC/${JvmSyncService.BACKUP_DIR}/devices.json").exists(), "the copy and its secrets go")
        pc.svc.keepOnce()
        assertEquals(1, pc.svc.hosts.value?.hosts?.size, "the host forgets the backup")
        // The host can't be a backup of itself.
        assertTrue(pc.svc.setBackupHost(true).isFailure)
        pc.svc.stop()
        desk.svc.stop()
    }
}
