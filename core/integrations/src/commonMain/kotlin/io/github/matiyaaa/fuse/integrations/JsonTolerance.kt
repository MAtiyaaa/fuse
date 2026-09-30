@file:OptIn(ExperimentalSerializationApi::class)

package io.github.matiyaaa.fuse.integrations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Serializers for APIs that are loose with types (RetroAchievements, ScreenScraper, TheGamesDB):
// numbers sent as strings, booleans as 0/1 or "true", empty maps sent as [], and so on.

internal fun JsonElement?.flexLong(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    p.longOrNull?.let { return it }
    p.doubleOrNull?.let { if (!it.isNaN()) return it.toLong() }
    p.booleanOrNull?.let { return if (it) 1 else 0 }
    return p.content.trim().toLongOrNull() ?: p.content.trim().toDoubleOrNull()?.toLong()
}

internal fun JsonElement?.flexDouble(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.doubleOrNull ?: p.content.trim().removeSuffix("%").toDoubleOrNull()
}

internal fun JsonElement?.flexBoolean(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    p.booleanOrNull?.let { return it }
    return when (p.content.trim().lowercase()) {
        "1", "true", "yes", "y" -> true
        "0", "false", "no", "n", "" -> false
        else -> p.longOrNull?.let { it != 0L }
    }
}

internal fun JsonElement?.flexString(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

private fun Decoder.element(): JsonElement =
    (this as? JsonDecoder)?.decodeJsonElement() ?: error("Only JSON is supported")

/** Long that may arrive as a number, a numeric string, a boolean, "" or null. */
internal object FlexLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexLong", PrimitiveKind.LONG).nullable
    override fun deserialize(decoder: Decoder): Long? = decoder.element().flexLong()
    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }
}

/** Int variant of [FlexLong]. */
internal object FlexInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexInt", PrimitiveKind.INT).nullable
    override fun deserialize(decoder: Decoder): Int? = decoder.element().flexLong()?.toInt()
    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
    }
}

/** Double that may arrive as a number, a numeric string ("45.00%" included) or null. */
internal object FlexDouble : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexDouble", PrimitiveKind.DOUBLE).nullable
    override fun deserialize(decoder: Decoder): Double? = decoder.element().flexDouble()
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}

/** Boolean that may arrive as true/false, 0/1, "0"/"1", "true"/"false" or null. */
internal object FlexBoolean : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexBoolean", PrimitiveKind.BOOLEAN).nullable
    override fun deserialize(decoder: Decoder): Boolean? = decoder.element().flexBoolean()
    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** String that may arrive as a string, a number or null. */
internal object FlexString : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexString", PrimitiveKind.STRING).nullable
    override fun deserialize(decoder: Decoder): String? = decoder.element().flexString()
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/** Non-null [FlexLong]: anything unreadable becomes 0. */
internal object LooseLong : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LooseLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long = decoder.element().flexLong() ?: 0L
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** Non-null [FlexInt]: anything unreadable becomes 0. */
internal object LooseInt : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LooseInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int = decoder.element().flexLong()?.toInt() ?: 0
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** Non-null [FlexBoolean]: anything unreadable becomes false. */
internal object LooseBoolean : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LooseBoolean", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean = decoder.element().flexBoolean() ?: false
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/**
 * A list that may arrive as a JSON array, as an object keyed by id (the values are taken in order),
 * as `[]` standing in for an empty object, or as null. Subclass it per element type.
 */
internal abstract class FlexListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    private val delegate = ListSerializer(element)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val json = (decoder as? JsonDecoder) ?: return delegate.deserialize(decoder)
        return decodeFlexList(json.json, json.decodeJsonElement(), element)
    }

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
}

internal fun <T> decodeFlexList(json: kotlinx.serialization.json.Json, element: JsonElement?, serializer: KSerializer<T>): List<T> =
    when (element) {
        is JsonArray -> element.filterNot { it is JsonNull }.map { json.decodeFromJsonElement(serializer, it) }
        is JsonObject -> element.values.filterNot { it is JsonNull }.map { json.decodeFromJsonElement(serializer, it) }
        else -> emptyList()
    }
