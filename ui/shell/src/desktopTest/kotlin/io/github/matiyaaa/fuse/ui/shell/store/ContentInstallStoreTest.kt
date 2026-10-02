package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.library.content.ContentState
import io.github.matiyaaa.fuse.library.content.ItemRole
import io.github.matiyaaa.fuse.library.content.Licences
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * Fuse's own installs into RPCS3 and Vita3K, through the store, with a fake emulator that writes
 * to its storage the way the real installers do (or doesn't, to check Fuse never claims an
 * install it can't see).
 */
class ContentInstallStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var hdd: File
    private lateinit var pref: File
    private lateinit var scope: CoroutineScope

    private val gameId = "UP0700-BLUS30443_00-DEMONSSOULS00000"
    private val dlcId = "UP0700-BLUS30443_00-DEMONSSOULSDLC01"
    private val vitaId = "UP9000-PCSA00001_00-GRAVITYRUSH00001"

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-content").toFile()
        cache = Files.createTempDirectory("fuse-content-cache").toFile()
        hdd = File(Files.createTempDirectory("rpcs3").toFile(), "dev_hdd0").also { File(it, "game").mkdirs() }
        pref = Files.createTempDirectory("vita3k").toFile().also { File(it, "ux0/app").mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ds = File(root, "ps3/Demon's Souls").also { it.mkdirs() }
        File(ds, "game.pkg").writeBytes(Pkg.ps3(gameId, 0x05, drm = 2, version = "01.00"))
        File(ds, "Update 1.04.pkg").writeBytes(Pkg.ps3(gameId, 0x04, flags = 0x10, drm = 3, version = "01.04"))
        File(ds, "Armour.pkg").writeBytes(Pkg.ps3(dlcId, 0x04, drm = 2))
        File(ds, "$gameId.rap").writeBytes(ByteArray(16) { 7 })
        File(root, "elsewhere").mkdirs()
        File(root, "elsewhere/armour licence.rap").writeBytes(ByteArray(16) { 9 })
        val gr = File(root, "psvita/Gravity Rush").also { it.mkdirs() }
        File(gr, "game.pkg").writeBytes(Pkg.vita(vitaId, "gd"))
        File(gr, "key.txt").writeText("zRIF: ${zrif()}\n")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, hdd.parentFile, pref).forEach { it.deleteRecursively() }
    }

    private fun zrif(): String = Licences.zrifOf(ByteArray(512).also { b -> b[1] = 1; vitaId.encodeToByteArray().copyInto(b, 0x10) })!!

    private fun sfo(vararg values: Pair<String, String>): ByteArray = Pkg.sfo(*values)

    /** RPCS3 as it behaves: licences copied into exdata, packages unpacked into game/<title id>. */
    private fun rpcs3(run: InstallerRun): InstallerResult {
        val file = File(run.argv.last())
        assertEquals(listOf("--headless", "--installpkg"), run.argv.dropLast(1).takeLast(2))
        when {
            file.name.endsWith(".rap") -> file.copyTo(File(hdd, "home/00000001/exdata/${file.name}"), overwrite = true)
            file.name == "game.pkg" -> File(hdd, "game/BLUS30443/PARAM.SFO").also { it.parentFile.mkdirs() }
                .writeBytes(sfo("APP_VER" to "01.00", "CATEGORY" to "HG", "TITLE_ID" to "BLUS30443"))
            file.name.startsWith("Update") -> File(hdd, "game/BLUS30443/PARAM.SFO")
                .writeBytes(sfo("APP_VER" to "01.04", "CATEGORY" to "HG", "TITLE_ID" to "BLUS30443"))
            file.name == "Armour.pkg" -> File(hdd, "game/BLUS30443/USRDIR/DLC/armour.edat").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(4))
        }
        return InstallerResult(0, "RPCS3 installed ${file.name}")
    }

    private suspend fun services(): FakeServices = FakeServices(
        FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
        installedEmulators = listOf(
            InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "PATH"),
            InstalledEmulator(EmulatorId("linux.vita3k"), "Vita3K", Host.LINUX, "/usr/bin/Vita3K", platforms = setOf(PlatformId("psvita")), detectedVia = "PATH"),
        ),
    ).also {
        it.rpcs3Hdd = hdd.absolutePath
        it.vitaPref = pref.absolutePath
    }

    @Test
    fun ps3ContentInstallsInOrderAndOnlyCountsWhatRpcs3Holds(): Unit = runBlocking {
        val services = services()
        services.contentInstaller = ::rpcs3
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }
        val ds = games.first { it.platformId == PlatformId("ps3") }

        val before = assertNotNull(store.content.view(ds.id))
        assertEquals(InstallMode.FUSE, before.mode)
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.MISSING_LICENCE, ContentState.UPDATE_AVAILABLE, ContentState.DLC_AVAILABLE), before.states)
        assertEquals(listOf(ItemRole.LICENCE, ItemRole.GAME, ItemRole.UPDATE), before.plan.toInstall.map { it.role })

        // Not installed yet: Play says so, a button away from installing.
        val notYet = assertIs<LaunchOutcome.Problem>(store.library.launch(ds.id))
        assertTrue(notYet.problem.actions.any { it is ProblemAction.InstallContent })
        assertTrue(services.launched.isEmpty())

        val report = store.content.install(ds.id)
        assertTrue(report.ok, report.message)
        assertEquals(listOf("$gameId.rap", "game.pkg", "Update 1.04.pkg"), services.installerRuns.map { File(it.argv.last()).name })
        val after = assertNotNull(store.content.view(ds.id))
        assertEquals(listOf(ContentState.READY, ContentState.MISSING_LICENCE, ContentState.DLC_AVAILABLE), after.states)

        // The DLC's licence, picked from another folder, goes in under the name RPCS3 looks for.
        val picked = mapOf(dlcId to File(root, "elsewhere/armour licence.rap").absolutePath)
        assertTrue(store.content.install(ds.id, picked).ok)
        assertTrue(File(hdd, "home/00000001/exdata/$dlcId.rap").isFile)
        assertEquals(listOf(ContentState.READY), store.content.view(ds.id, picked)!!.states)
        // The picked file itself is untouched, and Fuse's copy of it is gone again.
        assertTrue(File(root, "elsewhere/armour licence.rap").isFile)
        assertFalse(File(cache, "staged").exists())

        // Installed: Play starts it by its title id.
        assertIs<LaunchOutcome.Started>(store.library.launch(ds.id))
        assertEquals(io.github.matiyaaa.fuse.model.LaunchTarget.TitleId("BLUS30443"), services.launched.last().target)
    }

    @Test
    fun anInstallTheEmulatorDidntTakeIsReportedAndCanBeRetried(): Unit = runBlocking {
        val services = services()
        // An installer that exits 0 and does nothing, as RPCS3 does when a package is damaged.
        services.contentInstaller = { InstallerResult(0, "E PKG: Failed to decrypt\nlast line") }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val ds = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }.first { it.platformId == PlatformId("ps3") }

        val failed = store.content.install(ds.id)
        assertFalse(failed.ok)
        assertEquals(ItemRole.LICENCE, failed.failed?.role, "it stops at the first step that didn't take")
        assertTrue(failed.installed.isEmpty())
        assertTrue(failed.details.orEmpty().contains("Failed to decrypt"))
        assertEquals(1, services.installerRuns.size, "nothing after it is tried")
        assertTrue(ContentState.NEEDS_INSTALL in store.content.view(ds.id)!!.states)

        // A retry with a working emulator does the whole job once, and nothing twice.
        services.installerRuns.clear()
        services.contentInstaller = ::rpcs3
        assertTrue(store.content.install(ds.id).ok)
        assertEquals(3, services.installerRuns.size)
        services.installerRuns.clear()
        assertTrue(store.content.install(ds.id).installed.isEmpty())
        assertTrue(services.installerRuns.isEmpty(), "everything was already in")
    }

    @Test
    fun vitaPackagesGoInWithTheirZrifWhichFuseNeverKeeps(): Unit = runBlocking {
        val services = services()
        val key = zrif()
        services.contentInstaller = { run ->
            assertEquals("--zrif", run.argv[run.argv.size - 2])
            assertEquals(key, run.argv.last())
            File(pref, "ux0/app/PCSA00001/sce_sys/param.sfo").also { it.parentFile.mkdirs() }
                .writeBytes(sfo("APP_VER" to "01.00", "CATEGORY" to "gd", "TITLE_ID" to "PCSA00001"))
            File(pref, "ux0/license/PCSA00001/$vitaId.rif").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(512))
            InstallerResult(0, "installing with $key")
        }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val gr = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 2 } }.first { it.platformId == PlatformId("psvita") }
        assertEquals("Gravity Rush", gr.title)

        val view = assertNotNull(store.content.view(gr.id))
        assertEquals(listOf(ContentState.NEEDS_INSTALL), view.states)
        val report = store.content.install(gr.id)
        assertTrue(report.ok, report.message)
        assertEquals(listOf(ContentState.READY), store.content.view(gr.id)!!.states)
        assertIs<LaunchOutcome.Started>(store.library.launch(gr.id))
        assertEquals(io.github.matiyaaa.fuse.model.LaunchTarget.TitleId("PCSA00001"), services.launched.last().target)
        // The key went to Vita3K only.
        assertFalse(store.health.diagnostics().contains(key))
    }

    @Test
    fun threeDsCiasInstallWithAzaharAndPlayFromTheInstalledTitle(): Unit = runBlocking {
        val sdmc = File(cache, "azahar/sdmc").also { File(it, "Nintendo 3DS").mkdirs() }
        File(root, "3ds").mkdirs()
        File(root, "3ds/Pokemon X.cia").writeBytes(Pkg.cia("0004000000055D00", 0))
        File(root, "3ds/Pokemon X Update.cia").writeBytes(Pkg.cia("0004000E00055D00", 1 shl 10))
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.azahar"), "Azahar", Host.LINUX, "/usr/bin/azahar", platforms = setOf(PlatformId("3ds")), detectedVia = "PATH")),
        ).also { it.azaharSdmc = sdmc.absolutePath }
        fun installed(id: String, version: Int, app: Boolean) {
            val dir = File(sdmc, "Nintendo 3DS/${"0".repeat(32)}/${"0".repeat(32)}/title/${id.take(8).lowercase()}/${id.drop(8).lowercase()}/content").also { it.mkdirs() }
            File(dir, "00000000.tmd").writeBytes(Pkg.tmd(id, version, 0x2A))
            if (app) File(dir, "0000002a.app").writeBytes(ByteArray(16))
        }
        // The first time, the update is "encrypted": Azahar says so in its exit code.
        var encrypted = true
        services.contentInstaller = { run ->
            assertEquals("-i", run.argv[run.argv.size - 2])
            val file = File(run.argv.last())
            when {
                file.name == "Pokemon X.cia" -> { installed("0004000000055D00", 0, app = true); InstallerResult(0, "Installed CIA successfully.") }
                encrypted -> InstallerResult(7, "Failed to install CIA: CIA is encrypted.")
                else -> { installed("0004000E00055D00", 1 shl 10, app = false); InstallerResult(0, "Installed CIA successfully.") }
            }
        }
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val games = withTimeout(20_000) { store.library.games(GameQuery()).first { g -> g.any { it.platformId == PlatformId("3ds") } } }
        val pokemon = games.single { it.platformId == PlatformId("3ds") }
        assertEquals(listOf(ContentState.NEEDS_INSTALL, ContentState.UPDATE_AVAILABLE), store.content.view(pokemon.id)!!.states)

        val first = store.content.install(pokemon.id)
        assertFalse(first.ok)
        assertTrue(first.message.orEmpty().contains("encrypted"), first.message)
        assertEquals(1, first.installed.size, "the game went in before the update stopped")

        encrypted = false
        assertTrue(store.content.install(pokemon.id).ok)
        assertEquals(listOf(ContentState.READY), store.content.view(pokemon.id)!!.states)

        // Azahar never plays a .cia: Play starts the installed title's first content.
        assertIs<LaunchOutcome.Started>(store.library.launch(pokemon.id))
        val target = services.launched.last().target
        assertIs<io.github.matiyaaa.fuse.model.LaunchTarget.File>(target)
        assertTrue(target.path.endsWith("/title/00040000/00055d00/content/0000002a.app"), target.path)
    }

    @Test
    fun gamesWithNothingToInstallHaveNoContentPage(): Unit = runBlocking {
        File(root, "ps3/Metal Gear Solid 4 [BLUS30109].iso").writeBytes(ByteArray(64))
        val services = services()
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val mgs = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 3 } }.first { it.title.startsWith("Metal Gear") }
        assertNull(store.content.view(mgs.id))
    }
}

/** Package and PARAM.SFO bytes laid out as RPCS3's unpkg.h and PSF.cpp read them. */
private object Pkg {
    fun sfo(vararg values: Pair<String, String>): ByteArray {
        val keys = values.map { (it.first + "\u0000").encodeToByteArray() }
        val data = values.map { (it.second + "\u0000").encodeToByteArray() }
        val keysStart = 20 + values.size * 16
        val dataStart = keysStart + keys.sumOf { it.size }
        val out = ByteArray(dataStart + data.sumOf { it.size })
        fun le(at: Int, v: Int, n: Int) { for (i in 0 until n) out[at + i] = (v ushr (8 * i)).toByte() }
        out[1] = 'P'.code.toByte(); out[2] = 'S'.code.toByte(); out[3] = 'F'.code.toByte()
        le(4, 0x101, 4); le(8, keysStart, 4); le(12, dataStart, 4); le(16, values.size, 4)
        var k = 0
        var d = 0
        values.indices.forEach { i ->
            val at = 20 + i * 16
            le(at, k, 2); le(at + 2, 0x0204, 2); le(at + 4, data[i].size, 4); le(at + 8, data[i].size, 4); le(at + 12, d, 4)
            keys[i].copyInto(out, keysStart + k)
            data[i].copyInto(out, dataStart + d)
            k += keys[i].size
            d += data[i].size
        }
        return out
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun pkg(platform: Int, contentId: String, type: Int, flags: Int, drm: Int, version: String?, sfo: ByteArray?): ByteArray {
        val packets = mutableListOf(1 to be32(drm), 2 to be32(type), 3 to be32(flags))
        if (version != null) {
            val (a, b) = version.split('.').map { it.toInt(16) }
            packets += 8 to byteArrayOf(0, 4, 0x30, 0, 1, 0, a.toByte(), b.toByte())
        }
        val sfoAt = 0xC0 + packets.sumOf { 8 + it.second.size } + if (sfo != null) 8 + 0x38 else 0
        if (sfo != null) packets += 14 to (be32(sfoAt) + be32(sfo.size) + ByteArray(0x30))
        val meta = packets.fold(ByteArray(0)) { acc, (id, d) -> acc + be32(id) + be32(d.size) + d }
        val header = ByteArray(0xC0)
        be32(0x7F504B47).copyInto(header, 0)
        header[7] = platform.toByte()
        be32(0xC0).copyInto(header, 8)
        be32(packets.size).copyInto(header, 12)
        be32(meta.size).copyInto(header, 16)
        contentId.encodeToByteArray().copyInto(header, 0x30)
        return header + meta + (sfo ?: ByteArray(0)) + ByteArray(256)
    }

    fun ps3(contentId: String, type: Int, flags: Int = 0, drm: Int, version: String? = null) = pkg(1, contentId, type, flags, drm, version, null)

    /** A TMD (RSA-2048 signature, header at 0x140) for [titleId] with one content, [contentId]. */
    fun tmd(titleId: String, version: Int, contentId: Int = 0): ByteArray {
        val h = 0x140
        val out = ByteArray(h + 0xC4 + 0x900 + 0x30)
        be32(0x10004).copyInto(out, 0)
        for (i in 0 until 8) out[h + 0x4C + i] = titleId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        out[h + 0x9C] = (version shr 8).toByte(); out[h + 0x9D] = version.toByte(); out[h + 0x9F] = 1
        be32(contentId).copyInto(out, h + 0xC4 + 0x900)
        return out
    }

    /** A CIA: a little-endian header, then certificate chain, ticket and TMD, aligned to 64 bytes. */
    fun cia(titleId: String, version: Int): ByteArray {
        val t = tmd(titleId, version)
        val out = ByteArray(0x2DC0 + t.size + 0x40)
        fun le(at: Int, v: Int) { for (i in 0 until 4) out[at + i] = (v ushr (8 * i)).toByte() }
        le(0, 0x2020); le(0x08, 0xA00); le(0x0C, 0x350); le(0x10, t.size)
        t.copyInto(out, 0x2DC0)
        return out
    }

    fun vita(contentId: String, category: String) = pkg(
        2, contentId, 0x15, 0, 2, null,
        sfo("APP_VER" to "01.00", "CATEGORY" to category, "TITLE" to "Gravity Rush", "TITLE_ID" to contentId.substring(7, 16)),
    )
}
