package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A choice is never lost to settings written meanwhile: the person's latest choice is what Fuse
 * shows and stores, however Fuse One's own writes (and the reloads after them) fall around it.
 * Fuse One writes its settings as Fuse starts, so this is what a choice made at once meets.
 */
class ChoicesDuringWritesTest {
    @Test
    fun theLastChoiceStaysWhateverIsWrittenAroundIt(): Unit = runBlocking {
        val lost = ArrayList<Int>()
        repeat(12) { round ->
            val root = Files.createTempDirectory("fuse-choices").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val data = FuseData(DesktopDatabase.open(File(root, "fuse.db").absolutePath))
                val store = createFuseStore(FakeServices(data, File(root, "cache").apply { mkdirs() }), scope)
                val writes = scope.launch { repeat(200) { n -> store.sync.configure { it.copy(welcomedSeeded = n % 2 == 0) } } }
                repeat(400) { n ->
                    store.updatePrefs { it.copy(phoneLinkEnabled = n % 2 == 1) }
                    if (n % 20 == 0) delay(1)
                }
                writes.join()
                val kept = runCatching {
                    withTimeout(5_000) { while (!data.settings.current().library.phoneLinkEnabled) delay(20) }
                    store.prefs.value.phoneLinkEnabled
                }.getOrDefault(false)
                if (!kept) lost += round
            } finally {
                scope.cancel()
                root.deleteRecursively()
            }
        }
        assertEquals(emptyList(), lost, "rounds whose last choice was lost")
    }
}
