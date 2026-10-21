package io.github.matiyaaa.fuse.integrations.ports

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.json
import io.github.matiyaaa.fuse.model.AndroidPort
import io.github.matiyaaa.fuse.model.AndroidPortKind
import io.github.matiyaaa.fuse.model.AndroidPortStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Open Ports Games' catalogue read for its Android ports, and Fuse's own checked entries. */
class OpenPortsTest {
    @Test
    fun androidGamesAreReadAsUncheckedPortsWithTheLibrarysSystems() {
        val listing = OpenPorts.read(FEED).getOrThrow()
        assertEquals("2026-10-18", listing.generated)
        assertEquals(listOf("zelda64-recomp-android", "gen1recomp", "unknown-system"), listing.ports.map { it.id })
        val mm = listing.ports.first()
        assertEquals("Zelda64Recomp (Android)", mm.name)
        assertEquals("The Legend of Zelda: Majora's Mask", mm.game)
        assertEquals(listOf("n64"), mm.systems)
        assertEquals(AndroidPortKind.RECOMPILATION, mm.kind)
        assertEquals(AndroidPortStatus.BETA, mm.status)
        assertEquals("https://github.com/linkzenic/Zelda64Recomp-Android", mm.repo)
        assertEquals("GPL-3.0", mm.licence)
        assertFalse(mm.checked, "a listing is never one Fuse checked")
        assertTrue(mm.installable)
        val red = listing.ports[1]
        assertEquals(listOf("gb"), red.systems)
        assertNull(red.licence, "NOASSERTION is no licence")
        assertTrue(red.listedVerified)
        assertEquals(emptyList(), listing.ports[2].systems, "a system Fuse doesn't have matches no game")
    }

    @Test
    fun aListingsGameDetailsAndBoxArtAreRead() {
        val ports = OpenPorts.read(FEED).getOrThrow().ports.associateBy { it.id }
        val red = ports.getValue("gen1recomp")
        assertEquals("Nintendo", red.publisher)
        assertEquals(1996, red.year)
        assertEquals("rpg", red.genre)
        assertEquals("https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy/Named_Boxarts/Pokemon%20-%20Red.png", red.cover)
        assertEquals("Box art", red.coverCredit)
        assertEquals("https://example.org/red.png", red.screenshot, "the first picture over HTTPS")
        // Majora's Mask has none of its own: another listing's of the same game, a computer's here.
        val mm = ports.getValue("zelda64-recomp-android")
        assertEquals("https://upload.wikimedia.org/wikipedia/en/6/60/Majora.jpg", mm.cover)
        assertEquals("Wikipedia", mm.coverCredit)
        assertNull(mm.screenshot)
        val other = ports.getValue("unknown-system")
        assertNull(other.cover, "a picture not over HTTPS is no picture")
        assertNull(other.genre, "a genre that isn't a word or two")
        assertNull(other.year, "a year no game came out in")
    }

    @Test
    fun toolsDeadSourcesWarningsAndOtherSystemsAreLeftOut() {
        val ids = OpenPorts.read(FEED).getOrThrow().ports.map { it.id }
        for (out in listOf("desktop-only", "a-launcher", "gone", "ships-the-game")) assertFalse(out in ids, out)
    }

    @Test
    fun aListingsDashesBecomeCommasOrHyphens() {
        assertEquals("Undead Neighbor Eaters, Android Native Port", OpenPorts.plain("Undead Neighbor Eaters \u2014 Android Native Port"))
        assertEquals("Mission Pack, Portal of Praevus", OpenPorts.plain("Mission Pack \u2013 Portal of Praevus\u2060"))
        assertEquals("Released 1996-1998", OpenPorts.plain("Released 1996\u20131998"))
    }

    @Test
    fun anotherCatalogueOrANewerFormIsRefused() {
        assertTrue(OpenPorts.read("""{"schema":"something/else","version":2,"ports":[]}""").isFailure)
        assertTrue(OpenPorts.read("""{"schema":"openportsgames/catalog","version":3,"ports":[]}""").isFailure)
        assertTrue(OpenPorts.read("not json").isFailure)
    }

    @Test
    fun fusesOwnAndroidEntriesAreCheckedBeforeTheyAreOffered() {
        val good = AndroidPort(id = "starship-android", name = "Starship", game = "Star Fox 64", systems = listOf("n64"), repo = "https://github.com/izzy2lost/Starship", packageName = "com.starship.android", asset = "\\.apk$", roms = listOf("163fd3fc3813f54e"))
        assertNull(androidPortProblem(good))
        assertNotNull(androidPortProblem(good.copy(repo = "http://github.com/izzy2lost/Starship")), "not HTTPS")
        assertNotNull(androidPortProblem(good.copy(repo = "https://example.org/starship")), "not GitHub")
        assertNotNull(androidPortProblem(good.copy(packageName = "starship")), "not a package")
        assertNotNull(androidPortProblem(good.copy(asset = "(")), "an asset pattern that can't be read")
        assertNotNull(androidPortProblem(good.copy(roms = listOf("not-a-hash"))))
        assertNotNull(androidPortProblem(good.copy(id = "Has Spaces")))
    }

    @Test
    fun fusesOwnListKeepsOnlyPicturesOverHttps() {
        val text = """
            {"format":1,"revision":5,"ports":[],"android":[
              {"id":"starship-android","name":"Starship","game":"Star Fox 64","systems":["n64"],"repo":"https://github.com/izzy2lost/Starship","cover":"https://example.org/sf.png","coverCredit":"Box art"},
              {"id":"perfect-dark-android","name":"Perfect Dark","game":"Perfect Dark","systems":["n64"],"repo":"https://github.com/izzy2lost/Perfect-Dark","cover":"http://example.org/pd.png","coverCredit":"Box art"}
            ]}
        """.trimIndent()
        val android = PortsIndexReader.read(text).getOrThrow().index.android.associateBy { it.id }
        assertEquals("https://example.org/sf.png", android.getValue("starship-android").cover)
        assertEquals("Box art", android.getValue("starship-android").coverCredit)
        val pd = android.getValue("perfect-dark-android")
        assertNull(pd.cover, "a picture over plain HTTP is left out")
        assertNull(pd.coverCredit)
    }

    @Test
    fun theSignedListsAndroidEntriesAreFusesOwnAndBadOnesAreLeftOut() {
        val text = """
            {"format":1,"revision":4,"ports":[],"android":[
              {"id":"starship-android","name":"Starship","game":"Star Fox 64","systems":["n64"],"repo":"https://github.com/izzy2lost/Starship","package":"com.starship.android","listedVerified":true},
              {"id":"bad","name":"Bad","game":"Bad","repo":"https://example.org/bad"},
              {"id":"starship-android","name":"Again","game":"Star Fox 64","repo":"https://github.com/izzy2lost/Starship"}
            ]}
        """.trimIndent()
        val list = PortsIndexReader.read(text).getOrThrow()
        val only = list.index.android.single()
        assertEquals("com.starship.android", only.packageName)
        assertTrue(only.checked, "every entry of Fuse's list is checked")
        assertFalse(only.listedVerified)
        assertEquals(setOf("android:bad", "android:starship-android"), list.skipped.keys)
    }

    @Test
    fun theCatalogueIsFetchedAndRead() = runTest {
        val http = TestHttp { json(FEED) }
        val r = OpenPortsClient(http.client).fetch()
        assertIs<ApiResult.Success<OpenPorts.Listing>>(r)
        assertEquals(3, r.value.ports.size)
        assertEquals(OpenPortsClient.FEED_URL, http.last.url.toString())
    }

    private companion object {
        val FEED = """
            {"schema":"openportsgames/catalog","version":2,"generatedAt":"2026-10-18","site":"https://openportsgames.com/","ports":[
              {"id":"zelda64-recomp-android","kind":"game","title":"Zelda64Recomp (Android)","game":"The Legend of Zelda: Majora's Mask","portType":"recompilation","platforms":["android"],"status":"beta","sources":["https://github.com/linkzenic/Zelda64Recomp-Android"],"license":{"spdx":"GPL-3.0"},"verified":false,"originalSystems":["Nintendo 64"],"notes":"Majora's Mask, recompiled.","cover":null},
              {"id":"gen1recomp","kind":"game","title":"Gen1Recomp (Pokémon Red)","game":"Pokémon Red","portType":"reimplementation","platforms":["windows","android"],"status":"beta","sources":["https://github.com/bryanthaboi/gen1recomp"],"license":{"spdx":"NOASSERTION","note":"none"},"verified":true,"originalSystems":["Game Boy"],
               "publisher":"Nintendo","originalYear":1996,"genre":"rpg","cover":{"src":"https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy/Named_Boxarts/Pokemon%20-%20Red.png","alt":"Pokémon Red (box art)","credit":"Box art"},
               "screenshots":[{"src":"http://example.org/insecure.png"},{"src":"https://example.org/red.png","credit":"Libretro"}]},
              {"id":"unknown-system","kind":"game","title":"Something","game":"Something","portType":"source-port","platforms":["android"],"status":"alpha","sources":["https://github.com/someone/something"],"license":{"spdx":"MIT"},"verified":false,"originalSystems":["Unknown"],
               "genre":"Not A Genre!","originalYear":12,"cover":{"src":"http://example.org/something.png"}},
              {"id":"desktop-only","kind":"game","title":"Desktop","game":"The Legend of Zelda - Majora's Mask","platforms":["windows"],"sources":["https://github.com/someone/desktop"],"originalSystems":["Nintendo 64"],
               "cover":{"src":"https://upload.wikimedia.org/wikipedia/en/6/60/Majora.jpg","credit":"Wikipedia"}},
              {"id":"a-launcher","kind":"tool","title":"Launcher","game":"Launcher","platforms":["android"],"sources":["https://github.com/someone/launcher"]},
              {"id":"gone","kind":"game","title":"Gone","game":"Gone","platforms":["android"],"sources":["https://github.com/someone/gone"],"sourceDead":true},
              {"id":"ships-the-game","kind":"game","title":"Ships It","game":"Ships It","platforms":["android"],"sources":["https://github.com/someone/ships"],"warning":"This Android port distributes the game's own files."}
            ]}
        """.trimIndent()
    }
}
