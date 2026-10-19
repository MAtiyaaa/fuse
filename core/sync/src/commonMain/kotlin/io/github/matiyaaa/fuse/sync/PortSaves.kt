package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Games from Fuse Ports: each port keeps its saves in its own home in Fuse's folder of ports, named
 * in the launcher file Fuse wrote for it (the game's own file in the library, its `saves`). The
 * folder is the one game's alone, so all of it is the save, and it only ever goes to the same port
 * (`port.<id>`): another port's engine never reads it.
 */
internal object PortSaves : SaveAdapter {
    override val emulators = setOf("port")

    private val json = Json { ignoreUnknownKeys = true }

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val launcher = env.readText(q.romPath, 16 * 1024)?.let { text -> runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() }
            ?: return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "port", "Fuse couldn't read how this port is set up. Set it up again in Addons, Ports."))
        val port = (launcher["port"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val saves = (launcher["saves"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.replace('\\', '/')?.trimEnd('/')
        if (port == null || saves.isNullOrEmpty()) return listOf(SaveAdapters.unavailable(SaveKind.SAVE, "port", "This port keeps no saves of its own for Fuse to keep in step."))
        return listOf(SaveSpot(SaveKind.SAVE, "port.$port", root = saves))
    }
}
