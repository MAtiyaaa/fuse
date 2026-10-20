package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The settings a person changed that aren't saved yet, carried over settings that came in meanwhile.
 * Only what they changed moves, setting by setting: everything else is what came in. Fuse One's own
 * settings ([AppSettings.sync]) are never carried; the sync store owns them.
 */
object UnsavedChoices {
    /**
     * [onto] with every setting that differs between [base] (what was saved) and [shown] (what the
     * person sees), as [shown] has it; null when nothing differs.
     */
    fun carry(onto: AppSettings, base: AppSettings, shown: AppSettings): AppSettings? {
        val json = AppSettingsCodec.json
        val before = json.encodeToJsonElement(AppSettings.serializer(), base)
        val after = json.encodeToJsonElement(AppSettings.serializer(), shown)
        val changes = ArrayList<Pair<List<String>, JsonElement?>>()
        diff(emptyList(), before, after, changes)
        val kept = changes.filter { (path, _) -> path.firstOrNull() !in OWNED_ELSEWHERE }
        if (kept.isEmpty()) return null
        var root = json.encodeToJsonElement(AppSettings.serializer(), onto).jsonObject
        for ((path, value) in kept) root = if (value == null) remove(root, path) else set(root, path, value)
        return runCatching { json.decodeFromJsonElement(AppSettings.serializer(), root) }.getOrNull()
    }

    private val OWNED_ELSEWHERE = setOf("sync", "version")

    /**
     * Each difference from [a] to [b], as the path and [b]'s value there, or null where [b] has no
     * such key: an entry taken out of a map (every field of a setting is always written, null too).
     */
    private fun diff(path: List<String>, a: JsonElement?, b: JsonElement?, out: MutableList<Pair<List<String>, JsonElement?>>) {
        if (a == b) return
        if (b == null) {
            out += path to null
            return
        }
        if (a is JsonObject && b is JsonObject) {
            for (k in a.keys + b.keys) diff(path + k, a[k], b[k], out)
        } else {
            out += path to b
        }
    }

    private fun set(obj: JsonObject, parts: List<String>, value: JsonElement): JsonObject {
        val head = parts.first()
        if (parts.size == 1) return JsonObject(obj + (head to value))
        val child = obj[head] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(obj + (head to set(child, parts.drop(1), value)))
    }

    private fun remove(obj: JsonObject, parts: List<String>): JsonObject {
        val head = parts.first()
        if (parts.size == 1) return JsonObject(obj - head)
        val child = obj[head] as? JsonObject ?: return obj
        return JsonObject(obj + (head to remove(child, parts.drop(1))))
    }
}
