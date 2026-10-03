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
private const val SNES_EXT = "sfc smc fig swc bs st bsx $CART_ARCHIVES"
private const val NES_EXT = "nes unf unif $CART_ARCHIVES"
private const val N3DS_EXT = "3ds cci cia cxi 3dsx app axf elf zcci zcxi z3dsx"
private const val SWITCH_EXT = "nsp xci nca nro nso"

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
        "bin cue chd iso img m3u pbp ecm mds mdf cbn ccd toc exe psexe",
        "ps ps1 psone playstation playstation1 sony-playstation", 0xFF7D8BA8, ra = 12,
        aspect = JEWEL_CASE, bios = KnownBios.psx,
    ),
    platform(
        "ps2", "PlayStation 2", "PS2", CONSOLE, SONY, "Sony", 2000,
        "iso chd cso zso bin img mdf nrg m3u", "playstation2", 0xFF5B6FB5, ra = 21,
        aspect = DVD_CASE, bios = KnownBios.ps2,
    ),
    platform(
        "ps3", "PlayStation 3", "PS3", CONSOLE, SONY, "Sony", 2006,
        "iso ps3 ps3dir desktop", "ps3-psn playstation3", 0xFF6E7F99, ra = 82,
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
        "iso cso chd pbp elf prx zso", "playstationportable pspminis", 0xFF8A8FA3, ra = 41,
        aspect = UMD_CASE,
    ),
    platform(
        "psvita", "PlayStation Vita", "Vita", HANDHELD, SONY, "Sony", 2011,
        "psvita vpk", "vita psv playstationvita", 0xFF5A8FBF, aspect = 0.78f, bios = KnownBios.psvita,
    ),

    // Nintendo
    platform(
        "n64", "Nintendo 64", "N64", CONSOLE, NINTENDO, "Nintendo", 1996,
        "z64 n64 v64 ndd u1 d64 $CART_ARCHIVES", "nintendo64", 0xFF5E9C8F, ra = 2, aspect = SNES_US_BOX,
    ),
    platform(
        "nds", "Nintendo DS", "DS", HANDHELD, NINTENDO, "Nintendo", 2004,
        "nds $CART_ARCHIVES", "ds nintendods", 0xFF9AA3AE, ra = 18, aspect = DS_CASE, bios = KnownBios.nds,
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
        "gb dmg $CART_ARCHIVES", "gameboy", 0xFF8FA35B, ra = 4, aspect = 0.9f,
    ),
    platform(
        "gbc", "Game Boy Color", "GBC", HANDHELD, NINTENDO, "Nintendo", 1998,
        "gbc cgb gb sgb $CART_ARCHIVES", "gameboycolor sgb", 0xFFB26FA8, ra = 6, aspect = 0.9f,
    ),
    platform(
        "gba", "Game Boy Advance", "GBA", HANDHELD, NINTENDO, "Nintendo", 2001,
        "gba agb $CART_ARCHIVES", "gameboyadvance", 0xFF7867B5, ra = 5, aspect = JEWEL_CASE, bios = KnownBios.gba,
    ),
    platform(
        "nes", "Nintendo Entertainment System", "NES", CONSOLE, NINTENDO, "Nintendo", 1985,
        NES_EXT, "nintendoentertainmentsystem", 0xFFB25E5E, ra = 7,
    ),
    platform(
        "famicom", "Family Computer", "FC", CONSOLE, NINTENDO, "Nintendo", 1983,
        "$NES_EXT fds", "fc nintendofamicom", 0xFFB9785B, ra = 7,
    ),
    platform(
        "fds", "Famicom Disk System", "FDS", CONSOLE, NINTENDO, "Nintendo", 1986,
        "fds $CART_ARCHIVES", "famicomdisksystem", 0xFFC9A45C, ra = 81, bios = KnownBios.fds,
    ),
    platform(
        "snes", "Super Nintendo", "SNES", CONSOLE, NINTENDO, "Nintendo", 1991,
        SNES_EXT, "supernintendo snesna", 0xFF8C84B8, ra = 3, aspect = SNES_US_BOX,
    ),
    platform(
        "sfam", "Super Famicom", "SFC", CONSOLE, NINTENDO, "Nintendo", 1990,
        SNES_EXT, "sfc superfamicom", 0xFFA38FC4, ra = 3,
    ),
    platform(
        "ngc", "GameCube", "GC", CONSOLE, NINTENDO, "Nintendo", 2001,
        "iso gcm gcz rvz ciso dol elf tgc wia m3u", "gc gamecube nintendogamecube", 0xFF6D63A8, ra = 16,
    ),
    platform(
        "wii", "Wii", "Wii", CONSOLE, NINTENDO, "Nintendo", 2006,
        "iso wbfs rvz gcz ciso wia wad dol elf m3u", "wiiware nintendowii", 0xFF8FB3C7, ra = 19, aspect = DVD_CASE,
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
        "cdi chd gdi cue iso m3u", "dreamcast segadreamcast", 0xFFD08A4E, ra = 40, aspect = JEWEL_CASE,
        bios = KnownBios.dreamcast,
    ),
    platform(
        "saturn", "Saturn", "Saturn", CONSOLE, SEGA, "Sega", 1994,
        "$CD_IMAGES mds toc", "segasaturn saturnjp", 0xFF6A6F80, ra = 39, bios = KnownBios.saturn,
    ),
    platform(
        "genesis", "Mega Drive / Genesis", "MD", CONSOLE, SEGA, "Sega", 1988,
        "md gen bin smd 68k sgd $CART_ARCHIVES", "megadrive megadrivejp md segagenesis segamegadrive",
        0xFF3F5E8C, ra = 1,
    ),
    platform(
        "segacd", "Mega-CD / Sega CD", "MCD", CONSOLE, SEGA, "Sega", 1991,
        "cue chd iso m3u", "megacd megacdjp segamegacd", 0xFF5A6E9E, ra = 9, aspect = JEWEL_CASE,
        bios = KnownBios.segacd,
    ),
    platform(
        "sega32", "32X", "32X", CONSOLE, SEGA, "Sega", 1994,
        "32x bin $CART_ARCHIVES", "sega32x 32x sega32xjp sega32xna", 0xFFB0584F, ra = 10,
    ),
    platform(
        "sms", "Master System", "SMS", CONSOLE, SEGA, "Sega", 1986,
        "sms bin $CART_ARCHIVES", "mastersystem mark3 segamastersystem", 0xFF4E78A8, ra = 11,
    ),
    platform(
        "gamegear", "Game Gear", "GG", HANDHELD, SEGA, "Sega", 1990,
        "gg bin $CART_ARCHIVES", "gg segagamegear", 0xFF3F7F7A, ra = 15,
    ),
    platform(
        "sg1000", "SG-1000", "SG", CONSOLE, SEGA, "Sega", 1983,
        "sg bin $CART_ARCHIVES", "sg-1000", 0xFF8E6A5A, ra = 33,
    ),

    // Arcade and SNK
    platform(
        "arcade", "Arcade", "Arcade", PlatformKind.ARCADE, ARCADE, null, null,
        "zip 7z chd",
        "mame fbneo fba finalburn naomi naomi2 naomigd atomiswave hbmame cps cps1 cps2 cps3",
        0xFFC06C84, ra = 27, aspect = 0.75f,
    ),
    platform(
        "neogeoaes", "Neo Geo", "Neo Geo", CONSOLE, SNK, "SNK", 1990,
        CART_ARCHIVES, "neogeo", 0xFFC9A04A, bios = KnownBios.neoGeo,
    ),
    platform(
        "neo-geo-cd", "Neo Geo CD", "NGCD", CONSOLE, SNK, "SNK", 1994,
        "cue chd iso m3u", "neogeocd neogeocdjp", 0xFFB38E4F, ra = 56, aspect = JEWEL_CASE,
    ),
    platform(
        "neo-geo-pocket", "Neo Geo Pocket", "NGP", HANDHELD, SNK, "SNK", 1998,
        "ngp $CART_ARCHIVES", "ngp", 0xFF7A8A9C, ra = 14,
    ),
    platform(
        "neo-geo-pocket-color", "Neo Geo Pocket Color", "NGPC", HANDHELD, SNK, "SNK", 1999,
        "ngc ngp $CART_ARCHIVES", "ngpc", 0xFF5F9E7E, ra = 14,
    ),

    // NEC
    platform(
        "tg16", "PC Engine / TurboGrafx-16", "PCE", CONSOLE, NEC, "NEC", 1987,
        "pce sgx $CD_IMAGES $CART_ARCHIVES", "pcengine turbografx16 turbografx tg-16", 0xFFC47B4F, ra = 8,
        bios = KnownBios.pceHuCard,
    ),
    platform(
        "turbografx-cd", "PC Engine CD / TurboGrafx-CD", "PCE-CD", CONSOLE, NEC, "NEC", 1988,
        "$CD_IMAGES img toc", "pcenginecd tgcd tg-cd turbografxcd", 0xFFA8704E, ra = 76, aspect = JEWEL_CASE,
        bios = KnownBios.pceCd,
    ),
    platform(
        "supergrafx", "PC Engine SuperGrafx", "SGX", CONSOLE, NEC, "NEC", 1989,
        "sgx pce $CD_IMAGES $CART_ARCHIVES", "sgfx", 0xFF9E6A7E,
    ),
    platform(
        "pc-fx", "PC-FX", "PC-FX", CONSOLE, NEC, "NEC", 1994,
        "cue chd ccd toc m3u", "pcfx", 0xFF8B7FA3, aspect = JEWEL_CASE,
    ),

    // Atari
    platform(
        "atari2600", "Atari 2600", "2600", CONSOLE, ATARI, "Atari", 1977,
        "a26 bin $CART_ARCHIVES", "2600", 0xFFA0674B, ra = 25,
    ),
    platform(
        "atari5200", "Atari 5200", "5200", CONSOLE, ATARI, "Atari", 1982,
        "a52 bin car $CART_ARCHIVES", "5200", 0xFF6F6A64, ra = 50, bios = KnownBios.atari5200,
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
        "j64 jag rom abs cof bin prg $CART_ARCHIVES", "atarijaguar", 0xFFB84E4E, ra = 17,
    ),
    platform(
        "atari-st", "Atari ST", "ST", COMPUTER, ATARI, "Atari", 1985,
        "st msa stx dim ipf $CART_ARCHIVES", "atarist", 0xFF7E8C8A,
    ),

    // Bandai
    platform(
        "wonderswan", "WonderSwan", "WS", HANDHELD, BANDAI, "Bandai", 1999,
        "ws $CART_ARCHIVES", "wswan", 0xFF6D8C9E, ra = 53,
    ),
    platform(
        "wonderswan-color", "WonderSwan Color", "WSC", HANDHELD, BANDAI, "Bandai", 2000,
        "wsc ws $CART_ARCHIVES", "wswanc", 0xFF8C6DA8, ra = 53,
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
        "exe com bat conf dosz iso cue $CART_ARCHIVES", "pcdos msdos ms-dos", 0xFF7A8A6A,
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
        "swf swiffid", "adobeflash flashgames swf", 0xFFD8402F, aspect = JEWEL_CASE,
    ),
    platform(
        "android", "Android", "Android", PlatformKind.ANDROID, ANDROID, "Google", null,
        "app", "androidgames androidapps", 0xFF79A86B, aspect = JEWEL_CASE,
    ),

    // Home computers
    platform(
        "msx", "MSX", "MSX", COMPUTER, OTHER, "ASCII", 1983,
        "rom mx1 mx2 dsk cas $CART_ARCHIVES", "msx1", 0xFF6B7FA3, ra = 29,
    ),
    platform(
        "msx2", "MSX2", "MSX2", COMPUTER, OTHER, "ASCII", 1985,
        "rom mx2 dsk cas $CART_ARCHIVES", "", 0xFF7B6FA3,
    ),
    platform(
        "c64", "Commodore 64", "C64", COMPUTER, OTHER, "Commodore", 1982,
        "d64 d71 d81 g64 t64 tap prg crt p00 $CART_ARCHIVES", "commodore64", 0xFF8A7F6A,
    ),
    platform(
        "amiga", "Amiga", "Amiga", COMPUTER, OTHER, "Commodore", 1985,
        "adf adz dms ipf hdf hdz lha lzx uae m3u $CART_ARCHIVES", "amiga500 amiga600 amiga1200 commodoreamiga",
        0xFFC98A5A,
    ),
    platform(
        "zxs", "ZX Spectrum", "ZX", COMPUTER, OTHER, "Sinclair", 1982,
        "tzx tap z80 sna dsk scl trd $CART_ARCHIVES", "zxspectrum spectrum", 0xFF6E6E80,
    ),
    platform(
        "acpc", "Amstrad CPC", "CPC", COMPUTER, OTHER, "Amstrad", 1984,
        "dsk sna cdt m3u $CART_ARCHIVES", "amstradcpc", 0xFF5E8A7A,
    ),

    // Other consoles
    platform(
        "3do", "3DO", "3DO", CONSOLE, OTHER, "The 3DO Company", 1993,
        "iso chd cue m3u", "panasonic3do", 0xFFA85E6E, ra = 43, aspect = JEWEL_CASE, bios = KnownBios.threeDo,
    ),
    platform(
        "colecovision", "ColecoVision", "CV", CONSOLE, OTHER, "Coleco", 1982,
        "col rom bin $CART_ARCHIVES", "coleco", 0xFF5E5E6E, ra = 44, bios = KnownBios.colecovision,
    ),
    platform(
        "intellivision", "Intellivision", "INTV", CONSOLE, OTHER, "Mattel", 1979,
        "int bin rom $CART_ARCHIVES", "intv", 0xFF8E7A5A, ra = 45, bios = KnownBios.intellivision,
    ),
    platform(
        "vectrex", "Vectrex", "VEC", CONSOLE, OTHER, "GCE", 1982,
        "vec gam bin $CART_ARCHIVES", "", 0xFF7A7F8C, ra = 46,
    ),
)
