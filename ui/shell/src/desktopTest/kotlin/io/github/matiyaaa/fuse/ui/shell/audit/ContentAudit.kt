package io.github.matiyaaa.fuse.ui.shell.audit

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.model.PadButton
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.shell.store.FakeServices
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.InstallerResult
import io.github.matiyaaa.fuse.ui.shell.store.createFuseStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Installed Content: a PS3 game kept as packages on a computer with RPCS3. Its game page with the
 * state chips, the content page before installing (a licence missing for its DLC), and after.
 */
internal fun AuditDriver.contentScreens() {
    scenario("game", "installed content") {
        val (store, services) = contentStore()
        show(store)
        search("Sky Racer")
        tap(PadButton.START)
        tap(PadButton.X)
        tapText("Game Info")
        waitFor("Needs installation")
        shoot("game page: needs installation, licence, update and DLC")
        tapText("Install", step = PadButton.DPAD_RIGHT)
        waitFor("Installed content")
        settle(1_500)
        shoot("content page before installing")
        tap(PadButton.DPAD_DOWN, 3)
        shoot("a missing licence focused")
        tap(PadButton.DPAD_UP, 3)
        services.contentInstaller = { run -> rpcs3(services.rpcs3Hdd!!, run) }
        tap(PadButton.A)
        waitFor("Installed 4 items", timeoutMs = 20_000)
        settle(1_500)
        shoot("installed: ready, the DLC still waits for its licence")
    }
}

/** A computer with RPCS3 and one PS3 game kept as packages: the game, an update and DLC, the game's licence. */
private fun AuditDriver.contentStore(): Pair<FuseStore, FakeServices> = runBlocking {
    val base = File(cache, "content-audit").apply { deleteRecursively(); mkdirs() }
    val games = File(base, "Games/ps3/Sky Racer").apply { mkdirs() }
    val id = "UP9000-NPUB30001_00-SKYRACER00000000"
    File(games, "Sky Racer [NPUB30001].pkg").writeBytes(AuditPkg.ps3(id, 0x05, drm = 2, version = "01.00"))
    File(games, "Sky Racer Update 1.02.pkg").writeBytes(AuditPkg.ps3(id, 0x04, flags = 0x10, drm = 3, version = "01.02"))
    File(games, "Sky Racer Update 1.05.pkg").writeBytes(AuditPkg.ps3(id, 0x04, flags = 0x10, drm = 3, version = "01.05"))
    File(games, "Coastal Tracks DLC.pkg").writeBytes(AuditPkg.ps3("UP9000-NPUB30001_00-SKYRACERDLC00001", 0x04, drm = 2))
    File(games, "$id.rap").writeBytes(ByteArray(16) { 3 })
    val hdd = File(base, "rpcs3/dev_hdd0").apply { File(this, "game").mkdirs() }
    val services = FakeServices(
        FuseData(DesktopDatabase.open(File(base, "fuse.db").absolutePath)), File(base, "cache").apply { mkdirs() },
        installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.rpcs3"), "RPCS3", Host.LINUX, "/usr/bin/rpcs3", platforms = setOf(PlatformId("ps3")), detectedVia = "Flatpak")),
    ).also { it.rpcs3Hdd = hdd.absolutePath }
    val store = createFuseStore(services, scope)
    store.updatePrefs { it.copy(onboardingDone = true) }
    store.sources.add(File(base, "Games").absolutePath, LibrarySourceKind.ROMS_ROOT)
    withTimeout(30_000) { store.library.games(GameQuery()).first { it.isNotEmpty() } }
    store to services
}

/** RPCS3 as it behaves: licences into exdata, a package into game/<title id>, a little later. */
private fun rpcs3(hdd: String, run: io.github.matiyaaa.fuse.ui.shell.store.InstallerRun): InstallerResult {
    Thread.sleep(300)
    val file = File(run.argv.last())
    when {
        file.name.endsWith(".rap") -> file.copyTo(File(hdd, "home/00000001/exdata/${file.name}"), overwrite = true)
        file.name.startsWith("Sky Racer [") -> File(hdd, "game/NPUB30001/PARAM.SFO").also { it.parentFile.mkdirs() }
            .writeBytes(AuditPkg.sfo("APP_VER" to "01.00", "CATEGORY" to "HG", "TITLE_ID" to "NPUB30001"))
        file.name.contains("Update") -> File(hdd, "game/NPUB30001/PARAM.SFO")
            .writeBytes(AuditPkg.sfo("APP_VER" to file.name.substringAfter("Update ").removeSuffix(".pkg").let { "0$it" }, "CATEGORY" to "HG", "TITLE_ID" to "NPUB30001"))
    }
    return InstallerResult(0, "Installed ${file.name}")
}

/** Package and PARAM.SFO bytes as RPCS3's unpkg.h and PSF.cpp lay them out. */
private object AuditPkg {
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

    fun ps3(contentId: String, type: Int, flags: Int = 0, drm: Int, version: String? = null): ByteArray {
        val packets = mutableListOf(1 to be32(drm), 2 to be32(type), 3 to be32(flags))
        if (version != null) {
            val (a, b) = version.split('.').map { it.toInt(16) }
            packets += 8 to byteArrayOf(0, 4, 0x30, 0, 1, 0, a.toByte(), b.toByte())
        }
        val meta = packets.fold(ByteArray(0)) { acc, (id, d) -> acc + be32(id) + be32(d.size) + d }
        val header = ByteArray(0xC0)
        be32(0x7F504B47).copyInto(header, 0)
        header[7] = 1
        be32(0xC0).copyInto(header, 8)
        be32(packets.size).copyInto(header, 12)
        be32(meta.size).copyInto(header, 16)
        contentId.encodeToByteArray().copyInto(header, 0x30)
        return header + meta + ByteArray(4096)
    }
}
