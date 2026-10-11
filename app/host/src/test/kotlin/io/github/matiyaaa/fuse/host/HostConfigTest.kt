package io.github.matiyaaa.fuse.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostConfigTest {
    @Test
    fun readsTheEnvironmentAndFallsBackToSafeDefaults() {
        val c = HostConfig.from(mapOf("FUSE_ONE_DATA" to "/srv/fuse", "FUSE_ONE_PORT" to "48000", "FUSE_ONE_NAME" to "  Mini PC  "))
        assertEquals(File("/srv/fuse"), c.dataDir)
        assertEquals(48000, c.port)
        assertEquals("Mini PC", c.name)
        // A port outside what a non-root process may use, or not a number, is the default.
        assertEquals(HostConfig.DEFAULT_PORT, HostConfig.from(mapOf("FUSE_ONE_PORT" to "80")).port)
        assertEquals(HostConfig.DEFAULT_PORT, HostConfig.from(mapOf("FUSE_ONE_PORT" to "lots")).port)
        assertEquals(File("/data"), HostConfig.from(emptyMap()).dataDir)
        assertTrue(HostConfig.from(emptyMap()).name.isNotBlank())
    }

    @Test
    fun theBannerSaysWhereToReachItAndNoSecret() {
        val c = HostConfig(File("/data"), 47311, "Den")
        val b = HostConfig.banner(c, listOf("192.168.1.50", "100.64.0.2"))
        assertTrue("Fuse One Host is ready" in b)
        assertTrue("192.168.1.50:47311" in b)
        assertTrue("100.64.0.2:47311" in b)
        // The admin token and pairing codes are never printed unasked.
        assertFalse("token" in b.lowercase())
        assertFalse(Regex("\\b\\d{6}\\b").containsMatchIn(b.replace("47311", "")))
    }

    @Test
    fun sizesReadAsPeopleSayThem() {
        assertEquals("512 B", HostConfig.bytes(512))
        assertEquals("2 KB", HostConfig.bytes(2048))
        assertEquals("3 MB", HostConfig.bytes(3L * 1024 * 1024))
        assertEquals("1.5 GB", HostConfig.bytes(3L * 512 * 1024 * 1024))
    }
}
