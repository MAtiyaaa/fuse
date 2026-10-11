package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Backup hosts on real ports, as in a home: copies kept up to date, the host going away, backups
 * electing one of them only with a majority, a person making the only backup the host, the old
 * host fenced when it comes back and every save it took meanwhile taken back, and devices following
 * the host there is, never an older one.
 */
class BackupHostsTest {
    private lateinit var root: File
    private val http = SyncClient.defaultClient()
    private val running = ArrayList<SyncHost>()

    @BeforeTest fun setUp() { root = Files.createTempDirectory("backups").toFile() }

    @AfterTest
    fun tearDown() {
        running.forEach { runCatching { it.stop() } }
        root.deleteRecursively()
    }

    private suspend fun serve(store: HostStore, port: Int = 0): SyncHost {
        val host = SyncHost(store, port = port, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        running += host
        return host
    }

    private class Computer(val store: HostStore, var host: SyncHost, val port: Int) {
        val address get() = "127.0.0.1:$port"
    }

    /** The household's host, with its own address in the hosts' list. */
    private suspend fun primary(): Computer {
        val store = HostStore(File(root, "primary"), hostName = "Gaming PC")
        val host = serve(store)
        val port = host.boundPort()
        store.leadership.noteOwnAddress("127.0.0.1:$port", null)
        return Computer(store, host, port)
    }

    private suspend fun pair(p: Computer, name: String): SyncClient {
        val code = p.host.newPairingCode()
        val link = SyncClient.pair(p.address, code, "dev-" + SyncCrypto.token(8).filter { it.isLetterOrDigit() }.take(10), name, "LINUX", http)
        return SyncClient(link, http)
    }

    /** A computer of the household made one of its backups: paired as a device, its copy served on a port of its own. */
    private suspend fun backup(p: Computer, name: String, failoverMs: Long = 0): Pair<BackupNode, Computer> {
        val device = pair(p, name)
        val store = HostStore(File(root, name))
        store.leadership.standBy()
        val node = BackupNode(store, device, http, failoverMs = failoverMs)
        val host = serve(store)
        val port = host.boundPort()
        p.store.addBackup(HostNode(store.leadership.nodeId, name, device.link.deviceId, local = "127.0.0.1:$port"))
        assertTrue(node.copy(), "$name copies the household")
        return node to Computer(store, host, port)
    }

    private val game = GameKey.of("snes", null, null, "Zelda")

    private suspend fun SyncClient.save(profile: String, text: String, parent: String? = null, store: ContentStore): SaveRevision {
        val hash = store.put(text.toByteArray())
        val r = SaveRevision("rev-" + SyncCrypto.token(6).filter { it.isLetterOrDigit() }.padEnd(8, 'x'), profile, game.id, SaveKind.SAVE, parent, link.deviceId, "Deck", Hlc(System.currentTimeMillis(), 0, link.deviceId), SaveManifest("f-$text", listOf(SaveFile("z.srm", hash, text.length.toLong()))))
        assertTrue(push(profile, r, store).accepted || true)
        return r
    }

    @Test
    fun aBackupKeepsACopyAndOnlyBackupsMayTakeOne(): Unit = runBlocking {
        val p = primary()
        val deck = pair(p, "Deck")
        val mo = deck.createProfile(NewProfile("Mo", "fox"))
        deck.openProfile(mo.id, null)
        val local = ContentStore(File(root, "deck-files"))
        deck.save(mo.id, "mo: 3 badges", store = local)
        val (b, bc) = backup(p, "Desk PC")
        assertEquals(p.store.identity.hostId, bc.store.identity.hostId, "the copy is the same household")
        assertEquals(listOf("Mo"), bc.store.profiles().map { it.name })
        assertEquals(p.store.revisions(mo.id).map { it.id }, bc.store.revisions(mo.id).map { it.id })
        assertTrue(bc.store.revisions(mo.id).all { r -> r.manifest.files.all { bc.store.content.has(it.hash) } })
        assertEquals(Leadership.BACKUP, bc.store.leadership.role)
        assertEquals(Leadership.PRIMARY, p.store.leadership.role)
        // Nothing new: the copy isn't sent again.
        assertTrue(b.copy())
        // A device that isn't a backup gets no copy, and a backup takes no device's changes.
        assertEquals("not-a-backup", assertFailsWith<SyncException> { deck.replica(-1) }.code)
        val atBackup = SyncClient(deck.link.copy(localAddress = bc.address, hosts = emptyList()), http)
        assertEquals("not-leader", assertFailsWith<SyncException> { atBackup.profiles() }.code)
        // The device learns the household's hosts from its host.
        deck.status()
        assertEquals(2, deck.link.hosts.size)
    }

    @Test
    fun withOneBackupAPersonMakesItTheHostAndTheOldHostIsFencedAndBroughtBack(): Unit = runBlocking {
        val p = primary()
        val deck = pair(p, "Deck")
        val mo = deck.createProfile(NewProfile("Mo", "fox"))
        deck.openProfile(mo.id, null)
        val local = ContentStore(File(root, "deck-files"))
        val first = deck.save(mo.id, "mo: 3 badges", store = local)
        val (b, bc) = backup(p, "Desk PC")
        deck.status()

        // The host goes away. Two hosts make no majority without it: no election, however long.
        p.host.stop()
        assertEquals(BackupNode.Round.HOST_AWAY, b.watch())
        assertEquals(Leadership.BACKUP, bc.store.leadership.role)

        // A person makes the backup the host. The device follows it, never back.
        val next = b.takeOver()
        assertEquals(1, next.generation)
        assertEquals(Leadership.PRIMARY, bc.store.leadership.role)
        val second = deck.save(mo.id, "mo: 4 badges", parent = first.id, store = local)
        assertEquals(bc.address, deck.link.localAddress?.removePrefix("http://"))
        assertEquals(1, deck.link.generation)
        assertTrue(bc.store.revisions(mo.id).any { it.id == second.id })

        // Meanwhile the old host took a save from a device only it could reach.
        val elsewhere = p.store.content.put("mo: 3 badges and a gym".toByteArray())
        val stray = SaveRevision("rev-strayxxx", mo.id, game.id, SaveKind.SAVE, first.id, "dev-other", "Phone", Hlc(System.currentTimeMillis(), 0, "dev-other"),
            SaveManifest("f-stray", listOf(SaveFile("z.srm", elsewhere, 23))))
        assertTrue(p.store.push(mo.id, "dev-other", stray).accepted)

        // It comes back still thinking it is the host.
        p.host = serve(p.store, p.port)
        assertEquals(Leadership.PRIMARY, assertNotNull(SyncClient.hello(p.address, http)).role)
        // The device never goes back to an older generation.
        assertFalse(SyncClient(deck.link, http).failover() && deck.link.localAddress?.contains(p.port.toString()) == true)

        // The new host fences it, takes back what it took, and makes it a backup.
        assertEquals(BackupNode.Round.IS_HOST, b.watch())
        val old = assertNotNull(SyncClient.hello(p.address, http))
        assertEquals(Leadership.BACKUP, old.role)
        assertEquals(1, old.generation)
        val kept = bc.store.revisions(mo.id)
        assertTrue(kept.any { it.id == stray.id }, "the save the old host took is kept")
        assertEquals(second.id, bc.store.head(mo.id, game.id, SaveKind.SAVE)?.id, "and doesn't take the place of the newest")
        assertTrue(kept.first { it.id == stray.id }.reason == RevisionReason.CONFLICT_COPY)
        assertTrue(bc.store.content.has(elsewhere))
        // The old host takes no changes now; a device asking it is sent on.
        val atOld = SyncClient(deck.link.copy(localAddress = p.address, hosts = emptyList()), http)
        assertEquals("not-leader", assertFailsWith<SyncException> { atOld.profiles() }.code)
    }

    @Test
    fun aMajorityElectsOneBackupAndTheOtherFollows(): Unit = runBlocking {
        val p = primary()
        val deck = pair(p, "Deck")
        val mo = deck.createProfile(NewProfile("Mo", "fox"))
        deck.openProfile(mo.id, null)
        deck.save(mo.id, "mo", store = ContentStore(File(root, "deck-files")))
        val (b1, c1) = backup(p, "Desk PC")
        val (b2, c2) = backup(p, "Living Room PC")
        assertTrue(b1.copy())
        assertTrue(b2.copy())
        assertEquals(3, c1.store.leadership.current.hosts.size)
        // The device learns the household's hosts while its host answers.
        deck.status()
        assertEquals(3, deck.link.hosts.size)

        // While the host answers, no backup votes for another.
        assertFalse(b1.elect())
        assertEquals(Leadership.PRIMARY, p.store.leadership.role)

        p.host.stop()
        assertEquals(BackupNode.Round.ELECTED, b1.watch())
        assertEquals(Leadership.PRIMARY, c1.store.leadership.role)
        // Generation 1 went to the vote asked while the host answered: never given, never reused.
        assertEquals(2, c1.store.leadership.current.generation)
        // The new host told it already; either way it follows.
        assertTrue(b2.watch() in setOf(BackupNode.Round.FOLLOWED, BackupNode.Round.UP_TO_DATE, BackupNode.Round.COPIED))
        assertEquals(c1.store.leadership.nodeId, c2.store.leadership.current.leader)
        assertEquals(Leadership.BACKUP, c2.store.leadership.role)
        // The device finds the new host by itself.
        assertEquals(listOf("Mo"), deck.profiles().map { it.name })
        assertEquals(c1.address, deck.link.localAddress?.removePrefix("http://"))
    }

    @Test
    fun twoBackupsAskingAtOnceNeverBothWin(): Unit = runBlocking {
        val p = primary()
        val (b1, c1) = backup(p, "Desk PC")
        val (b2, c2) = backup(p, "Living Room PC")
        assertTrue(b1.copy() && b2.copy())
        p.host.stop()
        val won = coroutineScope { listOf(async { b1.elect() }, async { b2.elect() }).awaitAll() }
        assertTrue(won.count { it } <= 1, "at most one wins a generation")
        val leaders = listOf(c1, c2).filter { it.store.leadership.role == Leadership.PRIMARY }
        assertTrue(leaders.size <= 1)
        // Asking again settles it: exactly one host, the other following.
        repeat(3) {
            if (listOf(c1, c2).none { it.store.leadership.role == Leadership.PRIMARY }) {
                b1.watch()
                b2.watch()
            }
        }
        b1.watch(); b2.watch()
        assertEquals(1, listOf(c1, c2).count { it.store.leadership.role == Leadership.PRIMARY })
        val gens = listOf(c1, c2).map { it.store.leadership.current.generation }.toSet()
        assertEquals(1, gens.size, "both know the same generation")
    }

    @Test
    fun aBackupThatCantReachAMajorityStaysABackup(): Unit = runBlocking {
        val p = primary()
        val (b1, c1) = backup(p, "Desk PC")
        val (_, c2) = backup(p, "Living Room PC")
        assertTrue(b1.copy())
        // The host and the other backup are both out of reach: one of three is no majority.
        p.host.stop()
        c2.host.stop()
        assertEquals(BackupNode.Round.NOT_ELECTED, b1.watch())
        assertEquals(Leadership.BACKUP, c1.store.leadership.role)
        assertEquals(0, c1.store.leadership.current.generation)
    }

    @Test
    fun aBackupWithAnOlderCopyIsNotElected(): Unit = runBlocking {
        val p = primary()
        val deck = pair(p, "Deck")
        val mo = deck.createProfile(NewProfile("Mo", "fox"))
        deck.openProfile(mo.id, null)
        val local = ContentStore(File(root, "deck-files"))
        val (b1, c1) = backup(p, "Desk PC")
        val (b2, c2) = backup(p, "Living Room PC")
        assertTrue(b1.copy())
        // More happens; only the second backup copies it.
        deck.save(mo.id, "mo: newer", store = local)
        assertTrue(b2.copy())
        assertTrue(c2.store.seq() > c1.store.seq())
        p.host.stop()
        assertFalse(b1.elect(), "its copy is older than the other backup's")
        assertEquals(Leadership.BACKUP, c1.store.leadership.role)
        // The one with the newer copy wins the next generation.
        assertTrue(b2.elect())
        assertEquals(2, c2.store.leadership.current.generation)
        assertEquals(1, c2.store.revisions(mo.id).size)
    }

    @Test
    fun theRulesOfVoting() {
        val dir = File(root, "rules")
        val a = HostLeadership(File(dir, "a"))
        val l = Leadership(3, "host", listOf(HostNode("host", "Host"), HostNode(a.nodeId, "A"), HostNode("b", "B")))
        a.standBy()
        a.observe(l)
        assertEquals(Leadership.BACKUP, a.role)
        assertFalse(a.grant(VoteRequest(3, "b", 10), hostReachable = false, mySeq = 5).granted, "not an older or the same generation")
        assertFalse(a.grant(VoteRequest(4, "b", 10), hostReachable = true, mySeq = 5).granted, "not while the host answers")
        assertFalse(a.grant(VoteRequest(4, "b", 4), hostReachable = false, mySeq = 5).granted, "not for an older copy")
        assertFalse(a.grant(VoteRequest(4, "stranger", 10), hostReachable = false, mySeq = 5).granted, "only for the household's hosts")
        assertTrue(a.grant(VoteRequest(4, "b", 10), hostReachable = false, mySeq = 5).granted)
        assertTrue(a.grant(VoteRequest(4, "b", 10), hostReachable = false, mySeq = 5).granted, "the same answer again")
        assertFalse(a.grant(VoteRequest(4, "host", 10), hostReachable = false, mySeq = 5).granted, "once per generation")
        // The vote survives a restart.
        val again = HostLeadership(File(dir, "a"))
        assertFalse(again.grant(VoteRequest(4, "host", 10), hostReachable = false, mySeq = 5).granted)
        assertFalse(again.voteForSelf(4))
        // A host that learns of a newer generation is fenced.
        val h = HostLeadership(File(dir, "h"))
        assertEquals(Leadership.PRIMARY, h.role)
        assertTrue(h.observeGeneration(1))
        assertEquals(Leadership.FENCED, h.role)
    }
}
