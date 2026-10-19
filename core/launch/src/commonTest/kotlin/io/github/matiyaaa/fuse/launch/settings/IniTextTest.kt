package io.github.matiyaaa.fuse.launch.settings

import io.github.matiyaaa.fuse.launch.patches.IniText
import kotlin.test.Test
import kotlin.test.assertEquals

/** An ini file changed one value at a time, each line keeping the form it was written in. */
class IniTextTest {
    @Test
    fun aValueChangesAndNothingElseDoes() {
        // As DevilutionX writes diablo.ini (Ini::serialize, Source/utils/ini.cpp): key=value, CRLF.
        val text = "[Graphics]\r\nFullscreen=1\r\nScaling Quality=2\r\n\r\n[Audio]\r\nWalking Sound=1\r\n"
        val ini = IniText(text)
        ini.set("Graphics", "Fullscreen", "0")
        ini.set("Graphics", "Show FPS", "1")
        assertEquals("[Graphics]\r\nFullscreen=0\r\nScaling Quality=2\r\nShow FPS=1\r\n\r\n[Audio]\r\nWalking Sound=1\r\n", ini.toString())
        // A file with spaces keeps them, the value's own spacing too.
        val spaced = IniText("[Core]\nCPUThread = True\nGFXBackend =  Vulkan\n")
        spaced.set("Core", "GFXBackend", "OGL")
        spaced.set("Core", "Fastmem", "False")
        assertEquals("[Core]\nCPUThread = True\nGFXBackend =  OGL\nFastmem = False\n", spaced.toString())
    }
}
