package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.sync.JvmSyncService
import io.github.matiyaaa.fuse.sync.NoHostLifetime
import io.github.matiyaaa.fuse.sync.SyncService
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * RomM and Jellyfin sign-ins through Fuse Sync, as devices that update meet them: a device set up
 * before it updated is asked once, no leaves it to the next device that updates, and a device that
 * joins on this version just gets what the household shared (each person's own Jellyfin account
 * among it), without being asked. Nothing is ever kept in plain text on the host.
 */
class SignInsStoreTest {
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val port = ServerSocket(0).use { it.localPort }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-signins").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private class Device(val store: FuseStore, val services: FakeServices) {
        val sync: SyncService get() = assertNotNull(store.sync.service)
    }

    /** A device; [updated] ones finished setup on an earlier version. */
    private suspend fun device(name: String, updated: Boolean): Device {
        val dir = File(root, name).apply { mkdirs() }
        val data = FuseData(DesktopDatabase.open(File(dir, "fuse.db").absolutePath))
        data.settings.update {
            it.copy(
                sync = it.sync.copy(enabled = true, hostPort = port, deviceName = name),
                onboarding = it.onboarding.copy(completed = updated),
            )
        }
        lateinit var services: FakeServices
        services = FakeServices(data, File(dir, "cache").apply { mkdirs() }, sync = { port, scope ->
            JvmSyncService(File(dir, "sync"), data.settings, services.secrets, port, "LINUX", name, "test", NoHostLifetime("test"), scope)
        })
        return Device(createFuseStore(services, scope), services)
    }

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        withTimeout(20_000) { while (!check()) delay(50) }
        assertTrue(check(), what)
    }

    @Test
    fun theFirstDeviceToUpdateIsAskedNoAsksTheNextAndNewDevicesJustGetThem(): Unit = runBlocking {
        val pc = device("Gaming PC", updated = true)
        val deck = device("Steam Deck", updated = true)

        // As before the update: both in one household.
        pc.sync.hostHere("Gaming PC", installService = false).getOrThrow()
        deck.sync.connect("127.0.0.1:$port", assertNotNull(pc.sync.newPairingCode())).getOrThrow()
        val mo = pc.sync.createProfile("Mo", "cat", null).getOrThrow()
        deck.sync.switchTo(mo.id).getOrThrow()
        // They joined before this version, and the PC has RomM: neither has decided anything yet.
        eventually("joining here counts as new") { pc.store.sync.config.value.signInsChoice == "AUTO" && deck.store.sync.config.value.signInsChoice == "AUTO" }
        pc.store.sync.configure { it.copy(signInsChoice = "") }
        deck.store.sync.configure { it.copy(signInsChoice = "") }
        eventually("as before the update") { pc.store.sync.config.value.signInsChoice.isEmpty() && deck.store.sync.config.value.signInsChoice.isEmpty() }
        pc.services.secrets.put("romm.credential", """{"type":"token","token":"rmm_secret_token"}""")
        pc.store.romm.setAddresses("http://romm.home:8080", "", io.github.matiyaaa.fuse.integrations.net.RouteMode.AUTO)
        pc.sync.syncNow()

        // The PC has something to share, so it is asked; the Deck has nothing, so it isn't.
        eventually("the PC is asked") { pc.store.sync.signInsAsk.value }
        assertFalse(deck.store.sync.signInsAsk.value)

        // No: nothing goes, and the next device that updates is asked instead.
        pc.store.sync.answerSignIns(false)
        assertFalse(pc.store.sync.signInsAsk.value)
        eventually("the PC said no") { pc.store.sync.config.value.signInsChoice == "NO" && !pc.store.sync.config.value.shareSignIns }
        assertEquals(null, pc.sync.householdServices()?.services?.romm)

        // The Deck has Jellyfin, with Mo's own sign-in: now it is asked, and says yes.
        deck.services.secrets.put("jellyfin.login.p.${mo.id}", "mo\nhunter2")
        deck.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(enabled = true, localAddress = "http://jellyfin.home:8096")) }
        eventually("the Deck is asked") { deck.store.sync.signInsAsk.value }
        deck.store.sync.answerSignIns(true)
        eventually("the Deck's Jellyfin is the household's") { pc.sync.householdServices()?.services?.jellyfin?.local == "http://jellyfin.home:8096" }
        assertTrue(pc.sync.householdServices()!!.services.shared)

        // A tablet set up on this version joins: it isn't asked, it just gets Jellyfin, and Mo's account.
        val tablet = device("Tablet", updated = false)
        tablet.sync.connect("127.0.0.1:$port", assertNotNull(pc.sync.newPairingCode())).getOrThrow()
        tablet.sync.switchTo(mo.id).getOrThrow()
        eventually("the tablet has Jellyfin") {
            val j = tablet.store.prefs.value.jellyfin
            j.enabled && j.localAddress == "http://jellyfin.home:8096"
        }
        assertEquals("AUTO", tablet.store.sync.config.value.signInsChoice)
        assertFalse(tablet.store.sync.signInsAsk.value)
        assertEquals(mapOf("jellyfin:${mo.id}" to """{"username":"mo","password":"hunter2"}"""), tablet.sync.householdServices()?.signIns)
        // The PC said no, so its RomM stayed on the PC.
        assertTrue(tablet.services.data.settings.current().romm.localAddress.isEmpty())

        // The PC changes its mind in Settings: its RomM, with its sign-in, reaches the tablet.
        pc.store.sync.setShareSignIns(true)
        eventually("RomM reached the household") { pc.sync.householdServices()?.services?.romm?.local == "http://romm.home:8080" }
        tablet.sync.syncNow()
        eventually("the tablet has RomM, signed in") {
            tablet.services.data.settings.current().romm.let { it.enabled && it.localAddress == "http://romm.home:8080" } &&
                tablet.services.secrets.get("romm.credential") == """{"type":"token","token":"rmm_secret_token"}"""
        }

        // Nothing the host keeps reads as a sign-in.
        val kept = root.walkTopDown().filter { it.isFile && it.path.contains("${File.separator}sync${File.separator}") }.map { it.readBytes().decodeToString() }
        assertTrue(kept.none { "hunter2" in it || "rmm_secret_token" in it }, "sign-ins are sealed on the host")
        pc.sync.stop()
    }
}
