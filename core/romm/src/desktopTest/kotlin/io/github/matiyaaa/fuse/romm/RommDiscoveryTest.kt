package io.github.matiyaaa.fuse.romm

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RommDiscoveryTest {
    @Test
    fun onlyWhatAnswersAsRommIsFoundAndTheKnownHostsComeFirst() = runBlocking {
        var asked: List<String> = emptyList()
        val found = RommDiscovery.find(
            hints = listOf("http://192.168.1.30:8096", "", "192.168.1.30:8384"),
            listen = { hosts, ports ->
                asked = hosts
                assertEquals(RommDiscovery.PORTS, ports)
                listOf("192.168.1.30" to 8080, "192.168.1.30" to 80, "192.168.1.9" to 8080, "192.168.1.40" to 443)
            },
            ask = { address ->
                when (address) {
                    "http://192.168.1.30:8080" -> "4.1.0"
                    "http://192.168.1.30" -> "4.1.0"
                    "https://192.168.1.40" -> "3.10.2"
                    else -> null
                }
            },
        )
        // Jellyfin's or Fuse Sync's host is asked first, once, then RomM's usual names.
        assertEquals(listOf("192.168.1.30") + RommDiscovery.NAMES, asked)
        // A server behind its proxy too is one server; something else listening is passed over.
        assertEquals(listOf(FoundRomm("http://192.168.1.30:8080", "4.1.0"), FoundRomm("https://192.168.1.40", "3.10.2")), found)
    }

    @Test
    fun addressesAreReadForTheirHost() {
        assertEquals("192.168.1.20", RommDiscovery.hostOf("http://192.168.1.20:8096/jellyfin"))
        assertEquals("nas.lan", RommDiscovery.hostOf("NAS.lan:8080"))
        assertEquals("fd00::5", RommDiscovery.hostOf("http://[fd00::5]:8080"))
        assertEquals(null, RommDiscovery.hostOf("http://localhost:8080"))
        assertEquals(null, RommDiscovery.hostOf("  "))
    }

    @Test
    fun theHomeNetworkIsSweptAndThisDeviceToo() {
        val around = neighboursOf(listOf("10.0.0.7", "192.168.1.12"))
        assertEquals("192.168.1.1", around.first())
        assertTrue("192.168.1.12" in around)
        assertEquals(254 * 2, around.size)
    }
}
