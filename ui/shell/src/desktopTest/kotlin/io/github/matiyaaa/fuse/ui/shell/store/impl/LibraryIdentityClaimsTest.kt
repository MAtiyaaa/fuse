package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.UserGameRow
import io.github.matiyaaa.fuse.sync.GameKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LibraryIdentityClaimsTest {
    @Test fun serialIdentifiedRowsNeverPublishATitleBridge() {
        val first = row("CUSA00001")
        val second = row("CUSA00002")
        assertEquals(listOf(GameKey.of("ps4", first.serial, null, first.title)), LibraryProfileData.candidatesOf(first))
        assertNotEquals(LibraryProfileData.candidatesOf(first), LibraryProfileData.candidatesOf(second))
    }

    @Test fun unidentifiedRowsKeepTitleFallbackWithoutClaimingSerialEvidence() {
        val game = row(null)
        assertEquals(listOf(GameKey.of("ps4", null, null, game.title)), LibraryProfileData.candidatesOf(game))
    }

    private fun row(serial: String?) = UserGameRow(1, "ps4", "The Game", null, serial,
        false, false, false, null, 0, 0, null)
}
