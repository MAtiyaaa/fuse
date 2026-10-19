package io.github.matiyaaa.fuse.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** One way moves each game's save: the first, in the person's order, of those that can move it on that device. */
class SaveOwnersTest {
    /** Six devices, each with the ways it has for one game. */
    private val devices = mapOf(
        "a PC with all three" to setOf(SaveWay.FUSE_ONE, SaveWay.ROMM, SaveWay.SYNCTHING),
        "a Deck with Fuse One and RomM" to setOf(SaveWay.FUSE_ONE, SaveWay.ROMM),
        "a phone with RomM and Syncthing" to setOf(SaveWay.ROMM, SaveWay.SYNCTHING),
        "a laptop with Syncthing alone" to setOf(SaveWay.SYNCTHING),
        "a handheld with Fuse One alone" to setOf(SaveWay.FUSE_ONE),
        "a TV with none" to emptySet(),
    )

    @Test
    fun fuseOneThenRommThenSyncthingUnlessThePersonSaysOtherwise() {
        val usual = mapOf(
            "a PC with all three" to SaveWay.FUSE_ONE,
            "a Deck with Fuse One and RomM" to SaveWay.FUSE_ONE,
            "a phone with RomM and Syncthing" to SaveWay.ROMM,
            "a laptop with Syncthing alone" to SaveWay.SYNCTHING,
            "a handheld with Fuse One alone" to SaveWay.FUSE_ONE,
            "a TV with none" to null,
        )
        for ((device, ways) in devices) assertEquals(usual[device], SaveOwners.owner(ways, emptyList()), device)
        // RomM first: games from RomM go through RomM wherever it is; the rest as before.
        val rommFirst = listOf("romm")
        val chosen = mapOf(
            "a PC with all three" to SaveWay.ROMM,
            "a Deck with Fuse One and RomM" to SaveWay.ROMM,
            "a phone with RomM and Syncthing" to SaveWay.ROMM,
            "a laptop with Syncthing alone" to SaveWay.SYNCTHING,
            "a handheld with Fuse One alone" to SaveWay.FUSE_ONE,
            "a TV with none" to null,
        )
        for ((device, ways) in devices) assertEquals(chosen[device], SaveOwners.owner(ways, rommFirst), device)
    }

    @Test
    fun anOrderLeavingWaysOutOrNamingUnknownOnesStillHasEveryWay() {
        assertEquals(listOf(SaveWay.SYNCTHING, SaveWay.FUSE_ONE, SaveWay.ROMM), SaveOwners.order(listOf("syncthing", "dropbox", "syncthing")))
        assertEquals(SaveOwners.DEFAULT, SaveOwners.order(emptyList()))
    }
}
