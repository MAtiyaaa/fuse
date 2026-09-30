package io.github.matiyaaa.fuse.library

import io.github.matiyaaa.fuse.library.platform.catalogPlatforms
import io.github.matiyaaa.fuse.model.Platform
import io.github.matiyaaa.fuse.model.PlatformId

/** Finds platforms by id or folder name. [PlatformCatalog] is the built-in implementation. */
interface PlatformLookup {
    /** Every known platform. */
    val all: List<Platform>

    /** The platform with this id, or null. */
    fun byId(id: PlatformId): Platform?

    /** The platform a folder with this name holds, or null when the name means nothing to Fuse. */
    fun resolveFolder(name: String): Platform?
}

/**
 * Fuse's curated list of platforms. Ids are RomM slugs so a RomM-structured library maps one to
 * one; folder aliases also cover ES-DE system folders and common spellings.
 */
object PlatformCatalog : PlatformLookup {
    override val all: List<Platform> = catalogPlatforms

    private val byIdMap: Map<String, Platform> = all.associateBy { it.id.value }

    private val byAlias: Map<String, Platform> = buildMap {
        for (platform in all) for (alias in platform.folderAliases) getOrPut(alias) { platform }
    }

    // Loose keys (alphanumerics only) from aliases and full names, for "PlayStation 2" or "Game_Boy".
    private val byLooseKey: Map<String, Platform> = buildMap {
        for (platform in all) for (alias in platform.folderAliases) getOrPut(looseKey(alias)) { platform }
        for (platform in all) getOrPut(looseKey(platform.name)) { platform }
    }

    private val byExtension: Map<String, List<Platform>> = buildMap<String, MutableList<Platform>> {
        for (platform in all) for (ext in platform.extensions) getOrPut(ext) { mutableListOf() }.add(platform)
    }

    override fun byId(id: PlatformId): Platform? = byIdMap[id.value]

    /** The platform with this RomM slug, or null. */
    fun byId(id: String): Platform? = byIdMap[id.trim().lowercase()]

    /**
     * Maps a folder name to a platform, case-insensitively: first the exact RomM slug, RomM alias or
     * ES-DE system name ("megadrive", "n3ds", "pcenginecd"), then the same with every
     * non-alphanumeric character removed, which also matches full names ("PlayStation 2",
     * "Game Boy Advance", "Mega Drive / Genesis").
     */
    override fun resolveFolder(name: String): Platform? {
        val lower = name.trim().lowercase()
        if (lower.isEmpty()) return null
        byAlias[lower]?.let { return it }
        val loose = looseKey(lower)
        if (loose.isEmpty()) return null
        return byLooseKey[loose]
    }

    /** Platforms that list [extension] (with or without the leading dot), in catalog order. */
    fun forExtension(extension: String): List<Platform> =
        byExtension[extension.trim().removePrefix(".").lowercase()].orEmpty()

    internal fun looseKey(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
