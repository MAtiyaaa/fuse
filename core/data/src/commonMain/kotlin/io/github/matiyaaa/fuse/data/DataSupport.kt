package io.github.matiyaaa.fuse.data

import io.github.matiyaaa.fuse.data.db.FuseDatabase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** JSON used for data blobs (tags, metadata, cache values). Tolerant of unknown fields. */
internal val DataJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
    explicitNulls = false
}

internal inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    if (name == null) null else enumValues<E>().firstOrNull { it.name == name }

internal inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumOrNull<E>(name) ?: fallback

/** Decodes [json], or returns null when it is absent or unreadable (never throws on bad data). */
internal fun <T> decodeOrNull(serializer: KSerializer<T>, json: String?): T? =
    if (json.isNullOrEmpty()) null else runCatching { DataJson.decodeFromString(serializer, json) }.getOrNull()

internal fun Boolean.toDb(): Long = if (this) 1L else 0L

internal fun Long.asBool(): Boolean = this != 0L

/** SQLite on Android API 28 allows 999 bound variables per statement; stay well under it. */
internal const val SQL_CHUNK = 500

/** Id of the row the last INSERT created. Call it inside the same transaction as the INSERT. */
internal fun FuseDatabase.lastInsertId(): Long = utilQueries.lastInsertRowId().executeAsOne()

/**
 * Smallest string greater than every string starting with [prefix] (binary collation), so
 * `path >= prefix AND path < bound` is an index-friendly prefix match.
 */
internal fun prefixUpperBound(prefix: String): String {
    require(prefix.isNotEmpty())
    val last = prefix.last()
    return if (last == Char.MAX_VALUE) prefix + Char.MAX_VALUE else prefix.dropLast(1) + (last + 1)
}

/** Stable 64-bit FNV-1a hash rendered as hex; used for change detection only. */
internal fun fnv1a64(text: String): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (ch in text) {
        hash = hash xor ch.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash.toULong().toString(16)
}

internal const val DAY_MS: Long = 86_400_000L
