package io.github.matiyaaa.fuse.ui.shell.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class HostPartTest {
    @Test
    fun aTypedAddressIsNamedByItsHostAlone() {
        assertEquals("fuse.example.org", hostPart("https://fuse.example.org/"))
        assertEquals("192.168.1.20", hostPart("http://192.168.1.20:41021"))
        assertEquals("media-server.local", hostPart("  media-server.local:41021/status?x=1 "))
        assertEquals("fe80::1", hostPart("http://[fe80::1]:41021"))
        assertEquals("nas", hostPart("nas"))
    }
}
