package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Games from Fuse Ports: each port keeps its saves in its own home in Fuse's folder of ports, named
 * in the launcher file Fuse wrote for it (the game's own file in the library, its `saves`). The
 * folder is the one game's alone, so all of it is the save (or only the files its launcher names,
 * where the folder also holds settings), and it only ever goes to the same port on the same kind of
 * system (`port.<id>.<system>`): another port's engine never reads it, and a port's Linux build's
 * save never lands in its Windows build's folder, whose engine may keep its own another way.
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
        val format = formatFor(port, env.host)
        // Only these files when the folder also holds its settings and caches (P.T.'s two save slots).
        val named = (launcher["saveFiles"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { n -> n.isNotBlank() && '/' !in n && '\\' !in n } }
            .orEmpty()
        if (named.isNotEmpty()) return listOf(SaveSpot(SaveKind.SAVE, format, files = named.map { SpotFile(it, "$saves/$it") }))
        return listOf(SaveSpot(SaveKind.SAVE, format, root = saves))
    }

    /** A port's save format on [host]: its own, on its own kind of system. */
    fun formatFor(port: String, host: String): String = "$PREFIX$port.${host.lowercase()}"

    /**
     * A save made before saves were kept per system (`port.<id>`) is taken by that port on any
     * system once: it came from the one system the person played it on.
     */
    fun compatible(saved: String, wanted: String): Boolean {
        if (!saved.startsWith(PREFIX) || !wanted.startsWith(PREFIX)) return false
        val port = wanted.removePrefix(PREFIX).substringBeforeLast('.', "")
        return port.isNotEmpty() && saved == "$PREFIX$port"
    }

    private const val PREFIX = "port."
}

/**
 * Android ports and recomps (an app attached to a game, which Play starts in its emulator's place):
 * the port's saves are its own, kept apart from the emulator's and from every other port, and only
 * ever on Android (`androidport.<package>`): a computer's build of the same port keeps another shape.
 *
 * Android gives each app a private folder no other app can open, and most ports keep their saves
 * there; then nothing can be kept in step, and Fuse says so. Where a port keeps them in the device's
 * own storage, the folder chosen for it (Settings, Save folders) is its save.
 */
internal object AndroidPortSaves : SaveAdapter {
    override val emulators = emptySet<String>()

    /** The emulator id Fuse's save queries give an Android port: its package. */
    fun emulatorFor(packageName: String): String = "$PREFIX$packageName"

    fun handles(emulatorId: String): Boolean = emulatorId.startsWith(PREFIX)

    override fun locate(q: SaveQuery, env: SaveEnvironment): List<SaveSpot> {
        val format = SaveAdapters.baseId(q.emulatorId)
        if (env.host != "ANDROID") return listOf(SaveAdapters.unavailable(SaveKind.SAVE, format, "An Android port's saves are kept on Android only."))
        val folder = env.saveFolder(format)?.replace('\\', '/')?.trimEnd('/')
            ?: return listOf(
                SaveAdapters.unavailable(
                    SaveKind.SAVE, format,
                    "This port keeps its saves in its own folder on Android, which no other app can open. If it keeps them in your storage, choose that folder in Settings, Save folders.",
                ),
            )
        return listOf(SaveSpot(SaveKind.SAVE, format, root = folder))
    }

    private const val PREFIX = "androidport."
}

/** How the rest of Fuse names an Android port for its saves ([AndroidPortSaves]). */
object AndroidPorts {
    /** The emulator id a save query gives a game played as the Android port with [packageName]. */
    fun saveEmulator(packageName: String): String = AndroidPortSaves.emulatorFor(packageName)
}
