package io.github.matiyaaa.fuse.ui.shell.systems

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemOrderTest {
    @Test
    fun theSavedOrderComesFirstAndTheRestKeepTheirPlaces() {
        // The store listed them before the last move was saved: the saved order wins.
        val listed = listOf("snes", "gba", "psx", "n64", "nds")
        assertEquals(listOf("psx", "snes", "gba", "n64", "nds"), SystemOrder.shown(listed, listOf("psx", "snes", "gba")))
        assertEquals(listed, SystemOrder.shown(listed, emptyList()))
    }

    @Test
    fun aSavedSystemThatIsHiddenNowIsIgnoredThenKept() {
        val shown = SystemOrder.shown(listOf("gba", "snes"), listOf("psp", "snes", "gba"))
        assertEquals(listOf("snes", "gba"), shown)
        // Systems without games right now keep their saved place after the shown ones.
        assertEquals(listOf("gba", "snes", "psp"), SystemOrder.save(listOf("gba", "snes"), listOf("psp", "snes", "gba")))
    }

    @Test
    fun twoMovesInARowBothCount() {
        val listed = listOf("a", "b", "c", "d", "e")
        var saved = emptyList<String>()
        fun move(key: String, to: Int) {
            val ids = SystemOrder.shown(listed, saved).toMutableList()
            ids.add(to, ids.removeAt(ids.indexOf(key)))
            saved = SystemOrder.save(ids, saved)
        }
        // The list the screen holds hasn't caught up with either move: the order still follows both.
        move("a", 3)
        move("d", 0)
        assertEquals(listOf("d", "b", "c", "a", "e"), SystemOrder.shown(listed, saved))
    }
}
