package io.github.matiyaaa.fuse.integrations.ports

import io.github.matiyaaa.fuse.integrations.ApiResult
import io.github.matiyaaa.fuse.integrations.TestHttp
import io.github.matiyaaa.fuse.integrations.text
import io.github.matiyaaa.fuse.model.PortFileKind
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signature check and the index's own checks. [SIGNED] was signed with a throwaway Ed25519
 * key made for this test by OpenSSL 3.0 (`openssl genpkey -algorithm ed25519`, then
 * `openssl pkeyutl -sign -rawin`); only its public half is here, and it signs nothing else.
 */
class PortsIndexTest {
    private val testKey = "vGVgmIo2NSqKDoF0kzT5CgCsW/FbOuaBdXBG+AvCuPo="
    private val signature = "WyIyaANGY21QX/gIM5oYKZyUZcMIlj28tk5g14Ak8WnC+QHkvn8wgGVRhwZVvS8szHx36MFy/xH2o2qccHYQAg=="

    @Test
    fun rfc8032sOwnTestVectorVerifies() {
        // RFC 8032 section 7.1, TEST 1: the empty message.
        val key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val sig = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(ed25519Verify(ByteArray(0), sig, key))
        assertFalse(ed25519Verify(byteArrayOf(0), sig, key), "another message")
    }

    @Test
    fun onlyTheSignedIndexAsItWasSignedIsBelieved() {
        assertTrue(PortsSignature.verify(SIGNED.encodeToByteArray(), signature, testKey))
        val tampered = SIGNED.replace("\"revision\":12", "\"revision\":13")
        assertFalse(PortsSignature.verify(tampered.encodeToByteArray(), signature, testKey), "one changed number")
        assertFalse(PortsSignature.verify((SIGNED + "\n").encodeToByteArray(), signature, testKey), "even a line added at the end")
        assertFalse(PortsSignature.verify(SIGNED.encodeToByteArray(), signature, "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"), "a key that isn't Base64 of 32 bytes")
        assertFalse(PortsSignature.verify(SIGNED.encodeToByteArray(), "not a signature", testKey))
    }

    @Test
    fun fuseCarriesTheProjectsOwnKeyAndBelievesWhatItSigned() {
        val key = assertNotNull(PortsSignature.PUBLIC_KEY)
        // A sentence signed once with the project's key as the ports job signs (openssl pkeyutl -sign -rawin),
        // never a list of ports, so its signature can't stand for one.
        val message = "Fuse Ports: signed with the project's own key, never a list of ports"
        val signed = "diHDLCh/xNM5czKHFl5gIXAxykOQZSh49EcM+VxXgbXFEME4ut4GgTAdI6uY+b2Y7dSVZZCuWnPwyHVzbn+fBQ=="
        assertTrue(PortsSignature.verify(message.encodeToByteArray(), signed, key))
        assertFalse(PortsSignature.verify((message + ".").encodeToByteArray(), signed, key))
        assertFalse(PortsSignature.verify(message.encodeToByteArray(), signed, testKey), "and with no other key")
    }

    @Test
    fun theIndexIsCheckedPortByPortBeforeAnythingIsShown() {
        val list = PortsIndexReader.read(SIGNED).getOrThrow()
        assertEquals(12, list.index.revision)
        val port = list.index.ports.single()
        assertEquals("skyport", port.id)
        assertEquals(PortFileKind.TAR_GZ, port.builds.single().kind)
        assertEquals("2026-10-17", port.builds.single().verified)
        assertEquals(setOf("Bad Id"), list.skipped.keys, "a port that fails its checks is left out, the rest kept")

        fun problem(edit: (String) -> String): String? = PortsIndexReader.problem(PortsIndexReader.read(edit(SIGNED)).getOrThrow().index.ports.firstOrNull() ?: return "left out")
        assertEquals("left out", problem { it.replace("https://example.org/skyport-2.1", "http://example.org/skyport-2.1") }, "a build not over HTTPS")
        assertEquals("left out", problem { it.replace("\"run\":\"skyport\"", "\"run\":\"../../bin/sh\"") }, "a program outside the build")
        assertEquals("left out", problem { it.replace("\"sha256\":\"8f43", "\"sha256\":\"zz43") }, "no digest to check by")
        assertEquals("left out", problem { it.replace("{data:game}", "{data:other}") }, "a placeholder Fuse doesn't fill")
        assertEquals("left out", problem { it.replace("[[\"SKY.DAT\"]]", "[[\"/etc/passwd\"]]") }, "a need outside the folder")
        assertTrue(PortsIndexReader.read(SIGNED.replace("\"format\":1", "\"format\":2")).isFailure, "a newer format is refused whole")
        assertTrue(PortsIndexReader.read("<html>").isFailure)
    }

    @Test
    fun aPortsBoxArtIsKeptOnlyOverHttps() {
        fun cover(url: String) = PortsIndexReader.read(SIGNED.replace("\"id\":\"skyport\",", "\"id\":\"skyport\",\"cover\":\"$url\",\"coverCredit\":\"Wikipedia\",")).getOrThrow().index.ports.single()
        val kept = cover("https://example.org/sky.png")
        assertEquals("https://example.org/sky.png", kept.cover)
        assertEquals("Wikipedia", kept.coverCredit)
        val dropped = cover("http://example.org/sky.png")
        assertEquals("skyport", dropped.id, "the port stays")
        assertEquals(null, dropped.cover)
        assertEquals(null, dropped.coverCredit)
    }

    @Test
    fun aRecompilationsRomItsVariablesAndASettingsFileAreChecked() {
        val build = io.github.matiyaaa.fuse.model.PortBuild("linux", url = "https://example.org/r.zip", size = 10, sha256 = "a".repeat(64), kind = PortFileKind.ZIP_TAR_GZ, run = "Recomp")
        val rom = io.github.matiyaaa.fuse.model.PortData("rom", "Its ROM", "The US one.", needs = emptyList(), rom = io.github.matiyaaa.fuse.model.PortRom("n64", "ef18b4a9e2386169", "mm.n64.us.1.0.z64"))
        val port = io.github.matiyaaa.fuse.model.Port(
            id = "recomp", name = "Recomp", game = "A Game", about = "", homepage = "https://example.org", version = "1.0",
            builds = listOf(build), data = listOf(rom), env = mapOf("APP_FOLDER_PATH" to "{home}"), source = "made up",
            settings = io.github.matiyaaa.fuse.model.PortSettings("{home}/config.ini"),
        )
        assertEquals(null, PortsIndexReader.problem(port))
        assertEquals("the ROM it needs isn't said", PortsIndexReader.problem(port.copy(data = listOf(rom.copy(rom = rom.rom!!.copy(xxh3 = "EF18"))))))
        assertEquals("its ROM would go outside it", PortsIndexReader.problem(port.copy(data = listOf(rom.copy(rom = rom.rom!!.copy(place = "../mm.z64"))))))
        assertEquals("it needs a ROM and a folder at once", PortsIndexReader.problem(port.copy(data = listOf(rom.copy(needs = listOf(listOf("a.dat")))))))
        assertEquals("it names a variable oddly", PortsIndexReader.problem(port.copy(env = mapOf("app folder" to "{home}"))))
        assertEquals("its command names {secrets}, which Fuse doesn't fill", PortsIndexReader.problem(port.copy(env = mapOf("A" to "{secrets}"))))
        assertEquals("its settings file would be outside it", PortsIndexReader.problem(port.copy(settings = io.github.matiyaaa.fuse.model.PortSettings("/etc/config.ini"))))
        assertEquals("its settings file would be outside it", PortsIndexReader.problem(port.copy(settings = io.github.matiyaaa.fuse.model.PortSettings("{home}/../x.ini"))))
        assertEquals("its settings are in a form Fuse doesn't write", PortsIndexReader.problem(port.copy(settings = io.github.matiyaaa.fuse.model.PortSettings("{home}/x.json", "json"))))
    }

    @Test
    fun aFetchedIndexIsUsedOnlyWhenSignedAndNewer() = runTest {
        var body = SIGNED
        var sig = signature
        val http = TestHttp { request -> if (request.url.encodedPath.endsWith(".sig")) text(sig, HttpStatusCode.OK) else text(body, HttpStatusCode.OK) }
        val client = PortsClient(http.client, publicKey = { testKey })
        val fresh = (client.fetch(newerThan = 3) as ApiResult.Success).value!!
        assertEquals(SIGNED, fresh.text)
        assertEquals(signature, fresh.signature)
        assertTrue(fresh.signed)
        assertEquals("skyport", fresh.index.ports.single().id)
        assertEquals(listOf(PortsClient.INDEX_URL, PortsClient.INDEX_URL + ".sig"), http.requests.map { it.url.toString() })
        assertNull((client.fetch(newerThan = 12) as ApiResult.Success).value, "not newer than the one Fuse has")

        body = SIGNED.replace("SkyPort", "SkyPort Plus")
        assertIs<ApiResult.InvalidResponse>(client.fetch(newerThan = 3), "changed after it was signed")
        body = SIGNED
        sig = ""
        assertIs<ApiResult.InvalidResponse>(client.fetch(newerThan = 3))
        assertIs<ApiResult.NotConfigured>(PortsClient(http.client, publicKey = { null }).fetch(newerThan = 0), "no key, nothing fetched is believed")
        val before = http.requests.size
        PortsClient(http.client, publicKey = { null }).fetch(newerThan = 0)
        assertEquals(before, http.requests.size, "and nothing is even asked for")
    }

    @Test
    fun beforeTheFirstListIsPublishedTheOneInsideFuseStandsWithNoProblem() = runTest {
        val http = TestHttp { text("Not Found", HttpStatusCode.NotFound) }
        val nothing = assertIs<ApiResult.Success<PortsList?>>(PortsClient(http.client, publicKey = { testKey }).fetch(newerThan = 3))
        assertNull(nothing.value)
        // Any other failure is still said.
        val down = TestHttp { text("", HttpStatusCode.ServiceUnavailable) }
        assertIs<ApiResult.Failure>(PortsClient(down.client, publicKey = { testKey }).fetch(newerThan = 3))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val SIGNED = """{"format":1,"revision":12,"generated":"2026-10-18","ports":[{"id":"skyport","name":"SkyPort","game":"Sky Racer","about":"Sky Racer, rebuilt.","homepage":"https://example.org/skyport","version":"2.1","builds":[{"os":"linux","url":"https://example.org/skyport-2.1-linux.tar.gz","size":4096,"sha256":"8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4","kind":"TAR_GZ","run":"skyport","verified":"2026-10-17"}],"data":[{"id":"game","name":"Sky Racer files","about":"From the CD.","needs":[["SKY.DAT"]]}],"args":["--data","{data:game}","--home","{home}"],"saves":"saves","source":"made up for this test"},{"id":"Bad Id","name":"x","game":"x","about":"","homepage":"https://example.org","version":"1","builds":[],"source":""}]}"""
    }
}
