package io.github.matiyaaa.fuse.ui.designsystem.components

import androidx.compose.ui.text.TextRange
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.input.InputSource
import io.github.matiyaaa.fuse.ui.designsystem.input.NavEvent
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyboardTest {

    private fun field(text: String, caret: Int = text.length) = EditableText(text).apply { setCaret(caret) }
    private fun nav(action: NavAction, repeat: Int = 0) = NavEvent(action, repeat, InputSource.GAMEPAD)

    @Test
    fun typingReplacesTheSelectionAndMovesTheCaret() {
        val f = field("Super Mario", caret = 6)
        f.insert("New ")
        assertEquals("Super New Mario", f.text)
        assertEquals(TextRange(10), f.selection)
        f.select(6, 9)
        f.insert("Big")
        assertEquals("Super Big Mario", f.text)
        assertEquals(TextRange(9), f.selection)
    }

    @Test
    fun deletingByCharacterWordAndSelection() {
        val f = field("Final Fantasy VII")
        f.backspace()
        assertEquals("Final Fantasy VI", f.text)
        f.deleteWordBack()
        assertEquals("Final Fantasy ", f.text)
        f.deleteWordBack()
        assertEquals("Final ", f.text)
        f.selectAll()
        f.backspace()
        assertEquals("", f.text)
        f.backspace()
        assertEquals("", f.text)
        val g = field("abc", caret = 1)
        g.deleteForward()
        assertEquals("ac", g.text)
        assertEquals(TextRange(1), g.selection)
    }

    @Test
    fun caretMovesByCharacterAndWordWithoutSplittingSurrogatePairs() {
        val f = field("Go 🎮 now")
        f.moveWord(-1)
        assertEquals(TextRange(6), f.selection)
        f.moveCaret(-2)
        assertEquals(TextRange(3), f.selection, "the pair counts as one character")
        f.moveCaret(-10)
        assertEquals(TextRange(0), f.selection)
        f.moveWord(1)
        assertEquals(TextRange(2), f.selection)
        f.select(0, 2)
        f.moveCaret(1)
        assertEquals(TextRange(2), f.selection, "a selection collapses to its edge first")
    }

    @Test
    fun doubleTapSelectsTheWordUnderTheFinger() {
        val f = field("The Legend of Zelda")
        f.selectWordAt(6)
        assertEquals(TextRange(4, 10), f.selection)
        f.selectWordAt(19)
        assertEquals(TextRange(14, 19), f.selection, "past the end takes the last word")
        f.selectWordAt(10)
        assertEquals(TextRange(4, 10), f.selection, "just after a word takes that word")
        val g = field("Don't stop")
        g.selectWordAt(2)
        assertEquals(TextRange(0, 5), g.selection, "apostrophes belong to the word")
    }

    @Test
    fun shiftTypesOneCapitalAndDoubleTapLocksIt() {
        val state = KeyboardState()
        val f = field("")
        val shift = keyRows(KeyPage.LETTERS)[2].first()
        val q = keyRows(KeyPage.LETTERS)[0].first()
        state.press(shift, f, {})
        state.press(q, f, {})
        state.press(q, f, {})
        assertEquals("Qq", f.text)
        state.press(shift, f, {})
        state.press(shift, f, {})
        assertEquals(ShiftState.LOCK, state.shift)
        state.press(q, f, {})
        state.press(q, f, {})
        assertEquals("QqQQ", f.text)
    }

    @Test
    fun autoCapitalizeStartsSentencesAndDoubleSpaceEndsThem() {
        val state = KeyboardState(autoCapitalize = true)
        val f = field("")
        state.prepare(f)
        val keys = keyRows(KeyPage.LETTERS)
        val h = keys[1].first { it.label == "h" }
        val i = keys[0].first { it.label == "i" }
        val space = keys[3].first { it.kind == KeyKind.SPACE }
        state.press(h, f, {})
        state.press(i, f, {})
        state.press(space, f, {})
        state.press(space, f, {})
        state.press(h, f, {})
        assertEquals("Hi. H", f.text)
    }

    @Test
    fun controllerKeysEditMoveAndSkipTheIndents() {
        val state = KeyboardState()
        val f = field("mario kart")
        assertEquals(NavResult.MOVED, state.handle(nav(NavAction.PREVIOUS_SECTION), f, {}))
        assertEquals(TextRange(9), f.selection)
        state.handle(nav(NavAction.PAGE_UP), f, {})
        assertEquals(TextRange(6), f.selection)
        state.handle(nav(NavAction.SEARCH), f, {})
        assertEquals("mario  kart", f.text)
        state.handle(nav(NavAction.CONTEXT), f, {})
        assertEquals("mario kart", f.text)
        // Held X speeds up to whole words.
        state.handle(nav(NavAction.CONTEXT, repeat = 12), f, {})
        assertEquals("kart", f.text)
        // Down from Q lands on A, never on the half-key indent before it.
        state.row = 0
        state.column = 0
        state.handle(nav(NavAction.DOWN), f, {})
        assertEquals("a", state.rows[state.row][state.column].label)
        assertEquals(NavResult.BLOCKED, state.handle(nav(NavAction.LEFT), f, {}))
    }

    @Test
    fun pageKeysSwitchBetweenLettersNumbersAndSymbols() {
        val state = KeyboardState()
        val f = field("")
        val toNumbers = keyRows(KeyPage.LETTERS)[3].first { it.kind == KeyKind.PAGE }
        state.press(toNumbers, f, {})
        assertEquals(KeyPage.NUMBERS, state.page)
        state.press(keyRows(KeyPage.NUMBERS)[0][6], f, {})
        assertEquals("7", f.text)
        state.press(keyRows(KeyPage.NUMBERS)[2].first { it.kind == KeyKind.PAGE }, f, {})
        assertEquals(KeyPage.SYMBOLS, state.page)
        // Every row of every page is ten letter keys wide.
        for (page in KeyPage.entries) keyRows(page).forEach { row -> assertEquals(10f, row.sumOf { it.weight.toDouble() }.toFloat(), 0.001f) }
    }
}
