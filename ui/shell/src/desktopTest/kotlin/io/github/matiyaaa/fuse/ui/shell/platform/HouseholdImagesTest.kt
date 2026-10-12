package io.github.matiyaaa.fuse.ui.shell.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HouseholdImagesTest {
    @Test
    fun onlyTheProvidersPicturesAreSharedAndTheHostSeesOnlyTheirHash() {
        assertTrue(HouseholdImages.shared("https://cdn2.steamgriddb.com/grid/abc.png"))
        assertTrue(HouseholdImages.shared("https://images.igdb.com/igdb/image/upload/t_cover_big/co1.jpg"))
        assertTrue(HouseholdImages.shared("https://thumbnails.libretro.com/Nintendo%20-%20SNES/Named_Boxarts/x.png"))
        // A person's own servers and files are never sent to the host.
        assertFalse(HouseholdImages.shared("http://192.168.1.20:8096/Items/1/Images/Primary"))
        assertFalse(HouseholdImages.shared("https://romm.example.com/assets/cover.png"))
        assertFalse(HouseholdImages.shared("https://evilsteamgriddb.com/x.png"), "a lookalike name isn't a provider")
        assertFalse(HouseholdImages.shared("file:///home/mo/cover.png"))
        val key = HouseholdImages.key("https://cdn2.steamgriddb.com/grid/abc.png")
        assertEquals(64, key.length)
        assertEquals(key, HouseholdImages.key("https://cdn2.steamgriddb.com/grid/abc.png"))
    }
}
