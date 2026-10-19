package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.data.settings.SecretKeys
import io.github.matiyaaa.fuse.launch.achievements.RaSignIn
import io.github.matiyaaa.fuse.launch.patches.DuckStationHome
import io.github.matiyaaa.fuse.launch.patches.IniText
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.launch.patches.PpssppHome
import io.github.matiyaaa.fuse.launch.patches.RetroArchHome
import io.github.matiyaaa.fuse.launch.settings.CfgText
import io.github.matiyaaa.fuse.launch.settings.DolphinHome
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Signing every emulator here in to RetroAchievements once: the password goes in one request and is
 * kept nowhere, each emulator gets the token where its own code keeps it, Fuse's records never hold
 * a token, a profile switch signs them in as that person, and signing out puts each file back exactly.
 */
class RaEmulatorSignInTest {
    private lateinit var cache: File
    private lateinit var emu: File
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        cache = Files.createTempDirectory("fuse-ra-cache").toFile()
        emu = Files.createTempDirectory("fuse-ra-emu").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(cache, emu).forEach { it.deleteRecursively() }
    }

    private fun emulator(family: String, name: String) =
        InstalledEmulator(EmulatorId("linux.$family"), name, Host.LINUX, "/usr/bin/$family", platforms = setOf(PlatformId("psx")), detectedVia = "PATH")

    private fun file(path: String, text: String) = File(emu, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun everyEmulatorIsSignedInOnceAndPutBackExactly(): Unit = runBlocking {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(
                emulator("retroarch", "RetroArch"), emulator("pcsx2", "PCSX2"), emulator("duckstation", "DuckStation"),
                emulator("ppsspp", "PPSSPP"), emulator("dolphin", "Dolphin"), emulator("cemu", "Cemu"),
            ),
        )
        // Each emulator as it was before: RetroArch with achievements off, PCSX2 signed in as someone
        // else (its token in its settings, as an older PCSX2 kept it), the rest never signed in.
        val ra = file("retroarch/retroarch.cfg", "video_driver = \"vulkan\"\ncheevos_enable = \"false\"\n")
        val pcsx2 = file("pcsx2/inis/PCSX2.ini", "[UI]\r\nSettingsVersion = 1\r\n\r\n[Achievements]\r\nEnabled = false\r\nUsername = Old\r\nToken = OLDTOKEN0123456789\r\n")
        val duck = file("duckstation/settings.ini", "[Main]\nSettingsVersion = 3\n")
        val ppsspp = file("ppsspp/PSP/SYSTEM/ppsspp.ini", "[Graphics]\nFrameSkip = 0\n")
        val dolphin = file("dolphin/Config/Dolphin.ini", "[Core]\nCPUThread = True\n")
        val originals = listOf(ra, pcsx2, duck, ppsspp, dolphin).associateWith { it.readText() }
        services.retroArchHome = RetroArchHome(ra.absolutePath, File(emu, "retroarch/cheats").absolutePath, File(emu, "retroarch").absolutePath)
        services.pcsx2Home = Pcsx2Home(File(emu, "pcsx2").absolutePath, File(emu, "pcsx2/gamesettings").absolutePath, File(emu, "pcsx2/patches").absolutePath, null)
        services.duckstationHome = DuckStationHome(File(emu, "duckstation").absolutePath, "", "", "", null, null)
        services.ppssppHome = PpssppHome(File(emu, "ppsspp").absolutePath)
        services.dolphinHome = DolphinHome(File(emu, "dolphin/Config").absolutePath, File(emu, "dolphin/GameSettings").absolutePath)
        services.secrets.put(SecretKeys.RA_USERNAME, "ana")
        services.secrets.put(SecretKeys.RA_API_KEY, "web-key")
        val sent = mutableListOf<Map<String, String?>>()
        services.web = { request ->
            if (request.url.host == "retroachievements.org" && request.url.encodedPath == "/dorequest.php") {
                val form = (request.body as FormDataContent).formData
                sent += mapOf("r" to form["r"], "u" to form["u"], "p" to form["p"])
                val (user, token) = if (form["u"] == "Mo") "Mo" to "MoToken0123456789" else "Ana" to "AnaToken0123456789"
                respond("""{"Success":true,"User":"$user","Token":"$token"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            } else null
        }
        val store = createFuseStore(services, scope)
        withTimeout(10_000) { while (!store.achievements.configured.value) delay(20) }

        // Before: who each is signed in as; Cemu isn't one Fuse signs in.
        val before = assertIs<EmulatorSignIns.Ready>(store.achievements.emulatorSignIns())
        assertEquals("ana", before.account)
        assertEquals(listOf("RetroArch" to null, "PCSX2" to "Old", "DuckStation" to null, "PPSSPP" to null, "Dolphin" to null), before.emulators.map { it.emulator to it.user })

        val result = store.achievements.signInEmulators("hunter2")
        assertNull(result.problem)
        assertEquals(listOf("RetroArch", "PCSX2", "DuckStation", "PPSSPP", "Dolphin"), result.signedIn)
        // One request, the password in it and nowhere else.
        assertEquals(listOf<Map<String, String?>>(mapOf("r" to "login2", "u" to "ana", "p" to "hunter2")), sent)

        // Each emulator's own keys, the name as RetroAchievements has it.
        val cfg = CfgText(ra.readText())
        assertEquals(listOf("true"), cfg.values("", "cheevos_enable"))
        assertEquals(listOf("Ana"), cfg.values("", "cheevos_username"))
        assertEquals(listOf("AnaToken0123456789"), cfg.values("", "cheevos_token"))
        val p = IniText(pcsx2.readText())
        assertEquals(listOf("true"), p.values("Achievements", "Enabled"))
        assertEquals(listOf("AnaToken0123456789"), p.values("Achievements", "Token"))
        assertTrue(pcsx2.readText().contains("\r\n[Achievements]\r\n"), "PCSX2's file keeps its line endings")
        val d = IniText(duck.readText())
        assertEquals("AnaToken0123456789", RaSignIn.duckStationTokenOf(d.values("Cheevos", "Token").single(), "Ana", "fake-machine-id\n"))
        assertEquals(listOf("True"), IniText(ppsspp.readText()).values("Achievements", "AchievementsEnable"))
        assertEquals("AnaToken0123456789", File(emu, "ppsspp/PSP/SYSTEM/ppsspp_retroachievements.dat").readText())
        assertEquals(listOf("AnaToken0123456789"), IniText(File(emu, "dolphin/Config/RetroAchievements.ini").readText()).values("Achievements", "ApiToken"))
        assertEquals(originals[dolphin], dolphin.readText(), "Dolphin.ini itself isn't touched")

        val after = assertIs<EmulatorSignIns.Ready>(store.achievements.emulatorSignIns())
        assertTrue(after.emulators.all { it.user == "Ana" && it.byFuse })
        assertEquals("Ana", after.account, "the account takes the name as RetroAchievements spells it")

        // No token, old or new, and no password in Fuse's database: only fingerprints.
        val db = listOf(File(cache, "fuse.db"), File(cache, "fuse.db-wal")).filter { it.isFile }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        for (secret in listOf("AnaToken0123456789", "OLDTOKEN0123456789", "hunter2")) assertFalse(secret in db, "$secret is in Fuse's database")

        // Another profile with an account of its own: the emulators Fuse signed in follow, with no password.
        services.secrets.put("${SecretKeys.RA_USERNAME}@mo", "Mo")
        services.secrets.put("${SecretKeys.RA_API_KEY}@mo", "mo-key")
        services.secrets.put("${SecretKeys.RA_TOKEN}@mo", "MoToken0123456789")
        services.data.settings.update { it.copy(sync = it.sync.copy(activeProfile = "mo")) }
        withTimeout(10_000) { while (CfgText(ra.readText()).values("", "cheevos_username") != listOf("Mo")) delay(50) }
        assertEquals(listOf("MoToken0123456789"), CfgText(ra.readText()).values("", "cheevos_token"))
        assertEquals(1, sent.size, "a switch needs no password")
        // And back to the device's account.
        services.data.settings.update { it.copy(sync = it.sync.copy(activeProfile = "")) }
        withTimeout(10_000) { while (CfgText(ra.readText()).values("", "cheevos_username") != listOf("Ana")) delay(50) }

        // Signing out puts every file back as it was, PCSX2's own sign-in too; what Fuse made is gone.
        assertTrue(store.achievements.signOutEmulators())
        for ((f, text) in originals) assertEquals(text, f.readText(), f.name)
        assertFalse(File(emu, "dolphin/Config/RetroAchievements.ini").exists())
        assertFalse(File(emu, "ppsspp/PSP/SYSTEM/ppsspp_retroachievements.dat").exists())
        assertNotNull(services.secrets.get(SecretKeys.RA_TOKEN), "the account's token stays for signing in again")
        assertTrue(assertIs<EmulatorSignIns.Ready>(store.achievements.emulatorSignIns()).emulators.none { it.byFuse })
    }

    @Test
    fun aRefusedPasswordChangesNothing(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "no.db").absolutePath)), cache, installedEmulators = listOf(emulator("retroarch", "RetroArch")))
        val ra = file("retroarch/retroarch.cfg", "cheevos_enable = \"false\"\n")
        services.retroArchHome = RetroArchHome(ra.absolutePath, File(emu, "retroarch/cheats").absolutePath, File(emu, "retroarch").absolutePath)
        services.secrets.put(SecretKeys.RA_USERNAME, "Ana")
        services.secrets.put(SecretKeys.RA_API_KEY, "web-key")
        services.web = { request ->
            if (request.url.host == "retroachievements.org") respond("""{"Success":false,"Error":"Invalid User/Password combination. Please try again"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            else null
        }
        val store = createFuseStore(services, scope)
        withTimeout(10_000) { while (!store.achievements.configured.value) delay(20) }
        val r = store.achievements.signInEmulators("wrong")
        assertTrue(r.problem!!.contains("Invalid User/Password"))
        assertEquals("cheevos_enable = \"false\"\n", ra.readText())
        assertNull(services.secrets.get(SecretKeys.RA_TOKEN))
    }

    @Test
    fun withoutAnAccountNothingIsAsked(): Unit = runBlocking {
        val services = FakeServices(FuseData(DesktopDatabase.open(File(cache, "none.db").absolutePath)), cache, installedEmulators = listOf(emulator("retroarch", "RetroArch")))
        val store = createFuseStore(services, scope)
        assertEquals("Connect your RetroAchievements account first.", store.achievements.signInEmulators("x").problem)
        assertTrue(services.requestHosts.none { it == "retroachievements.org" })
    }
}
