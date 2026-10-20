package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.launch.extras.BiosPlacement
import io.github.matiyaaa.fuse.launch.extras.CemuGraphicPacks
import io.github.matiyaaa.fuse.launch.extras.Rpcs3PatchFile
import io.github.matiyaaa.fuse.launch.extras.SwitchFirmware
import io.github.matiyaaa.fuse.launch.extras.Unpacking
import io.github.matiyaaa.fuse.launch.extras.XeniaPatches
import io.github.matiyaaa.fuse.launch.online.CemuNetwork
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Downloads, patches, firmware and online services for emulators, each as the emulator does it. */
class ExtrasTest {
    @Test
    fun cemuTakesTheFirstAssetOfTheLatestReleaseAndComparesItsName() {
        val release = assertNotNull(CemuGraphicPacks.release(
            """{"name":"Graphic Packs 1027 (Cemu 2.0+)","assets":[{"name":"graphicPacks1027.zip","size":4096,"browser_download_url":"https://github.com/cemu-project/cemu_graphic_packs/releases/download/Github1027/graphicPacks1027.zip"},{"name":"other.zip"}]}""",
        ))
        assertEquals("Graphic Packs 1027 (Cemu 2.0+)", release.name)
        assertEquals(4096L, release.size)
        assertTrue(CemuGraphicPacks.upToDate("graphic packs 1027 (cemu 2.0+)\n", release), "the name is compared without minding case, as Cemu does")
        assertFalse(CemuGraphicPacks.upToDate("Graphic Packs 1026 (Cemu 2.0+)", release))
        assertFalse(CemuGraphicPacks.upToDate(null, release))
        assertNull(CemuGraphicPacks.release("""{"name":"x","assets":[]}"""), "no asset, nothing to fetch")
        assertNull(CemuGraphicPacks.release("""{"name":"x","assets":[{"browser_download_url":"http://insecure/x.zip"}]}"""))
        // Folders, empty files, files climbing out and huge files are left out, as Cemu leaves them.
        assertEquals(
            listOf("BotW_Resolution/rules.txt"),
            CemuGraphicPacks.files(listOf("BotW_Resolution/" to 0L, "BotW_Resolution/rules.txt" to 120L, "Empty/x.txt" to 0L, "../evil/rules.txt" to 5L, "Huge/blob.bin" to CemuGraphicPacks.MAX_FILE + 1)),
        )
    }

    @Test
    fun rpcs3sPatchFileIsKeptOnlyWhenItsChecksumAndVersionAreRight() {
        val patch = "Version: 1.2\n\nPPU-0123456789abcdef0123456789abcdef01234567:\n  \"60 fps\":\n    Games: {}\n"
        val sum = Rpcs3PatchFile.sha256Hex(patch)
        assertEquals(64, sum.length)
        assertEquals("https://rpcs3.net/compatibility?patch&api=v1&v=1.2", Rpcs3PatchFile.url(null))
        assertEquals("https://rpcs3.net/compatibility?patch&api=v1&v=1.2&sha256=$sum", Rpcs3PatchFile.url(patch))
        val body = { version: String, hash: String -> """{"return_code":0,"version":"$version","sha256":"$hash","patch":${kotlinx.serialization.json.JsonPrimitive(patch)}}""" }
        assertEquals(Rpcs3PatchFile.Answer.Newer(patch), Rpcs3PatchFile.read(body("1.2", sum)))
        assertIs<Rpcs3PatchFile.Answer.Failed>(Rpcs3PatchFile.read(body("1.2", "0".repeat(64))), "a patch that didn't arrive whole is refused")
        assertIs<Rpcs3PatchFile.Answer.Failed>(Rpcs3PatchFile.read(body("1.3", sum)), "patches for another engine are refused")
        assertEquals(Rpcs3PatchFile.Answer.UpToDate, Rpcs3PatchFile.read("""{"return_code":1}"""))
        assertIs<Rpcs3PatchFile.Answer.Failed>(Rpcs3PatchFile.read("""{"return_code":-2}"""))
        assertIs<Rpcs3PatchFile.Answer.Failed>(Rpcs3PatchFile.read("<html>"))
        // The known answer: SHA-256 of "abc".
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Rpcs3PatchFile.sha256Hex("abc"))
    }

    @Test
    fun xeniaPatchesTurnedOnStayOnInTheNewFiles() {
        val entries = listOf("patches/4D5307E6 - Halo 3.patch.toml", "patches/README.md", "data/FFXIII-font/test.dds", "../4D5307E6 - X.patch.toml", "415607D2 - Quake 4.patch.toml")
        assertEquals(
            listOf("patches/4D5307E6 - Halo 3.patch.toml" to "4D5307E6 - Halo 3.patch.toml", "415607D2 - Quake 4.patch.toml" to "415607D2 - Quake 4.patch.toml"),
            XeniaPatches.files(entries),
        )
        val old = """
            title_name = "Quake 4"
            [[patch]]
                name = "60 FPS"
                is_enabled = true

                [[patch.be32]]
                    address = 0x821b7140
                    value = 0x39600001
            [[patch]]
                name = "No vignette"
                is_enabled = false
        """.trimIndent()
        assertEquals(setOf("60 FPS"), XeniaPatches.enabled(old))
        val fresh = old.replace("is_enabled = true", "is_enabled = false") + "\n[[patch]]\n    name = \"New one\"\n    is_enabled = false # off as released"
        val carried = XeniaPatches.carryOver(fresh, setOf("60 FPS"))
        assertEquals(setOf("60 FPS"), XeniaPatches.enabled(carried))
        assertTrue("is_enabled = false # off as released" in carried, "everything else is as released")
        assertEquals(fresh, XeniaPatches.carryOver(fresh, emptySet()))
    }

    @Test
    fun switchFirmwareIsTheNcaFilesAtItsTop() {
        val ncas = (1..120).map { it.toString(16).padStart(32, '0') + if (it % 2 == 0) ".nca" else ".cnmt.nca" }
        assertEquals(ncas, SwitchFirmware.files(ncas + listOf("readme.txt", "nested/" + "a".repeat(32) + ".nca")))
        assertTrue(SwitchFirmware.isFirmware(SwitchFirmware.files(ncas)))
        // A zip with everything in one folder is read from inside it.
        assertEquals(120, SwitchFirmware.files(ncas.map { "Firmware 19.0.1/$it" }).size)
        assertFalse(SwitchFirmware.isFirmware(SwitchFirmware.files(ncas.take(3))))
        assertEquals(emptyList(), SwitchFirmware.files(listOf("prod.keys", "notes.nca")))
    }

    @Test
    fun firmwareGoesWhereEachEmulatorReadsItAndNeverOverAFileThere() {
        assertEquals("/d/DuckStation/bios", BiosPlacement.duckStation("/d/DuckStation", null))
        assertEquals("/d/DuckStation/mybios", BiosPlacement.duckStation("/d/DuckStation", "[Main]\nX = 1\n[BIOS]\nSearchDirectory = mybios\n"))
        assertEquals("/firmware/ps1", BiosPlacement.duckStation("/d/DuckStation", "[BIOS]\nSearchDirectory = /firmware/ps1\n"))
        assertEquals("/p/PCSX2/bios", BiosPlacement.pcsx2("/p/PCSX2", "[Folders]\nSnapshots = snaps\n"))
        assertEquals("D:\\BIOS", BiosPlacement.pcsx2("C:/PCSX2", "[Folders]\nBios = D:\\BIOS\n"))
        assertEquals("/ra/system/dc", BiosPlacement.retroArch("/ra/system", "dc"))
        assertEquals("/ra/system", BiosPlacement.retroArch("/ra/system/", "psx"))
        assertEquals(
            listOf("/bios/scph5501.bin" to "/d/bios/scph5501.bin"),
            BiosPlacement.copies(listOf("/bios/scph5501.bin", "/bios/SCPH1001.BIN", "/d/bios/scph7001.bin"), "/d/bios", setOf("scph1001.bin", "scph7001.bin")),
        )
    }

    @Test
    fun aDiscGameThatCameAsAnArchiveIsUnpackedButCartridgesAndArcadeStayAsTheyAre() {
        fun p(id: String) = PlatformCatalog.byId(PlatformId(id))!!
        assertTrue(Unpacking.needed(p("psx"), "/g/psx/Crash.7z"), "DuckStation can't open it")
        assertTrue(Unpacking.needed(p("ps2"), "/g/ps2/Okami.zip"))
        assertTrue(Unpacking.needed(p("ngc"), "/g/gc/Melee.rar"))
        assertFalse(Unpacking.needed(p("snes"), "/g/snes/Mario.zip"), "cartridge emulators open archives")
        assertFalse(Unpacking.needed(p("arcade"), "/g/arcade/sf2.zip"), "arcade sets must stay zipped")
        assertFalse(Unpacking.needed(p("psx"), "/g/psx/Crash.chd"))
        assertEquals("/g/psx/Crash Bandicoot (USA)", Unpacking.folder("/g/psx/Crash Bandicoot (USA).7z"))
        assertTrue(Unpacking.safe("Disc 1/track01.bin"))
        assertFalse(Unpacking.safe("../evil.bin"))
        assertFalse(Unpacking.safe("/etc/passwd"))
        assertFalse(Unpacking.safe("C:/x.bin"))
    }

    @Test
    fun cemusAccountGoesThroughPretendoAndBackLeavingOtherAccountsAlone() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <content>
                <Account>
                    <PersistentId>2147483650</PersistentId>
                    <OnlineEnabled>false</OnlineEnabled>
                    <ActiveService>0</ActiveService>
                </Account>
                <AccountService>
                    <SelectedService PersistentId="2147483649" Service="1"/>
                </AccountService>
            </content>
        """.trimIndent()
        assertEquals(2147483650L, CemuNetwork.account(xml))
        assertNull(CemuNetwork.service(xml))
        val on = CemuNetwork.withService(xml, CemuNetwork.PRETENDO)
        assertEquals(CemuNetwork.PRETENDO, CemuNetwork.service(on))
        assertTrue("<SelectedService PersistentId=\"2147483649\" Service=\"1\"/>" in on, "the other account's choice stays")
        val off = CemuNetwork.withService(on, CemuNetwork.OFFLINE)
        assertEquals(CemuNetwork.OFFLINE, CemuNetwork.service(off))
        assertEquals(1, Regex("PersistentId=\"2147483650\"").findAll(off).count(), "one choice per account")
        // No AccountService yet: one is made, inside the settings.
        val bare = "<content>\n    <Account>\n        <PersistentId>2147483649</PersistentId>\n    </Account>\n</content>\n"
        val made = CemuNetwork.withService(bare, CemuNetwork.PRETENDO)
        assertEquals(CemuNetwork.PRETENDO, CemuNetwork.service(made))
        assertTrue(made.trimEnd().endsWith("</content>"))
    }
}
