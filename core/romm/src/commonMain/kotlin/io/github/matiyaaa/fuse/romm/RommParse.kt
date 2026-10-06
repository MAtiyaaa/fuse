package io.github.matiyaaa.fuse.romm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * RomM's answers, read defensively. Fields come and go between RomM versions, lists can be null and
 * older servers lack whole blocks, so every field is read on its own: a missing or odd one becomes an
 * empty value, never an error that stops a sync. Only what Fuse keeps is read; the rest of a large
 * answer is never held.
 */
object RommParse {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun element(text: String): JsonElement = json.parseToJsonElement(text)

    /** RomM's version from `/api/heartbeat` (`SYSTEM.VERSION`, or `VERSION` on old servers). */
    fun version(heartbeat: JsonElement): String {
        val o = heartbeat.obj()
        return o.obj("SYSTEM").str("VERSION") ?: o.str("VERSION") ?: ""
    }

    fun platforms(e: JsonElement): List<RommPlatform> = e.items().mapNotNull { p ->
        val o = p.obj()
        val id = o.long("id") ?: return@mapNotNull null
        val slug = o.str("slug") ?: return@mapNotNull null
        RommPlatform(
            id = id,
            slug = slug,
            fsSlug = o.str("fs_slug") ?: slug,
            name = o.str("display_name") ?: o.str("custom_name") ?: o.str("name") ?: slug,
            romCount = o.long("rom_count")?.toInt() ?: 0,
            sizeBytes = o.long("fs_size_bytes") ?: 0,
        )
    }

    /** A page of `/api/roms`: a list (old servers) or `{items, total}`. */
    fun page(e: JsonElement): RommPage {
        val items = e.items().mapNotNull(::rom)
        val total = (e as? JsonObject)?.long("total")?.toInt()
        return RommPage(items, total)
    }

    fun rom(e: JsonElement): RommRom? {
        val o = e as? JsonObject ?: return null
        val id = o.long("id") ?: return null
        val md = o.obj("metadatum")
        val files = o.arr("files").mapNotNull { f ->
            val fo = f.obj()
            val fid = fo.long("id") ?: return@mapNotNull null
            val name = fo.str("file_name") ?: return@mapNotNull null
            RommFile(
                id = fid,
                name = name,
                path = innerPath(fo.str("file_path"), o.str("fs_path"), o.str("fs_name")),
                sizeBytes = fo.long("file_size_bytes") ?: 0,
                md5 = fo.str("md5_hash")?.lowercase(),
                sha1 = fo.str("sha1_hash")?.lowercase(),
                crc = fo.str("crc_hash")?.lowercase(),
                category = fo.str("category"),
            )
        }
        val fsName = o.str("fs_name") ?: o.str("file_name") ?: ""
        return RommRom(
            id = id,
            platformId = o.long("platform_id") ?: 0,
            platformSlug = o.str("platform_slug") ?: o.str("platform_fs_slug") ?: "",
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: o.str("fs_name_no_ext") ?: fsName,
            fsName = fsName,
            sizeBytes = o.long("fs_size_bytes") ?: files.sumOf { it.sizeBytes },
            md5 = o.str("md5_hash")?.lowercase(),
            sha1 = o.str("sha1_hash")?.lowercase(),
            crc = o.str("crc_hash")?.lowercase(),
            titleId = o.str("title_id"),
            regions = o.arr("regions").mapNotNull { it.strOrNull() },
            revision = o.str("revision"),
            year = md.long("first_release_date")?.let(::yearOf) ?: o.long("first_release_date")?.let(::yearOf),
            summary = o.str("summary")?.take(2_000),
            genres = (md.arr("genres").takeIf { it.isNotEmpty() } ?: o.arr("genres")).mapNotNull { it.strOrNull() }.take(4),
            developer = developerOf(o),
            cover = o.str("path_cover_large") ?: o.str("path_cover_small"),
            logo = logoOf(o),
            screenshot = o.arr("merged_screenshots").firstOrNull()?.strOrNull(),
            files = files,
            multi = o.bool("has_multiple_files") ?: o.bool("multi") ?: (files.size > 1),
            createdAt = o.str("created_at")?.let(::millis) ?: 0,
            updatedAt = o.str("updated_at")?.let(::millis) ?: 0,
        )
    }

    fun firmware(e: JsonElement): List<RommFirmware> = e.items().mapNotNull { f ->
        val o = f.obj()
        val id = o.long("id") ?: return@mapNotNull null
        if (o.bool("missing_from_fs") == true) return@mapNotNull null
        RommFirmware(
            id = id,
            platformId = o.long("platform_id") ?: 0,
            fileName = o.str("file_name") ?: return@mapNotNull null,
            sizeBytes = o.long("file_size_bytes") ?: 0,
            md5 = o.str("md5_hash")?.lowercase()?.ifBlank { null },
            sha1 = o.str("sha1_hash")?.lowercase()?.ifBlank { null },
            crc = o.str("crc_hash")?.lowercase()?.ifBlank { null },
            verified = o.bool("is_verified") ?: false,
        )
    }

    fun collections(e: JsonElement, smart: Boolean): List<RommCollection> = e.items().mapNotNull { c ->
        val o = c.obj()
        val id = o.long("id") ?: return@mapNotNull null
        RommCollection(
            id = (if (smart) "smart-" else "user-") + id,
            name = o.str("name") ?: return@mapNotNull null,
            smart = smart,
            romIds = o.arr("rom_ids").mapNotNull { (it as? JsonPrimitive)?.longOrNull },
            cover = o.str("path_cover_large") ?: o.str("path_cover_small"),
        )
    }

    /** Every game's id, from `/api/roms/identifiers`. */
    fun identifiers(e: JsonElement): List<Long> = e.items().mapNotNull { (it as? JsonPrimitive)?.longOrNull }

    /**
     * A file's folder inside its game: RomM's `file_path` is the folder on the server (the game's
     * own folder and below); what is below the game's folder is what Fuse keeps.
     */
    internal fun innerPath(filePath: String?, romPath: String?, fsName: String?): String {
        val fp = filePath?.replace('\\', '/')?.trim('/') ?: return ""
        val game = listOfNotNull(romPath?.replace('\\', '/')?.trim('/'), fsName).filter { it.isNotEmpty() }.joinToString("/")
        return when {
            game.isNotEmpty() && fp == game -> ""
            game.isNotEmpty() && fp.startsWith("$game/") -> fp.removePrefix("$game/")
            else -> ""
        }.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")
    }

    /** RomM keeps developers apart in its metadata and in each source's block; the first found, at most two. */
    private fun developerOf(o: JsonObject): String? {
        for (key in listOf("metadatum", "igdb_metadata", "ss_metadata", "launchbox_metadata", "moby_metadata", "gamelist_metadata")) {
            val d = o.obj(key).arr("developers").mapNotNull { it.strOrNull()?.trim()?.ifEmpty { null } }.distinct().take(2)
            if (d.isNotEmpty()) return d.joinToString(", ")
        }
        return o.obj("metadatum").arr("companies").firstOrNull()?.strOrNull()
    }

    private fun logoOf(o: JsonObject): String? {
        val p = o.obj("ss_metadata").str("logo_path") ?: o.obj("gamelist_metadata").str("marquee_path") ?: return null
        return if (p.startsWith("/assets/") || p.startsWith("http")) p else "/assets/romm/resources/" + p.trimStart('/')
    }

    /** A release date in seconds or milliseconds since 1970 as its year (UTC). */
    internal fun yearOf(v: Long): Int? {
        val ms = if (v > 10_000_000_000L) v else v * 1000
        if (ms <= 0) return null
        // Days to a civil year (Howard Hinnant's days_from_civil, reversed).
        val z = ms / 86_400_000L + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = if (mp < 10) mp + 3 else mp - 9
        return (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    }

    /** An ISO-8601 time as epoch milliseconds; 0 when it can't be read. */
    fun millis(text: String): Long = runCatching {
        val t = text.trim()
        val withZone = if (t.endsWith("Z") || Regex("[+-]\\d{2}:?\\d{2}$").containsMatchIn(t.substringAfter('T'))) t else "${t}Z"
        kotlin.time.Instant.parse(withZone).toEpochMilliseconds()
    }.getOrDefault(0L)

    /** Epoch milliseconds as RomM's `updated_after` wants them. */
    fun iso(millis: Long): String = kotlin.time.Instant.fromEpochMilliseconds(millis).toString()

    // ---------------------------------------------------------------- reading without trusting

    private fun JsonElement.items(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["items"] as? JsonArray) ?: emptyList()
        else -> emptyList()
    }

    private fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
    private fun JsonObject.arr(key: String): List<JsonElement> = this[key] as? JsonArray ?: emptyList()
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonElement.strOrNull(): String? = when (this) {
        is JsonPrimitive -> if (this is JsonNull) null else contentOrNull
        is JsonObject -> (this["name"] as? JsonPrimitive)?.contentOrNull
        else -> null
    }
}
