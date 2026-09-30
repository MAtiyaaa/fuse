package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.android.AndroidEmulatorCatalog
import io.github.matiyaaa.fuse.launch.android.AndroidFamilies
import io.github.matiyaaa.fuse.launch.android.AndroidIntentAdapter
import io.github.matiyaaa.fuse.launch.android.AndroidIntentPlan
import io.github.matiyaaa.fuse.launch.android.AndroidIntentSpec
import io.github.matiyaaa.fuse.launch.android.IntentExtra
import io.github.matiyaaa.fuse.launch.android.NativeAppAdapter
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LaunchPlan
import io.github.matiyaaa.fuse.model.LaunchTarget
import io.github.matiyaaa.fuse.model.ShortcutFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidAdapterTest {
    private fun adapter(id: String) = AndroidEmulatorCatalog.adapters.first { it.id == EmulatorId(id) } as AndroidIntentAdapter

    private fun planOf(id: String, game: Game, installed: InstalledEmulator, target: LaunchTarget, options: LaunchOptions = LaunchOptions()): AndroidIntentPlan =
        assertNotNull(adapter(id).planAndroid(LaunchRequest(game, installed, target, options)).intent, "no intent for $id")

    @Test
    fun retroArchCoreAndExtras() {
        val rom = "$ROMS/gba/Metroid Fusion.gba"
        val plan = planOf("retroarch", game("gba", rom), androidEmu("retroarch", "com.retroarch.aarch64"), LaunchTarget.File(rom))
        assertEquals("com.retroarch.aarch64", plan.intent.packageName)
        assertEquals("com.retroarch.browser.retroactivity.RetroActivityFuture", plan.intent.activity)
        assertEquals(
            mapOf(
                "CONFIGFILE" to "{EXTDATA}/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
                "LIBRETRO" to "{INTDATA}/com.retroarch.aarch64/cores/mgba_libretro_android.so",
                "ROM" to rom,
            ),
            plan.intent.stringExtras,
        )
        assertEquals(setOf("{EXTDATA}", "{INTDATA}"), plan.unresolvedTokens)
        assertFalse(plan.requiresActivityCheck)

        val gpsp = planOf("retroarch", game("gba", rom), androidEmu("retroarch", "com.retroarch"), LaunchTarget.File(rom), LaunchOptions(core = "gpsp"))
        assertEquals("{INTDATA}/com.retroarch/cores/gpsp_libretro_android.so", gpsp.intent.stringExtras["LIBRETRO"])

        val n64 = planOf("retroarch", game("n64", "$ROMS/n64/a.z64"), androidEmu("retroarch", "com.retroarch"), LaunchTarget.File("$ROMS/n64/a.z64"))
        assertTrue(n64.intent.stringExtras.getValue("LIBRETRO").endsWith("/mupen64plus_next_gles3_libretro_android.so"))
    }

    @Test
    fun safAndProviderTokensStayForTheAndroidApp() {
        val rom = "$ROMS/psx/a.chd"
        val duck = planOf("duckstation", game("psx", rom), androidEmu("duckstation", "com.github.stenzek.duckstation"), LaunchTarget.File(rom))
        assertEquals(mapOf("bootPath" to "{SAF}"), duck.intent.stringExtras)
        assertEquals(mapOf("resumeState" to false), duck.intent.boolExtras)
        assertTrue(duck.intent.clearTask && duck.intent.clearTop)
        assertEquals("com.github.stenzek.duckstation.EmulationActivity", duck.intent.activity)

        val nes = planOf("nes-emu", game("nes", "$ROMS/nes/a.nes"), androidEmu("nes-emu", "com.explusalpha.NesEmu"), LaunchTarget.File("$ROMS/nes/a.nes"))
        assertEquals("{PROVIDER}", nes.intent.data)
        assertTrue(nes.grantReadUri)
        assertEquals(setOf("{PROVIDER}"), nes.unresolvedTokens)
    }

    @Test
    fun everyAndroidTemplateOnlyUsesKnownTokensAndProviderOnlyInData() {
        val allowedOnAndroid = LaunchTokens.all - setOf(LaunchTokens.CORE_PATH, LaunchTokens.EMUDIR, LaunchTokens.ROM)
        for (def in AndroidEmulatorCatalog.defs) for (mode in def.modes) {
            val spec: AndroidIntentSpec = mode.spec
            val templates = AndroidIntentAdapter.templatesOf(spec)
            for (t in templates) {
                val unknown = LaunchTokens.tokensIn(t) - allowedOnAndroid
                assertTrue(unknown.isEmpty(), "${def.id} uses $unknown")
            }
            val extraTemplates = spec.extras.flatMap {
                when (it) {
                    is IntentExtra.Text -> listOf(it.template)
                    is IntentExtra.Number -> listOf(it.template)
                    is IntentExtra.TextArray -> it.templates
                    is IntentExtra.Flag -> emptyList()
                }
            }
            assertTrue(extraTemplates.none { LaunchTokens.PROVIDER in it }, "${def.id}: {PROVIDER} is data only")
        }
    }

    @Test
    fun gameNativeTakesAnIntAppIdFromTheSteamFile() {
        val path = "$ROMS/steam/Celeste.steam"
        val g = game("steam", path)
        val installed = androidEmu("gamenative", "app.gamenative")
        val plan = planOf("gamenative", g, installed, LaunchTarget.Shortcut(path, ShortcutFormat.GAMENATIVE), LaunchOptions(injectedText = "504230\n"))
        assertEquals("app.gamenative.LAUNCH_GAME", plan.intent.action)
        assertEquals(mapOf("app_id" to 504230), plan.intExtras)
        assertEquals(mapOf("game_source" to "STEAM"), plan.intent.stringExtras)
        assertEquals("app.gamenative.MainActivity", plan.intent.activity)

        val epic = planOf("gamenative", game("win", "$ROMS/win/Fortnite.epic"), installed, LaunchTarget.File("$ROMS/win/Fortnite.epic"), LaunchOptions(injectedText = "12"))
        assertEquals("EPIC", epic.intent.stringExtras["game_source"])

        val bad = adapter("gamenative").plan(LaunchRequest(g, installed, LaunchTarget.File(path), LaunchOptions(injectedText = "not a number")))
        assertIs<LaunchPlan.Unsupported>(bad)
        val missing = adapter("gamenative").plan(LaunchRequest(g, installed, LaunchTarget.File(path)))
        assertTrue((missing as LaunchPlan.Unsupported).reason.contains("Celeste.steam"))
    }

    @Test
    fun gameHubLiteUsesBothIdsForSteam() {
        val path = "$ROMS/steam/Celeste.steam"
        val plan = planOf("gamehub-lite", game("steam", path), androidEmu("gamehub-lite", "gamehub.lite"), LaunchTarget.File(path), LaunchOptions(injectedText = "504230"))
        assertEquals("gamehub.lite.LAUNCH_GAME", plan.intent.action)
        assertEquals(mapOf("steamAppId" to "504230", "localGameId" to "504230"), plan.intent.stringExtras)
        assertEquals(mapOf("autoStartGame" to true), plan.intent.boolExtras)
        val win = planOf("gamehub-lite", game("win", path), androidEmu("gamehub-lite", "gamehub.lite"), LaunchTarget.File(path), LaunchOptions(injectedText = "504230"))
        assertEquals(mapOf("steamAppId" to "504230"), win.intent.stringExtras)
    }

    @Test
    fun winlatorCmodGetsTheShortcutPath() {
        val path = "/storage/emulated/0/Download/Winlator/Frontend/Celeste.desktop"
        val plan = planOf("winlator-cmod", game("win", path), androidEmu("winlator-cmod", "com.winlator.cmod"), LaunchTarget.Shortcut(path, ShortcutFormat.WINLATOR_DESKTOP))
        assertEquals("com.winlator.cmod.XServerDisplayActivity", plan.intent.activity)
        assertEquals(mapOf("shortcut_path" to path), plan.intent.stringExtras)
        assertTrue(plan.intent.clearTask && plan.intent.clearTop)
        assertTrue(plan.unresolvedTokens.isEmpty())
    }

    @Test
    fun mainlineWinlatorOnlyOpensTheApp() {
        val path = "$ROMS/win/Celeste.desktop"
        val plan = adapter("winlator").plan(LaunchRequest(game("win", path), androidEmu("winlator", "com.winlator/com.winlator.MainActivity"), LaunchTarget.File(path)))
        val open = assertIs<LaunchPlan.OpenAppOnly>(plan)
        assertEquals("com.winlator", open.appId)
        assertTrue(open.reason.contains("does not export"))
    }

    @Test
    fun openAppOnlyForLaunchersWithoutExternalLaunch() {
        for (id in listOf("lemuroid", "rpcsx", "rpcs3-android", "winlator-frost", "gamehub", "strato", "shadps4", "panda3ds")) {
            assertTrue(adapter(id).opensAppOnly, id)
        }
    }

    @Test
    fun vita3kStartParameters() {
        val path = "$ROMS/psvita/Persona 4 Golden.psvita"
        val plan = planOf("vita3k", game("psvita", path), androidEmu("vita3k", "org.vita3k.emulator"), LaunchTarget.File(path), LaunchOptions(injectedText = "PCSB00245\r\n"))
        assertEquals(mapOf("AppStartParameters" to listOf("-r", "PCSB00245")), plan.intent.arrayExtras)
        assertEquals("org.vita3k.emulator.Emulator", plan.intent.activity)
        val bad = adapter("vita3k").plan(LaunchRequest(game("psvita", path), androidEmu("vita3k", "org.vita3k.emulator"), LaunchTarget.File(path), LaunchOptions(injectedText = "not an id")))
        assertIs<LaunchPlan.Unsupported>(bad)
    }

    @Test
    fun spoofedPackagesRequireAnActivityCheck() {
        for (pkg in listOf("com.ludashi.benchmark", "com.tencent.ig", "com.antutu.ABenchMark", "com.miHoYo.Yuanshen")) {
            assertTrue(AndroidEmulatorCatalog.requiresActivityCheck(pkg), pkg)
            assertTrue(AndroidEmulatorCatalog.detectionEntries().filter { it.pkg == pkg }.all { it.requiresActivityCheck })
        }
        // Shared by mainline Winlator and Winlator Cmod Glibc.
        assertTrue(AndroidEmulatorCatalog.requiresActivityCheck("com.winlator"))
        assertFalse(AndroidEmulatorCatalog.requiresActivityCheck("org.ppsspp.ppsspp"))

        val path = "$ROMS/switch/Zelda.nsp"
        val spoofed = planOf("eden", game("switch", path), androidEmu("eden", "com.miHoYo.Yuanshen"), LaunchTarget.File(path))
        assertTrue(spoofed.requiresActivityCheck)
        assertEquals(listOf("org.yuzu.yuzu_emu.activities.EmulationActivity"), spoofed.activityCandidates)
        val plain = planOf("eden", game("switch", path), androidEmu("eden", "dev.eden.eden_emulator"), LaunchTarget.File(path))
        assertFalse(plain.requiresActivityCheck)
    }

    @Test
    fun identifyResolvesSharedPackagesByActivity() {
        val tencent = AndroidEmulatorCatalog.identify("com.tencent.ig") { it == "com.winlator.cmod.XServerDisplayActivity" }
        assertEquals(listOf("winlator-cmod"), tencent.map { it.def.id })
        assertTrue(AndroidEmulatorCatalog.identify("com.tencent.ig") { false }.isEmpty())

        val glibc = AndroidEmulatorCatalog.identify("com.winlator") { true }
        assertEquals(listOf("winlator-glibc"), glibc.map { it.def.id })
        val mainline = AndroidEmulatorCatalog.identify("com.winlator") { it == "com.winlator.MainActivity" }
        assertEquals(listOf("winlator"), mainline.map { it.def.id })

        val installed = AndroidEmulatorCatalog.toInstalled(tencent.single(), label = "Winlator Ludashi")
        assertEquals("com.tencent.ig/com.winlator.cmod.XServerDisplayActivity", installed.appId)
        assertFalse(installed.isFamilyMatch)
    }

    @Test
    fun familyMatchingOfForks() {
        assertEquals("citron", AndroidFamilies.familyOf("org.citron.citron_emu.nightly"))
        assertEquals("azaharplus", AndroidFamilies.familyOf("com.example.azaharplus.fork"))
        assertEquals("azahar", AndroidFamilies.familyOf("io.github.borked3ds.android", "Borked3DS"))
        assertEquals("winlator-frost", AndroidFamilies.familyOf("com.frost.app", "Winlator Frost"))
        assertEquals("winlator-cmod", AndroidFamilies.familyOf("com.example.win", "Winlator Cmod 11"))
        assertEquals("gamehub-lite", AndroidFamilies.familyOf("com.banner.hub", "GameHub Lite"))
        assertNull(AndroidFamilies.familyOf("com.magneticchen.daijishou", "Daijisho launcher"))
        assertNull(AndroidFamilies.familyOf("com.example.notes", "Notes"))

        val fork = AndroidEmulatorCatalog.identify("org.citron.citron_emu.nightly", "Citron Nightly") {
            it == "org.citron.citron_emu.activities.EmulationActivity"
        }.single()
        assertTrue(fork.isFamilyMatch)
        val installed = AndroidEmulatorCatalog.toInstalled(fork, "Citron Nightly")
        assertEquals("org.citron.citron_emu.nightly/org.citron.citron_emu.activities.EmulationActivity", installed.appId)
        val path = "$ROMS/switch/Zelda.nsp"
        val plan = planOf("citron", game("switch", path), installed, LaunchTarget.File(path))
        assertTrue(plan.isFamilyMatch && plan.requiresActivityCheck)
        assertEquals("org.citron.citron_emu.nightly", plan.intent.packageName)

        // Fork without any known activity: open the app only.
        val unknown = AndroidEmulatorCatalog.identify("org.citron.citron_emu.nightly", "Citron Nightly") { false }.single()
        val open = adapter("citron").plan(LaunchRequest(game("switch", path), AndroidEmulatorCatalog.toInstalled(unknown), LaunchTarget.File(path)))
        assertIs<LaunchPlan.OpenAppOnly>(open)
    }

    @Test
    fun nativeAppFiles() {
        val path = "$ROMS/android/Stardew Valley.app"
        val withActivity = NativeAppAdapter.planAndroid(
            LaunchRequest(game("android", path), androidEmu("android-app", "builtin"), LaunchTarget.File(path), LaunchOptions(injectedText = "com.chucklefish.stardewvalley/.MainActivity\r\n")),
        ).intent!!
        assertEquals("com.chucklefish.stardewvalley", withActivity.intent.packageName)
        assertEquals("com.chucklefish.stardewvalley.MainActivity", withActivity.intent.activity)
        val pkgOnly = NativeAppAdapter.plan(LaunchRequest(game("android", path), androidEmu("android-app", "builtin"), LaunchTarget.App("com.chucklefish.stardewvalley")))
        assertNull((pkgOnly as LaunchPlan.AndroidIntent).activity)
        val bad = NativeAppAdapter.plan(LaunchRequest(game("android", path), androidEmu("android-app", "builtin"), LaunchTarget.File(path), LaunchOptions(injectedText = "not a package")))
        assertIs<LaunchPlan.Unsupported>(bad)
    }

    @Test
    fun queriesPackageListIsSortedAndUnique() {
        val pkgs = AndroidEmulatorCatalog.allPackages()
        assertEquals(pkgs.sorted(), pkgs)
        assertEquals(pkgs.distinct(), pkgs)
        assertTrue("com.retroarch.aarch64" in pkgs && "app.gamenative" in pkgs && "com.tencent.ig" in pkgs)
        println("<queries> packages (${pkgs.size}):")
        pkgs.forEach { println("    <package android:name=\"$it\" />") }
    }
}
