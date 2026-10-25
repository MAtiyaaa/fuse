package io.github.matiyaaa.fuse.ui.shell.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotesTest {
    private val notes = """
        # Fuse 0.2.7 - The Swap & Clean Update

        Menus can move to the lower screen.

        ## New

        - **Menus below.** On a handheld with two screens the
          menus can sit on the touch screen.
        - **Add any app**, on any system, by its address.

        ## Fixed

        1. Holding [Delete](https://example.com) removes more than one letter.
    """.trimIndent()

    @Test
    fun wrappedItemsAreJoinedAndBoldLeadsKeptApart() {
        val lines = noteLines(notes)
        val first = lines.first { it.lead == "Menus below" }
        assertEquals("On a handheld with two screens the menus can sit on the touch screen.", first.text)
        val runOn = lines.first { it.text.startsWith("Add any app") }
        assertNull(runOn.lead)
        assertEquals("Add any app, on any system, by its address.", runOn.text)
    }

    @Test
    fun sectionsFollowTheHeadingsAndTheTitleIsLeftOut() {
        val sections = noteSections(notes)
        assertEquals(listOf(null, "New", "Fixed"), sections.map { it.title })
        assertEquals("Menus can move to the lower screen.", sections[0].lines.single().text)
        assertEquals("Holding Delete removes more than one letter.", sections[2].lines.single().text)
    }

    @Test
    fun tableRowsBecomeItemsAndCodeIsLeftOut() {
        val lines = noteLines(
            """
            ## Benchmarks

            Run it again:

            ```
            ./gradlew :ui:fuseline:desktopTest -Pfuse.bench=true
            ```

            | Case | Fuseline 3 | Compose |
            |---|---|---|
            | 1 tweens | 0.82 µs | 1.25 µs |
            | 1 decays | 0.79 µs | n/a |

            After the table.
            """.trimIndent(),
        )
        assertEquals(listOf("Benchmarks", "Run it again:", "Fuseline 3 0.82 µs, Compose 1.25 µs", "Fuseline 3 0.79 µs, Compose n/a", "After the table."), lines.map { it.text })
        assertEquals(listOf(null, null, "1 tweens", "1 decays", null), lines.map { it.lead })
        assertEquals(NoteLine.Kind.ITEM, lines[2].kind)
    }

    @Test
    fun versionAndNameComeFromTheTitle() {
        assertEquals("0.2.7", releaseVersionOf(notes))
        assertEquals("The Swap & Clean Update", releaseNameOf(notes))
    }
}
