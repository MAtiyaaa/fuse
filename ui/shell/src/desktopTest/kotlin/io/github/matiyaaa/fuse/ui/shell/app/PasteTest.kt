package io.github.matiyaaa.fuse.ui.shell.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PasteTest {
    @Test
    fun pastedTextBecomesOneTrimmedLine() {
        assertEquals("abc123", cleanPasted("  abc123\n"))
        assertEquals("Super Metroid", cleanPasted("Super\r\nMetroid"))
        assertEquals("a b", cleanPasted("a\t\u0007 b"))
        assertEquals("", cleanPasted("\n\n"))
    }
}
