package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.data.FuseData
import io.github.matiyaaa.fuse.data.db.DesktopDatabase
import io.github.matiyaaa.fuse.launch.patches.EnhancementKind
import io.github.matiyaaa.fuse.launch.patches.Pcsx2Home
import io.github.matiyaaa.fuse.model.EmulatorId
import io.github.matiyaaa.fuse.model.Host
import io.github.matiyaaa.fuse.model.InstalledEmulator
import io.github.matiyaaa.fuse.model.LibrarySourceKind
import io.github.matiyaaa.fuse.model.PlatformId
import io.ktor.client.engine.mock.respond
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * EmuCoreX's packs and pnach files end to end: matched by what the disc says (serial, CRC), a pack
 * checked against its SHA-256 before it is opened, then added and taken out like a person's own.
 */
class EmuCoreXStoreTest {
    private lateinit var root: File
    private lateinit var cache: File
    private lateinit var pcsx2: File
    private lateinit var scope: CoroutineScope

    /** A minimal PS2 ISO: SYSTEM.CNF and a boot program of ten bytes (CRC 0C040404). */
    private fun ps2Iso(): ByteArray {
        val sector = 2048
        val image = ByteArray(sector * 24)
        fun le32(at: Int, v: Int) { for (i in 0..3) image[at + i] = (v ushr (8 * i)).toByte() }
        fun record(at: Int, lba: Int, size: Int, dir: Boolean, name: String): Int {
            val n = name.encodeToByteArray()
            val len = 33 + n.size + (if (n.size % 2 == 0) 1 else 0)
            image[at] = len.toByte(); le32(at + 2, lba); le32(at + 10, size)
            image[at + 25] = if (dir) 2 else 0; image[at + 32] = n.size.toByte(); n.copyInto(image, at + 33)
            return len
        }
        val pvd = 16 * sector
        image[pvd] = 1
        "CD001".encodeToByteArray().copyInto(image, pvd + 1)
        record(pvd + 156, 18, sector, true, "\u0000")
        val cnf = "BOOT2 = cdrom0:\\SLUS_209.46;1\r\nVMODE = NTSC\r\n".encodeToByteArray()
        val elf = ByteArray(10) { (it + 1).toByte() }
        var at = 18 * sector
        at += record(at, 18, sector, true, "\u0000")
        at += record(at, 18, sector, true, "\u0001")
        at += record(at, 20, cnf.size, false, "SYSTEM.CNF;1")
        record(at, 21, elf.size, false, "SLUS_209.46;1")
        cnf.copyInto(image, 20 * sector)
        elf.copyInto(image, 21 * sector)
        return image
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("fuse-ecx").toFile()
        cache = Files.createTempDirectory("fuse-ecx-cache").toFile()
        pcsx2 = Files.createTempDirectory("fuse-ecx-pcsx2").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        File(root, "ps2").mkdirs()
        File(root, "ps2/Sky Racer.iso").writeBytes(ps2Iso())
        listOf("inis", "patches", "cheats", "gamesettings", "textures").forEach { File(pcsx2, it).mkdirs() }
        File(pcsx2, "inis/PCSX2.ini").writeText("[EmuCore]\nEnableCheats = false\n")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        listOf(root, cache, pcsx2).forEach { it.deleteRecursively() }
    }

    private fun sha256(f: File) = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02X".format(it) }

    private fun services(): FakeServices {
        val services = FakeServices(
            FuseData(DesktopDatabase.open(File(cache, "fuse.db").absolutePath)), cache,
            installedEmulators = listOf(InstalledEmulator(EmulatorId("linux.pcsx2"), "PCSX2", Host.LINUX, "/usr/bin/pcsx2-qt", platforms = setOf(PlatformId("ps2")), detectedVia = "PATH")),
        )
        val home = pcsx2.absolutePath
        services.pcsx2Home = Pcsx2Home(home, "$home/gamesettings", "$home/patches", null, cheats = "$home/cheats", textures = "$home/textures")
        return services
    }

    /** EmuCoreX's two catalogues, as its GitHub serves them, with [pack]'s SHA-256 as given. */
    private fun catalogues(services: FakeServices, pack: File, sha: String) {
        val base = "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/t"
        val textures = """{"schemaVersion": 1, "entries": [
            {"id": "hd-sky", "name": "Sky Racer HD", "serials": ["SLUS-20946"], "authors": ["Pixel"], "version": "2", "license": "See source page terms",
             "downloadUrl": "$base/sky.zip", "sourceUrl": "https://example.org/sky", "sizeBytes": ${pack.length()}, "sha256": "$sha", "fileCount": 1},
            {"id": "hd-other", "name": "Another Game HD", "serials": ["SLES-50000"], "downloadUrl": "$base/other.zip", "sizeBytes": 10, "sha256": "AA"},
            {"id": "hd-pieces", "name": "Sky Racer UHD", "serials": ["SLUS-20946"], "downloadUrl": "$base/u.zip.part001", "sizeBytes": 20, "sha256": "BB",
             "parts": [{"downloadUrl": "$base/u.zip.part001"}, {"downloadUrl": "$base/u.zip.part002"}]}]}"""
        val raw = "https://raw.githubusercontent.com/PCSX2/pcsx2_patches/x/patches"
        val cheats = """{"schemaVersion": 1, "entries": [
            {"id": "ws", "title": "Sky Racer", "crc": "0C040404", "serials": ["SLUS-20946"], "authors": ["Wide"], "description": "Widescreen 16:9",
             "downloadUrl": "$raw/SLUS-20946_0C040404.pnach", "sourceName": "PCSX2 Curated Game Patches"},
            {"id": "money", "title": "Sky Racer", "crc": "0C040404", "authors": ["Codes"], "description": "Max Money",
             "downloadUrl": "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Cheat/main/files/a/0C040404.pnach", "sourceName": "PS2 Cheats PNACH Collection"},
            {"id": "elsewhere", "title": "Other", "crc": "DEADBEEF", "downloadUrl": "$raw/x.pnach"}]}"""
        services.web = { request ->
            val body = when (request.url.toString()) {
                "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Textures/main/textures.json" -> textures
                "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Cheat/main/cheats.json" -> cheats
                else -> null
            }
            body?.let { respond(it, io.ktor.http.HttpStatusCode.OK, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")) }
        }
        services.downloads["$base/sky.zip"] = pack
        services.downloads["$raw/SLUS-20946_0C040404.pnach"] = File(cache, "ws.pnach").apply { writeText("[Widescreen 16:9]\npatch=1,EE,00200000,word,3F400000\n") }
        services.downloads["https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Cheat/main/files/a/0C040404.pnach"] =
            File(cache, "money.pnach").apply { writeText("[Max Money]\npatch=1,EE,00400004,word,0098967F\n") }
    }

    private fun pack(): File = File(cache, "sky.zip").also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("SLUS-20946/replacements/sky.png"))
            z.write("sky".encodeToByteArray())
            z.closeEntry()
        }
    }

    @Test
    fun whatTheDiscSaysFindsItsPacksAndPnachAndTheyGoInLikeYourOwn(): Unit = runBlocking {
        val services = services()
        val pack = pack()
        catalogues(services, pack, sha256(pack))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()

        val found = store.library.emuCoreX(game.id)
        assertNull(found.problem)
        assertEquals("SLUS-20946", found.serial)
        assertEquals("0C040404", found.crc)
        // Only this disc's: another game's pack and another CRC's file aren't listed.
        assertEquals(listOf("hd-sky", "hd-pieces"), found.packs.map { it.first.id })
        assertEquals(listOf(EnhancementKind.TEXTURES, EnhancementKind.TEXTURES), found.packs.map { it.second })
        // The patch first, as a patch; the cheat as a cheat.
        assertEquals(listOf("ws" to EnhancementKind.PATCHES, "money" to EnhancementKind.CHEATS), found.pnach.map { it.first.id to it.second })

        // A pack in pieces is left to its page, and nothing of it is fetched.
        assertIs<EnhancementPreview.Unavailable>(store.library.fetchEmuCoreX(game.id, "hd-pieces"))
        assertTrue(services.fetchedUrls.none { "u.zip" in it })

        val preview = assertIs<EnhancementPreview.Ready>(store.library.fetchEmuCoreX(game.id, "hd-sky"))
        assertEquals("Sky Racer HD", preview.name)
        assertNull(store.library.addEnhancement(preview.token))
        assertEquals("sky", File(pcsx2, "textures/SLUS-20946/replacements/sky.png").readText())
        val added = store.library.enhancements(game.id).added.single()
        assertEquals("EmuCoreX", added.origin)
        assertEquals("https://example.org/sky", added.originUrl)

        val cheat = assertIs<EnhancementPreview.Ready>(store.library.fetchEmuCoreX(game.id, "money"))
        assertNull(store.library.addEnhancement(cheat.token))
        assertTrue(File(pcsx2, "cheats").listFiles()!!.any { "Max Money" in it.readText() })

        assertEquals(EnhancementRemoval.Removed, store.library.removeEnhancement(game.id, added.id))
        assertFalse(File(pcsx2, "textures/SLUS-20946/replacements/sky.png").exists())
    }

    @Test
    fun aPackThatIsntWhatTheCatalogueSaysIsNeverOpened(): Unit = runBlocking {
        val services = services()
        val pack = pack()
        catalogues(services, pack, "0".repeat(64))
        val store = createFuseStore(services, scope)
        store.sources.add(root.absolutePath, LibrarySourceKind.ROMS_ROOT)
        val game = withTimeout(20_000) { store.library.games(GameQuery()).first { it.size == 1 } }.single()
        val refused = assertIs<EnhancementPreview.Unavailable>(store.library.fetchEmuCoreX(game.id, "hd-sky"))
        assertTrue("SHA-256" in refused.reason, refused.reason)
        assertFalse(File(pcsx2, "textures/SLUS-20946").exists())
        assertTrue(store.library.enhancements(game.id).added.isEmpty())
    }
}
