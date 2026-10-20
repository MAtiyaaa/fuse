package io.github.matiyaaa.fuse.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A theme a device's Fuse hasn't got, sent back as Fuse. Before 0.4.3.2, Fuse showed such a theme
 * (one from a newer Fuse, like Twin and Viewfinder from 0.4.3.1) as Fuse and saved it that way, so
 * Fuse went to every device as the person's newest choice and their theme went back to Fuse a few
 * seconds after they chose it. Those devices can't learn better, so the host, which every change
 * passes through, leaves that one out: everything else the device sent still counts.
 */
object OlderFuseThemes {
    const val THEME = "appearance.themeId"
    const val CUSTOM = "appearance.customThemes"
    const val FUSE = "fuse"

    /** The first Fuse that keeps a theme it hasn't got as the person chose it. */
    const val KEEPS_FROM = "0.4.3.2"

    /** Built in to every Fuse that syncs profiles. */
    private val ALWAYS = setOf(
        "fuse", "glass", "pitch", "starlight", "orbital", "crossbar", "wave", "blossom", "lagoon", "canopy",
        "blades", "sundown", "crt", "daylight", "paper-mint", "channels", "opal", "noon", "ridge", "olive", "fusi",
    )

    /** Built in from the Fuse named on. */
    private val SINCE = mapOf("twin" to "0.4.3.1", "viewfinder" to "0.4.3.1")

    /** Built in only from the first Fuse named to the second (retired after). */
    private val BETWEEN = mapOf(
        "fii" to ("0.4.2.12" to "0.4.2.13"), "fii-night" to ("0.4.2.12" to "0.4.2.13"),
        "duo" to ("0.4.2.13" to "0.4.2.13"), "duo-night" to ("0.4.2.13" to "0.4.2.13"),
        "trio" to ("0.4.2.13" to "0.4.2.13"), "trio-night" to ("0.4.2.13" to "0.4.2.13"),
    )

    /**
     * Whether Fuse [version] ("" before 0.4.2, which didn't say) has the theme [id]: built in to it,
     * or added to the profile ([added], which every device gets with the profile).
     */
    fun has(version: String, id: String, added: Set<String>): Boolean = when {
        id in ALWAYS || id in added -> true
        id in SINCE -> !SyncApi.older(version, SINCE.getValue(id))
        id in BETWEEN -> BETWEEN.getValue(id).let { (from, to) -> !SyncApi.older(version, from) && !SyncApi.older(to, version) }
        // A theme this host has never heard of is a newer Fuse's: no Fuse before 0.4.3.2 has it.
        else -> false
    }

    /**
     * [incoming] from a device running Fuse [version], without the Fuse it sent over [current]'s
     * theme when its Fuse hasn't got that theme and would show Fuse in its place anyway.
     */
    fun keep(current: ProfileMeta, incoming: ProfileMeta, version: String): ProfileMeta {
        if (!SyncApi.older(version, KEEPS_FROM)) return incoming
        val sent = (incoming.settings[THEME]?.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return incoming
        if (sent != FUSE) return incoming
        val chosen = (current.settings[THEME]?.value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return incoming
        if (chosen == FUSE || has(version, chosen, added(current))) return incoming
        return incoming.copy(settings = incoming.settings - THEME)
    }

    /** The ids of the themes added to the profile (from a link, a file, or the Theme Studio). */
    private fun added(meta: ProfileMeta): Set<String> =
        (meta.settings[CUSTOM]?.value as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .toSet()
}
