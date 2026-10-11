package io.github.matiyaaa.fuse.sync

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

/** The household kept whole: a copy a day, week and month kept as long as each should be, files kept once, and putting it back. */
class HouseholdBackupsTest {
    private lateinit var root: File
    private var now = 1_800_000_000_000L
    private val day = HouseholdBackups.DAY_MS

    @BeforeTest fun setUp() { root = Files.createTempDirectory("household-backups").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    private fun store() = HostStore(File(root, "host"), clock = { now }, hostName = "Gaming PC")

    private fun save(store: HostStore, profile: String, text: String, parent: String? = null): SaveRevision {
        val h = store.content.put(text.toByteArray())
        val r = SaveRevision("rev-" + SyncCrypto.token(6).filter { it.isLetterOrDigit() }.padEnd(8, 'x'), profile, GameKey.of("snes", null, null, "Zelda").id, SaveKind.SAVE, parent, "d", "Deck", Hlc(now, 0, "d"), SaveManifest("f-$text", listOf(SaveFile("z.srm", h, text.length.toLong()))))
        assertTrue(store.push(profile, "d", r).accepted)
        return r
    }

    @Test
    fun aCopyADayKeptAWeekAMonthAndHalfAYear() {
        val store = store()
        val mo = store.createProfile(NewProfile("Mo", "fox")).id
        save(store, mo, "the same save every day")
        val backups = HouseholdBackups(File(root, "backups"), clock = { now })
        assertTrue(backups.due())
        val first = backups.snapshot(store)
        assertEquals(listOf("daily", "weekly", "monthly"), first.kinds)
        assertFalse(backups.due(), "one a day")
        repeat(200) {
            now += day
            assertTrue(backups.due())
            backups.snapshot(store)
        }
        val kept = backups.list()
        // Every day of the last week, the last four weeks and the last six months.
        assertEquals(7, kept.count { now - it.at < 7 * day })
        assertEquals(4, kept.count { "weekly" in it.kinds })
        assertEquals(6, kept.count { "monthly" in it.kinds })
        assertTrue(kept.size <= 17)
        assertTrue(kept.none { now - it.at > 190 * day }, "nothing older than half a year and a bit")
        // The one save file is kept once, however many copies use it.
        assertEquals(1, File(root, "backups/objects").walkTopDown().count { it.isFile && !it.name.startsWith(".") })
        // A copy made by a person counts for none of the schedule's.
        assertEquals(listOf("manual"), backups.snapshot(store, listOf(HouseholdBackups.MANUAL)).kinds)
    }

    @Test
    fun filesNoCopyUsesAnyMoreGoAndNoneACopyUsesEver() {
        val store = store()
        val mo = store.createProfile(NewProfile("Mo", "fox")).id
        val backups = HouseholdBackups(File(root, "backups"), clock = { now }, keepDaily = 2, keepWeekly = 0, keepMonthly = 0)
        var parent: String? = null
        repeat(5) { i ->
            parent = save(store, mo, "save $i", parent).id
            // Only the newest version stays on the host.
            store.compact(Retention(recent = 1, days = 0, weeks = 0))
            backups.snapshot(store)
            now += day
        }
        val kept = backups.list()
        assertEquals(2, kept.size)
        val used = kept.flatMap { it.objects }.toSet()
        assertEquals(2, used.size)
        val onDisk = File(root, "backups/objects").walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.count()
        assertEquals(2, onDisk, "files of copies let go are gone; those still used stay")
    }

    @Test
    fun puttingTheHouseholdBackKeepsWhatItWasAndTheHostsAsTheyAre(): Unit = runBlocking {
        val store = store()
        val mo = store.createProfile(NewProfile("Mo", "fox")).id
        val first = save(store, mo, "mo: 3 badges")
        val backups = HouseholdBackups(File(root, "backups"), clock = { now })
        val snap = backups.snapshot(store)
        now += day
        // Afterwards: Mo is deleted, a new profile is made, and the household moved on a generation.
        store.createProfile(NewProfile("Sam", "owl"))
        store.deleteProfile(mo)
        val generation = store.leadership.promote(forkSeq = store.seq()).generation
        val seqBefore = store.seq()

        val restored = backups.restore(snap.name, store)
        assertEquals(snap.name, restored.name)
        assertEquals(listOf("Mo"), store.profiles().filterNot { it.hostOnly }.map { it.name })
        assertEquals(first.id, store.head(mo, first.game, SaveKind.SAVE)?.id)
        assertTrue(store.content.has(first.manifest.files.single().hash))
        assertEquals(generation, store.leadership.current.generation, "the hosts stay as they are")
        assertTrue(store.seq() > seqBefore, "devices look again")
        // The household as it was is kept first, so the restore can be undone.
        val before = backups.list().first { "before-restore" in it.kinds }
        assertEquals(1, before.profiles)
        // A restart reads the restored household.
        val again = HostStore(File(root, "host"), clock = { now })
        assertEquals(listOf("Mo"), again.profiles().filterNot { it.hostOnly }.map { it.name })
        assertFailsWith<IllegalStateException> { backups.restore("nope", store) }
    }

    @Test
    fun onlyTheHostIsPutBack() {
        val store = store()
        val backups = HouseholdBackups(File(root, "backups"), clock = { now })
        val snap = backups.snapshot(store)
        store.leadership.observeGeneration(5)
        assertFailsWith<IllegalStateException> { backups.restore(snap.name, store) }
    }

    @Test
    fun theHeadlessHostBacksUpOnItsOwnAndOnRequest(): Unit = runBlocking {
        val dir = File(root, "headless")
        val port = java.net.ServerSocket(0).use { it.localPort }
        val host = assertNotNull(HeadlessHost.serve(dir, port, "Mini PC", "test", backupsDir = File(root, "nas-backups")))
        try {
            val admin = assertNotNull(HostAdmin.of(dir, port))
            // The first is made as it starts.
            var state = admin.householdBackups()
            for (i in 0 until 50) { if (state.snapshots.isNotEmpty()) break; Thread.sleep(100); state = admin.householdBackups() }
            assertEquals(1, state.snapshots.size)
            assertTrue(state.folder.endsWith("nas-backups"))
            val manual = admin.backUpNow()
            assertEquals(listOf("manual"), manual.kinds)
            admin.restoreHousehold(manual.name)
            assertTrue(admin.householdBackups().snapshots.any { "before-restore" in it.kinds })
        } finally {
            host.close()
        }
    }
}
