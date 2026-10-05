package io.github.matiyaaa.fuse.ui.shell.onboarding

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooksAtHomeTest {
    @Test
    fun privateAddressesAndLocalNamesAreHome() {
        assertTrue(looksAtHome("http://192.168.1.20:8096"))
        assertTrue(looksAtHome("10.0.0.5:8096"))
        assertTrue(looksAtHome("http://172.20.1.2:8096/jellyfin"))
        assertTrue(looksAtHome("http://nas.local:8096"))
        assertTrue(looksAtHome("http://100.101.1.1:8096"))
    }

    @Test
    fun publicAddressesAreNot() {
        assertFalse(looksAtHome("https://jellyfin.example.com"))
        assertFalse(looksAtHome("http://172.32.0.1:8096"))
        assertFalse(looksAtHome("https://8.8.8.8"))
    }
}
