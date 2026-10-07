package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.integrations.UrlCoding

/**
 * Pictures of the systems themselves (a Game Boy Advance, a PlayStation 2 as it sits on a shelf),
 * for the Systems page's small tiles: RetroArch's Systematic icons, by the libretro team, licensed
 * CC BY 4.0. Fuse fetches them at runtime from a pinned commit and caches them on the device;
 * nothing from the set is bundled with Fuse, only their names.
 */
object SystemIcons {
    const val REPO_URL = "https://github.com/libretro/retroarch-assets"

    /** The commit Fuse reads from (upstream HEAD on 2026-10-07), so a reorganisation upstream cannot break it. */
    const val REF = "d9f969054dc7fbb6fa89519036d2b971e0855b51"

    const val BASE_URL = "https://raw.githubusercontent.com/libretro/retroarch-assets/$REF/xmb/systematic/png"

    const val LICENSE = "CC BY 4.0"
    const val LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"

    /** Credits, to show wherever the icons are offered. */
    const val ATTRIBUTION =
        "System icons from RetroArch's Systematic theme by the libretro team, licensed under CC BY 4.0. " +
            "Fetched on demand from the retroarch-assets repository, not distributed with Fuse."

    /** The icon called [name] under [baseUrl]. */
    fun url(name: String, baseUrl: String = BASE_URL): String = "$baseUrl/${UrlCoding.encode(name)}.png"

    /** Every icon Fuse knows for the system [platformId], its usual look first, then its other models and colours. */
    fun forPlatform(platformId: String): List<String> = NAMES[platformId].orEmpty()

    /**
     * A short label for icon [name] of a system whose usual icon is [base]: "Standard" for the usual
     * one, else the model and colour the set names it by ("CUH-2000 W").
     */
    fun label(name: String, base: String): String = if (name == base) "Standard" else name.removePrefix(base).trim().ifEmpty { "Standard" }

    /** Fuse's system ids and the icons of the set for each, made from the set's file names. */
    private val NAMES: Map<String, List<String>> = mapOf(
        "psx" to listOf("Sony - PlayStation", "Sony - PlayStation DTL-1000", "Sony - PlayStation DTL-1200", "Sony - PlayStation DTL-3000", "Sony - PlayStation DTL-H1000", "Sony - PlayStation DTL-H1200", "Sony - PlayStation DTL-H3000", "Sony - PlayStation SCPH-100"),
        "ps2" to listOf("Sony - PlayStation 2", "Sony - PlayStation 2 PSX-5000", "Sony - PlayStation 2 PSX-5100", "Sony - PlayStation 2 SCPH-10000", "Sony - PlayStation 2 SCPH-70000", "Sony - PlayStation 2 SCPH-70000 T", "Sony - PlayStation 2 SCPH-90000", "Sony - PlayStation 2 SCPH-90000 T"),
        "ps3" to listOf("Sony - Playstation 3", "Sony - Playstation 3 CECH-2000 K", "Sony - Playstation 3 CECH-2000 KT", "Sony - Playstation 3 CECH-4000 K", "Sony - Playstation 3 CECH-4000 KT", "Sony - Playstation 3 CECHA00 KT"),
        "ps4" to listOf("Sony - PlayStation 4", "Sony - PlayStation 4 CUH-1000 KT", "Sony - PlayStation 4 CUH-1000 S", "Sony - PlayStation 4 CUH-1000 ST", "Sony - PlayStation 4 CUH-1000 W", "Sony - PlayStation 4 CUH-1000 WT", "Sony - PlayStation 4 CUH-2000 K", "Sony - PlayStation 4 CUH-2000 KT", "Sony - PlayStation 4 CUH-2000 W", "Sony - PlayStation 4 CUH-2000 WT", "Sony - PlayStation 4 CUH-7000 K", "Sony - PlayStation 4 CUH-7000 KT"),
        "ps5" to listOf("Sony - PlayStation 5", "Sony - PlayStation 5 T", "Sony - PlayStation 5 WT"),
        "psp" to listOf("Sony - PlayStation Portable", "Sony - PlayStation Portable PSP-2000 B", "Sony - PlayStation Portable PSP-2000 H", "Sony - PlayStation Portable PSP-2000 R", "Sony - PlayStation Portable PSP-2000 S", "Sony - PlayStation Portable PSP-2000 W", "Sony - PlayStation Portable PSP-E1000 K", "Sony - PlayStation Portable PSP-E1000 W", "Sony - PlayStation Portable PSP-N1000 K", "Sony - PlayStation Portable PSP-N1000 W"),
        "psvita" to listOf("Sony - PlayStation Vita", "Sony - PlayStation Vita PCH-1000 B", "Sony - PlayStation Vita PCH-1000 R", "Sony - PlayStation Vita PCH-1000 W", "Sony - PlayStation Vita PCH-2000 K", "Sony - PlayStation Vita PCH-2000 W", "Sony - PlayStation Vita VTE-1000 K", "Sony - PlayStation Vita VTE-1000 W"),
        "n64" to listOf("Nintendo - Nintendo 64", "Nintendo - Nintendo 64 NUS-001 A", "Nintendo - Nintendo 64 NUS-001 B", "Nintendo - Nintendo 64 NUS-001 G", "Nintendo - Nintendo 64 NUS-001 H", "Nintendo - Nintendo 64 NUS-001 K", "Nintendo - Nintendo 64 NUS-001 O", "Nintendo - Nintendo 64 NUS-001 P", "Nintendo - Nintendo 64 NUS-001 Q"),
        "nds" to listOf("Nintendo - Nintendo DS", "Nintendo - Nintendo DS NTR-001 B", "Nintendo - Nintendo DS NTR-001 R", "Nintendo - Nintendo DS NTR-001 S", "Nintendo - Nintendo DS USG-001 K"),
        "nintendo-dsi" to listOf("Nintendo - Nintendo DSi", "Nintendo - Nintendo DSi TWL-001 A", "Nintendo - Nintendo DSi TWL-001 K", "Nintendo - Nintendo DSi TWL-001 P", "Nintendo - Nintendo DSi TWL-001 W", "Nintendo - Nintendo DSi UTL-001 B", "Nintendo - Nintendo DSi UTL-001 K", "Nintendo - Nintendo DSi UTL-001 R"),
        "3ds" to listOf("Nintendo - Nintendo 3DS", "Nintendo - Nintendo 3DS CTR-001 A", "Nintendo - Nintendo 3DS CTR-001 K", "Nintendo - Nintendo 3DS CTR-001 Q", "Nintendo - Nintendo 3DS CTR-001 R", "Nintendo - Nintendo 3DS CTR-001 W", "Nintendo - Nintendo 3DS FTR-001 BK", "Nintendo - Nintendo 3DS FTR-001 KB", "Nintendo - Nintendo 3DS FTR-001 KR", "Nintendo - Nintendo 3DS FTR-001 RK", "Nintendo - Nintendo 3DS SPR-001 W"),
        "new-nintendo-3ds" to listOf("Nintendo - Nintendo 3DS", "Nintendo - Nintendo 3DS CTR-001 A", "Nintendo - Nintendo 3DS CTR-001 K", "Nintendo - Nintendo 3DS CTR-001 Q", "Nintendo - Nintendo 3DS CTR-001 R", "Nintendo - Nintendo 3DS CTR-001 W", "Nintendo - Nintendo 3DS FTR-001 BK", "Nintendo - Nintendo 3DS FTR-001 KB", "Nintendo - Nintendo 3DS FTR-001 KR", "Nintendo - Nintendo 3DS FTR-001 RK", "Nintendo - Nintendo 3DS SPR-001 W"),
        "gb" to listOf("Nintendo - Game Boy", "Nintendo - Game Boy DMG-001 B", "Nintendo - Game Boy DMG-001 G", "Nintendo - Game Boy DMG-001 K", "Nintendo - Game Boy DMG-001 P", "Nintendo - Game Boy DMG-001 R", "Nintendo - Game Boy DMG-001 W", "Nintendo - Game Boy DMG-001 Y", "Nintendo - Game Boy MGB-001 A", "Nintendo - Game Boy MGB-001 B", "Nintendo - Game Boy MGB-001 DMG", "Nintendo - Game Boy MGB-001 G", "Nintendo - Game Boy MGB-001 K", "Nintendo - Game Boy MGB-001 P", "Nintendo - Game Boy MGB-001 R", "Nintendo - Game Boy MGB-001 S"),
        "gbc" to listOf("Nintendo - Game Boy Color", "Nintendo - Game Boy Color CGB-001 G", "Nintendo - Game Boy Color CGB-001 P", "Nintendo - Game Boy Color CGB-001 Q", "Nintendo - Game Boy Color CGB-001 Y"),
        "gba" to listOf("Nintendo - Game Boy Advance", "Nintendo - Game Boy Advance AGB-001 B", "Nintendo - Game Boy Advance AGB-001 G", "Nintendo - Game Boy Advance AGB-001 GO", "Nintendo - Game Boy Advance AGB-001 K", "Nintendo - Game Boy Advance AGB-001 O", "Nintendo - Game Boy Advance AGB-001 P", "Nintendo - Game Boy Advance AGB-001 R", "Nintendo - Game Boy Advance AGB-001 S", "Nintendo - Game Boy Advance AGB-001 Y", "Nintendo - Game Boy Advance AGS-001 B", "Nintendo - Game Boy Advance AGS-001 K", "Nintendo - Game Boy Advance AGS-001 NES", "Nintendo - Game Boy Advance AGS-001 S", "Nintendo - Game Boy Advance AGS-101 B", "Nintendo - Game Boy Advance AGS-101 GO"),
        "nes" to listOf("Nintendo - Nintendo Entertainment System", "Nintendo - Nintendo Entertainment System HVC-001", "Nintendo - Nintendo Entertainment System HVC-101", "Nintendo - Nintendo Entertainment System NES-101"),
        "famicom" to listOf("Nintendo - Nintendo Entertainment System", "Nintendo - Nintendo Entertainment System HVC-001", "Nintendo - Nintendo Entertainment System HVC-101", "Nintendo - Nintendo Entertainment System NES-101"),
        "fds" to listOf("Nintendo - Family Computer Disk System", "Nintendo - Family Computer Disk System AN500B", "Nintendo - Family Computer Disk System AN500R", "Nintendo - Family Computer Disk System AN505BK", "Nintendo - Family Computer Disk System AN505RD", "Nintendo - Family Computer Disk System HVC-101"),
        "snes" to listOf("Nintendo - Super Nintendo Entertainment System", "Nintendo - Super Nintendo Entertainment System SHVC-101", "Nintendo - Super Nintendo Entertainment System SNS-001", "Nintendo - Super Nintendo Entertainment System SNS-101", "Nintendo - Super Nintendo Entertainment System SNSP-001"),
        "sfam" to listOf("Nintendo - Super Nintendo Entertainment System", "Nintendo - Super Nintendo Entertainment System SHVC-101", "Nintendo - Super Nintendo Entertainment System SNS-001", "Nintendo - Super Nintendo Entertainment System SNS-101", "Nintendo - Super Nintendo Entertainment System SNSP-001"),
        "ngc" to listOf("Nintendo - GameCube", "Nintendo - GameCube DOL-001 G", "Nintendo - GameCube DOL-001 K", "Nintendo - GameCube DOL-001 O", "Nintendo - GameCube DOL-001 R", "Nintendo - GameCube DOL-001 S", "Nintendo - GameCube DOL-001 W", "Nintendo - GameCube DOT-001", "Nintendo - GameCube DOT-002", "Nintendo - GameCube SL-GC10"),
        "wii" to listOf("Nintendo - Wii", "Nintendo - Wii RVL-001 B", "Nintendo - Wii RVL-001 BT", "Nintendo - Wii RVL-001 K", "Nintendo - Wii RVL-001 KT", "Nintendo - Wii RVL-001 R", "Nintendo - Wii RVL-001 RT", "Nintendo - Wii RVL-001 WT", "Nintendo - Wii RVL-201"),
        "wiiu" to listOf("Nintendo - Wii U", "Nintendo - Wii U WUP-001 KT", "Nintendo - Wii U WUP-001 W", "Nintendo - Wii U WUP-001 WT"),
        "switch" to listOf("Nintendo - Switch", "Nintendo - Switch HAC-001", "Nintendo - Switch HAC-001 B", "Nintendo - Switch HAC-001 BK", "Nintendo - Switch HAC-001 BR", "Nintendo - Switch HAC-001 K", "Nintendo - Switch HAC-001 KB", "Nintendo - Switch HAC-001 KR", "Nintendo - Switch HAC-001 R", "Nintendo - Switch HAC-001 RB", "Nintendo - Switch HAC-001 RK", "Nintendo - Switch HAC-001 Y", "Nintendo - Switch HAC-007", "Nintendo - Switch HAC-007 BR", "Nintendo - Switch HAC-007 K", "Nintendo - Switch HAC-007 KT"),
        "switch-2" to listOf("Nintendo - Switch 2", "Nintendo - Switch 2 BEE-001 BR", "Nintendo - Switch 2 BEE-005"),
        "virtualboy" to listOf("Nintendo - Virtual Boy", "Nintendo - Virtual Boy T"),
        "pokemon-mini" to listOf("Nintendo - Pokemon Mini", "Nintendo - Pokemon Mini MIN-001 G", "Nintendo - Pokemon Mini MIN-001 Q"),
        "dc" to listOf("Sega - Dreamcast", "Sega - Dreamcast B", "Sega - Dreamcast H", "Sega - Dreamcast K"),
        "saturn" to listOf("Sega - Saturn", "Sega - Saturn HST-3200 (J)", "Sega - Saturn MK-80000 (U)", "Sega - Saturn MK-80000A (U)", "Sega - Saturn MMP-1000NV"),
        "genesis" to listOf("Sega - Mega Drive - Genesis", "Sega - Mega Drive - Genesis FB3680", "Sega - Mega Drive - Genesis HAA-2502 (J)", "Sega - Mega Drive - Genesis HAA-2510 (J)", "Sega - Mega Drive - Genesis HMJ-0300 (J)", "Sega - Mega Drive - Genesis MK-1461 (U)", "Sega - Mega Drive - Genesis MK-1601 (E)", "Sega - Mega Drive - Genesis MK-1631 (E)", "Sega - Mega Drive - Genesis MK-1631 (U)", "Sega - Mega Drive - Genesis MK-6100 (U)"),
        "segacd" to listOf("Sega - Mega-CD - Sega CD", "Sega - Mega-CD - Sega CD CSD-GM1 (J)", "Sega - Mega-CD - Sega CD RG-M1 (J)", "Sega - Mega-CD - Sega CD RG-M2 (J)"),
        "sega32" to listOf("Sega - 32X"),
        "sms" to listOf("Sega - Master System - Mark III", "Sega - Master System - Mark III MK-3006", "Sega - Master System - Mark III SG-1000M3 (J)"),
        "gamegear" to listOf("Sega - Game Gear", "Sega - Game Gear A", "Sega - Game Gear B", "Sega - Game Gear Q", "Sega - Game Gear R", "Sega - Game Gear S", "Sega - Game Gear W", "Sega - Game Gear Y"),
        "sg1000" to listOf("Sega - SG-1000", "Sega - SG-1000 II"),
        "arcade" to listOf("MAME"),
        "neogeoaes" to listOf("SNK - Neo Geo", "SNK - Neo Geo CDZ", "SNK - Neo Geo X"),
        "neo-geo-cd" to listOf("SNK - Neo Geo CD"),
        "neo-geo-pocket" to listOf("SNK - Neo Geo Pocket", "SNK - Neo Geo Pocket K", "SNK - Neo Geo Pocket S", "SNK - Neo Geo Pocket W"),
        "neo-geo-pocket-color" to listOf("SNK - Neo Geo Pocket Color"),
        "tg16" to listOf("NEC - PC Engine - TurboGrafx 16", "NEC - PC Engine - TurboGrafx 16 PI-TG2 (J)", "NEC - PC Engine - TurboGrafx 16 PI-TG3 (J)", "NEC - PC Engine - TurboGrafx 16 PI-TG7 (J)", "NEC - PC Engine - TurboGrafx 16 PI-TG9 (J)"),
        "turbografx-cd" to listOf("NEC - PC Engine CD - TurboGrafx-CD", "NEC - PC Engine CD - TurboGrafx-CD CDR-30 (J)", "NEC - PC Engine CD - TurboGrafx-CD PI-CD1 (J)", "NEC - PC Engine CD - TurboGrafx-CD PI-TG8 (J)"),
        "supergrafx" to listOf("NEC - PC Engine SuperGrafx"),
        "pc-fx" to listOf("NEC - PC-FX"),
        "atari2600" to listOf("Atari - 2600", "Atari - 2600 CX2600A", "Atari - 2600 CX2600A K", "Atari - 2600 CX2600JR", "Atari - 2600 CX2600JRA", "Atari - 2600 CX2600JRB", "Atari - 2600 CX2700"),
        "atari5200" to listOf("Atari - 5200"),
        "atari7800" to listOf("Atari - 7800"),
        "lynx" to listOf("Atari - Lynx", "Atari - Lynx PAG-0401"),
        "jaguar" to listOf("Atari - Jaguar"),
        "atari-st" to listOf("Atari - ST"),
        "wonderswan" to listOf("Bandai - WonderSwan"),
        "wonderswan-color" to listOf("Bandai - WonderSwan Color", "Bandai - WonderSwan Color SCT-001 R", "Bandai - WonderSwan Color WSC-001 B", "Bandai - WonderSwan Color WSC-001 K", "Bandai - WonderSwan Color WSC-001 O", "Bandai - WonderSwan Color WSC-001 S"),
        "xbox" to listOf("Microsoft - Xbox", "Microsoft - Xbox G", "Microsoft - Xbox O", "Microsoft - Xbox S", "Microsoft - Xbox W"),
        "xbox360" to listOf("Microsoft - Xbox 360", "Microsoft - Xbox 360 K", "Microsoft - Xbox 360 KT", "Microsoft - Xbox 360 WT"),
        "dos" to listOf("DOS"),
        "steam" to listOf("Valve - Steam Deck"),
        "scummvm" to listOf("ScummVM"),
        "msx" to listOf("Microsoft - MSX", "Microsoft - MSX CPC-50", "Microsoft - MSX CPC-50A", "Microsoft - MSX CPC-50B", "Microsoft - MSX CPC-50B P", "Microsoft - MSX CPC-51B", "Microsoft - MSX CPC-51R", "Microsoft - MSX CPC-51W"),
        "msx2" to listOf("Microsoft - MSX2", "Microsoft - MSX2 CPC-61B", "Microsoft - MSX2 CPC-61W", "Microsoft - MSX2 CPG-120"),
        "c64" to listOf("Commodore - 64"),
        "amiga" to listOf("Commodore - Amiga"),
        "zxs" to listOf("Sinclair - ZX Spectrum"),
        "acpc" to listOf("Amstrad - CPC"),
        "3do" to listOf("The 3DO Company - 3DO", "The 3DO Company - 3DO FZ-10", "The 3DO Company - 3DO GDO-101", "The 3DO Company - 3DO GDO-203P", "The 3DO Company - 3DO IMP-21J"),
        "colecovision" to listOf("Coleco - ColecoVision"),
        "intellivision" to listOf("Mattel - Intellivision"),
        "vectrex" to listOf("GCE - Vectrex"),
        "64dd" to listOf("Nintendo - Nintendo 64DD"),
        "acorn-archimedes" to listOf("Acorn - Archimedes"),
        "adventure-vision" to listOf("Entex - Adventure Vision"),
        "amiga-cd32" to listOf("Commodore - CD32"),
        "commodore-cdtv" to listOf("Commodore - CDTV"),
        "amstrad-gx4000" to listOf("Amstrad - GX4000"),
        "appleii" to listOf("Apple - II"),
        "apple-iigs" to listOf("Apple - IIGS"),
        "mac" to listOf("Apple - Macintosh"),
        "arcadia-2001" to listOf("Emerson - Arcadia 2001"),
        "arduboy" to listOf("Arduboy Inc - Arduboy"),
        "astrocade" to listOf("Bally - Astrocade"),
        "atari8bit" to listOf("Atari - 8-bit Family"),
        "atari-jaguar-cd" to listOf("Atari - Jaguar CD"),
        "bbcmicro" to listOf("Acorn - BBC Micro"),
        "c-plus-4" to listOf("Commodore - Plus-4"),
        "vic-20" to listOf("Commodore - VIC-20"),
        "cpet" to listOf("Commodore - PET"),
        "casio-pv-1000" to listOf("Casio - PV-1000"),
        "colecoadam" to listOf("Coleco - ColecoVision ADAM"),
        "creativision" to listOf("VTech - CreatiVision"),
        "doom" to listOf("DOOM"),
        "epoch-super-cassette-vision" to listOf("Epoch - Super Cassette Vision"),
        "epoch-game-pocket-computer" to listOf("Epoch - Game Pocket Computer"),
        "fairchild-channel-f" to listOf("Fairchild - Channel F"),
        "fm-7" to listOf("Fujitsu - FM-7"),
        "fm-towns" to listOf("Fujitsu - FM Towns"),
        "g-and-w" to listOf("Handheld Electronic Game"),
        "handheld-electronic-lcd" to listOf("Handheld Electronic Game"),
        "game-dot-com" to listOf("Tiger - Game.com"),
        "gamate" to listOf("Bit Corporation - Gamate"),
        "hartung" to listOf("Hartung - Game Master"),
        "j2me" to listOf("Mobile - J2ME"),
        "mega-duck-slash-cougar-boy" to listOf("Welback - Mega Duck"),
        "msx2plus" to listOf("Microsoft - MSX2", "Microsoft - MSX2 CPC-61B", "Microsoft - MSX2 CPC-61W", "Microsoft - MSX2 CPG-120"),
        "odyssey-2" to listOf("Magnavox - Odyssey2"),
        "videopac-g7400" to listOf("Philips - Videopac+"),
        "palm-os" to listOf("Mobile - Palm OS"),
        "pc-8800-series" to listOf("NEC - PC-8001 - PC-8801"),
        "pc-9800-series" to listOf("NEC - PC-98"),
        "philips-cd-i" to listOf("Philips - CD-i"),
        "rpg-maker" to listOf("RPG Maker"),
        "satellaview" to listOf("Nintendo - Satellaview"),
        "sufami-turbo" to listOf("Nintendo - Sufami Turbo", "Nintendo - Sufami Turbo SHVC-101", "Nintendo - Sufami Turbo SNS-001", "Nintendo - Sufami Turbo SNS-101"),
        "sharp-x68000" to listOf("Sharp - X68000", "Sharp - X68000 K", "Sharp - X68000 KT", "Sharp - X68000 ST"),
        "x1" to listOf("Sharp - X1"),
        "super-acan" to listOf("Funtech - Super Acan"),
        "supervision" to listOf("Watara - Supervision"),
        "thomson-mo5" to listOf("Thomson - MOTO", "Thomson - MOTO TO8", "Thomson - MOTO TO8D"),
        "ti-99" to listOf("Texas Instruments - TI-99-4A"),
        "tic-80" to listOf("TIC-80"),
        "tomy-tutor" to listOf("Tomy - Tutor"),
        "uzebox" to listOf("Uzebox"),
        "vc-4000" to listOf("Interton - VC 4000"),
        "vsmile" to listOf("VTech - V.Smile", "VTech - V.Smile P"),
        "wasm-4" to listOf("WASM-4"),
        "z-machine" to listOf("Infocom - Z-Machine"),
        "zx81" to listOf("Sinclair - ZX 81"),
        "ngage" to listOf("Nokia - N-Gage", "Nokia - N-Gage QD"),
        "symbian" to listOf("Mobile - Symbian"),
        "spectravideo" to listOf("Spectravideo - SVI-318 - SVI-328"),
        "xboxone" to listOf("Microsoft - Xbox One"),
        "quake" to listOf("Quake", "Quake II", "Quake III"),
        "lowresnx" to listOf("LowRes NX"),
        "lutro" to listOf("Lutro"),
        "chailove" to listOf("ChaiLove"),
        "vircon32" to listOf("Vircon32"),
        "laserdisc" to listOf("Dragons Lair"),
    )
}
