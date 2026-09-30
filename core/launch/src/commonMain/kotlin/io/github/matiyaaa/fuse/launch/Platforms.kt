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
        "scummvm", "msx", "c64", "amiga", "3do", "colecovision", "intellivision", "vectrex",
    ).map(::PlatformId)

    private val known = all.toSet()

    fun isKnown(id: PlatformId): Boolean = id in known
}

/** Shorthand for sets of platform ids in catalogs. */
internal fun platforms(vararg ids: String): Set<PlatformId> = ids.map(::PlatformId).toSet()
