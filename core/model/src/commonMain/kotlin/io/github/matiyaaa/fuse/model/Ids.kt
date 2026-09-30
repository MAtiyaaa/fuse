package io.github.matiyaaa.fuse.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Stable local identity of a game in Fuse's database. Never derived from a scraper. */
@Serializable
@JvmInline
value class GameId(val value: Long)

/**
 * Canonical Fuse platform key, for example "ps2" or "gba". Fuse uses RomM's platform slugs where
 * RomM has one, so a RomM-structured library maps one-to-one.
 */
@Serializable
@JvmInline
value class PlatformId(val value: String) {
    override fun toString() = value
}

@Serializable
@JvmInline
value class CollectionId(val value: Long)

@Serializable
@JvmInline
value class LibrarySourceId(val value: Long)

/** Adapter identity, for example "duckstation" or "linux.pcsx2". */
@Serializable
@JvmInline
value class EmulatorId(val value: String) {
    override fun toString() = value
}
