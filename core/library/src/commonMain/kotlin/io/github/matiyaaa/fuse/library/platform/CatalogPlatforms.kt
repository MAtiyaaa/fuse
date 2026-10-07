package io.github.matiyaaa.fuse.library.platform

import io.github.matiyaaa.fuse.model.BiosRequirement
import io.github.matiyaaa.fuse.model.FolderPolicy
import io.github.matiyaaa.fuse.model.LibraryLayout
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformFamily
import io.github.matiyaaa.fuse.model.PlatformFamily.ANDROID
import io.github.matiyaaa.fuse.model.PlatformFamily.ARCADE
import io.github.matiyaaa.fuse.model.PlatformFamily.ATARI
import io.github.matiyaaa.fuse.model.PlatformFamily.BANDAI
import io.github.matiyaaa.fuse.model.PlatformFamily.MICROSOFT
import io.github.matiyaaa.fuse.model.PlatformFamily.NEC
import io.github.matiyaaa.fuse.model.PlatformFamily.NINTENDO
import io.github.matiyaaa.fuse.model.PlatformFamily.OTHER
import io.github.matiyaaa.fuse.model.PlatformFamily.PC
import io.github.matiyaaa.fuse.model.PlatformFamily.SEGA
import io.github.matiyaaa.fuse.model.PlatformFamily.SNK
import io.github.matiyaaa.fuse.model.PlatformFamily.SONY
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.PlatformKind
import io.github.matiyaaa.fuse.model.PlatformKind.COMPUTER
import io.github.matiyaaa.fuse.model.PlatformKind.CONSOLE
import io.github.matiyaaa.fuse.model.PlatformKind.HANDHELD
import io.github.matiyaaa.fuse.model.PlatformKind.PC_GAMES

// Cover aspects (width / height) of common retail cases.
private const val DVD_CASE = 0.71f
private const val BLU_RAY_CASE = 0.8f
private const val JEWEL_CASE = 1f
private const val SNES_US_BOX = 1.37f
private const val DS_CASE = 0.9f
private const val UMD_CASE = 0.58f
private const val SWITCH_CASE = 0.62f
private const val STEAM_CAPSULE = 0.667f
private const val PORTRAIT = 0.72f

/**
 * Builds one catalog entry. [ext] and [aliases] are space-separated lists; the id is always an
 * alias too. Everything is lower-cased here so the table below can stay readable.
 */
@Suppress("LongParameterList")
private fun platform(
    id: String,
    name: String,
    short: String,
    kind: PlatformKind,
    family: PlatformFamily,
    maker: String?,
    year: Int?,
    ext: String,
    aliases: String,
    accent: Long,
    ra: Int? = null,
    policy: FolderPolicy = FolderPolicy.AUTO,
    layout: LibraryLayout = LibraryLayout.ICON,
    aspect: Float = PORTRAIT,
    bios: BiosRequirement? = null,
): Platform = Platform(
    id = PlatformId(id),
    name = name,
    shortName = short,
    kind = kind,
    family = family,
    manufacturer = maker,
    releaseYear = year,
    extensions = ext.split(' ').filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
    folderAliases = (listOf(id) + aliases.split(' ')).filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
    defaultFolderPolicy = policy,
    retroAchievementsConsoleId = ra,
    accent = accent,
    bios = bios,
    defaultLayout = layout,
    coverAspect = aspect,
)

private const val CART_ARCHIVES = "zip 7z"
private const val CD_IMAGES = "cue chd ccd iso m3u"
private const val SNES_EXT = "sfc smc fig swc bs st bsx bml dx2 gd3 gd7 mgd bin $CART_ARCHIVES"
private const val NES_EXT = "nes unf unif 3dsen $CART_ARCHIVES"
private const val N3DS_EXT = "3ds cci cia cxi 3dsx app axf elf zcci zcxi z3dsx 7z zip"
private const val SWITCH_EXT = "nsp xci nsz xcz nca nro nso"

/**
 * The curated platform table. Ids are RomM slugs (docs/research/romm-ra-scrapers.md); aliases add
 * RomM's alias table, ES-DE system folder names and common spellings. RetroAchievements ids come
 * from rcheevos' console list and are only set where certain. Accents are original, desaturated
 * hues, not brand colours.
 */
internal val catalogPlatforms: List<Platform> = listOf(
    // Sony
    platform(
        "psx", "PlayStation", "PS1", CONSOLE, SONY, "Sony", 1994,
        "bin cue chd iso img m3u pbp ecm mds mdf cbn ccd toc exe psexe znx 7z zip",
        "ps ps1 psone playstation playstation1 sony-playstation", 0xFF7D8BA8, ra = 12,
        aspect = JEWEL_CASE, bios = KnownBios.psx,
    ),
    platform(
        "ps2", "PlayStation 2", "PS2", CONSOLE, SONY, "Sony", 2000,
        "iso chd cso zso bin img mdf nrg m3u ciso gz isz ngr", "playstation2", 0xFF5B6FB5, ra = 21,
        aspect = DVD_CASE, bios = KnownBios.ps2,
    ),
    platform(
        "ps3", "PlayStation 3", "PS3", CONSOLE, SONY, "Sony", 2006,
        "iso ps3 ps3dir desktop pkg", "ps3-psn playstation3", 0xFF6E7F99, ra = 82,
        aspect = BLU_RAY_CASE, bios = KnownBios.ps3,
    ),
    platform(
        "ps4", "PlayStation 4", "PS4", CONSOLE, SONY, "Sony", 2013,
        "ps4 desktop", "playstation4", 0xFF4F7BB0, aspect = BLU_RAY_CASE,
    ),
    platform(
        "ps5", "PlayStation 5", "PS5", CONSOLE, SONY, "Sony", 2020,
        "ps5 desktop", "playstation5", 0xFFA9B4C8, aspect = BLU_RAY_CASE,
    ),
    platform(
        "psp", "PlayStation Portable", "PSP", HANDHELD, SONY, "Sony", 2004,
        "iso cso chd pbp elf prx zso 7z zip", "playstationportable pspminis", 0xFF8A8FA3, ra = 41,
        aspect = UMD_CASE,
    ),
    platform(
        "psvita", "PlayStation Vita", "Vita", HANDHELD, SONY, "Sony", 2011,
        "psvita vpk pkg zip", "vita psv playstationvita", 0xFF5A8FBF, aspect = 0.78f, bios = KnownBios.psvita,
    ),

    // Nintendo
    platform(
        "n64", "Nintendo 64", "N64", CONSOLE, NINTENDO, "Nintendo", 1996,
        "z64 n64 v64 ndd u1 d64 $CART_ARCHIVES", "nintendo64", 0xFF5E9C8F, ra = 2, aspect = SNES_US_BOX,
    ),
    platform(
        "nds", "Nintendo DS", "DS", HANDHELD, NINTENDO, "Nintendo", 2004,
        "nds $CART_ARCHIVES dsi ids", "ds nintendods", 0xFF9AA3AE, ra = 18, aspect = DS_CASE, bios = KnownBios.nds,
    ),
    platform(
        "nintendo-dsi", "Nintendo DSi", "DSi", HANDHELD, NINTENDO, "Nintendo", 2008,
        "nds dsi $CART_ARCHIVES", "dsi nintendodsi dsiware", 0xFF7FA6C9, ra = 78, aspect = DS_CASE,
        bios = KnownBios.dsi,
    ),
    platform(
        "3ds", "Nintendo 3DS", "3DS", HANDHELD, NINTENDO, "Nintendo", 2011,
        N3DS_EXT, "n3ds nintendo3ds", 0xFFC0676B, ra = 62, aspect = DS_CASE, bios = KnownBios.n3ds,
    ),
    platform(
        "new-nintendo-3ds", "New Nintendo 3DS", "New 3DS", HANDHELD, NINTENDO, "Nintendo", 2014,
        N3DS_EXT, "new3ds newnintendo3ds", 0xFFB07A8C, ra = 62, aspect = DS_CASE, bios = KnownBios.n3ds,
    ),
    platform(
        "gb", "Game Boy", "GB", HANDHELD, NINTENDO, "Nintendo", 1989,
        "gb dmg $CART_ARCHIVES bs cgb gbc gbx sgb sfc smc", "gameboy gb2players", 0xFF8FA35B, ra = 4, aspect = 0.9f,
    ),
    platform(
        "gbc", "Game Boy Color", "GBC", HANDHELD, NINTENDO, "Nintendo", 1998,
        "gbc cgb gb sgb $CART_ARCHIVES bs dmg gbx sfc smc", "gameboycolor sgb gbc2players sgb-msu1", 0xFFB26FA8, ra = 6, aspect = 0.9f,
    ),
    platform(
        "gba", "Game Boy Advance", "GBA", HANDHELD, NINTENDO, "Nintendo", 2001,
        "gba agb $CART_ARCHIVES cgb dmg gb gbc gbx sgb", "gameboyadvance gba2players", 0xFF7867B5, ra = 5, aspect = JEWEL_CASE, bios = KnownBios.gba,
    ),
    platform(
        "nes", "Nintendo Entertainment System", "NES", CONSOLE, NINTENDO, "Nintendo", 1985,
        NES_EXT, "nintendoentertainmentsystem", 0xFFB25E5E, ra = 7,
    ),
    platform(
        "famicom", "Family Computer", "FC", CONSOLE, NINTENDO, "Nintendo", 1983,
        "$NES_EXT fds 3dsen", "fc nintendofamicom", 0xFFB9785B, ra = 7,
    ),
    platform(
        "fds", "Famicom Disk System", "FDS", CONSOLE, NINTENDO, "Nintendo", 1986,
        "fds $CART_ARCHIVES nes unf unif", "famicomdisksystem", 0xFFC9A45C, ra = 81, bios = KnownBios.fds,
    ),
    platform(
        "snes", "Super Nintendo", "SNES", CONSOLE, NINTENDO, "Nintendo", 1991,
        SNES_EXT, "supernintendo snesna snes-msu1", 0xFF8C84B8, ra = 3, aspect = SNES_US_BOX,
    ),
    platform(
        "sfam", "Super Famicom", "SFC", CONSOLE, NINTENDO, "Nintendo", 1990,
        SNES_EXT, "sfc superfamicom", 0xFFA38FC4, ra = 3,
    ),
    platform(
        "ngc", "GameCube", "GC", CONSOLE, NINTENDO, "Nintendo", 2001,
        "iso gcm gcz rvz ciso dol elf tgc wia m3u wad wbfs 7z zip", "gc gamecube nintendogamecube", 0xFF6D63A8, ra = 16,
    ),
    platform(
        "wii", "Wii", "Wii", CONSOLE, NINTENDO, "Nintendo", 2006,
        "iso wbfs rvz gcz ciso wia wad dol elf m3u gcm tgc 7z zip", "wiiware nintendowii", 0xFF8FB3C7, ra = 19, aspect = DVD_CASE,
    ),
    // AUTO recognises extracted Wii U titles (code/content/meta) and still handles organisational subfolders.
    platform(
        "wiiu", "Wii U", "Wii U", CONSOLE, NINTENDO, "Nintendo", 2012,
        "wua wud wux rpx elf wuhb", "wii-u nintendowiiu", 0xFF4F9BB0, aspect = DVD_CASE,
    ),
    platform(
        "switch", "Nintendo Switch", "Switch", CONSOLE, NINTENDO, "Nintendo", 2017,
        SWITCH_EXT, "nintendoswitch nsw", 0xFFC45C5C, aspect = SWITCH_CASE, bios = KnownBios.switch,
    ),
    platform(
        "switch-2", "Nintendo Switch 2", "Switch 2", CONSOLE, NINTENDO, "Nintendo", 2025,
        "nsp xci", "switch2 nintendoswitch2", 0xFF5C7EC4, aspect = SWITCH_CASE,
    ),
    platform(
        "virtualboy", "Virtual Boy", "VB", HANDHELD, NINTENDO, "Nintendo", 1995,
        "vb vboy $CART_ARCHIVES", "virtual-boy vb", 0xFFA84848, ra = 28,
    ),
    platform(
        "pokemon-mini", "Pokemon mini", "Mini", HANDHELD, NINTENDO, "Nintendo", 2001,
        "min $CART_ARCHIVES", "pokemini pokemonmini", 0xFFC9B458, ra = 24,
    ),

    // Sega
    platform(
        "dc", "Dreamcast", "DC", CONSOLE, SEGA, "Sega", 1998,
        "cdi chd gdi cue iso m3u 7z zip", "dreamcast segadreamcast", 0xFFD08A4E, ra = 40, aspect = JEWEL_CASE,
        bios = KnownBios.dreamcast,
    ),
    platform(
        "saturn", "Saturn", "Saturn", CONSOLE, SEGA, "Sega", 1994,
        "$CD_IMAGES mds toc 7z zip", "segasaturn saturnjp", 0xFF6A6F80, ra = 39, bios = KnownBios.saturn,
    ),
    platform(
        "genesis", "Mega Drive / Genesis", "MD", CONSOLE, SEGA, "Sega", 1988,
        "md gen bin smd 68k sgd $CART_ARCHIVES 32x bms chd cue gg iso m3u mdx sg sms", "megadrive megadrivejp md segagenesis segamegadrive megadrive-msu msu-md",
        0xFF3F5E8C, ra = 1,
    ),
    platform(
        "segacd", "Mega-CD / Sega CD", "MCD", CONSOLE, SEGA, "Sega", 1991,
        "cue chd iso m3u 68k bms gen gg md mdx sg sgd smd sms 7z zip", "megacd megacdjp segamegacd", 0xFF5A6E9E, ra = 9, aspect = JEWEL_CASE,
        bios = KnownBios.segacd,
    ),
    platform(
        "sega32", "32X", "32X", CONSOLE, SEGA, "Sega", 1994,
        "32x bin $CART_ARCHIVES 68k chd cue gen iso m3u md smd sms", "sega32x 32x sega32xjp sega32xna", 0xFFB0584F, ra = 10,
    ),
    platform(
        "sms", "Master System", "SMS", CONSOLE, SEGA, "Sega", 1986,
        "sms bin $CART_ARCHIVES 68k bms chd col cue gen gg iso m3u md mdx rom sg sgd smd", "mastersystem mark3 segamastersystem", 0xFF4E78A8, ra = 11,
    ),
    platform(
        "gamegear", "Game Gear", "GG", HANDHELD, SEGA, "Sega", 1990,
        "gg bin $CART_ARCHIVES 68k bms chd col cue gen iso m3u md mdx rom sg sgd smd sms", "gg segagamegear", 0xFF3F7F7A, ra = 15,
    ),
    platform(
        "sg1000", "SG-1000", "SG", CONSOLE, SEGA, "Sega", 1983,
        "sg bin $CART_ARCHIVES 68k bms chd cue gen gg iso m3u md mdx ri rom sgd smd sms", "sg-1000", 0xFF8E6A5A, ra = 33,
    ),

    // Arcade and SNK
    platform(
        "arcade", "Arcade", "Arcade", PlatformKind.ARCADE, ARCADE, null, null,
        "zip 7z chd neo",
        "mame fbneo fba finalburn naomi naomi2 naomigd atomiswave hbmame cps cps1 cps2 cps3 consolearcade mame-advmame namco22 namco2x6 triforce",
        0xFFC06C84, ra = 27, aspect = 0.75f,
    ),
    platform(
        "neogeoaes", "Neo Geo", "Neo Geo", CONSOLE, SNK, "SNK", 1990,
        "neo $CART_ARCHIVES", "neogeo", 0xFFC9A04A, bios = KnownBios.neoGeo, ra = 27,
    ),
    platform(
        "neo-geo-cd", "Neo Geo CD", "NGCD", CONSOLE, SNK, "SNK", 1994,
        "cue chd iso m3u", "neogeocd neogeocdjp", 0xFFB38E4F, ra = 56, aspect = JEWEL_CASE,
    ),
    platform(
        "neo-geo-pocket", "Neo Geo Pocket", "NGP", HANDHELD, SNK, "SNK", 1998,
        "ngp $CART_ARCHIVES ngc ngpc npc", "ngp", 0xFF7A8A9C, ra = 14,
    ),
    platform(
        "neo-geo-pocket-color", "Neo Geo Pocket Color", "NGPC", HANDHELD, SNK, "SNK", 1999,
        "ngc ngp $CART_ARCHIVES ngpc npc", "ngpc", 0xFF5F9E7E, ra = 14,
    ),

    // NEC
    platform(
        "tg16", "PC Engine / TurboGrafx-16", "PCE", CONSOLE, NEC, "NEC", 1987,
        "pce sgx $CD_IMAGES $CART_ARCHIVES img rom toc", "pcengine turbografx16 turbografx tg-16", 0xFFC47B4F, ra = 8,
        bios = KnownBios.pceHuCard,
    ),
    platform(
        "turbografx-cd", "PC Engine CD / TurboGrafx-CD", "PCE-CD", CONSOLE, NEC, "NEC", 1988,
        "$CD_IMAGES img toc pce sgx 7z zip", "pcenginecd tgcd tg-cd turbografxcd", 0xFFA8704E, ra = 76, aspect = JEWEL_CASE,
        bios = KnownBios.pceCd,
    ),
    platform(
        "supergrafx", "PC Engine SuperGrafx", "SGX", CONSOLE, NEC, "NEC", 1989,
        "sgx pce $CD_IMAGES $CART_ARCHIVES rom", "sgfx", 0xFF9E6A7E, ra = 8,
    ),
    platform(
        "pc-fx", "PC-FX", "PC-FX", CONSOLE, NEC, "NEC", 1994,
        "cue chd ccd toc m3u 7z zip", "pcfx", 0xFF8B7FA3, aspect = JEWEL_CASE, ra = 49,
    ),

    // Atari
    platform(
        "atari2600", "Atari 2600", "2600", CONSOLE, ATARI, "Atari", 1977,
        "a26 bin $CART_ARCHIVES", "2600", 0xFFA0674B, ra = 25,
    ),
    platform(
        "atari5200", "Atari 5200", "5200", CONSOLE, ATARI, "Atari", 1982,
        "a52 bin car $CART_ARCHIVES atr atx cas cdm com rom xex xfd", "5200", 0xFF6F6A64, ra = 50, bios = KnownBios.atari5200,
    ),
    platform(
        "atari7800", "Atari 7800", "7800", CONSOLE, ATARI, "Atari", 1986,
        "a78 bin $CART_ARCHIVES", "7800", 0xFF8D6E63, ra = 51, bios = KnownBios.atari7800,
    ),
    platform(
        "lynx", "Atari Lynx", "Lynx", HANDHELD, ATARI, "Atari", 1989,
        "lnx lyx o $CART_ARCHIVES", "atarilynx", 0xFFB88A4A, ra = 13, bios = KnownBios.lynx,
    ),
    platform(
        "jaguar", "Atari Jaguar", "Jaguar", CONSOLE, ATARI, "Atari", 1993,
        "j64 jag rom abs cof bin prg $CART_ARCHIVES bigpimg cdi cue", "atarijaguar", 0xFFB84E4E, ra = 17,
    ),
    platform(
        "atari-st", "Atari ST", "ST", COMPUTER, ATARI, "Atari", 1985,
        "st msa stx dim ipf $CART_ARCHIVES m3u", "atarist", 0xFF7E8C8A,
    ),

    // Bandai
    platform(
        "wonderswan", "WonderSwan", "WS", HANDHELD, BANDAI, "Bandai", 1999,
        "ws $CART_ARCHIVES pc2", "wswan", 0xFF6D8C9E, ra = 53,
    ),
    platform(
        "wonderswan-color", "WonderSwan Color", "WSC", HANDHELD, BANDAI, "Bandai", 2000,
        "wsc ws $CART_ARCHIVES", "wswanc wonderswancolor", 0xFF8C6DA8, ra = 53,
    ),

    // Microsoft
    platform(
        "xbox", "Xbox", "Xbox", CONSOLE, MICROSOFT, "Microsoft", 2001,
        "iso xiso", "originalxbox", 0xFF6B9A55, aspect = DVD_CASE, bios = KnownBios.xbox,
    ),
    // Extracted and XBLA titles are folders (or extensionless files inside folders).
    platform(
        "xbox360", "Xbox 360", "X360", CONSOLE, MICROSOFT, "Microsoft", 2005,
        "iso xex zar", "x360 xbox-360", 0xFF7FAA5C, policy = FolderPolicy.FOLDER_AS_GAME, aspect = DVD_CASE,
    ),

    // PC
    platform(
        "win", "Windows", "PC", PC_GAMES, PC, "Microsoft", null,
        "desktop steam gog epic amazon pcgame exe lnk bat", "windows pc pcwindows", 0xFF5A86B5,
        policy = FolderPolicy.FOLDER_AS_GAME, layout = LibraryLayout.CAPSULE, aspect = STEAM_CAPSULE,
    ),
    platform(
        "dos", "MS-DOS", "DOS", COMPUTER, PC, "Microsoft", 1981,
        "exe com bat conf dosz iso cue $CART_ARCHIVES img", "pcdos msdos ms-dos", 0xFF7A8A6A,
        policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "steam", "Steam", "Steam", PC_GAMES, PC, "Valve", null,
        "steam desktop", "", 0xFF4C6A8C, layout = LibraryLayout.CAPSULE, aspect = STEAM_CAPSULE,
    ),
    platform(
        "scummvm", "ScummVM", "ScummVM", PC_GAMES, PC, null, null,
        "scummvm svm", "", 0xFF6FA36F, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    // PICO-8 carts are .p8 text or .p8.png pictures (ES-DE reads both).
    platform(
        "pico8", "PICO-8", "PICO-8", CONSOLE, OTHER, "Lexaloffle", 2015,
        "p8 png", "pico-8 pico", 0xFFE0475B, aspect = JEWEL_CASE,
    ),
    // Flash games: .swf files, or Swiff's .swiffid files for the games in its own library.
    platform(
        "flash", "Flash", "Flash", PC_GAMES, OTHER, "Adobe", 1996,
        "swf swiffid ruf", "adobeflash flashgames swf", 0xFFD8402F, aspect = JEWEL_CASE,
    ),
    platform(
        "android", "Android", "Android", PlatformKind.ANDROID, ANDROID, "Google", null,
        "app", "androidgames androidapps", 0xFF79A86B, aspect = JEWEL_CASE,
    ),

    // Home computers
    platform(
        "msx", "MSX", "MSX", COMPUTER, OTHER, "ASCII", 1983,
        "rom mx1 mx2 dsk cas $CART_ARCHIVES col di1 di2 dmk fd1 fd2 m3u ri sc sg xsa", "msx1", 0xFF6B7FA3, ra = 29,
    ),
    platform(
        "msx2", "MSX2", "MSX2", COMPUTER, OTHER, "ASCII", 1985,
        "rom mx2 dsk cas $CART_ARCHIVES col di1 di2 dmk fd1 fd2 m3u mx1 ri sc sg xsa", "", 0xFF7B6FA3, ra = 29,
    ),
    platform(
        "c64", "Commodore 64", "C64", COMPUTER, OTHER, "Commodore", 1982,
        "d64 d71 d81 g64 t64 tap prg crt p00 $CART_ARCHIVES d2m d4m d6z d7z d80 d82 d8z g41 g4z g6z gz lnx m3u nbz nib vfl vsf x64 x6z", "commodore64", 0xFF8A7F6A,
    ),
    platform(
        "amiga", "Amiga", "Amiga", COMPUTER, OTHER, "Commodore", 1985,
        "adf adz dms ipf hdf hdz lha lzx uae m3u $CART_ARCHIVES ccd chd cue fdi iso mds nrg rp9", "amiga500 amiga600 amiga1200 commodoreamiga amiga4000",
        0xFFC98A5A,
    ),
    platform(
        "zxs", "ZX Spectrum", "ZX", COMPUTER, OTHER, "Sinclair", 1982,
        "tzx tap z80 sna dsk scl trd $CART_ARCHIVES gz img mgt rzx szx udi", "zxspectrum spectrum", 0xFF6E6E80, ra = 59,
    ),
    platform(
        "acpc", "Amstrad CPC", "CPC", COMPUTER, OTHER, "Amstrad", 1984,
        "dsk sna cdt m3u $CART_ARCHIVES cpr kcr tap", "amstradcpc", 0xFF5E8A7A, ra = 37,
    ),

    // Other consoles
    platform(
        "3do", "3DO", "3DO", CONSOLE, OTHER, "The 3DO Company", 1993,
        "iso chd cue m3u bin 7z zip", "panasonic3do", 0xFFA85E6E, ra = 43, aspect = JEWEL_CASE, bios = KnownBios.threeDo,
    ),
    platform(
        "colecovision", "ColecoVision", "CV", CONSOLE, OTHER, "Coleco", 1982,
        "col rom bin $CART_ARCHIVES cas cv dsk m3u mx1 mx2 myv ri sc sg", "coleco", 0xFF5E5E6E, ra = 44, bios = KnownBios.colecovision,
    ),
    platform(
        "intellivision", "Intellivision", "INTV", CONSOLE, OTHER, "Mattel", 1979,
        "int bin rom $CART_ARCHIVES", "intv", 0xFF8E7A5A, ra = 45, bios = KnownBios.intellivision,
    ),
    platform(
        "vectrex", "Vectrex", "VEC", CONSOLE, OTHER, "GCE", 1982,
        "vec gam bin $CART_ARCHIVES vc", "", 0xFF7A7F8C, ra = 46,
    ),

    // Everything else ES-DE knows, under RomM's slugs (docs.romm.app, Supported platforms) with
    // ES-DE's folder names and extensions (es_systems.xml), so any EmuDeck, RetroDeck, Batocera or
    // RomM library is read as it is.
    platform(
        "64dd", "Nintendo 64DD", "64DD", CONSOLE, NINTENDO, "Nintendo", 1999,
        "bin d64 n64 ndd u1 v64 z64 7z zip", "n64dd", 0xFF5E8C9C,
    ),
    platform(
        "acorn-archimedes", "Acorn Archimedes", "Archie", COMPUTER, OTHER, "Acorn", 1987,
        "1dd 360 adf adl adm ads apd bbc chd cqi cqm d77 d88 dfi dsd dsk hfe ima imd img ipf jfd mfi mfm msa ssd st td0 ufi 7z zip", "archimedes", 0xFF8C7A5E,
    ),
    platform(
        "acorn-electron", "Acorn Electron", "Electron", COMPUTER, OTHER, "Acorn", 1983,
        "1dd adf adl adm ads bbc bin cqi cqm csw d77 d88 dfi dsd dsk hfe imd img mfi mfm rom ssd td0 uef 7z zip", "electron", 0xFF7E7068,
    ),
    platform(
        "adventure-vision", "Adventure Vision", "AdVision", CONSOLE, OTHER, "Entex", 1982,
        "bin 7z zip", "advision", 0xFF9C5E5E,
    ),
    platform(
        "amiga-cd32", "Amiga CD32", "CD32", CONSOLE, OTHER, "Commodore", 1993,
        "adf adz ccd chd cue dms fdi hdf hdz ipf iso lha m3u mds nrg rp9 uae 7z zip", "amigacd32 cd32", 0xFFB0805A, aspect = JEWEL_CASE,
    ),
    platform(
        "commodore-cdtv", "Commodore CDTV", "CDTV", CONSOLE, OTHER, "Commodore", 1991,
        "adf adz ccd chd cue dms fdi hdf hdz ipf iso lha m3u mds nrg rp9 uae 7z zip", "cdtv amigacdtv", 0xFF8A7266, aspect = JEWEL_CASE,
    ),
    platform(
        "amstrad-gx4000", "Amstrad GX4000", "GX4000", CONSOLE, OTHER, "Amstrad", 1990,
        "bin cdt cpr dsk kcr m3u sna tap tar voc 7z zip", "gx4000", 0xFF5E8A8A,
    ),
    platform(
        "appleii", "Apple II", "Apple II", COMPUTER, OTHER, "Apple", 1977,
        "do dsk nib po 7z zip", "apple2", 0xFF7FA36E, ra = 38,
    ),
    platform(
        "apple-iigs", "Apple IIGS", "IIGS", COMPUTER, OTHER, "Apple", 1986,
        "2mg 7z zip", "apple2gs", 0xFF8E9A6E,
    ),
    platform(
        "mac", "Macintosh", "Mac", COMPUTER, OTHER, "Apple", 1984,
        "dsk game img", "macintosh", 0xFF9A9AA6,
    ),
    platform(
        "arcadia-2001", "Arcadia 2001", "Arcadia", CONSOLE, OTHER, "Emerson", 1982,
        "bin tvc 7z zip", "arcadia", 0xFF6E7FA6, ra = 73,
    ),
    platform(
        "arduboy", "Arduboy", "Arduboy", HANDHELD, OTHER, "Arduboy", 2016,
        "arduboy hex 7z zip", "", 0xFF5E9A9A, ra = 71, aspect = JEWEL_CASE,
    ),
    platform(
        "astrocade", "Bally Astrocade", "Astrocade", CONSOLE, OTHER, "Bally", 1978,
        "7z zip", "astrocde", 0xFFA6885E,
    ),
    platform(
        "atari8bit", "Atari 8-bit", "Atari 800", COMPUTER, ATARI, "Atari", 1979,
        "a52 atr atx bin car cas cdm com rom xex xfd 7z zip", "atari800 atarixe xegs atari-xegs", 0xFF9A7A5E,
    ),
    platform(
        "atari-jaguar-cd", "Atari Jaguar CD", "Jaguar CD", CONSOLE, ATARI, "Atari", 1995,
        "abs bigpimg bin cdi cof cue j64 jag prg rom", "atarijaguarcd jaguarcd", 0xFF8A4E4E, ra = 77, aspect = JEWEL_CASE,
    ),
    platform(
        "bbcmicro", "BBC Micro", "BBC", COMPUTER, OTHER, "Acorn", 1981,
        "dsd img ssd 7z zip", "bbc", 0xFFA65E5E,
    ),
    platform(
        "c-plus-4", "Commodore Plus/4", "Plus/4", COMPUTER, OTHER, "Commodore", 1984,
        "bin cmd crt d2m d4m d64 d6z d71 d7z d80 d81 d82 d8z g41 g4z g64 g6z gz lnx m3u nbz nib p00 prg t64 tap vfl vsf x64 x6z 7z zip", "plus4 cplus4", 0xFF7A7068,
    ),
    platform(
        "vic-20", "Commodore VIC-20", "VIC-20", COMPUTER, OTHER, "Commodore", 1980,
        "a0 b0 bin cmd crt d2m d4m d64 d6z d71 d7z d80 d81 d82 d8z g41 g4z g64 g6z gz lnx m3u nbz nib p00 prg rom t64 tap vfl vsf x64 x6z 7z zip", "vic20 c20", 0xFF8C8068, ra = 34,
    ),
    platform(
        "cpet", "Commodore PET", "PET", COMPUTER, OTHER, "Commodore", 1977,
        "prg d64 t64 tap 7z zip", "pet", 0xFF6E8070,
    ),
    platform(
        "casio-pv-1000", "Casio PV-1000", "PV-1000", CONSOLE, OTHER, "Casio", 1983,
        "bin 7z zip", "pv1000", 0xFF5E6E9A,
    ),
    platform(
        "colecoadam", "Coleco Adam", "Adam", COMPUTER, OTHER, "Coleco", 1982,
        "1dd bin col cqi cqm d77 d88 ddp dfi dsk hfe imd mfi mfm rom td0 7z zip", "adam", 0xFF6E6E80,
    ),
    platform(
        "creativision", "CreatiVision", "CreatiVision", CONSOLE, OTHER, "VTech", 1981,
        "bin rom 7z zip", "crvision", 0xFF7A6EA6,
    ),
    platform(
        "doom", "Doom", "Doom", PC_GAMES, PC, "id Software", 1993,
        "ipk3 iwad pk3 pk4 pwad wad idtech", "gzdoom prboom", 0xFFB0503E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "dragon-32-slash-64", "Dragon 32/64", "Dragon", COMPUTER, OTHER, "Dragon Data", 1982,
        "cas ccc dsk rom 7z zip", "dragon32 dragon tanodragon", 0xFF9A5E6E,
    ),
    platform(
        "trs-80-color-computer", "TRS-80 Color Computer", "CoCo", COMPUTER, OTHER, "Tandy", 1980,
        "cas ccc dsk rom", "coco", 0xFF6E8A6E,
    ),
    platform(
        "trs-80", "TRS-80", "TRS-80", COMPUTER, OTHER, "Tandy", 1977,
        "cmd dsk 7z zip", "trs80", 0xFF7A8070,
    ),
    platform(
        "epoch-super-cassette-vision", "Super Cassette Vision", "SCV", CONSOLE, OTHER, "Epoch", 1984,
        "bin 7z zip", "scv", 0xFFA65E7A, ra = 55,
    ),
    platform(
        "epoch-game-pocket-computer", "Game Pocket Computer", "Game Pocket", HANDHELD, OTHER, "Epoch", 1984,
        "bin 7z zip", "gamepock", 0xFF8A6E9A,
    ),
    platform(
        "fairchild-channel-f", "Channel F", "Channel F", CONSOLE, OTHER, "Fairchild", 1976,
        "bin chf 7z zip", "channelf", 0xFF9A805E, ra = 57,
    ),
    platform(
        "fm-7", "FM-7", "FM-7", COMPUTER, OTHER, "Fujitsu", 1982,
        "1dd cqi cqm d77 d88 dfi dsk hfe imd mfi mfm t77 td0 7z zip", "fm7", 0xFF5E7A9A,
    ),
    platform(
        "fm-towns", "FM Towns", "FM Towns", COMPUTER, OTHER, "Fujitsu", 1989,
        "cdr chd cue gdi iso", "fmtowns", 0xFF6E8AA6, ra = 58, aspect = JEWEL_CASE,
    ),
    platform(
        "g-and-w", "Game & Watch", "G&W", HANDHELD, NINTENDO, "Nintendo", 1980,
        "mgw 7z zip", "gameandwatch gw", 0xFFA6935E, ra = 60,
    ),
    platform(
        "handheld-electronic-lcd", "LCD Handheld Games", "LCD", HANDHELD, OTHER, null, null,
        "mgw 7z zip", "lcdgames", 0xFF7A8A7A,
    ),
    platform(
        "game-dot-com", "Game.com", "Game.com", HANDHELD, OTHER, "Tiger", 1997,
        "tgc 7z zip", "gamecom", 0xFF6E7A6E,
    ),
    platform(
        "gamate", "Gamate", "Gamate", HANDHELD, OTHER, "Bit Corporation", 1990,
        "bin 7z zip", "", 0xFF7A7A8C,
    ),
    platform(
        "hartung", "Game Master", "Game Master", HANDHELD, OTHER, "Hartung", 1990,
        "bin 7z zip", "gmaster", 0xFF8C7A7A,
    ),
    platform(
        "j2me", "J2ME", "J2ME", HANDHELD, OTHER, "Sun", 2002,
        "jar 7z zip", "java", 0xFFC07A4E,
    ),
    platform(
        "mega-duck-slash-cougar-boy", "Mega Duck", "Mega Duck", HANDHELD, OTHER, "Creatronic", 1993,
        "bin 7z zip", "megaduck", 0xFF9AA65E, ra = 69,
    ),
    platform(
        "model2", "Sega Model 2", "Model 2", PlatformKind.ARCADE, SEGA, "Sega", 1993,
        "7z zip", "", 0xFF5E7AB0, aspect = 0.75f,
    ),
    platform(
        "model3", "Sega Model 3", "Model 3", PlatformKind.ARCADE, SEGA, "Sega", 1996,
        "7z zip", "", 0xFF6E6EB0, aspect = 0.75f,
    ),
    platform(
        "stv", "Sega ST-V", "ST-V", PlatformKind.ARCADE, SEGA, "Sega", 1994,
        "7z zip", "segastv", 0xFF7A8AB0, aspect = 0.75f,
    ),
    platform(
        "msx-turbo", "MSX Turbo R", "Turbo R", COMPUTER, OTHER, "ASCII", 1990,
        "cas col di1 di2 dmk dsk fd1 fd2 m3u mx1 mx2 ri rom sc sg xsa 7z zip", "msxturbor", 0xFF7E8AB0,
    ),
    platform(
        "msx2plus", "MSX2+", "MSX2+", COMPUTER, OTHER, "ASCII", 1988,
        "cas col di1 di2 dmk dsk fd1 fd2 m3u mx1 mx2 ri rom sc sg xsa 7z zip", "msx2+", 0xFF6E8AA0,
    ),
    platform(
        "odyssey-2", "Odyssey 2", "Odyssey 2", CONSOLE, OTHER, "Magnavox", 1978,
        "bin 7z zip", "odyssey2", 0xFF8A6E5E, ra = 23,
    ),
    platform(
        "videopac-g7400", "Videopac+", "Videopac+", CONSOLE, OTHER, "Philips", 1983,
        "bin 7z zip", "videopac videopacplus", 0xFF6E7A8A,
    ),
    platform(
        "oric", "Oric", "Oric", COMPUTER, OTHER, "Tangerine", 1983,
        "dsk ort tap", "oricatmos", 0xFFA66E6E, ra = 32,
    ),
    platform(
        "palm-os", "Palm OS", "Palm", HANDHELD, OTHER, "Palm", 1996,
        "img pqa prc 7z zip", "palm", 0xFF6E9A8A,
    ),
    platform(
        "pc-8800-series", "PC-8800 Series", "PC-88", COMPUTER, NEC, "NEC", 1981,
        "88d cmt d88 m3u t88 u88", "pc88", 0xFF6E7AA6, ra = 47,
    ),
    platform(
        "pc-9800-series", "PC-9800 Series", "PC-98", COMPUTER, NEC, "NEC", 1982,
        "2hd 88d 98d d88 d98 cmd dup fdd fdi hdd hdi hdm hdn m3u nhd tfd thd xdf 7z zip", "pc98", 0xFF7A6EA6, ra = 48,
    ),
    platform(
        "philips-cd-i", "Philips CD-i", "CD-i", CONSOLE, OTHER, "Philips", 1991,
        "chd cue iso", "cdi cdimono1", 0xFF6E8A9A, ra = 42, aspect = JEWEL_CASE,
    ),
    platform(
        "pinball", "Pinball", "Pinball", PC_GAMES, PC, null, null,
        "vpt vpx vpinball fpt 7z zip", "vpinball fpinball", 0xFF9A6EA6, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "rpg-maker", "RPG Maker", "RPG Maker", PC_GAMES, PC, null, null,
        "easyrpg zip", "easyrpg rpgmaker", 0xFF6EA68A, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "sam-coupe", "SAM Coupé", "SAM", COMPUTER, OTHER, "MGT", 1989,
        "dsk mgt sad sbt 7z zip", "samcoupe", 0xFF8A8A6E,
    ),
    platform(
        "satellaview", "Satellaview", "BS-X", CONSOLE, NINTENDO, "Nintendo", 1995,
        "bml bs fig sfc smc swc st 7z zip", "bsx", 0xFF8A7AA6,
    ),
    platform(
        "sufami-turbo", "Sufami Turbo", "Sufami", CONSOLE, BANDAI, "Bandai", 1996,
        "bml bs fig sfc smc st 7z zip", "sufami", 0xFFA67A8A,
    ),
    platform(
        "sharp-x68000", "Sharp X68000", "X68000", COMPUTER, OTHER, "Sharp", 1987,
        "2hd 88d cmd d88 dim dup hdf hdm img m3u xdf 7z zip", "x68000 x68k", 0xFF9A6E6E, ra = 52,
    ),
    platform(
        "x1", "Sharp X1", "X1", COMPUTER, OTHER, "Sharp", 1982,
        "2d 2hd 88d cmd d88 dup dx1 hdm tap tfd xdf 7z zip", "sharpx1", 0xFF8A6E6E, ra = 64,
    ),
    platform(
        "super-acan", "Super A'Can", "A'Can", CONSOLE, OTHER, "Funtech", 1995,
        "bin 7z zip", "supracan", 0xFF7A9A6E,
    ),
    platform(
        "supervision", "Supervision", "Supervision", HANDHELD, OTHER, "Watara", 1992,
        "bin sv 7z zip", "", 0xFF7A8A6E, ra = 63,
    ),
    platform(
        "thomson-mo5", "Thomson MO/TO", "Thomson", COMPUTER, OTHER, "Thomson", 1984,
        "fd k7 m5 m7 rom sap 7z zip", "thomson moto to8", 0xFF6E7A9A,
    ),
    platform(
        "ti-99", "TI-99/4A", "TI-99", COMPUTER, OTHER, "Texas Instruments", 1981,
        "rpk 7z zip", "ti99", 0xFF9A7A6E,
    ),
    platform(
        "tic-80", "TIC-80", "TIC-80", CONSOLE, OTHER, "Nesbox", 2017,
        "png tic", "tic80", 0xFF5EA68A, ra = 65, aspect = JEWEL_CASE,
    ),
    platform(
        "tomy-tutor", "Tomy Tutor", "Tutor", COMPUTER, OTHER, "Tomy", 1983,
        "bin cas 7z zip", "tutor", 0xFF8A8A9A,
    ),
    platform(
        "uzebox", "Uzebox", "Uzebox", CONSOLE, OTHER, null, 2008,
        "uze 7z zip", "", 0xFF6E9A6E, ra = 80,
    ),
    platform(
        "vc-4000", "Interton VC 4000", "VC 4000", CONSOLE, OTHER, "Interton", 1978,
        "bin 7z zip", "vc4000", 0xFF8A7A6E, ra = 74,
    ),
    platform(
        "vsmile", "V.Smile", "V.Smile", CONSOLE, OTHER, "VTech", 2004,
        "bin 7z zip", "", 0xFFA6A65E,
    ),
    platform(
        "wasm-4", "WASM-4", "WASM-4", CONSOLE, OTHER, null, 2021,
        "wasm 7z zip", "wasm4", 0xFF7A5EA6, ra = 72, aspect = JEWEL_CASE,
    ),
    platform(
        "win3x", "Windows 3.x", "Win 3.x", PC_GAMES, PC, "Microsoft", 1990,
        "bat dosz 7z zip", "windows3x win31", 0xFF5E8AA6, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "win9x", "Windows 9x", "Win 9x", PC_GAMES, PC, "Microsoft", 1995,
        "bat dosz 7z zip", "windows9x win95 win98", 0xFF5E7AA6, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "z-machine", "Z-machine", "Z-machine", PC_GAMES, PC, "Infocom", 1979,
        "z1 z2 z3 z4 z5 z6 z7 z8 zlb zblorb", "zmachine infocom", 0xFF8A8A8A, aspect = BLU_RAY_CASE,
    ),
    platform(
        "zx81", "ZX81", "ZX81", COMPUTER, OTHER, "Sinclair", 1981,
        "p tzx 7z zip", "", 0xFF6E6E6E, ra = 31,
    ),
    platform(
        "zx-spectrum-next", "ZX Spectrum Next", "Next", COMPUTER, OTHER, "SpecNext", 2020,
        "nex sna 7z zip", "zxnext specnext", 0xFF7A5E8A,
    ),
    platform(
        "ngage", "N-Gage", "N-Gage", HANDHELD, OTHER, "Nokia", 2003,
        "ngage zip", "", 0xFF7A8A5E, ra = 61,
    ),
    platform(
        "symbian", "Symbian", "Symbian", HANDHELD, OTHER, "Nokia", 2001,
        "sis sisx symbian", "", 0xFF5E8A7A,
    ),
    platform(
        "multivision", "Othello Multivision", "Multivision", CONSOLE, OTHER, "Tsukuda", 1983,
        "bin gg rom sg sms 7z zip", "", 0xFF6E8A6E,
    ),
    platform(
        "spectravideo", "Spectravideo", "SVI", COMPUTER, OTHER, "Spectravideo", 1983,
        "cas col dsk m3u mx1 mx2 ri rom sc sg 7z zip", "svi", 0xFF8A6E7A,
    ),
    platform(
        "xboxone", "Xbox One", "XB1", CONSOLE, MICROSOFT, "Microsoft", 2013,
        "7z zip", "", 0xFF5E8A5E, aspect = BLU_RAY_CASE,
    ),
    platform(
        "openbor", "OpenBOR", "OpenBOR", PC_GAMES, PC, null, null,
        "openbor", "", 0xFFA65E5E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "mugen", "M.U.G.E.N", "MUGEN", PC_GAMES, PC, "Elecbyte", 1999,
        "mugen 7z zip", "", 0xFFA6705E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "quake", "Quake", "Quake", PC_GAMES, PC, "id Software", 1996,
        "pak pk3 idtech", "", 0xFF8A6E4E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = BLU_RAY_CASE,
    ),
    platform(
        "lowresnx", "LowRes NX", "LowRes NX", CONSOLE, OTHER, null, 2017,
        "nx", "", 0xFF5EA6A6, aspect = JEWEL_CASE,
    ),
    platform(
        "lutro", "Lutro", "Lutro", PC_GAMES, PC, null, null,
        "lua lutro 7z zip", "", 0xFF8AA65E, aspect = JEWEL_CASE,
    ),
    platform(
        "chailove", "ChaiLove", "ChaiLove", PC_GAMES, PC, null, null,
        "chai chailove 7z zip", "", 0xFFA68A5E, aspect = JEWEL_CASE,
    ),
    platform(
        "vircon32", "Vircon32", "Vircon32", CONSOLE, OTHER, null, 2021,
        "v32 7z zip", "", 0xFF5E6EA6, aspect = JEWEL_CASE,
    ),
    platform(
        "solarus", "Solarus", "Solarus", PC_GAMES, PC, null, null,
        "solarus 7z zip", "", 0xFF6EA65E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = JEWEL_CASE,
    ),
    platform(
        "laserdisc", "LaserDisc", "LaserDisc", PlatformKind.ARCADE, ARCADE, null, null,
        "daphne dirksimple mmi singe 7z zip", "daphne", 0xFF9A9A6E, policy = FolderPolicy.FOLDER_AS_GAME, aspect = 0.75f,
    ),
)
