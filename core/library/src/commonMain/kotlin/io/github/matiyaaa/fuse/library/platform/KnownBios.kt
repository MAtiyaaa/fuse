package io.github.matiyaaa.fuse.library.platform

import io.github.matiyaaa.fuse.model.BiosFile
import io.github.matiyaaa.fuse.model.BiosRequirement

/**
 * Firmware requirements for catalog platforms. Fuse never ships firmware; these only describe what
 * to look for. File names follow the libretro/ES-DE conventions; MD5s are only listed where the
 * dump is well documented (PlayStation), because a wrong hash would hide a perfectly good file.
 *
 * A name containing `*` or `?` is a case-insensitive glob (see [io.github.matiyaaa.fuse.library.bios.BiosChecker]).
 */
internal object KnownBios {
    private const val PS2_BIOS_MIN_SIZE = 3_670_016L // 3.5 MiB; real dumps are 4 MiB.

    private fun scph(model: String, md5: String? = null) = BiosFile(
        name = "scph$model.bin",
        md5 = setOfNotNull(md5),
        aliases = setOf("scph-$model.bin"),
    )

    val psx = BiosRequirement(
        label = "PlayStation BIOS",
        files = listOf(
            scph("5501", "490f666e1afb15b7362b406ed1cea246"),
            scph("5500", "8dd7d5296a650fac7319bce665a6a53c"),
            scph("5502", "32736f17079d0b2b7024407c39bd3050"),
            scph("1001", "924e392ed05558ffdb115408c263dccf"),
            scph("7001", "1e68c231d0896b7eadcad1d7d8e76129"),
            scph("101", "6e3735ff4c7dc899ee98981385f6f3d0"),
            scph("1000"),
            scph("1002"),
            scph("7000"),
            scph("7002"),
            scph("102"),
            BiosFile("psxonpsp660.bin"),
            BiosFile("ps1_rom.bin"),
        ),
        requiredCount = 1,
        hint = "Any one PlayStation BIOS dumped from your own console, for example scph5501.bin, in the BIOS folder your emulator reads.",
    )

    val ps2 = BiosRequirement(
        label = "PlayStation 2 BIOS",
        files = listOf(
            BiosFile(
                name = "SCPH-*.bin",
                aliases = setOf("scph*.bin", "ps2-*.bin"),
                minSize = PS2_BIOS_MIN_SIZE,
            ),
        ),
        requiredCount = 1,
        hint = "A PlayStation 2 BIOS dump (a 4 MB SCPH-xxxxx.bin) from your own console, in the emulator's BIOS folder.",
    )

    val ps3 = BiosRequirement(
        label = "PlayStation 3 system software",
        files = listOf(BiosFile("PS3UPDAT.PUP")),
        requiredCount = 1,
        installedInEmulator = true,
        hint = "Download PS3UPDAT.PUP from Sony and install it from the emulator's menu. Fuse cannot see inside the emulator.",
    )

    val psvita = BiosRequirement(
        label = "PS Vita firmware",
        files = listOf(BiosFile("PSVUPDAT.PUP"), BiosFile("PSP2UPDAT.PUP")),
        requiredCount = 1,
        installedInEmulator = true,
        hint = "Install the firmware (PSVUPDAT.PUP) and the font package (PSP2UPDAT.PUP) from inside Vita3K.",
    )

    val switch = BiosRequirement(
        label = "Switch keys and firmware",
        files = listOf(BiosFile("prod.keys"), BiosFile("title.keys")),
        requiredCount = 1,
        installedInEmulator = true,
        hint = "Dump prod.keys and the system firmware from your own Switch and install both from the emulator's settings.",
    )

    val n3ds = BiosRequirement(
        label = "3DS keys",
        files = listOf(BiosFile("aes_keys.txt")),
        requiredCount = 1,
        installedInEmulator = true,
        optional = true,
        hint = "Only needed for encrypted dumps. Decrypted .3ds, .cci and .cia files play without it.",
    )

    val nds = BiosRequirement(
        label = "Nintendo DS BIOS",
        files = listOf(BiosFile("bios7.bin"), BiosFile("bios9.bin"), BiosFile("firmware.bin")),
        requiredCount = 3,
        optional = true,
        hint = "Optional. melonDS runs most games with its built-in BIOS; its DS mode and the system menu need bios7.bin, bios9.bin and firmware.bin.",
    )

    val dsi = BiosRequirement(
        label = "Nintendo DSi BIOS and NAND",
        files = listOf(
            BiosFile("dsi_bios7.bin"),
            BiosFile("dsi_bios9.bin"),
            BiosFile("dsi_firmware.bin"),
            BiosFile("dsi_nand.bin"),
        ),
        requiredCount = 4,
        hint = "DSi mode in melonDS needs dsi_bios7.bin, dsi_bios9.bin, dsi_firmware.bin and dsi_nand.bin dumped from your own DSi.",
    )

    val gba = BiosRequirement(
        label = "Game Boy Advance BIOS",
        files = listOf(BiosFile("gba_bios.bin")),
        requiredCount = 1,
        optional = true,
        hint = "Optional. Most emulators include a replacement BIOS; the original gba_bios.bin improves accuracy.",
    )

    val fds = BiosRequirement(
        label = "Famicom Disk System BIOS",
        files = listOf(BiosFile("disksys.rom")),
        requiredCount = 1,
        hint = "Disk System games need disksys.rom in the BIOS folder.",
    )

    val saturn = BiosRequirement(
        label = "Saturn BIOS",
        files = listOf(
            BiosFile("sega_101.bin"),
            BiosFile("mpr-17933.bin"),
            BiosFile("saturn_bios.bin"),
        ),
        requiredCount = 1,
        optional = true,
        hint = "Optional for emulators with a built-in BIOS (Yaba Sanshiro); Beetle Saturn needs sega_101.bin (JP) or mpr-17933.bin (US/EU).",
    )

    val segacd = BiosRequirement(
        label = "Mega-CD / Sega CD BIOS",
        files = listOf(
            BiosFile("bios_CD_U.bin", aliases = setOf("us_scd1_9210.bin", "us_scd2_9303.bin")),
            BiosFile("bios_CD_E.bin", aliases = setOf("eu_mcd1_9210.bin", "eu_mcd2_9303.bin", "eu_mcd2_9306.bin")),
            BiosFile("bios_CD_J.bin", aliases = setOf("jp_mcd1_9112.bin", "jp_mcd1_9111.bin")),
        ),
        requiredCount = 1,
        hint = "One Mega-CD / Sega CD BIOS for the region of your games, for example bios_CD_U.bin.",
    )

    val dreamcast = BiosRequirement(
        label = "Dreamcast BIOS",
        files = listOf(BiosFile("dc_boot.bin"), BiosFile("dc_flash.bin")),
        requiredCount = 2,
        optional = true,
        hint = "Optional. Flycast boots most games with its HLE BIOS; some games need dc_boot.bin and dc_flash.bin (RetroArch: system/dc/).",
    )

    private val pceSystemCards = listOf(
        BiosFile("syscard3.pce"),
        BiosFile("syscard2.pce"),
        BiosFile("syscard1.pce"),
        BiosFile("gexpress.pce"),
    )

    val pceCd = BiosRequirement(
        label = "PC Engine CD System Card",
        files = pceSystemCards,
        requiredCount = 1,
        hint = "CD games need a System Card image, usually syscard3.pce.",
    )

    val pceHuCard = pceCd.copy(
        optional = true,
        hint = "Only CD games need a System Card image (syscard3.pce); HuCard games run without it.",
    )

    val xbox = BiosRequirement(
        label = "Xbox boot ROM and flash",
        files = listOf(BiosFile("mcpx_1.0.bin"), BiosFile("Complex_4627.bin")),
        requiredCount = 2,
        hint = "xemu needs the MCPX boot ROM (mcpx_1.0.bin) and a flash BIOS (Complex_4627.bin) dumped from your own Xbox.",
    )

    val threeDo = BiosRequirement(
        label = "3DO BIOS",
        files = listOf(
            BiosFile("panafz10.bin"),
            BiosFile("panafz1.bin"),
            BiosFile("panafz10-norsa.bin"),
            BiosFile("panafz1j.bin"),
            BiosFile("goldstar.bin"),
            BiosFile("sanyotry.bin"),
        ),
        requiredCount = 1,
        hint = "One 3DO BIOS, for example panafz10.bin.",
    )

    val neoGeo = BiosRequirement(
        label = "Neo Geo BIOS",
        files = listOf(BiosFile("neogeo.zip")),
        requiredCount = 1,
        hint = "Neo Geo games need neogeo.zip next to the games or in the BIOS folder.",
    )

    val intellivision = BiosRequirement(
        label = "Intellivision BIOS",
        files = listOf(BiosFile("exec.bin"), BiosFile("grom.bin")),
        requiredCount = 2,
        hint = "FreeIntv needs exec.bin and grom.bin.",
    )

    val colecovision = BiosRequirement(
        label = "ColecoVision BIOS",
        files = listOf(BiosFile("colecovision.rom", aliases = setOf("coleco.rom"))),
        requiredCount = 1,
        hint = "ColecoVision games need the console BIOS (colecovision.rom).",
    )

    val atari5200 = BiosRequirement(
        label = "Atari 5200 BIOS",
        files = listOf(BiosFile("5200.rom", aliases = setOf("atari5200.rom"))),
        requiredCount = 1,
        hint = "Atari 5200 games need 5200.rom.",
    )

    val atari7800 = BiosRequirement(
        label = "Atari 7800 BIOS",
        files = listOf(BiosFile("7800 BIOS (U).rom", aliases = setOf("7800 BIOS (E).rom"))),
        requiredCount = 1,
        optional = true,
        hint = "Optional. Games run without it; the BIOS adds the original boot sequence.",
    )

    val lynx = BiosRequirement(
        label = "Atari Lynx boot ROM",
        files = listOf(BiosFile("lynxboot.img")),
        requiredCount = 1,
        optional = true,
        hint = "Some Lynx emulators need lynxboot.img.",
    )
}
