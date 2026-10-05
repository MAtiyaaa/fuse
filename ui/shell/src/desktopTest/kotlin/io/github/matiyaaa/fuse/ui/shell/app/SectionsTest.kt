package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.model.CartridgeStatus
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseMarks
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one difference between platforms: where the Store exists (Android) the Cartridge section is
 * Addons; everywhere else it stays Cartridge, exactly as before.
 */
class SectionsTest {
    private val elsewhere = Sections(addons = false)
    private val android = Sections(addons = true)
    private val notInstalled = CartridgeStatus(installed = false)
    private val installed = CartridgeStatus(installed = true)

    @Test
    fun withoutTheStoreCartridgeIsUnchanged() {
        assertEquals("Cartridge", elsewhere.label(Destination.CARTRIDGE))
        assertEquals(FuseMarks.Cartridge, elsewhere.icon(Destination.CARTRIDGE))
        // Its tab waits for Cartridge, as it always did.
        assertFalse(elsewhere.showsTab(Destination.CARTRIDGE, offers = true, status = notInstalled))
        assertTrue(elsewhere.showsTab(Destination.CARTRIDGE, offers = true, status = installed))
        // Turned off, Settings doesn't offer it.
        assertFalse(elsewhere.offersTab(Destination.CARTRIDGE, offers = true, prefs = UiPrefs(cartridgeEnabled = false)))
        assertTrue(elsewhere.offersTab(Destination.CARTRIDGE, offers = true, prefs = UiPrefs()))
    }

    @Test
    fun withTheStoreItIsAddonsAndAlwaysThere() {
        assertEquals("Addons", android.label(Destination.CARTRIDGE))
        assertEquals(FuseIcons.Blocks, android.icon(Destination.CARTRIDGE))
        assertTrue(android.showsTab(Destination.CARTRIDGE, offers = true, status = notInstalled))
        assertTrue(android.offersTab(Destination.CARTRIDGE, offers = true, prefs = UiPrefs(cartridgeEnabled = false)))
    }

    @Test
    fun withEverythingInAddonsOffThereIsNoTab() {
        val off = Sections(addons = false, cartridgeOn = false)
        assertFalse(off.showsTab(Destination.CARTRIDGE, offers = true, status = installed))
        assertFalse(off.showsTab(Destination.CARTRIDGE, offers = true, status = notInstalled))
    }

    @Test
    fun otherSectionsAreTheSameEverywhere() {
        for (d in Destination.entries.filter { it != Destination.CARTRIDGE }) {
            assertEquals(elsewhere.label(d), android.label(d))
            assertEquals(elsewhere.icon(d), android.icon(d))
        }
        assertFalse(android.offersTab(Destination.HOME, offers = true, prefs = UiPrefs()))
        assertFalse(android.showsTab(Destination.APPS, offers = false, status = installed))
    }
}
