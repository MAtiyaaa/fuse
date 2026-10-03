package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.integrations.cartridge.RommGuard.Verdict
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals

/** RomM's details are only taken for the game they describe. */
class RommGuardTest {
    private val resolve: (String) -> PlatformId? = { PlatformCatalog.resolveFolder(it)?.id }

    private fun check(platform: String, file: String, title: String, slug: String) =
        RommGuard.check(PlatformId(platform), listOf(file), title, slug, resolve)

    @Test
    fun theSameGameIsAccepted() {
        assertEquals(Verdict.ACCEPT, check("gb", "Pokemon - Red Version (USA, Europe) (SGB Enhanced)", "Pokémon Red Version", "gb"))
        assertEquals(Verdict.ACCEPT, check("ps2", "Ratchet & Clank - Going Commando (USA)", "Ratchet & Clank: Going Commando", "ps2"))
        assertEquals(Verdict.ACCEPT, check("genesis", "Sonic The Hedgehog (USA, Europe)", "Sonic the Hedgehog", "genesis-slash-megadrive"))
    }

    @Test
    fun anotherGameOnAnotherSystemIsTurnedDown() {
        // Pokemon Red was renamed Ratchet & Clank; Cooking Mama was renamed Pokemon Red.
        assertEquals(Verdict.OTHER_SYSTEM, check("gb", "Pokemon - Red Version (USA, Europe)", "Ratchet & Clank", "ps2"))
        assertEquals(Verdict.OTHER_SYSTEM, check("nds", "Cooking Mama 3 - Shop & Chop (USA)", "Pokémon Red Version", "gb"))
    }

    @Test
    fun anotherGameOnTheSameSystemIsTurnedDown() {
        assertEquals(Verdict.OTHER_TITLE, check("psx", "Cooking Mama (USA)", "Dino Crisis", "psx"))
        assertEquals(Verdict.OTHER_TITLE, check("gb", "Pokemon - Red Version (USA, Europe)", "Pokémon Blue Version", "gb"))
        assertEquals(Verdict.OTHER_TITLE, check("gb", "Pokemon - Red Version (USA, Europe)", "", "gb"))
    }

    @Test
    fun closeSystemsAndUnknownSlugsDependOnTheName() {
        assertEquals(Verdict.ACCEPT, check("gbc", "Tetris (World)", "Tetris", "gb"))
        assertEquals(Verdict.ACCEPT, check("gb", "Tetris (World)", "Tetris", "some-new-slug"))
        assertEquals(Verdict.OTHER_TITLE, check("gb", "Tetris (World)", "Dr. Mario", "some-new-slug"))
    }
}
