package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes [AppSettings] so that no version of Fuse destroys another's settings:
 * - unknown fields are ignored when reading and carried over when writing;
 * - a section that fails to decode falls back to its defaults without losing the other sections;
 * - [migrate] upgrades documents written by older versions.
 */
internal object AppSettingsCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = true
    }

    /** Step i upgrades a version i + 1 document to version i + 2. Empty while at version 1. */
    private val steps: List<(JsonObject) -> JsonObject> = emptyList()

    fun decode(text: String?): AppSettings {
        if (text.isNullOrBlank()) return AppSettings()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return AppSettings()
        val migrated = migrate(obj)
        return runCatching { json.decodeFromJsonElement(AppSettings.serializer(), migrated) }.getOrElse {
            // Keep only the sections that decode on their own.
            val readable = migrated.filter { (key, value) ->
                runCatching { json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(mapOf(key to value))) }.isSuccess
            }
            runCatching { json.decodeFromJsonElement(AppSettings.serializer(), JsonObject(readable)) }.getOrDefault(AppSettings())
        }.copy(version = AppSettings.CURRENT_VERSION)
    }

    /** Encodes [settings], carrying over fields of [previous] that this version does not know. */
    fun encode(settings: AppSettings, previous: String?): String {
        val fresh = json.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
        val old = previous?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val merged = if (old == null) fresh else keepUnknown(old, fresh, AppSettings.serializer().descriptor)
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    /** Upgrades a document written by an older version. */
    fun migrate(obj: JsonObject): JsonObject {
        val from = ((obj["version"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1)
        return steps.drop(from - 1).fold(obj) { acc, step -> step(acc) }
    }

    /** Adds fields of [old] unknown to [descriptor], recursing into nested classes (not maps). */
    @OptIn(ExperimentalSerializationApi::class)
    private fun keepUnknown(old: JsonObject, fresh: JsonObject, descriptor: SerialDescriptor): JsonObject {
        val merged = LinkedHashMap(fresh)
        for ((key, oldValue) in old) {
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) {
                merged[key] = oldValue
                continue
            }
            val child = descriptor.getElementDescriptor(index)
            val newValue = fresh[key]
            if (child.kind == StructureKind.CLASS && oldValue is JsonObject && newValue is JsonObject) {
                merged[key] = keepUnknown(oldValue, newValue, child)
            }
        }
        return JsonObject(merged)
    }
}
