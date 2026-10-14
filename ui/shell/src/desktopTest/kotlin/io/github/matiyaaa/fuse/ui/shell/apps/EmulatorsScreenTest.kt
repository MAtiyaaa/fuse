package io.github.matiyaaa.fuse.ui.shell.apps

import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.InFuse
import io.github.matiyaaa.fuse.ui.shell.store.SourceKind
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** An emulator finds its Store app by name, and never a guess between two. */
class EmulatorsScreenTest {
    private fun app(name: String) = StoreApp(
        key = name.lowercase(), id = name, packageName = null, pinned = false, name = name, author = "", about = null,
        categories = listOf("Emulator"), sourceUrl = "https://github.com/x/$name", sourceHost = "github.com",
        sourceKind = SourceKind.GITHUB, availability = Availability.INSTALLABLE, allowIdChange = false,
        systems = emptyList<PlatformId>(), color = null, inFuse = InFuse.LAUNCHES_GAMES,
    )

    private val catalogue = listOf(app("Dolphin Emulator"), app("PCSX2"), app("DuckStation"), app("RPCS3"), app("Citra"), app("Citron"))

    @Test
    fun theSameNameMatches() {
        assertEquals("pcsx2", storeAppFor("PCSX2", catalogue)?.key)
        assertEquals("dolphin emulator", storeAppFor("Dolphin", catalogue)?.key)
        assertEquals("duckstation", storeAppFor("duckstation", catalogue)?.key)
    }

    @Test
    fun noGuessesBetweenTwoOrFromTooLittle() {
        // "Cit" could be Citra or Citron.
        assertNull(storeAppFor("Cit", catalogue))
        assertNull(storeAppFor("ab", catalogue))
        assertNull(storeAppFor("Ryujinx", catalogue))
    }
}
