package io.github.matiyaaa.fuse.ui.shell.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class FolderNameTest {
    @Test
    fun grantedFoldersReadAsWhoseTheyAre() {
        assertEquals("/storage/emulated/0/Emulation/saves", folderName("/storage/emulated/0/Emulation/saves"))
        assertEquals("the folder Eden shares", folderName("tree:content://dev.eden.eden_emulator.user/tree/root%3A"))
        assertEquals("nand/user, shared by Eden", folderName("tree:content://dev.eden.eden_emulator.user/tree/root%3Anand%2Fuser"))
    }
}
