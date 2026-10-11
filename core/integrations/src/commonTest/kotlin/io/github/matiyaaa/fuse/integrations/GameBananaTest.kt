package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.gamebanana.GameBanana
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GameBanana's answers, as its API sends them (trimmed from real responses). */
class GameBananaTest {
    private val games = """
        {"_aMetadata": {"_nRecordCount": 2, "_nPerpage": 15, "_bIsComplete": true}, "_aRecords": [
          {"_idRow": 17910, "_sModelName": "Game", "_sName": "Okami HD", "_sProfileUrl": "https://gamebanana.com/games/17910",
           "_aPreviewMedia": {"_aImages": [{"_sType": "icon", "_sUrl": "https://images.gamebanana.com/img/ico/games/6a065c7b28e44.png"},
             {"_sType": "banner", "_sUrl": "https://images.gamebanana.com/img/banners/games/6a013f561c174.jpg"}]}},
          {"_idRow": "8502", "_sModelName": "Game", "_sName": " Persona 3 FES "},
          {"_idRow": 5, "_sModelName": "Member", "_sName": "Someone"}
        ]}
    """.trimIndent()

    private val mods = """
        {"_aMetadata": {"_nRecordCount": 171, "_bIsComplete": false, "_nPerpage": 3}, "_aRecords": [
          {"_idRow": 50307, "_sModelName": "Mod", "_sName": "New Moon Balance Patch", "_sProfileUrl": "https://gamebanana.com/mods/50307",
           "_tsDateModified": 1676240166, "_aSubmitter": {"_sName": "Phoenix!"}, "_aRootCategory": {"_sName": "Game files"},
           "_sVersion": "v4.2.2", "_bIsObsolete": false, "_nLikeCount": 74,
           "_aPreviewMedia": {"_aImages": [{"_sType": "screenshot", "_sBaseUrl": "https://images.gamebanana.com/img/ss/mods",
             "_sFile": "60bf8492f22e8.jpg", "_sFile220": "220-90_60bf8492f22e8.jpg"}]}},
          {"_sName": "no id"}
        ]}
    """.trimIndent()

    private val mod = """
        {"_idRow": 714707, "_bIsPrivate": false, "_bIsTrashed": false, "_bIsWithheld": false, "_sName": "Run Faster",
         "_sProfileUrl": "https://gamebanana.com/mods/714707", "_tsDateModified": 1788778560,
         "_sLicense": "<a rel=\"license\" href=\"http://creativecommons.org/licenses/by-nc-nd/4.0/\">CC BY-NC-ND</a>",
         "_sText": "This&nbsp;mod changes the running speed<br><br><h2>Edit/Adjust Speed</h2><br>Edit the pnach &amp; enjoy",
         "_bIsObsolete": false, "_nLikeCount": 1, "_sVersion": "1.0", "_aCategory": {"_idRow": 12901, "_sName": "Cheats"},
         "_aSubmitter": {"_sName": "Vyou0"},
         "_aFiles": [
          {"_idRow": 1809828, "_sFile": "p3f_run_faster.zip", "_nFilesize": 145262, "_tsDateAdded": 1788776148,
           "_sDownloadUrl": "https://gamebanana.com/dl/1809828", "_sMd5Checksum": "D783238196BD9AC80FA98DD5B9FCEEEB",
           "_sAnalysisState": "done", "_sAnalysisResult": "ok", "_sAnalysisResultVerbose": "File passed preliminary analysis",
           "_sAvState": "done", "_sAvResult": "clean", "_bIsArchived": false, "_sVersion": "v1.0"},
          {"_idRow": 2, "_sFile": "pack.7z", "_nFilesize": "1000", "_sDownloadUrl": "https://gamebanana.com/dl/2",
           "_sAnalysisResult": "ok", "_sAvResult": "clean"},
          {"_idRow": 3, "_sFile": "pack.zip", "_nFilesize": 1000, "_sDownloadUrl": "https://gamebanana.com/dl/3",
           "_sAnalysisResult": "ok", "_sAvResult": "pending"},
          {"_idRow": 4, "_sFile": "x.zip", "_nFilesize": 1000, "_sDownloadUrl": "http://insecure.example/x.zip",
           "_sAnalysisResult": "ok", "_sAvResult": "clean"}
         ]}
    """.trimIndent()

    @Test
    fun gamesAreReadAndOnlyGames() {
        val found = GameBanana.parseGames(games)!!
        assertEquals(listOf(17910L, 8502L), found.map { it.id })
        assertEquals("Persona 3 FES", found[1].name)
        assertEquals("https://gamebanana.com/games/8502", found[1].url)
        assertEquals("https://images.gamebanana.com/img/ico/games/6a065c7b28e44.png", found[0].icon)
        assertNull(GameBanana.parseGames("<html>down</html>"))
    }

    @Test
    fun aGamesModsAreReadWithTheirCategoryAndPicture() {
        val list = GameBanana.parseMods(mods)!!
        assertEquals(171, list.total)
        assertFalse(list.complete)
        val m = list.mods.single()
        assertEquals("New Moon Balance Patch", m.name)
        assertEquals("Game files", m.category)
        assertEquals("v4.2.2", m.version)
        assertEquals("Phoenix!", m.submitter)
        assertEquals(74, m.likes)
        assertEquals("https://images.gamebanana.com/img/ss/mods/220-90_60bf8492f22e8.jpg", m.thumbnail)
    }

    @Test
    fun onlyCheckedZipFilesOverHttpsAreFetched() {
        val page = GameBanana.parseMod(mod)!!
        assertEquals("Cheats", page.mod.category)
        assertEquals("This mod changes the running speed\n\nEdit/Adjust Speed\n\nEdit the pnach & enjoy", page.text)
        assertEquals("CC BY-NC-ND", page.license)
        // The file with an http address isn't offered at all.
        assertEquals(listOf(1809828L, 2L, 3L), page.files.map { it.id })
        val ok = page.files[0]
        assertTrue(ok.fetchable)
        assertEquals("d783238196bd9ac80fa98dd5b9fceeeb", ok.md5)
        assertEquals(145262, ok.size)
        assertNull(ok.notFetchable)
        assertFalse(page.files[1].fetchable)
        assertTrue(".7z" in page.files[1].notFetchable!!)
        assertFalse(page.files[2].fetchable)
        assertTrue("virus scan" in page.files[2].notFetchable!!)
    }

    @Test
    fun hiddenOrMissingModsAreNotOffered() {
        assertNull(GameBanana.parseMod(mod.replace("\"_bIsWithheld\": false", "\"_bIsWithheld\": true")))
        assertNull(GameBanana.parseMod("""{"_sErrorCode": "NOT_FOUND"}"""))
        assertNull(GameBanana.parseMod("not json"))
    }

    @Test
    fun addressesAreBuiltSafely() {
        assertEquals(
            "https://gamebanana.com/apiv11/Util/Search/Results?_sModelName=Game&_nPage=1&_sSearchString=Persona%203%20FES%20%26%20more",
            GameBanana.searchGamesUrl(" Persona 3 FES & more "),
        )
        assertEquals("https://gamebanana.com/apiv11/Mod/714707/ProfilePage", GameBanana.modUrl(714707))
        assertTrue(GameBanana.modsUrl(8502, 2).contains("_nPage=2") && GameBanana.modsUrl(8502, 2).contains("%5BGeneric_Game%5D=8502"))
    }
}
