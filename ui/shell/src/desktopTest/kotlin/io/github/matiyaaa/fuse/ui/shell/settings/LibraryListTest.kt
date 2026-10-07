package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
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
import kotlin.test.Test
import kotlin.test.assertTrue

/** Developer options, Library list: each game with its folder, file and what was found with it. */
class LibraryListTest {
    private val root = Files.createTempDirectory("fuse-list").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun theListNamesEachGameItsFileAndItsUpdate() = runBlocking {
        val switch = File(root, "roms/switch/Harbor Lights").apply { mkdirs() }
        File(switch, "Harbor Lights [0100AAAA11112000][v0].nsp").writeBytes(ByteArray(64))
        File(switch, "Harbor Lights [0100AAAA11112800][v65536].nsp").writeBytes(ByteArray(16))
        val services = FakeServices(FuseData(DesktopDatabase.open(File(root, "fuse.db").absolutePath)), root)
        val store = createFuseStore(services, scope)
        store.sources.add(File(root, "roms").absolutePath, LibrarySourceKind.ROMS_ROOT)
        withTimeout(30_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }

        val text = libraryList(store, "test")
        assertTrue("roms  [ROMS_ROOT]" in text, text)
        assertTrue("== switch (1) ==" in text, text)
        assertTrue("Harbor Lights [0100AAAA11112000][v0].nsp" in text, text)
        assertTrue("+ UPDATE Harbor Lights [0100AAAA11112800][v65536].nsp" in text, text)
    }
}
