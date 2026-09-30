package io.github.matiyaaa.fuse.integrations.systemart

/**
 * A system's facts from the pack's `_metadata-global` file. [year] is null when the pack writes
 * something other than a year ("Various" for arcade). Colours are ARGB, like `Platform.accent`.
 */
data class SystemArtMeta(
    val name: String,
    val manufacturer: String?,
    val year: Int?,
    /** "Console", "Portable", "Computer", "Collection"... as the pack writes it. */
    val hardwareType: String?,
    val color: Long?,
    /** systemColorPalette1..4, in order, skipping missing or unreadable ones. */
    val palette: List<Long>,
)

/**
 * Reads the handful of variables Fuse uses from a `_metadata-global` file. The files are small,
 * flat ES-DE theme XML: one top-level `<variables>` block followed by translated `<language>`
 * blocks, which are ignored. A regex over the part before the first `<language` is enough and keeps
 * the module free of an XML dependency.
 */
object SystemArtMetadata {
    private val element = Regex("<([A-Za-z][A-Za-z0-9]*)>([^<]*)</\\1>")
    private val leadingYear = Regex("^\\d{4}")
    private val hexColor = Regex("^#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

    /** The metadata in [xml], or null when it has no top-level `systemName`. */
    fun parse(xml: String): SystemArtMeta? {
        val head = xml.substringBefore("<language")
        val values = element.findAll(head).associate { it.groupValues[1] to decodeEntities(it.groupValues[2]).trim() }
        val name = values["systemName"]?.takeIf { it.isNotEmpty() } ?: return null
        return SystemArtMeta(
            name = name,
            manufacturer = values["systemManufacturer"]?.takeIf { it.isNotEmpty() },
            year = values["systemReleaseYear"]?.let { leadingYear.find(it)?.value?.toInt() },
            hardwareType = values["systemHardwareType"]?.takeIf { it.isNotEmpty() },
            color = values["systemColor"]?.let(::parseColor),
            palette = (1..4).mapNotNull { i -> values["systemColorPalette$i"]?.let(::parseColor) },
        )
    }

    /** "df5142" or "df5142ff" (ES-DE's RRGGBB[AA]) as ARGB; null for anything else. */
    fun parseColor(value: String): Long? {
        val hex = hexColor.matchEntire(value.trim())?.groupValues?.get(1) ?: return null
        val rgb = hex.substring(0, 6).toLong(16)
        val alpha = if (hex.length == 8) hex.substring(6).toLong(16) else 0xFF
        return (alpha shl 24) or rgb
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
