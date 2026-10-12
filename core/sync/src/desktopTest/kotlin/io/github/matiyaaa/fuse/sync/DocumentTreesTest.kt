package io.github.matiyaaa.fuse.sync

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Saves in a folder granted through Android's picker (Eden's documents provider): found by the
 * adapters through the folder, worked on through a copy, and put back as one transaction that
 * survives a provider without renames, a failure part way and a crash, and never overwrites what
 * the emulator wrote meanwhile.
 */
class DocumentTreesTest {
    /** A documents provider in memory: files by path, optionally without renames, failing on a chosen write. */
    private class FakeTree(val renames: Boolean = true) : DocumentTree {
        val files = sortedMapOf<String, ByteArray>()
        val folders = sortedSetOf("")
        var failCreate: (String) -> Boolean = { false }

        override fun list(path: String): List<TreeEntry>? {
            if (path !in folders) return null
            val prefix = if (path.isEmpty()) "" else "$path/"
            val dirs = folders.filter { it.startsWith(prefix) && it != path && '/' !in it.removePrefix(prefix) }.map { TreeEntry(it.removePrefix(prefix), true) }
            val fs = files.keys.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.map { TreeEntry(it.removePrefix(prefix), false, files[it]!!.size.toLong(), 1L) }
            return dirs + fs
        }

        override fun open(path: String): InputStream? = files[path]?.let(::ByteArrayInputStream)

        override fun create(path: String): OutputStream? {
            if (failCreate(path)) return null
            var p = ""
            for (part in path.split('/').dropLast(1)) { p = if (p.isEmpty()) part else "$p/$part"; folders += p }
            return object : ByteArrayOutputStream() { override fun close() { files[path] = toByteArray() } }
        }

        override fun rename(path: String, newName: String): Boolean {
            if (!renames || path !in files) return false
            val to = path.substringBeforeLast('/', "").let { if (it.isEmpty()) newName else "$it/$newName" }
            files[to] = files.remove(path)!!
            return true
        }

        override fun delete(path: String): Boolean { files.remove(path); return true }

        fun text(path: String) = files[path]?.decodeToString()
        fun put(path: String, text: String) { create(path)!!.use { it.write(text.toByteArray()) } }
    }

    private lateinit var root: File
    private val uri = "content://dev.eden.eden_emulator.user/tree/root%3A"
    private val game = GameKey.of("switch", "0100000000010000", null, "Game")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("trees").toFile() }
    @AfterTest fun tearDown() { root.deleteRecursively() }

    @Test
    fun edenFindsItsSaveThroughTheGrantedFolder() {
        val tree = FakeTree()
        val raw = EdenUser.fromLegacy("0123456789ABCDEFFEDCBA9876543210")
        tree.put("user/save/account/$raw/0100000000010000/0/slot1.dat", "progress")
        val env = WithSaveFolders(TreeSaveEnvironment(FileSaveEnvironment("ANDROID", root.path)) { if (it == uri) tree else null }) { mapOf("eden" to TreePaths.of(uri)) }
        val spot = SaveAdapters.forEmulator("eden")!!.locate(SaveQuery(game, "switch", "/r/Game [0100000000010000].nsp", "eden"), env).single()
        assertEquals(TreePaths.of(uri, "user/save/account/$raw/0100000000010000/0"), spot.root)
    }

    private fun roundTrip(tree: FakeTree) = runBlocking {
        tree.put("save/a.dat", "john a")
        tree.put("save/b.dat", "john b")
        val mirror = TreeMirror(File(root, "m"), { if (it == uri) tree else null })
        val spot = SaveSpot(SaveKind.SAVE, "x", root = TreePaths.of(uri, "save"))
        val device = SyncDevice(File(root, "state"), "thor", "Thor")
        fun slot(): LocalSlot { val (local, parts) = mirror.pull(spot)!!; return Slots.of(game, local).copy(sink = { mirror.push(parts) }) }
        device.handover("john", slot())
        // Mark has never played: the folder is emptied through the provider, John's kept.
        device.handover("mark", slot())
        assertEquals(emptyList(), tree.files.keys.filter { it.startsWith("save/") })
        tree.put("save/a.dat", "mark a")
        device.handover("john", slot())
        assertEquals("john a", tree.text("save/a.dat"))
        assertEquals("john b", tree.text("save/b.dat"))
        assertTrue(tree.files.keys.none { it.endsWith(TreeMirror.PART) || it.endsWith(TreeMirror.OLD) }, "nothing left behind: ${tree.files.keys}")
    }

    @Test fun peopleSwapThroughAProviderThatRenames() = roundTrip(FakeTree(renames = true))

    @Test fun peopleSwapThroughAProviderThatCannotRename() = roundTrip(FakeTree(renames = false))

    @Test
    fun aFailedPushChangesNothingAnywhere(): Unit = runBlocking {
        val tree = FakeTree(renames = false)
        tree.put("save/a.dat", "mark a")
        val mirror = TreeMirror(File(root, "m"), { tree })
        val (local, parts) = mirror.pull(SaveSpot(SaveKind.SAVE, "x", root = TreePaths.of(uri, "save")))!!
        File(local.root!!, "a.dat").writeText("john a")
        File(local.root!!, "c.dat").writeText("john c")
        tree.failCreate = { it == "save/c.dat.fuse-part" }
        assertFailsWith<IllegalStateException> { mirror.push(parts) }
        assertEquals(mapOf("save/a.dat" to "mark a"), tree.files.mapValues { it.value.decodeToString() })
    }

    @Test
    fun whatTheEmulatorWroteMeanwhileIsNeverOverwritten() {
        val tree = FakeTree()
        tree.put("save/a.dat", "before")
        val mirror = TreeMirror(File(root, "m"), { tree })
        val (local, parts) = mirror.pull(SaveSpot(SaveKind.SAVE, "x", root = TreePaths.of(uri, "save")))!!
        File(local.root!!, "a.dat").writeText("fuse")
        tree.put("save/a.dat", "the emulator, just now")
        assertFailsWith<IllegalArgumentException> { mirror.push(parts) }
        assertEquals("the emulator, just now", tree.text("save/a.dat"))
    }

    @Test
    fun aCrashMidPushIsUndoneOnTheNextTouch() {
        val tree = FakeTree(renames = true)
        tree.put("save/a.dat", "old a")
        tree.put("save/b.dat", "old b")
        val dir = File(root, "m")
        val mirror = TreeMirror(dir, { tree })
        val (local, parts) = mirror.pull(SaveSpot(SaveKind.SAVE, "x", root = TreePaths.of(uri, "save")))!!
        File(local.root!!, "a.dat").writeText("new a")
        File(local.root!!, "b.dat").writeText("new b")
        // The second file's final write never happens: the process died there.
        var renamesLeft = 3
        val dying = object : DocumentTree by tree {
            override fun rename(path: String, newName: String): Boolean {
                if (renamesLeft-- <= 0) throw IllegalStateException("power lost")
                return tree.rename(path, newName)
            }
        }
        val crashing = TreeMirror(dir, { dying })
        // Simulate the crash: the rollback inside push is skipped by failing recovery too.
        runCatching { crashing.push(parts) }
        // On the next start, any touch recovers first.
        TreeMirror(dir, { tree }).recover()
        assertEquals("old a", tree.text("save/a.dat"))
        assertEquals("old b", tree.text("save/b.dat"))
        assertTrue(tree.files.keys.none { it.endsWith(TreeMirror.OLD) || it.endsWith(TreeMirror.PART) }, tree.files.keys.toString())
    }
}
