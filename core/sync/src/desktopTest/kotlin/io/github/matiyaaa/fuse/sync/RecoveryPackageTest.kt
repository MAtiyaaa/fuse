package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The household in one sealed file: read back only with its password, every change to it found, and
 * put back on a new host that devices take as the household's from then on.
 */
class RecoveryPackageTest {
    private lateinit var root: File
    private var now = 1_800_000_000_000L

    @BeforeTest fun setUp() { root = Files.createTempDirectory("recovery").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun household(): Pair<HostStore, String> {
        val store = HostStore(File(root, "old"), clock = { now }, hostName = "Old PC")
        val mo = store.createProfile(NewProfile("Mo", "fox")).id
        repeat(3) { i ->
            // Bigger than a sealed piece, so the package has many.
            val h = store.content.put(ByteArray(700_000) { (it * 31 + i).toByte() })
            val r = SaveRevision("rev-000$i-x", mo, GameKey.of("snes", null, null, "Zelda").id, SaveKind.SAVE, if (i == 0) null else "rev-000${i - 1}-x", "d", "Deck", Hlc(now + i, 0, "d"), SaveManifest("f$i", listOf(SaveFile("z.srm", h, 700_000))))
            assertTrue(store.push(mo, "d", r).accepted)
        }
        store.addDevice("dev-12345678", "Deck", "LINUX")
        return store to mo
    }

    @Test
    fun itOpensOnlyWithItsPasswordAndHoldsTheWholeHousehold() {
        val (store, _) = household()
        val file = File(root, "home.fuserecovery")
        val m = RecoveryPackage.write(store, file, "correct horse", clock = { now })
        assertEquals(1, m.profiles)
        assertEquals(3, m.objects.size)
        assertEquals("Old PC" to now, RecoveryPackage.peek(file))
        assertFailsWith<RecoveryPackage.WrongPassword> { RecoveryPackage.open(file, "wrong horse", File(root, "o1")) }
        val opened = RecoveryPackage.open(file, "correct horse", File(root, "o2"))
        assertEquals(store.replicaFiles().keys, opened.files.keys)
        assertEquals(store.identity.hostId, opened.manifest.hostId)
        assertTrue(m.objects.all { File(root, "o2/$it").isFile })
        // Nothing a password protects is in it as plain text.
        assertTrue("dev-12345678" !in file.readBytes().decodeToString(), "the package is sealed")
        assertFailsWith<IllegalArgumentException> { RecoveryPackage.write(store, File(root, "short.fuserecovery"), "short") }
    }

    @Test
    fun aChangedOrCutOffPackageIsFound() {
        val (store, _) = household()
        val file = File(root, "home.fuserecovery")
        RecoveryPackage.write(store, file, "correct horse", clock = { now })
        // One byte changed well inside: a piece that won't open.
        val changed = File(root, "changed.fuserecovery").also { file.copyTo(it) }
        RandomAccessFile(changed, "rw").use { f -> f.seek(f.length() / 2); val b = f.read(); f.seek(f.length() / 2); f.write(b xor 0xFF) }
        assertFailsWith<RecoveryPackage.Damaged> { RecoveryPackage.open(changed, "correct horse", File(root, "o3")) }
        // Its last piece cut off: the package is known to be incomplete.
        val cut = File(root, "cut.fuserecovery").also { it.writeBytes(file.readBytes().copyOf((file.length() - 100).toInt())) }
        assertFailsWith<RecoveryPackage.Damaged> { RecoveryPackage.open(cut, "correct horse", File(root, "o4")) }
        assertFailsWith<RecoveryPackage.Damaged> { RecoveryPackage.open(File(root, "not.txt").apply { writeText("hello") }, "x", File(root, "o5")) }
    }

    @Test
    fun aNewHostTakesTheHouseholdOverFromAPackage(): Unit = runBlocking {
        val (old, mo) = household()
        val file = File(root, "home.fuserecovery")
        val made = RecoveryPackage.write(old, file, "correct horse", clock = { now })
        val fresh = HostStore(File(root, "new"), clock = { now }, hostName = "New PC")
        val host = SyncHost(fresh, port = 0, bind = "127.0.0.1", clock = { now })
        host.startRecovery(RecoveryJob.RESTORE, file, "correct horse")
        var job = host.recoveryJob()!!
        while (job.running) { kotlinx.coroutines.delay(50); job = host.recoveryJob()!! }
        assertEquals(RecoveryJob.DONE, job.state, job.message)
        assertEquals(1, job.profiles)
        // The same household: its id, people, saves with their files, and devices (which keep their keys).
        assertEquals(old.identity.hostId, fresh.identity.hostId)
        assertEquals(old.revisions(mo).map { it.id }, fresh.revisions(mo).map { it.id })
        assertTrue(made.objects.all { fresh.content.has(it) })
        assertTrue(fresh.device("dev-12345678") != null)
        // A newer generation than the package knew: devices take this host, and the old one is fenced should it come back.
        assertTrue(fresh.leadership.current.generation > made.generation)
        assertTrue(fresh.leadership.isLeader)
        // A wrong password changes nothing.
        host.startRecovery(RecoveryJob.RESTORE, file, "wrong horse")
        job = host.recoveryJob()!!
        while (job.running) { kotlinx.coroutines.delay(50); job = host.recoveryJob()!! }
        assertEquals(RecoveryJob.FAILED, job.state)
        assertTrue("password" in job.message!!)
        assertEquals(old.revisions(mo).size, fresh.revisions(mo).size)
    }

    @Test
    fun aDevicePairedWithTheOldHostFollowsTheNewOneToItsNewAddress(): Unit = runBlocking {
        val http = SyncClient.defaultClient()
        val old = HostStore(File(root, "old"), hostName = "Old PC")
        val oldHost = SyncHost(old, port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        val oldAddress = "127.0.0.1:${oldHost.boundPort()}"
        val link = SyncClient.pair(oldAddress, oldHost.newPairingCode(), "dev-12345678", "Deck", "LINUX", http)
        val device = SyncClient(link, http)
        assertEquals("Old PC", device.status().hello.name)
        val file = File(root, "home.fuserecovery")
        RecoveryPackage.write(old, file, "correct horse")
        oldHost.stop()

        // The new computer: set up as a host, then the household restored into it.
        val newHost = SyncHost(HostStore(File(root, "new"), hostName = "New PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        newHost.startRecovery(RecoveryJob.RESTORE, file, "correct horse")
        while (newHost.recoveryJob()!!.running) kotlinx.coroutines.delay(50)
        assertEquals(RecoveryJob.DONE, newHost.recoveryJob()!!.state)
        val newAddress = "127.0.0.1:${newHost.boundPort()}"
        try {
            // Asked on the home network, the household's host answers from its new address, and
            // the device carries on with the link it had: same household, same device key.
            val found = listOf(FoundHost(SyncClient.hello(newAddress, http)!!, newAddress))
            val stranger = listOf(FoundHost(SyncClient.hello(newAddress, http)!!.copy(hostId = "someone-else"), newAddress))
            assertFalse(device.rediscover { stranger }, "another household's host is never followed")
            assertFalse(device.rediscover { found }, "asked at most every few minutes")
            val fresh = SyncClient(device.link, http)
            assertTrue(fresh.rediscover { found })
            assertEquals(newAddress, fresh.link.localAddress!!.removePrefix("http://"))
            assertEquals(old.identity.hostId, fresh.status().hello.hostId)
            assertTrue(fresh.link.generation >= 1)
        } finally {
            newHost.stop()
        }
    }
}
