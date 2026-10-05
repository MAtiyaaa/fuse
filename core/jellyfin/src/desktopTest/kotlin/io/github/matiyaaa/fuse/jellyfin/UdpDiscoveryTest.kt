package io.github.matiyaaa.fuse.jellyfin

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class UdpDiscoveryTest {
    @Test
    fun aServerInDockerIsReachedAtTheAddressItAnsweredFrom() {
        val lan = InetAddress.getByName("192.168.1.20")
        assertEquals("http://192.168.1.20:8096", UdpDiscovery.reachable("http://172.18.0.3:8096", lan))
        assertEquals("https://192.168.1.20:8920/jf", UdpDiscovery.reachable("https://172.18.0.3:8920/jf", lan))
        assertEquals("http://192.168.1.20:8096", UdpDiscovery.reachable("http://192.168.1.20:8096", lan))
        assertEquals("http://172.18.0.3:8096", UdpDiscovery.reachable("http://172.18.0.3:8096", null))
    }
}
