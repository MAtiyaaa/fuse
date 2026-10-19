package io.github.matiyaaa.fuse.launch.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RPCN's account file and password, and each emulator's values for playing together. */
class PlayTogetherTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha3AndItsHmacAndPbkdf2AreFips202s() {
        // FIPS 202's own examples, then ones made with OpenSSL (Python's hashlib and hmac).
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", hex(Sha3.sha256(ByteArray(0))))
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", hex(Sha3.sha256("abc".encodeToByteArray())))
        assertEquals("cce34485baf2bf2aca99b94833892a4f52896d3d153f7b840cc4f9fe695f1387", hex(Sha3.sha256(ByteArray(200) { 'a'.code.toByte() })), "more than one block")
        assertEquals("8c6e0683409427f8931711b10ca92a506eb1fafa48fadd66d76126f47ac2c333", hex(Sha3.hmac("key".encodeToByteArray(), "The quick brown fox jumps over the lazy dog".encodeToByteArray())))
        assertEquals("4beeb04d637f2720b7472094d5aec0e1a6d78e52b0e4efc9870196be1593f4f2", hex(Sha3.hmac(ByteArray(200) { 'k'.code.toByte() }, "msg".encodeToByteArray())), "a key longer than a block")
        val salt = "No matter where you go, everybody's connected.".encodeToByteArray()
        assertEquals("F978CA0EEE6DED4B61E6856878E164B6582AE8B2043DE0ADDB853894484F8AA3", hex(Sha3.pbkdf2("password".encodeToByteArray(), salt, 2, 32)).uppercase())
    }

    @Test
    fun rpcnsPasswordIsStretchedAsRpcs3sOwnDialogDoes() {
        assertEquals("E4F60B0FE5A32FF53523ED7C9009869E6624E9C243FDA987DB171B5AA71E94A6", Rpcn.derivePassword("correct horse"))
        assertTrue(Rpcn.validUser("Ana_99") && Rpcn.validUser("a-b"))
        assertFalse(Rpcn.validUser("Ana Lee") || Rpcn.validUser("seventeen_letters") || Rpcn.validUser(""))
        assertTrue(Rpcn.validToken("A1B2C3D4E5F6A7B8"))
        assertFalse(Rpcn.validToken("A1B2C3D4E5F6A7B8C") || Rpcn.validToken("a b"))
    }

    @Test
    fun theAccountFileIsSignedInAndOutKeepingTheRest() {
        // A file as RPCS3 writes it, with a server of the person's own added.
        val existing = "Version: 2\nHost: np.rpcs3.net\nNPID: RPCS3_AbCdEfGhIj\nPassword: \"\"\nToken: \"\"\n" +
            "Hosts: \"Official RPCN Server|np.rpcs3.net|||Home|rpcn.home.lan\"\nExperimental IPv6 support: false\n"
        assertNull(Rpcn.account(existing), "a made-up user name and no password: not signed in")
        val signed = Rpcn.signIn(existing, "Ana_99", "E4F6", "TOKEN123")
        assertEquals(Rpcn.Account("Ana_99", "np.rpcs3.net", hasToken = true), Rpcn.account(signed))
        assertTrue("Hosts: \"Official RPCN Server|np.rpcs3.net|||Home|rpcn.home.lan\"" in signed, signed)
        assertTrue("Experimental IPv6 support: false" in signed)
        val out = Rpcn.signOut(signed)
        assertNull(Rpcn.account(out))
        assertTrue("NPID: Ana_99" in out && "E4F6" !in out && "TOKEN123" !in out, out)
        // No file yet: made from RPCS3's own defaults.
        val made = Rpcn.signIn(null, "Ana_99", "E4F6", "TOKEN123")
        assertEquals("np.rpcs3.net", Rpcn.account(made)?.host)
        assertTrue(made.startsWith("Version: 2\n"))
    }

    @Test
    fun eachEmulatorsValuesForPlayingTogether() {
        assertEquals(listOf("--host", "--nick=Ana"), PlayTogether.retroArchArgs(null, nick = "Ana"))
        assertEquals(listOf("--connect=192.168.1.20", "--port=55436"), PlayTogether.retroArchArgs("192.168.1.20", 55436, nick = null))
        // Before the core, after however RetroArch is started (a Flatpak, through Steam).
        val id = io.github.matiyaaa.fuse.model.EmulatorId("linux.retroarch")
        val target = io.github.matiyaaa.fuse.model.LaunchTarget.File("/roms/snes/a.sfc")
        val flatpak = io.github.matiyaaa.fuse.model.LaunchPlan.Command(id, listOf("flatpak", "run", "org.libretro.RetroArch", "-L", "/cores/snes9x_libretro.so", "/roms/snes/a.sfc"), target = target)
        assertEquals(
            listOf("flatpak", "run", "org.libretro.RetroArch", "--host", "-L", "/cores/snes9x_libretro.so", "/roms/snes/a.sfc"),
            (PlayTogether.withNetplay(flatpak, listOf("--host")) as io.github.matiyaaa.fuse.model.LaunchPlan.Command).argv,
        )
        assertNull(PlayTogether.withNetplay(flatpak.copy(argv = listOf("mgba", "/roms/a.gba")), listOf("--host")), "no -L: never guessed")
        val host = PlayTogether.ppsspp("192.168.1.20", hosting = true, nick = "Ana <3")
        assertEquals(
            listOf("EnableWlan" to "True", "EnableAdhocServer" to "True", "proAdhocServer" to "192.168.1.20", "NickName" to "Ana 3"),
            host.map { it.key to it.value },
        )
        assertEquals("False", PlayTogether.ppsspp("192.168.1.20", hosting = false, nick = null).single { it.key == "EnableAdhocServer" }.value)
        // DefaultInfrastructureUsername's rule: letters, digits, - and _, 3 to 16, not PPSSPP's own.
        assertEquals("AnaLee", PlayTogether.ppssppUsername("Ana Lee"))
        assertEquals("a-very_long_name", PlayTogether.ppssppUsername("a-very_long_name_indeed"))
        assertNull(PlayTogether.ppssppUsername("Zoë"))
        assertNull(PlayTogether.ppssppUsername("PPSSPP"))
        assertEquals(listOf("Nickname", "TraversalChoice", "Address"), PlayTogether.dolphin("192.168.1.20", "Ana").map { it.key })
        assertEquals(listOf("Nickname"), PlayTogether.dolphin(null, "Ana").map { it.key })
        assertEquals(
            listOf("Nickname" to "Ana", "TraversalChoice" to "traversal", "HostCode" to "1a2b3c4d"),
            PlayTogether.dolphinCode("1a2b3c4d", "Ana").map { it.key to it.value },
        )
        assertEquals(OnlineValue(null, "Core", "EnableWiiLink", "True"), PlayTogether.wiiLink(true))
        assertEquals("False", PlayTogether.wiiLink(false).value)
        // As the traversal server makes them ({:08x}): typed in any case, kept in its.
        assertEquals("1a2b3c4d", PlayTogether.hostCode(" 1A2B3C4D "))
        assertNull(PlayTogether.hostCode("1a2b3c4"))
        assertNull(PlayTogether.hostCode("1a2b3c4g"))
        assertNull(PlayTogether.hostCode("1a2b3c4d5"), "Dolphin refuses a code over 8")
        // Ready only when every value is there, a switch's case aside.
        val have = mapOf("EnableWlan" to "true", "EnableAdhocServer" to "True", "proAdhocServer" to "192.168.1.20", "NickName" to "Ana 3")
        assertTrue(PlayTogether.ready(host) { have[it.key] })
        assertFalse(PlayTogether.ready(host) { (have - "proAdhocServer")[it.key] })
        assertEquals("192.168.1.20", PlayTogether.address(" 192.168.1.20 "))
        assertEquals("deck.local", PlayTogether.address("deck.local"))
        assertNull(PlayTogether.address("192.168.1.20:55435"))
        assertNull(PlayTogether.address("http://x"))
        assertEquals(io.github.matiyaaa.fuse.launch.online.TogetherWay.AD_HOC, PlayTogether.way("ppsspp"))
        assertNull(PlayTogether.way("xemu"))
    }

    @Test
    fun shadPs4sNetworkAndShadNetAreWhereItsVersionKeepsThem() {
        assertEquals(TogetherWay.ONLINE_SERVICE, PlayTogether.way("shadps4"))
        val newest = listOf(OnlineValue(null, "Network", "connected_to_network", "true"), OnlineValue(null, "Network", "shad_net_enabled", "true"))
        assertEquals(newest, PlayTogether.shadPs4("0.20.0"))
        assertEquals(newest, PlayTogether.shadPs4(null), "a version Fuse can't tell: the newest's")
        assertEquals(listOf("General", "General"), PlayTogether.shadPs4("v.0.19.0").map { it.section }, "its General group until 0.19")
    }

    @Test
    fun azaharsAndEdensRoomsTakeTheNameTheirOwnCheckAllowsAndStayUnlisted() {
        for (family in listOf("azahar", "lime3ds", "eden", "eden-nightly", "citron")) assertEquals(TogetherWay.ROOMS, PlayTogether.way(family), family)
        assertEquals(TogetherWay.IN_EMULATOR, PlayTogether.way("cemu"))
        // multiplayer/validation.h: ^[a-zA-Z0-9._\- ]{4,20}$.
        assertEquals("Ana 3", PlayTogether.roomName("Ana <3"))
        assertNull(PlayTogether.roomName("Zoë"), "only ASCII letters, so two are left: too short for a room")
        assertNull(PlayTogether.roomName("Ana"))
        assertEquals("A Very Long Player N", PlayTogether.roomName("A Very Long Player Name Indeed"))
        assertEquals("mo.k_2-x", PlayTogether.roomName("mo.k_2-x"))
        assertEquals(
            listOf("nickname" to "Ana 3", "room_nickname" to "Ana 3", "host_type" to "1"),
            PlayTogether.rooms("Ana <3", null).map { it.key to it.value },
        )
        assertTrue(PlayTogether.rooms("Ana <3", null).all { it.section == "Multiplayer" && it.file == null })
        assertEquals(listOf("host_type" to "1", "ip" to "192.168.1.20"), PlayTogether.rooms(null, "192.168.1.20").map { it.key to it.value }, "no name a room takes: the person's own stays")
    }
}
