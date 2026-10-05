package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.HdrFormat
import io.github.matiyaaa.fuse.playback.PlayItem
import io.github.matiyaaa.fuse.playback.PlayMethod
import io.github.matiyaaa.fuse.playback.PlayRequest
import io.github.matiyaaa.fuse.playback.SubtitleDelivery
import io.github.matiyaaa.fuse.playback.SubtitleFormat
import io.github.matiyaaa.fuse.playback.VideoCodec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private class MemorySecrets : SecretStore {
    val map = HashMap<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}

/** A pretend Jellyfin: answers by path, can be "unplugged" per host, and records what it was asked. */
private class FakeServer {
    val requests = mutableListOf<HttpRequestData>()
    val down = mutableSetOf<String>()
    var routes: (HttpRequestData) -> Pair<Int, String>? = { null }

    val http = HttpClient(MockEngine { r ->
        requests += r
        if (r.url.host in down) throw IllegalStateException("Connection refused")
        val path = r.url.encodedPath
        val answer = routes(r) ?: when {
            path.endsWith("/System/Info/Public") -> 200 to """{"ServerName":"Media Server","Version":"10.11.11","Id":"srv1"}"""
            path.endsWith("/Users/AuthenticateByName") -> 200 to """{"User":{"Id":"u1","Name":"pat"},"AccessToken":"tok123","ServerId":"srv1"}"""
            path.endsWith("/UserViews") -> 200 to """{"Items":[{"Id":"lib1","Name":"Films","Type":"CollectionFolder","CollectionType":"movies"},{"Id":"lib2","Name":"Shows","Type":"CollectionFolder","CollectionType":"tvshows"}],"TotalRecordCount":2}"""
            path.endsWith("/UserItems/Resume") -> 200 to """{"Items":[{"Id":"m1","Name":"Dune","Type":"Movie","RunTimeTicks":93600000000,"UserData":{"PlaybackPositionTicks":36000000000},"ImageTags":{"Primary":"p1"},"BackdropImageTags":["b1"]}],"TotalRecordCount":1}"""
            path.endsWith("/Shows/NextUp") -> 200 to """{"Items":[],"TotalRecordCount":0}"""
            path.endsWith("/Items/Latest") -> 200 to (if (r.url.parameters["parentId"] == "lib1") """[{"Id":"m2","Name":"Arrival","Type":"Movie","ProductionYear":2016}]""" else "[]")
            path.endsWith("/Items") -> 200 to """{"Items":[],"TotalRecordCount":0}"""
            else -> null
        } ?: (404 to "")
        respond(answer.second, HttpStatusCode.fromValue(answer.first), headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(HttpTimeout) }
}

class JellyfinTest {
    private val device = DeviceInfo("Thor", "dev-1", "0.2.8")
    private val caps = Capabilities(
        videoCodecs = listOf(VideoCodec("h264", 3840, 2160, 8), VideoCodec("hevc", 3840, 2160, 10)),
        audioCodecs = setOf("aac", "ac3", "eac3", "opus"),
        maxAudioChannels = 8,
        containers = setOf("mkv", "mp4", "ts"),
        embeddedSubtitles = setOf("pgssub", "subrip", "ass"),
    )

    private fun service(server: FakeServer, secrets: SecretStore, scope: CoroutineScope) =
        JellyfinService(JellyfinClient(server.http, device), secrets, scope)

    @Test
    fun signingInKeepsTheTokenNeverThePasswordAndSaysWhoIsAsking() = runTest {
        val server = FakeServer()
        val secrets = MemorySecrets()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, secrets, scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        val account = s.signIn("pat", "secret-pw").getOrThrow()
        assertEquals("tok123", account.token)
        assertEquals("tok123", secrets.map[JellyfinService.TOKEN])
        assertFalse(secrets.map.values.any { "secret-pw" in it }, "the password must never be kept")
        val auth = server.requests.first { it.url.encodedPath.endsWith("AuthenticateByName") }
        assertEquals("https", auth.url.protocol.name)
        val header = auth.headers[HttpHeaders.Authorization]!!
        assertTrue("Client=\"Fuse\"" in header && "DeviceId=\"dev-1\"" in header && "Token" !in header, header)
        assertTrue("\"Pw\":\"secret-pw\"" in (auth.body as TextContent).text)
        // Later calls carry the token in the header, never in the address.
        s.home()
        val later = server.requests.last()
        assertTrue("Token=\"tok123\"" in later.headers[HttpHeaders.Authorization]!!)
        assertFalse(server.requests.any { "tok123" in it.url.toString() })
        scope.cancel()
    }

    @Test
    fun homeHasContinueAndNewShelvesAndHidesEmptyOnes() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "https://media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        val shelves = s.home()
        assertEquals(listOf("continue", "latest.lib1"), shelves.map { it.id })
        val dune = shelves[0].items.single()
        assertEquals(3_600_000, dune.resumeMs)
        assertEquals(5_760_000, dune.leftMs)
        assertEquals(JellyfinArt("m1", ArtKind.PRIMARY, "p1"), dune.poster)
        scope.cancel()
    }

    @Test
    fun autoUsesHomeFirstThenOutsideAndComesBackHome() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        server.down += "192.168.1.5"
        s.configure(true, JellyfinConnection(ConnectionMode.AUTO, localAddress = "192.168.1.5", remoteAddress = "media.example.com"))
        s.reconnect(force = true)
        assertEquals(Route.REMOTE, s.state.value.route)
        assertEquals("https://media.example.com", s.state.value.base)
        server.down.clear()
        s.reconnect(force = true)
        assertEquals(Route.LOCAL, s.state.value.route)
        assertEquals("http://192.168.1.5:8096", s.state.value.base)
        scope.cancel()
    }

    @Test
    fun keptPagesOpenWhenTheServerIsUnreachable() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        val first = s.home()
        s.client.freshForMs = 0
        server.down += "media.example.com"
        val again = s.home()
        assertEquals(first.map { it.id }, again.map { it.id })
        // Unreachable is decided by looking for every way in, never by one failed call.
        s.reconnect(force = true)
        assertTrue(s.state.value.offline)
        scope.cancel()
    }

    @Test
    fun keptPagesShowAtOnceAndTheFreshAnswerFollows() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        assertEquals("Dune", s.home().first().items.single().name)
        // Time passes and the server's answer changes: the kept page shows first, then the new one.
        s.client.freshForMs = 1
        kotlinx.coroutines.delay(5)
        server.routes = { r ->
            if (r.url.encodedPath.endsWith("/UserItems/Resume")) 200 to """{"Items":[{"Id":"m9","Name":"Heat","Type":"Movie"}],"TotalRecordCount":1}""" else null
        }
        val asked = server.requests.size
        val rev = s.revision.value
        assertEquals("Dune", s.home().first().items.single().name)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (s.revision.value == rev) kotlinx.coroutines.delay(20) }
        }
        assertTrue(server.requests.size > asked, "fresh answers are fetched behind the kept ones")
        s.client.freshForMs = 60_000
        assertEquals("Heat", s.home().first().items.single().name)
        scope.cancel()
    }

    @Test
    fun everyWayInIsAskedAtOnceAndHomeWinsWhenItAnswers() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.AUTO, localAddress = "192.168.1.5:8096", remoteAddress = "media.example.com"))
        s.reconnect(force = true)
        assertEquals(Route.LOCAL, s.state.value.route)
        // Both were asked in the same look, not one after the other's timeout.
        val hosts = server.requests.filter { it.url.encodedPath.endsWith("/System/Info/Public") }.map { it.url.host }.toSet()
        assertTrue("192.168.1.5" in hosts && "media.example.com" in hosts, hosts.toString())
        scope.cancel()
    }

    @Test
    fun aCallThatCantGetThroughTriesTheOtherWayInsteadOfGivingUp() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.AUTO, localAddress = "192.168.1.5:8096", remoteAddress = "media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        assertEquals(Route.LOCAL, s.state.value.route)
        // Leaving home: the home address stops answering mid-session.
        server.down += "192.168.1.5"
        s.client.freshForMs = 0
        val libs = s.libraries()
        assertEquals(2, libs.size)
        assertEquals(Route.REMOTE, s.state.value.route)
        assertFalse(s.state.value.offline)
        scope.cancel()
    }

    @Test
    fun theProfileFollowsWhatThePlayerCanPlay() {
        val p = DeviceProfiles.build(caps, 20_000_000)
        val codecs = p["CodecProfiles"] as JsonArray
        val hevc = codecs.map { it as JsonObject }.first { it["Codec"]?.jsonPrimitive?.content == "hevc" }
        val range = (hevc["Conditions"] as JsonArray).map { it as JsonObject }.first { it["Property"]?.jsonPrimitive?.content == "VideoRangeType" }
        // No HDR claimed: the server tone maps it.
        assertEquals("SDR", range["Value"]!!.jsonPrimitive.content)
        val subs = (p["SubtitleProfiles"] as JsonArray).map { it as JsonObject }
        assertTrue(subs.any { it["Format"]!!.jsonPrimitive.content == "pgssub" && it["Method"]!!.jsonPrimitive.content == "Embed" })
        assertTrue(subs.any { it["Format"]!!.jsonPrimitive.content == "dvdsub" && it["Method"]!!.jsonPrimitive.content == "Encode" })
        assertTrue(subs.none { it["Format"]!!.jsonPrimitive.content == "dvdsub" && it["Method"]!!.jsonPrimitive.content == "Embed" })
        assertEquals(listOf("SDR", "HDR10", "DOVIWithHDR10"), DeviceProfiles.rangeTypes(setOf(HdrFormat.HDR10)))
    }

    @Test
    fun streamsAreDirectWhenTheyCanBeAndTracksSayHowTheyArrive() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        var transcode = false
        server.routes = { r ->
            if (r.url.encodedPath.endsWith("/PlaybackInfo")) {
                200 to if (!transcode) {
                    """{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":true,"RunTimeTicks":60000000000,
                      "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,"MediaStreams":[
                      {"Type":"Video","Index":0,"Codec":"hevc","Width":1920,"Height":1080,"VideoRangeType":"SDR"},
                      {"Type":"Audio","Index":1,"Codec":"eac3","Channels":6,"Language":"eng","DisplayTitle":"English - Dolby Digital+ - 5.1","IsDefault":true},
                      {"Type":"Audio","Index":2,"Codec":"aac","Channels":2,"Language":"jpn","DisplayTitle":"Japanese - AAC - Stereo"},
                      {"Type":"Subtitle","Index":3,"Codec":"ass","Language":"eng","DisplayTitle":"English","DeliveryMethod":"External","DeliveryUrl":"/Videos/m1/ms1/Subtitles/3/0/Stream.ass","IsTextSubtitleStream":true},
                      {"Type":"Subtitle","Index":4,"Codec":"PGSSUB","Language":"eng","DisplayTitle":"English (SDH)","DeliveryMethod":"Embed"},
                      {"Type":"Subtitle","Index":5,"Codec":"dvd_subtitle","DisplayTitle":"Commentary","DeliveryMethod":"Encode"}]}]}"""
                } else {
                    """{"PlaySessionId":"ps2","MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":false,"SupportsTranscoding":true,
                      "TranscodingUrl":"/videos/m1/master.m3u8?MediaSourceId=ms1&VideoCodec=h264","TranscodingSubProtocol":"hls","TranscodeReasons":["VideoRangeTypeNotSupported"],
                      "MediaStreams":[{"Type":"Video","Index":0,"Codec":"hevc","Width":3840,"Height":2160,"VideoRangeType":"HDR10"},{"Type":"Audio","Index":1,"Codec":"truehd","Channels":8,"IsDefault":true}]}]}"""
                }
            } else {
                null
            }
        }
        val resolver = JellyfinResolver(s) { JellyfinQuality() }
        val item = PlayItem("m1", "Dune")
        val direct = resolver.resolve(item, PlayRequest(startMs = 60_000, capabilities = caps))
        assertEquals(PlayMethod.DIRECT_PLAY, direct.method)
        assertTrue(direct.url.startsWith("https://media.example.com/Videos/m1/stream?static=true&mediaSourceId=ms1"), direct.url)
        assertFalse("tok123" in direct.url)
        assertTrue("Token=\"tok123\"" in direct.headers["Authorization"]!!)
        assertEquals(60_000, direct.startMs)
        assertEquals(listOf(0, 1), direct.audioTracks.map { it.order })
        assertEquals("1", direct.audio)
        assertNull(direct.subtitle)
        val (ass, pgs, dvd) = direct.subtitleTracks
        assertEquals(SubtitleDelivery.External("https://media.example.com/Videos/m1/ms1/Subtitles/3/0/Stream.ass", SubtitleFormat.ASS), ass.delivery)
        assertEquals(SubtitleDelivery.Embedded(1), pgs.delivery)
        assertEquals(SubtitleDelivery.BurnIn, dvd.delivery)
        assertEquals("1080p HEVC, 5.1 EAC3", direct.description)

        transcode = true
        val converted = resolver.resolve(item, PlayRequest(capabilities = caps))
        assertEquals(PlayMethod.TRANSCODE, converted.method)
        assertTrue(converted.isHls)
        assertEquals("https://media.example.com/videos/m1/master.m3u8?MediaSourceId=ms1&VideoCodec=h264", converted.url)
        assertEquals("HDR is converted for this screen", converted.transcodeReason)
        val body = (server.requests.last { it.url.encodedPath.endsWith("/PlaybackInfo") }.body as TextContent).text
        assertTrue("\"DeviceProfile\"" in body && "\"MaxStreamingBitrate\":20000000" in body, body.take(300))
        scope.cancel()
    }

    @Test
    fun languagesPickTracksAndAskTheServerAgainOnlyWhenTheyDiffer() = runTest {
        val server = FakeServer()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, MemorySecrets(), scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        s.signIn("pat", "pw").getOrThrow()
        val answer = """{"PlaySessionId":"ps1","MediaSources":[{"Id":"ms1","Container":"mkv","SupportsDirectPlay":true,
          "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,"MediaStreams":[
          {"Type":"Video","Index":0,"Codec":"h264"},
          {"Type":"Audio","Index":1,"Codec":"aac","Language":"jpn","IsDefault":true},
          {"Type":"Audio","Index":2,"Codec":"aac","Language":"eng"},
          {"Type":"Subtitle","Index":3,"Codec":"srt","Language":"eng","IsForced":true,"IsTextSubtitleStream":true},
          {"Type":"Subtitle","Index":4,"Codec":"srt","Language":"eng","IsTextSubtitleStream":true}]}]}"""
        server.routes = { r -> if (r.url.encodedPath.endsWith("/PlaybackInfo")) 200 to answer else null }
        val ms = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(PlaybackInfoDto.serializer(), answer).mediaSources.first()
        fun pick(q: JellyfinQuality) = JellyfinResolver(s) { q }.preferredTracks(ms, q)
        // Nothing set: the server's choice stands.
        assertEquals(null to null, pick(JellyfinQuality()))
        // English sound, and the full English subtitles only when the sound is foreign.
        assertEquals(2 to -1, pick(JellyfinQuality(audioLanguage = "eng", subtitleMode = "FOREIGN")))
        assertEquals(null to 4, pick(JellyfinQuality(subtitleLanguage = "eng", subtitleMode = "FOREIGN")))
        assertEquals(null to 4, pick(JellyfinQuality(subtitleLanguage = "eng", subtitleMode = "ALWAYS")))
        assertEquals(null to 3, pick(JellyfinQuality(subtitleMode = "FORCED")))
        assertEquals(null to -1, pick(JellyfinQuality(subtitleMode = "OFF")))

        val before = server.requests.count { it.url.encodedPath.endsWith("/PlaybackInfo") }
        val src = JellyfinResolver(s) { JellyfinQuality(audioLanguage = "eng", subtitleLanguage = "eng", subtitleMode = "ALWAYS") }
            .resolve(PlayItem("m1", "Spirited Away"), PlayRequest(capabilities = caps))
        assertEquals(2, server.requests.count { it.url.encodedPath.endsWith("/PlaybackInfo") } - before)
        val again = (server.requests.last { it.url.encodedPath.endsWith("/PlaybackInfo") }.body as TextContent).text
        assertTrue("\"AudioStreamIndex\":2" in again && "\"SubtitleStreamIndex\":4" in again, again.take(400))
        assertEquals("2", src.audio)
        assertEquals("4", src.subtitle)
        scope.cancel()
    }

    @Test
    fun typedAddressesBecomeUrls() {
        assertEquals(listOf("https://media.example.com", "http://media.example.com", "http://media.example.com:8096"), JellyfinClient.candidates("media.example.com/"))
        assertEquals("http://192.168.1.5:8096", JellyfinClient.candidates("192.168.1.5").first())
        assertEquals(listOf("http://nas:8920/jf"), JellyfinClient.candidates("http://nas:8920/jf/"))
        assertNotNull(JellyfinClient.candidates("nas.local").firstOrNull { it == "http://nas.local:8096" })
    }

    @Test
    fun aHomeAddressIsFoundHoweverItIsTyped() {
        val home = "http://192.168.1.5:8096"
        for (typed in listOf(
            "192.168.1.5", "192.168.1.5:8096", " 192.168.1.5 : 8096 ", "http://192.168.1.5:8096/",
            "HTTP://192.168.1.5:8096", "http//192.168.1.5:8096", "http:/192.168.1.5:8096", "192,168,1,5:8096",
            "http://192.168.1.5:8096/web/index.html#!/home.html", "192.168.1.5:8096/web/", "http://192.168.1.5",
            "\uFF11\uFF19\uFF12.168.1.5", "192.168.1.5.",
        )) {
            assertTrue(home in JellyfinClient.candidates(typed), "$typed gave ${JellyfinClient.candidates(typed)}")
        }
        assertEquals("http://192.168.1.5:8096", JellyfinClient.candidates("192.168.1.5:8096").first())
        assertEquals("https://192.168.1.5:8920", JellyfinClient.candidates("192.168.1.5:8920").first())
        assertEquals("http://jellyfin.lan:8096", JellyfinClient.candidates("jellyfin.lan").first())
        assertEquals("http://10.0.0.4:8096/jellyfin", JellyfinClient.candidates("10.0.0.4/jellyfin/web/").first())
        assertEquals("http://[fd00::5]:8096", JellyfinClient.candidates("[fd00::5]:8096").first())
        assertEquals("https://httpserver.example.com", JellyfinClient.candidates("httpserver.example.com").first())
        assertTrue(JellyfinClient.candidates("   ").isEmpty())
    }

    @Test
    fun aRefusedSignInSaysSoAndKeepsNothing() = runTest {
        val server = FakeServer()
        server.routes = { r -> if (r.url.encodedPath.endsWith("AuthenticateByName")) 401 to "" else null }
        val secrets = MemorySecrets()
        val scope = CoroutineScope(SupervisorJob())
        val s = service(server, secrets, scope)
        s.configure(true, JellyfinConnection(ConnectionMode.REMOTE, remoteAddress = "media.example.com"))
        val r = s.signIn("pat", "wrong")
        assertTrue(r.isFailure)
        assertEquals(JellyfinException.Kind.AUTH, (r.exceptionOrNull() as JellyfinException).kind)
        assertTrue(secrets.map.isEmpty())
        // Only one attempt: the right server said no, so no other address is tried.
        assertEquals(1, server.requests.count { it.url.encodedPath.endsWith("AuthenticateByName") })
        scope.cancel()
    }
}
