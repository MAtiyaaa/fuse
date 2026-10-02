package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Compatibility
import io.github.matiyaaa.fuse.integrations.rpcs3.Rpcs3Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Rpcs3CompatibilityTest {
    // The shape rpcs3.net/compatibility?api=v1&g=<id> answers with.
    private val found = """
        {"return_code": 0, "results": {
          "BLES00932": {"title": "Demon's Souls", "alternative-title": null, "wiki-id": 4, "status": "Playable",
            "date": "2020-05-04", "thread": 176925, "commit": "9a4c26d", "pr": 8142, "network": 0},
          "BLUS30443": {"title": "Demon's Souls", "alternative-title": null, "wiki-id": 4, "status": "Playable",
            "date": "2020-05-04", "thread": 194290, "commit": "9a4c26d", "pr": 8142, "network": 0}
        }}
    """.trimIndent()

    // For an id it doesn't know, the list searches text instead and answers with other games.
    private val searched = """
        {"return_code": 2, "search_term": "F1 2010", "results": {
          "BLES00917": {"title": "F1 2010", "status": "Playable", "date": "2026-04-14", "thread": 198784}
        }}
    """.trimIndent()

    @Test
    fun onlyTheExactTitleIdIsTrusted() {
        val c = Rpcs3Compatibility.parse(found, "BLUS30443")!!
        assertEquals(Rpcs3Status.PLAYABLE, c.status)
        assertEquals("Demon's Souls", c.title)
        assertEquals("2020-05-04", c.date)
        assertEquals("https://forums.rpcs3.net/thread-194290.html", c.reportUrl)
        assertNull(Rpcs3Compatibility.parse(found, "BLUS99999"))
        assertNull(Rpcs3Compatibility.parse(searched, "BLES00917"))
        assertNull(Rpcs3Compatibility.parse("not json", "BLUS30443"))
    }

    @Test
    fun titleIdsLookLikeTitleIds() {
        assertTrue(Rpcs3Compatibility.isTitleId("NPUB30001"))
        assertFalse(Rpcs3Compatibility.isTitleId("SLUS-20946"))
        assertFalse(Rpcs3Compatibility.isTitleId("npub30001"))
    }
}
