package io.github.matiyaaa.fuse.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Durable ordering of one profile preference. A remote observation advances the next local edit. */
@Serializable
data class ProfileSettingRevision(val millis: Long = 0, val counter: Int = 0, val origin: String = "") : Comparable<ProfileSettingRevision> {
    override fun compareTo(other: ProfileSettingRevision): Int = compareValuesBy(this, other,
        ProfileSettingRevision::millis, ProfileSettingRevision::counter, ProfileSettingRevision::origin)
}

/** Value and provenance are committed in the same database transaction, including offline edits. */
@Serializable
data class ProfileSettingValue(val value: JsonElement, val revision: ProfileSettingRevision = ProfileSettingRevision())

@Serializable
internal data class ProfileSettingDocument(val values: Map<String, ProfileSettingValue> = emptyMap())

@Serializable
internal data class ProfileSettingClock(val revision: ProfileSettingRevision = ProfileSettingRevision(), val origin: String = "")
