package io.github.matiyaaa.fuse.link

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkRulesTest {

    @Test
    fun onlyLocalAddressesAreLetIn() {
        for (a in listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "172.31.255.1", "127.0.0.1", "169.254.1.1", "::1", "fe80::1%wlan0", "fd12:3456::1", "::ffff:192.168.0.9", "/192.168.1.2")) {
            assertTrue(LinkNetwork.isLocal(a), a)
        }
        for (a in listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "2001:4860::8888", "192.169.1.1", "", "not-an-ip")) {
            assertFalse(LinkNetwork.isLocal(a), a)
        }
    }

    @Test
    fun onlyAddressesMayNameTheDevice() {
        for (h in listOf("192.168.1.20:47300", "192.168.1.20", "[fe80::1]:47300", "localhost:47300", "127.0.0.1")) {
            assertTrue(LinkNetwork.isAddressHost(h), h)
        }
        for (h in listOf("evil.example", "evil.example:47300", "fuse.local:47300", "", null)) {
            assertFalse(LinkNetwork.isAddressHost(h), h.toString())
        }
    }

    @Test
    fun passwordsAreHashedAndWrongTriesLockSignIn(): Unit = runBlocking {
        val secrets = MemorySecrets()
        var now = 1_000L
        val auth = LinkAuth(secrets) { now }
        assertIs<LoginResult.NoAccount>(auth.login("player", "secret123"))
        auth.setAccount("Player", "secret123")
        val stored = secrets.get(LinkAuth.PASSWORD)!!
        assertFalse("secret123" in stored, "the password is never stored as text")
        assertTrue(stored.startsWith("pbkdf2-sha256\$"))

        val ok = auth.login("player", "secret123")
        assertIs<LoginResult.Ok>(ok)
        assertTrue(auth.isSignedIn(ok.token))
        assertFalse(auth.isSignedIn("forged"))

        repeat(4) { assertIs<LoginResult.Wrong>(auth.login("player", "nope")) }
        assertIs<LoginResult.Locked>(auth.login("player", "nope"))
        // Locked even for the right password, until the minute is over.
        assertIs<LoginResult.Locked>(auth.login("player", "secret123"))
        now += LinkAuth.LOCK_MS + 1
        assertIs<LoginResult.Ok>(auth.login("player", "secret123"))

        // A new password signs every phone out.
        auth.setAccount("player", "another1")
        assertFalse(auth.isSignedIn(ok.token))
        assertEquals(0, auth.sessionCount())
    }
}

class CaptureRulesTest {
    @Test
    fun rangesAreReadTheWayHttpSays() {
        assertEquals(ByteRange.Whole, ByteRange.parse(null, 1_000))
        assertEquals(ByteRange.Part(0, 499), ByteRange.parse("bytes=0-499", 1_000))
        assertEquals(ByteRange.Part(500, 999), ByteRange.parse("bytes=500-", 1_000))
        assertEquals(ByteRange.Part(900, 999), ByteRange.parse("bytes=-100", 1_000))
        assertEquals(ByteRange.Part(0, 999), ByteRange.parse("bytes=-5000", 1_000))
        // An end past the file is cut to the file.
        assertEquals(ByteRange.Part(990, 999), ByteRange.parse("bytes=990-5000", 1_000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=1000-", 1_000))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 1_000))
        // Several ranges, or ones that can't be read, get the whole file.
        assertEquals(ByteRange.Whole, ByteRange.parse("bytes=0-1,5-9", 1_000))
        assertEquals(ByteRange.Whole, ByteRange.parse("bytes=9-5", 1_000))
        assertEquals(ByteRange.Whole, ByteRange.parse("items=0-5", 1_000))
        assertEquals(ByteRange.Whole, ByteRange.parse("bytes=a-b", 1_000))
    }
}
