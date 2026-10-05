package io.github.matiyaaa.fuse.launch

import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Canonical platform ids this module knows (RomM slugs). Kept here so `:core:launch` does not depend
 * on the library's platform catalog.
 */
object Platforms {
    val all: List<PlatformId> = listOf(
        "psx", "ps2", "ps3", "ps4", "psp", "psvita",
        "n64", "nds", "nintendo-dsi", "3ds", "new-nintendo-3ds", "gb", "gbc", "gba", "nes", "famicom", "fds",
        "snes", "sfam", "ngc", "wii", "wiiu", "switch", "virtualboy", "pokemon-mini",
        "dc", "saturn", "genesis", "segacd", "sega32", "sms", "gamegear", "sg1000",
        "arcade", "neogeoaes", "neo-geo-cd", "neo-geo-pocket", "neo-geo-pocket-color",
        "tg16", "turbografx-cd", "atari2600", "atari5200", "atari7800", "lynx", "jaguar",
        "wonderswan", "wonderswan-color", "xbox", "xbox360", "win", "dos", "steam", "android",
        "scummvm", "msx", "c64", "amiga", "3do", "colecovision", "intellivision", "vectrex", "pico8", "flash",
        // 0.3.0: the systems RetroArch runs on every host, from ES-DE's list.
        "64dd", "amiga-cd32", "amstrad-gx4000", "appleii", "arcadia-2001", "arduboy", "atari-jaguar-cd", "atari8bit",
        "bbcmicro", "c-plus-4", "chailove", "commodore-cdtv", "creativision", "doom", "fairchild-channel-f",
        "g-and-w", "handheld-electronic-lcd", "j2me", "laserdisc", "lowresnx", "lutro", "mac",
        "mega-duck-slash-cougar-boy", "model2", "model3", "msx-turbo", "msx2plus", "multivision", "odyssey-2",
        "palm-os", "pc-8800-series", "pc-9800-series", "philips-cd-i", "quake", "rpg-maker", "satellaview",
        "sharp-x68000", "spectravideo", "stv", "sufami-turbo", "supervision", "thomson-mo5", "tic-80", "uzebox",
        "vic-20", "videopac-g7400", "vircon32", "wasm-4", "win3x", "win9x", "x1", "z-machine", "zx81",
    ).map(::PlatformId)

    private val known = all.toSet()

    fun isKnown(id: PlatformId): Boolean = id in known
}

/** Shorthand for sets of platform ids in catalogs. */
internal fun platforms(vararg ids: String): Set<PlatformId> = ids.map(::PlatformId).toSet()
