package io.github.matiyaaa.fuse.host

import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretStore
import io.github.matiyaaa.fuse.data.settings.SettingsStore
import io.github.matiyaaa.fuse.sync.CollectionRecord
import io.github.matiyaaa.fuse.sync.GameKey
import io.github.matiyaaa.fuse.sync.GameRecord
import io.github.matiyaaa.fuse.sync.HeadlessHost
import io.github.matiyaaa.fuse.sync.Hlc
import io.github.matiyaaa.fuse.sync.HlcClock
import io.github.matiyaaa.fuse.sync.HostAdmin
import io.github.matiyaaa.fuse.sync.JvmSyncService
import io.github.matiyaaa.fuse.sync.Lww
import io.github.matiyaaa.fuse.sync.NoHostLifetime
import io.github.matiyaaa.fuse.sync.ProfileDataPort
import io.github.matiyaaa.fuse.sync.ProfileMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fuse One Host on its own is a whole host: two devices pair with it by code, one makes a profile,
 * and what each person has follows them to the other, with no Fuse app hosting anywhere. The host's
 * data survives it being stopped and started again (a container being recreated).
 */
class HeadlessHouseholdTest {
    private class Secrets : SecretStore {
        val map = HashMap<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    /** A device's records as Fuse One sees them. */
    private class Library(
        val games: MutableMap<String, GameRecord> = HashMap(),
        val settings: MutableMap<String, JsonPrimitive> = HashMap(),
        /** When the person chose a setting here; a setting not listed was never chosen (Fuse's default). */
        val chosen: MutableMap<String, Hlc> = HashMap(),
    ) : ProfileDataPort {
        override suspend fun read(device: String, clock: HlcClock): ProfileMeta = synchronized(this) {
            ProfileMeta(games = games.toMap(), collections = emptyMap<String, CollectionRecord>(), settings = settings.mapValues { (k, v) -> Lww(v as kotlinx.serialization.json.JsonElement, chosen[k] ?: Hlc.ZERO) })
        }

        override suspend fun write(meta: ProfileMeta) = synchronized(this) {
            games.clear()
            meta.games.forEach { (k, r) ->
                games[k] = GameRecord(r.key, playSeconds = mapOf("here" to r.totalSeconds), sessions = r.sessions, lastPlayed = r.lastPlayed, favorite = r.favorite?.let { Lww(it.value, Hlc.ZERO) })
            }
            settings.clear()
            meta.settings.forEach { (k, v) -> settings[k] = v.value as JsonPrimitive }
        }

        override suspend fun keyOf(gameId: Long): GameKey? = null
    }

    private lateinit var root: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val port = ServerSocket(0).use { it.localPort }
    private val ruby = GameKey.of("gba", "AGB-AXVE", null, "Pokemon Ruby")

    @BeforeTest fun setUp() { root = Files.createTempDirectory("headless").toFile() }

    @AfterTest fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun device(name: String, library: Library): JvmSyncService {
        val settings = SettingsStore(DesktopDatabase.open(null))
        runBlocking { settings.update { it.copy(sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name)) } }
        return JvmSyncService(File(root, name), settings, Secrets(), library, "LINUX", name, "test", NoHostLifetime("test"), scope)
    }

    @Test
    fun twoDevicesShareAPersonThroughTheHeadlessHost(): Unit = runBlocking {
        val hostDir = File(root, "host")
        var host = assertNotNull(HeadlessHost.serve(hostDir, port, "Mini PC", "test"))
        try {
            val admin = assertNotNull(HostAdmin.of(hostDir, port))
            val deckLib = Library(
                games = hashMapOf(ruby.id to GameRecord(ruby, playSeconds = mapOf("x" to 1_800), lastPlayed = 1_000, favorite = Lww(true, Hlc.ZERO))),
                settings = hashMapOf("appearance.themeId" to JsonPrimitive("fusi")),
                chosen = hashMapOf("appearance.themeId" to Hlc(System.currentTimeMillis() - 60_000, 0, "deck")),
            )
            val pcLib = Library()
            val deck = device("Steam Deck", deckLib)
            val pc = device("Desktop", pcLib)
            assertEquals("Mini PC", deck.connect("127.0.0.1:$port", admin.pairingCode()).getOrThrow())
            assertEquals("Mini PC", pc.connect("127.0.0.1:$port", admin.pairingCode()).getOrThrow())

            // A profile made on a device, not on the host: the host has no screen.
            val mo = deck.createProfile("Mo", "fox", null).getOrThrow()
            deck.switchTo(mo.id).getOrThrow()
            pc.switchTo(mo.id).getOrThrow()
            assertEquals(1_800L, pcLib.games[ruby.id]?.totalSeconds)
            assertEquals(true, pcLib.games[ruby.id]?.favorite?.value)
            assertEquals(JsonPrimitive("fusi"), pcLib.settings["appearance.themeId"])

            // Both devices are the host's, by name.
            val status = admin.status()
            assertEquals(setOf("Steam Deck", "Desktop"), status.devices.map { it.name }.toSet())
            assertTrue(status.profiles.any { it.name == "Mo" })

            // Stopped and started again from the same folder: the same household, the same person.
            host.close()
            host = assertNotNull(HeadlessHost.serve(hostDir, port, "Mini PC", "test"))
            // Chosen on the PC now, so it is the newest choice.
            pcLib.settings["appearance.themeId"] = JsonPrimitive("dawn")
            pcLib.chosen["appearance.themeId"] = Hlc(System.currentTimeMillis(), 0, "pc")
            pc.syncNow().getOrThrow()
            deck.syncNow().getOrThrow()
            assertEquals(JsonPrimitive("dawn"), deckLib.settings["appearance.themeId"])
            assertTrue(assertNotNull(HostAdmin.of(hostDir, port)).status().profiles.any { it.id == mo.id })
            deck.stop()
            pc.stop()
        } finally {
            host.close()
        }
    }
}
