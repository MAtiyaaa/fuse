package io.github.matiyaaa.fuse.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.pow

/**
 * Themes as files anyone can write and share: a small JSON document (docs/THEMES.md) that names a
 * built-in theme to start from and changes what it likes. Everything but the name is optional.
 * A theme is repaired rather than refused: unknown parts are skipped, numbers are kept in range and
 * text that would be hard to read is lightened or darkened until it reads well, and each repair is
 * listed so the person adding it knows.
 */
object ThemeCodec {
    /** The schema version this Fuse writes and reads in full. */
    const val VERSION = 1

    /** The largest theme file Fuse reads. */
    const val MAX_BYTES = 64 * 1024

    /** Added themes' ids start with this, so they never take a built-in theme's place. */
    const val CUSTOM_PREFIX = "custom."

    sealed interface Result
    data class Imported(val spec: ThemeSpec, val notes: List<String>) : Result
    data class Failed(val reason: String) : Result

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * Reads a theme from [text]. [base] finds the built-in theme a file `extends` (and [fallback]
     * is used when it names none, or one this Fuse doesn't have).
     */
    fun parse(text: String, base: (String) -> ThemeSpec?, fallback: ThemeSpec): Result {
        if (text.encodeToByteArray().size > MAX_BYTES) return Failed("This theme is larger than 64 KB.")
        val root = runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject
            ?: return Failed("This isn't a Fuse theme: it isn't a JSON object.")
        val notes = mutableListOf<String>()
        val name = root.string("name")?.clean(40)?.takeIf { it.isNotBlank() }
            ?: return Failed("A theme needs a name.")
        val version = (root["fuseTheme"] as? JsonPrimitive)?.intOrNull
        if (version != null && version > VERSION) notes += "Made for a newer Fuse, so some parts may look different."

        val extends = root.string("extends")?.trim()?.lowercase()
        val start = when {
            extends == null -> fallback
            else -> base(extends) ?: fallback.also { notes += "Fuse has no \"$extends\" theme to start from, so it starts from ${fallback.name}." }
        }

        val colors = root["colors"] as? JsonObject
        fun color(vararg keys: String): Long? {
            val key = keys.firstOrNull { colors?.containsKey(it) == true } ?: return null
            val raw = colors?.string(key) ?: return null
            return parseColor(raw) ?: run { notes += "Skipped the colour \"$key\": \"$raw\" isn't a colour like #FF6A3D."; null }
        }
        val p = start.palette
        val background = color("background")?.opaque() ?: p.background
        val surface = color("surface")?.opaque() ?: p.surface
        val surfaceRaised = color("surfaceRaised", "raised")?.opaque() ?: p.surfaceRaised
        val dark = (root["dark"] as? JsonPrimitive)?.booleanOrNull
            ?: if (colors?.containsKey("background") == true) Contrast.luminance(background) < 0.4 else p.dark
        val accent = color("accent") ?: p.accent
        val accentSoft = color("accentSoft") ?: if (colors?.containsKey("accent") == true) withAlpha(accent, 0x33) else p.accentSoft
        val onAccentGiven = color("onAccent")
        val onAccent = onAccentGiven ?: if (colors?.containsKey("accent") == true) Contrast.bestOn(accent) else p.onAccent
        var text = color("text", "textPrimary") ?: p.textPrimary
        var muted = color("textMuted", "textSecondary") ?: p.textSecondary
        var focus = color("focus", "focusRing") ?: p.focusRing
        var onAcc = onAccent

        // Text must read on the room and on panels; muted text a little less so; the focus outline
        // must show against the room.
        val lighten = dark
        Contrast.repair(text, listOf(background, surface), 4.5, lighten)?.let { text = it; notes += "Adjusted the text colour so it reads well." }
        Contrast.repair(muted, listOf(surface), 3.0, lighten)?.let { muted = it; notes += "Adjusted the muted text colour so it reads well." }
        Contrast.repair(onAcc, listOf(accent), 4.5, Contrast.luminance(accent) < 0.4)?.let {
            onAcc = it
            if (onAccentGiven != null) notes += "Adjusted the text on the accent so it reads well."
        }
        Contrast.repair(focus, listOf(background), 3.0, lighten)?.let { focus = it; notes += "Adjusted the focus colour so it shows against the background." }

        val palette = ThemePalette(
            dark = dark,
            background = background, surface = surface, surfaceRaised = surfaceRaised,
            accent = accent, accentSoft = accentSoft, onAccent = onAcc,
            textPrimary = text, textSecondary = muted, focusRing = focus,
            success = color("success") ?: p.success,
            warning = color("warning") ?: p.warning,
            danger = color("danger") ?: p.danger,
        )

        // The background: a style name, or an object with its style and character.
        var style = start.background
        var ambient = start.ambient
        when (val bg = root["background"]) {
            is JsonPrimitive -> style = enumOf(bg.contentOrNull, start.background, "background", notes)
            is JsonObject -> {
                style = enumOf(bg.string("style"), start.background, "background", notes)
                ambient = AmbientSpec(
                    intensity = bg.float("intensity", ambient.intensity, 0f, 1.5f, notes),
                    speed = bg.float("speed", ambient.speed, 0f, 2f, notes),
                    secondary = bg.string("secondary")?.let(::parseColor) ?: ambient.secondary,
                )
            }
            else -> Unit
        }

        val glass = when (val g = root["glass"]) {
            is JsonPrimitive -> g.booleanOrNull?.let { start.glass.copy(enabled = it) } ?: start.glass
            is JsonObject -> start.glass.copy(
                enabled = g.bool("enabled") ?: true,
                blur = g.float("blur", start.glass.blur, 0f, 48f, notes),
                surfaceOpacity = g.float("opacity", start.glass.surfaceOpacity, 0.3f, 1f, notes),
            )
            else -> start.glass
        }
        val crt = when (val g = root["crt"]) {
            is JsonPrimitive -> g.booleanOrNull?.let { start.crt.copy(enabled = it) } ?: start.crt
            is JsonObject -> start.crt.copy(
                enabled = g.bool("enabled") ?: true,
                scanlines = g.float("scanlines", start.crt.scanlines, 0f, 0.6f, notes),
                bloom = g.float("bloom", start.crt.bloom, 0f, 1f, notes),
                curvature = g.float("curvature", start.crt.curvature, 0f, 1f, notes),
                chromatic = g.float("chromatic", start.crt.chromatic, 0f, 1f, notes),
                vignette = g.float("vignette", start.crt.vignette, 0f, 1f, notes),
            )
            else -> start.crt
        }

        val id = CUSTOM_PREFIX + slug(root.string("id")?.removePrefix(CUSTOM_PREFIX) ?: name).ifEmpty { "theme" }
        val spec = start.copy(
            id = id,
            name = name,
            tagline = root.string("tagline")?.clean(80) ?: "",
            author = root.string("author")?.clean(40)?.takeIf { it.isNotBlank() },
            palette = palette,
            background = style,
            ambient = ambient,
            geometry = enumOf(root.string("corners"), start.geometry, "corners", notes),
            focus = enumOf(root.string("focus"), start.focus, "focus", notes),
            motion = enumOf(root.string("motion"), start.motion, "motion", notes),
            sound = enumOf(root.string("sound"), start.sound, "sound", notes),
            glass = glass,
            crt = crt,
        )
        return Imported(spec, notes.distinct())
    }

    /** [spec] as a theme file, starting from [extends] (the built-in theme it is based on). */
    fun encode(spec: ThemeSpec, extends: String? = spec.id.takeUnless { it.startsWith(CUSTOM_PREFIX) }): String {
        val p = spec.palette
        val doc = buildJsonObject {
            put("fuseTheme", VERSION)
            put("name", spec.name)
            put("id", spec.id.removePrefix(CUSTOM_PREFIX))
            spec.author?.let { put("author", it) }
            if (spec.tagline.isNotEmpty()) put("tagline", spec.tagline)
            extends?.let { put("extends", it) }
            put("dark", p.dark)
            put("colors", buildJsonObject {
                put("background", hex(p.background))
                put("surface", hex(p.surface))
                put("surfaceRaised", hex(p.surfaceRaised))
                put("accent", hex(p.accent))
                put("accentSoft", hex(p.accentSoft))
                put("onAccent", hex(p.onAccent))
                put("text", hex(p.textPrimary))
                put("textMuted", hex(p.textSecondary))
                put("focus", hex(p.focusRing))
                p.success?.let { put("success", hex(it)) }
                p.warning?.let { put("warning", hex(it)) }
                p.danger?.let { put("danger", hex(it)) }
            })
            put("background", buildJsonObject {
                put("style", spec.background.name.lowercase())
                put("intensity", spec.ambient.intensity)
                put("speed", spec.ambient.speed)
                spec.ambient.secondary?.let { put("secondary", hex(it)) }
            })
            put("corners", spec.geometry.name.lowercase())
            put("focus", spec.focus.name.lowercase())
            put("motion", spec.motion.name.lowercase())
            put("sound", spec.sound.name.lowercase())
            put("glass", buildJsonObject {
                put("enabled", spec.glass.enabled)
                put("blur", spec.glass.blur)
                put("opacity", spec.glass.surfaceOpacity)
            })
            put("crt", buildJsonObject {
                put("enabled", spec.crt.enabled)
                put("scanlines", spec.crt.scanlines)
                put("bloom", spec.crt.bloom)
                put("curvature", spec.crt.curvature)
                put("chromatic", spec.crt.chromatic)
                put("vignette", spec.crt.vignette)
            })
        }
        return json.encodeToString(JsonElement.serializer(), doc) + "\n"
    }

    /** `#RGB`, `#RRGGBB` or `#RRGGBBAA` (alpha last, as on the web) as ARGB; null if it isn't one. */
    fun parseColor(raw: String): Long? {
        val h = raw.trim().removePrefix("#")
        if (h.isEmpty() || h.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            6 -> h + "ff"
            8 -> h
            else -> return null
        }
        val rgb = full.substring(0, 6).toLong(16)
        val a = full.substring(6, 8).toLong(16)
        return (a shl 24) or rgb
    }

    /** ARGB as `#RRGGBB`, or `#RRGGBBAA` when it isn't opaque. */
    fun hex(argb: Long): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
        return if (a == 0xFFL) "#$rgb" else "#$rgb${a.toString(16).padStart(2, '0').uppercase()}"
    }

    /** A theme name as an id: lower case letters, digits and single dashes, at most 40. */
    fun slug(name: String): String {
        val out = StringBuilder()
        for (ch in name.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> out.append(ch)
                out.isNotEmpty() && out.last() != '-' -> out.append('-')
            }
        }
        return out.toString().trim('-').take(40).trim('-')
    }

    private fun Long.opaque(): Long = this or 0xFF000000L

    private fun withAlpha(argb: Long, alpha: Int): Long = (alpha.toLong() shl 24) or (argb and 0xFFFFFF)

    private fun String.clean(max: Int): String = filter { it >= ' ' && it != '\u007F' }.trim().take(max)

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.float(key: String, default: Float, min: Float, max: Float, notes: MutableList<String>): Float {
        val v = (this[key] as? JsonPrimitive)?.floatOrNull ?: return default
        if (v.isNaN()) return default
        val clamped = v.coerceIn(min, max)
        if (clamped != v) notes += "Kept \"$key\" between ${trim(min)} and ${trim(max)}."
        return clamped
    }

    private fun trim(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

    private inline fun <reified E : Enum<E>> enumOf(raw: String?, default: E, key: String, notes: MutableList<String>): E {
        if (raw == null) return default
        val wanted = raw.trim().uppercase()
        return enumValues<E>().firstOrNull { it.name == wanted } ?: default.also {
            notes += "Fuse doesn't know the $key \"$raw\", so it keeps ${default.name.lowercase()}."
        }
    }
}

/** WCAG contrast between ARGB colours, and nudging a colour until it reads. */
object Contrast {
    /** Relative luminance (0 black to 1 white) of the colour's RGB. */
    fun luminance(argb: Long): Double {
        fun channel(v: Long): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Black or white, whichever reads better on [argb]. */
    fun bestOn(argb: Long): Long = if (ratio(0xFF000000, argb) >= ratio(0xFFFFFFFF, argb)) 0xFF111111 else 0xFFFFFFFF

    /**
     * [color] moved toward white ([lighten]) or black in small steps until it reaches [min] against
     * every one of [against]; null when it already does. Transparent colours count as opaque.
     */
    fun repair(color: Long, against: List<Long>, min: Double, lighten: Boolean): Long? {
        fun ok(c: Long) = against.all { ratio(c, it) >= min }
        if (ok(color)) return null
        val target = if (lighten) 0xFFFFFFFFL else 0xFF000000L
        for (step in 1..20) {
            val c = mix(color, target, step / 20f)
            if (ok(c)) return c
        }
        return target
    }

    private fun mix(a: Long, b: Long, t: Float): Long {
        fun ch(shift: Int): Long {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toLong().coerceIn(0, 255)
        }
        return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Turns the links people share into the file behind them. */
object ThemeLinks {
    /**
     * A GitHub file page becomes its raw file, a gist page its raw text; anything else is kept.
     * Only https is accepted (null otherwise).
     */
    fun normalize(url: String): String? {
        val u = url.trim()
        if (!u.startsWith("https://")) return null
        val blob = Regex("""^https://github\.com/([^/]+)/([^/]+)/blob/(.+)$""").find(u)
        if (blob != null) {
            val (owner, repo, rest) = blob.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$rest"
        }
        val gist = Regex("""^https://gist\.github\.com/([^/]+)/([0-9a-fA-F]+)/?$""").find(u)
        if (gist != null) {
            val (owner, id) = gist.destructured
            return "https://gist.githubusercontent.com/$owner/$id/raw"
        }
        return u
    }
}
