package io.github.matiyaaa.fuse.ui.shell.sync

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Sync tab's rows show this library's name and art for a game another device saved under its file name. */
class SyncCoversTest {
    private val rdr = GameCard(GameId(7), PlatformId("switch"), "Red Dead Redemption", "Switch", 0xFF8A2020, Art(boxart = "rdr.png"))

    @Test
    fun aSaveNamedByItsFileFindsTheLibrarysGame() {
        val covers = SyncCovers(mapOf("name:red dead redemption" to rdr, SyncCovers.searchKey(rdr.title) to rdr))
        assertEquals(rdr, covers.of("switch:unknown-id", "[ROMsNS] Red Dead Redemption v0 (01007820196A6000) (BASE)"))
        assertEquals(rdr, covers.of("switch:unknown-id", "Red Dead Redemption"))
        assertNull(covers.of("switch:unknown-id", "Red Dead Redemption 2"))
    }
}
