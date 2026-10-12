package io.github.matiyaaa.fuse.library.parse

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Files people number to keep them in order ("1 - Mario Kart", "1. Mario Kart", "3- Mario Kart")
 * show and search by the game's name, through every step a scanned file goes: parsing, the
 * display title and the search title. Names that start with a number of their own keep it.
 */
class ListNumberNamesTest {
    private fun display(file: String) = DisplayNameCleaner.clean(FilenameParser.parse(file).baseTitle.let { file.substringBeforeLast('.') })
    private fun search(file: String) = SearchTitles.clean(file.substringBeforeLast('.'))

    @Test
    fun numberedFilesShowAndSearchByTheGamesName() {
        for (file in listOf(
            "1 - Mario Kart.z64", "1. Mario Kart.z64", "1.Mario Kart.z64", "3- Mario Kart.z64", "3 - Mario Kart.z64",
            "3 -Mario Kart.z64", "12) Mario Kart.z64", "01_Mario Kart.z64", "1 – Mario Kart.z64", "#4 Mario Kart.z64",
            "2. Mario Kart (USA).z64", "07 - Mario Kart [!].z64",
        )) {
            assertEquals("Mario Kart", display(file), file)
            assertEquals("Mario Kart", search(file), file)
        }
    }

    @Test
    fun namesThatStartWithANumberKeepIt() {
        assertEquals("2Pak Yellow", search("2Pak Yellow.gb"))
        assertEquals("2Pak Yellow", display("2Pak Yellow.gb"))
        assertEquals("2Pak Yellow", search("1 - 2Pak Yellow.gb"))
        assertEquals("1942", search("1942.nes"))
        assertEquals("1080 Snowboarding", search("1080 Snowboarding.z64"))
        assertEquals("10-Yard Fight", display("10-Yard Fight.nes"))
        assertEquals("1943 - The Battle of Midway", display("1943 - The Battle of Midway.nes"))
        assertEquals("007 GoldenEye", display("007 GoldenEye.z64"))
        assertEquals("3-D WorldRunner", display("3-D WorldRunner.nes"))
    }

    @Test
    fun aSceneSwitchNameSearchesAsTheGame() {
        // The name another device's save carries, matched to this library's game by its search title.
        assertEquals("Red Dead Redemption", search("[ROMsNS] Red Dead Redemption v0 (01007820196A6000) (BASE).nsp"))
    }
}
