package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdState
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdSystem
import io.github.matiyaaa.fuse.ui.shell.store.ReachOps
import io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommOps
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import io.github.matiyaaa.fuse.ui.shell.store.RommSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a port's game is besides this device: RomM's library and the household's other devices, and bringing it here from each. */
class PortGameSourcesTest {
    private val n64 = PlatformId("n64")
    private val snes = PlatformId("snes")

    private fun card(id: Long, title: String, platform: PlatformId) = GameCard(GameId(id), platform, title, platform.value.uppercase(), 0xFF000000, Art(boxart = "https://example.org/$id.png"))

    private val asked = mutableListOf<String>()

    private val romm = object : RommOps by RommOps.None {
        override val supported = true
        override val state = MutableStateFlow(RommState(link = RommLink.ONLINE, games = 3))
        override val systems = MutableStateFlow(listOf(RommSystem(n64, "n64", "Nintendo 64", 2, 1, 0), RommSystem(snes, "snes", "Super Nintendo", 1, 0, 0), RommSystem(null, "odd", "Something", 1, 0, 0)))
        override fun games(slug: String?): Flow<List<RommGame>> = flowOf(
            when (slug) {
                "n64" -> listOf(
                    RommGame(5, card(-5, "Legend of Zelda, The - Majora's Mask (USA)", n64), RommPresence.ON_SERVER, sizeBytes = 32),
                    // Already here: this device's.
                    RommGame(6, card(7, "Super Mario 64 (USA)", n64), RommPresence.INSTALLED, game = GameId(7)),
                )
                else -> listOf(RommGame(8, card(-8, "Zelda 3", snes), RommPresence.ON_SERVER))
            },
        )
        override suspend fun download(romId: Long, what: RommDownloadWhat): String? = null.also { asked += "romm:$romId" }
        override fun libraryGame(romId: Long): Flow<GameId?> = flowOf(GameId(100 + romId))
    }

    private val reach = object : ReachOps by ReachOps.None {
        override val supported = true
        override val state = MutableStateFlow(HouseholdState(supported = true, linked = true))
        override val systems = MutableStateFlow(listOf(HouseholdSystem(n64, "Nintendo 64", 1)))
        override fun games(platform: PlatformId?): Flow<List<HouseholdGame>> = flowOf(
            listOf(HouseholdGame(GameId(-900), card(-900, "Majora's Mask", n64), "key", listOf("Gaming PC", "Deck"), onlineHolders = 0, sizeBytes = 64, addedAt = 0)),
        )
        override suspend fun download(id: GameId): String? = null.also { asked += "device:${id.value}" }
        override fun libraryGame(id: GameId): Flow<GameId?> = flowOf(GameId(200))
    }

    @Test
    fun rommsAndTheHouseholdsGamesOfThePortsSystemsAreListed(): Unit = runBlocking {
        val copies = RommAndHousehold(romm, reach).games(setOf("n64"))
        assertEquals(listOf("RomM" to -5L, "Gaming PC" to -900L), copies.map { it.from to it.id.value }, "RomM's not here, then the household's; no other system")
        val fromRomm = copies.first()
        assertEquals(CopyPlace.ROMM, fromRomm.place)
        assertEquals(5L, fromRomm.romId)
        assertEquals(n64, fromRomm.platform)
        assertEquals("https://example.org/-5.png", fromRomm.art.boxart, "drawn with the art Fuse has for it")
        val fromPc = copies.last()
        assertEquals(CopyPlace.DEVICE, fromPc.place)
        assertEquals(false, fromPc.online, "no device that has it is on")
        assertEquals(64L, fromPc.sizeBytes)
    }

    @Test
    fun aCopyIsBroughtFromWhereItIsAndKnownOnceItIsHere(): Unit = runBlocking {
        val s = RommAndHousehold(romm, reach)
        val (fromRomm, fromPc) = s.games(setOf("n64"))
        assertEquals(null, s.bring(fromRomm))
        assertEquals(null, s.bring(fromPc))
        assertEquals(listOf("romm:5", "device:-900"), asked)
        assertEquals(GameId(105), s.arrived(fromRomm).first())
        assertEquals(GameId(200), s.arrived(fromPc).first())
    }

    @Test
    fun withNeitherSetUpNothingIsListed(): Unit = runBlocking {
        assertEquals(emptyList(), RommAndHousehold(RommOps.None, ReachOps.None).games(setOf("n64")))
    }
}
