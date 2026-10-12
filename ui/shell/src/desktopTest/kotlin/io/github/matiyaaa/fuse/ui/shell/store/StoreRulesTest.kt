package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.StoreVariant
import io.github.matiyaaa.fuse.ui.shell.addons.byMaker
import io.github.matiyaaa.fuse.ui.shell.store.impl.managedOf
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreRulesTest {

    private fun app(key: String, vararg systems: String) = StoreApp(
        key = key, id = key, packageName = null, pinned = false, name = key, author = "", about = null,
        categories = listOf("Emulator"), sourceUrl = "https://github.com/x/$key", sourceHost = "github.com",
        sourceKind = SourceKind.GITHUB, availability = Availability.INSTALLABLE, allowIdChange = false,
        systems = systems.map(::PlatformId), color = null, inFuse = InFuse.LAUNCHES_GAMES,
    )

    @Test
    fun emulatorsGoUnderTheMakerOfTheirConsoles() {
        val groups = byMaker(
            listOf(
                app("dolphin", "ngc", "wii"), app("duckstation", "psx"), app("xemu", "xbox"), app("flycast", "dc", "arcade"),
                app("winlator", "win"), app("mame", "arcade"), app("retroarch", "nes", "psx", "genesis"), app("stella", "atari2600"),
            ),
        ).associate { (name, apps) -> name to apps.map { it.key } }
        assertEquals(listOf("dolphin"), groups["Nintendo"])
        assertEquals(listOf("duckstation"), groups["PlayStation"])
        assertEquals(listOf("xemu"), groups["Xbox"])
        // Its console makes it Sega's, even though it plays arcade boards too.
        assertEquals(listOf("flycast"), groups["Sega"])
        // Windows games aren't Xbox games because Microsoft makes both.
        assertEquals(listOf("winlator"), groups["PC and computers"])
        assertEquals(listOf("mame"), groups["Arcade"])
        assertEquals(listOf("retroarch"), groups["Many systems"])
        assertEquals(listOf("stella"), groups["Classic consoles"])
        // In that order: the big console makers first, many systems last.
        val order = byMaker(listOf(app("r", "nes", "psx"), app("d", "ngc"), app("p", "psx"))).map { it.first }
        assertEquals(listOf("Nintendo", "PlayStation", "Many systems"), order)
    }

    @Test
    fun onlyFusesOwnInstallsAreUpdatedFromTheirReleasesAndLeftOutOnesWait() {
        val apps = listOf(app("mine", "psx"), app("flat", "ps2"), app("sys", "gc"), app("held", "nes"))
        fun release(v: String) = ReleaseCheck.Ready(StoreRelease(v, null, null, "", StoreFile("f", "u", 1, null), null), 0)
        val state = StoreState(
            catalogue = StoreCatalogue(StoreVariant.STANDARD, apps, emptyList(), null, 0, ""),
            installed = apps.associate { it.key to InstalledApp(it.key, "1.0", 0, it.name) },
            releases = apps.associate { it.key to release("2.0") },
            managed = mapOf("flat" to Managed.FLATPAK, "sys" to Managed.SYSTEM),
            excluded = setOf("held"),
        )
        assertEquals(listOf("mine", "held"), state.updates.map { it.key })
        assertEquals(listOf("mine"), state.updatable.map { it.key })
    }

    @Test
    fun whoLooksAfterAProgramFollowsHowItWasFound() {
        assertEquals(Managed.FLATPAK, managedOf("org.DolphinEmu.dolphin-emu", "Flatpak"))
        assertEquals(Managed.SYSTEM, managedOf("/usr/bin/pcsx2-qt", "PATH"))
        assertEquals(Managed.SYSTEM, managedOf("/opt/rpcs3/rpcs3", "PATH"))
        assertEquals(Managed.ELSEWHERE, managedOf("/home/deck/Applications/Cemu.AppImage", "AppImage"))
    }
}
