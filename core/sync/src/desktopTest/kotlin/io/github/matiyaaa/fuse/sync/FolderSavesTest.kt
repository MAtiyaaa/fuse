package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Saves that are whole folders (a PSP or PS3 game's save folder, a Switch save): they travel with
 * their structure, nested folders and empty ones included, and a save put in place is that save as
 * it was made, never a mix of it and the one it replaced.
 */
class FolderSavesTest {
    private lateinit var root: File
    private lateinit var host: SyncHost
    private var port = 0
    private val http = SyncClient.defaultClient()
    private val game = GameKey.of("psp", "ULUS10041", null, "Lumines")

    @BeforeTest
    fun start(): Unit = runBlocking {
        root = Files.createTempDirectory("fuse-folders").toFile()
        host = SyncHost(HostStore(File(root, "host"), hostName = "Gaming PC"), port = 0, bind = "127.0.0.1", callsPerMinute = 100_000).start()
        port = host.boundPort()
    }

    @AfterTest
    fun stop() {
        host.stop()
        root.deleteRecursively()
    }

    private suspend fun pairDevice(name: String): Pair<SyncClient, SyncDevice> {
        val code = host.newPairingCode()
        val id = "dev-" + SyncCrypto.token(8)
        val link = SyncClient.pair("127.0.0.1:$port", code, id, name, "LINUX", http)
        return SyncClient(link, http) to SyncDevice(File(root, name), id, name)
    }

    /** The game's save folder inside [saves], as the PSP adapter hands it over. */
    private fun slot(saves: File): LocalSlot =
        Slots.of(game, SaveSpot(SaveKind.SAVE, "ppsspp.savedata", root = saves.path, folders = listOf("ULUS10041")))

    /** Every file under [dir] by its path, and every empty folder (ending in a slash). */
    private fun tree(dir: File): Map<String, String> = dir.walkTopDown().filter { it != dir }.mapNotNull { f ->
        val path = f.relativeTo(dir).path.replace('\\', '/')
        when {
            f.isFile -> path to f.readText()
            f.list()?.isEmpty() == true -> "$path/" to ""
            else -> null
        }
    }.toMap()

    private fun write(dir: File, vararg files: Pair<String, String>) {
        for ((p, text) in files) File(dir, p).apply { parentFile.mkdirs(); writeText(text) }
    }

    @Test
    fun aFolderSaveTravelsWithItsWholeStructure(): Unit = runBlocking {
        val (pcClient, pc) = pairDevice("PC")
        val (deckClient, deck) = pairDevice("Deck")
        val p = pcClient.createProfile(NewProfile("Mo", "fox")).id
        val pcSaves = File(root, "pc/SAVEDATA")
        write(
            pcSaves,
            "ULUS10041/PARAM.SFO" to "param",
            "ULUS10041/DATA/slot1/SAVE.BIN" to "level 3",
            "ULUS10041/DATA/slot2/SAVE.BIN" to "level 1",
            "ULUS10041/DATA/deep/er/still/NOTE.TXT" to "three levels down",
            // Another game's folder beside it is never part of this save.
            "ULES00151/OTHER.BIN" to "not this game",
        )
        File(pcSaves, "ULUS10041/DATA/slot3").mkdirs()
        val made = assertNotNull(pc.capture(p, slot(pcSaves), 600))
        assertEquals(listOf("ULUS10041/DATA/slot3"), made.manifest.folders)
        assertEquals(4, made.manifest.files.size)
        pc.flush(pcClient)

        // The handheld had an older try at the same game, laid out differently.
        val deckSaves = File(root, "deck/SAVEDATA")
        write(deckSaves, "ULUS10041/PARAM.SFO" to "old param", "ULUS10041/DATA/old/SAVE.BIN" to "level 0")
        // Both have a save the other hasn't seen: the person takes the PC's.
        val conflict = assertIs<PrepareResult.Conflict>(deck.prepare(deckClient, p, slot(deckSaves)))
        deck.takeRemote(deckClient, p, slot(deckSaves), conflict.remote)
        deck.flush(deckClient)

        val pcTree = tree(File(pcSaves, "ULUS10041"))
        val deckTree = tree(File(deckSaves, "ULUS10041"))
        assertEquals(pcTree, deckTree, "the save came over exactly, empty folder and all, with nothing of the old one left")
        assertTrue("DATA/slot3/" in deckTree)
        assertFalse(File(deckSaves, "ULUS10041/DATA/old").exists(), "the old save's folder went with it")
        assertFalse(File(deckSaves, "ULES00151").exists())
        // What the handheld had is kept, not lost: it went up as the copy before the restore.
        val kept = deckClient.revisions(p, game.id, SaveKind.SAVE).single { it.reason != RevisionReason.PLAYED }
        assertEquals(setOf("ULUS10041/PARAM.SFO", "ULUS10041/DATA/old/SAVE.BIN"), kept.manifest.files.map { it.path }.toSet())
    }

    @Test
    fun aFileRemovedOnOneDeviceIsGoneOnTheOtherAndRestoringPutsTheTreeBackExactly(): Unit = runBlocking {
        val (pcClient, pc) = pairDevice("PC")
        val (deckClient, deck) = pairDevice("Deck")
        val p = pcClient.createProfile(NewProfile("Mo", "fox")).id
        val pcSaves = File(root, "pc/SAVEDATA")
        write(pcSaves, "ULUS10041/A.BIN" to "a", "ULUS10041/sub/B.BIN" to "b")
        val first = assertNotNull(pc.capture(p, slot(pcSaves), 100))
        pc.flush(pcClient)
        val deckSaves = File(root, "deck/SAVEDATA")
        deck.prepare(deckClient, p, slot(deckSaves))
        assertEquals(mapOf("A.BIN" to "a", "sub/B.BIN" to "b"), tree(File(deckSaves, "ULUS10041")))

        // The game drops a file and its folder on the PC.
        File(pcSaves, "ULUS10041/sub").deleteRecursively()
        File(pcSaves, "ULUS10041/A.BIN").writeText("a2")
        assertNotNull(pc.capture(p, slot(pcSaves), 200))
        pc.flush(pcClient)
        deck.prepare(deckClient, p, slot(deckSaves))
        assertEquals(mapOf("A.BIN" to "a2"), tree(File(deckSaves, "ULUS10041")))

        // Restoring the first version brings the folder and its file back, as they were.
        val old = deckClient.revisions(p, game.id, SaveKind.SAVE).first { it.id == first.id }
        deck.restore(deckClient, p, slot(deckSaves), old)
        assertEquals(mapOf("A.BIN" to "a", "sub/B.BIN" to "b"), tree(File(deckSaves, "ULUS10041")))
    }

    @Test
    fun twoPeopleOnOneDeviceKeepTheirOwnTrees(): Unit = runBlocking {
        val (client, deck) = pairDevice("Deck")
        val mo = client.createProfile(NewProfile("Mo", "fox")).id
        val sam = client.createProfile(NewProfile("Sam", "cat")).id
        val saves = File(root, "deck/SAVEDATA")
        write(saves, "ULUS10041/mo/deep/SAVE.BIN" to "mo's")
        File(saves, "ULUS10041/mo/empty").mkdirs()
        deck.handover(mo, slot(saves))
        assertNotNull(deck.capture(mo, slot(saves), 100))
        val mos = tree(File(saves, "ULUS10041"))

        // Sam starts their own game: nothing of Mo's is left in the folder, not even its folders.
        deck.handover(sam, slot(saves))
        assertEquals(emptyMap(), tree(File(saves, "ULUS10041")))
        write(saves, "ULUS10041/sam/SAVE.BIN" to "sam's")
        assertNotNull(deck.capture(sam, slot(saves), 50))

        // Back to Mo: their tree, empty folder included, and none of Sam's.
        deck.handover(mo, slot(saves))
        assertEquals(mos, tree(File(saves, "ULUS10041")))
        deck.handover(sam, slot(saves))
        assertEquals(mapOf("sam/SAVE.BIN" to "sam's"), tree(File(saves, "ULUS10041")))
    }

    @Test
    fun aManifestWithoutEmptyFoldersReadsAndWritesAsBefore() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val plain = SaveManifest("f", listOf(SaveFile("a", "0".repeat(64), 1)))
        // Older hosts and devices never see the new field when there is nothing in it.
        assertFalse("folders" in json.encodeToString(SaveManifest.serializer(), plain))
        assertEquals(plain, json.decodeFromString(SaveManifest.serializer(), """{"format":"f","files":[{"path":"a","hash":"${"0".repeat(64)}","size":1}]}"""))
        // An empty folder alone never makes a save new.
        assertEquals(plain.fingerprint, plain.copy(folders = listOf("x")).fingerprint)
    }
}
