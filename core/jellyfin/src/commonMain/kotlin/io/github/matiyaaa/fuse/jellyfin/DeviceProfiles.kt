package io.github.matiyaaa.fuse.jellyfin

import io.github.matiyaaa.fuse.playback.Capabilities
import io.github.matiyaaa.fuse.playback.HdrFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Jellyfin's device profile, written from what the player measured it can play on this device
 * ([Capabilities]): the server plays files as they are when it can, repackages them when only the
 * container is the trouble, and converts only what the player really can't play. Text subtitles
 * always come as files Fuse draws itself; picture subtitles stay in the stream where the engine
 * decodes them, else the server draws them into the picture.
 */
object DeviceProfiles {
    fun build(caps: Capabilities, maxBitrate: Long?): JsonObject = buildJsonObject {
        put("Name", "Fuse")
        val limit = maxBitrate ?: UNLIMITED
        put("MaxStreamingBitrate", limit)
        put("MaxStaticBitrate", limit)
        put("MusicStreamingTranscodingBitrate", 320_000)
        val videoCodecs = caps.videoCodecs.joinToString(",") { it.codec }
        val audioCodecs = caps.audioCodecs.filter { it != "pcm" }.joinToString(",")
        putJsonArray("DirectPlayProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", caps.containers.filter { it in VIDEO_CONTAINERS }.joinToString(","))
                put("VideoCodec", videoCodecs)
                put("AudioCodec", audioCodecs)
            }
            addJsonObject {
                put("Type", "Audio")
                put("Container", caps.containers.filter { it in AUDIO_CONTAINERS }.joinToString(","))
            }
        }
        putJsonArray("TranscodingProfiles") {
            addJsonObject {
                put("Type", "Video")
                put("Container", "ts")
                put("Protocol", "hls")
                put("Context", "Streaming")
                // H.264 first: every server converts to it quickly; HEVC where the player has it.
                put("VideoCodec", listOf("h264", "hevc").filter { c -> caps.video(c) != null }.joinToString(",").ifEmpty { "h264" })
                put("AudioCodec", listOf("aac", "ac3", "eac3", "mp3").filter { caps.playsAudio(it) }.joinToString(",").ifEmpty { "aac" })
                put("MaxAudioChannels", caps.maxAudioChannels.coerceAtMost(6).toString())
                put("MinSegments", 1)
                put("BreakOnNonKeyFrames", true)
            }
            addJsonObject {
                put("Type", "Audio")
                put("Container", "mp3")
                put("Protocol", "http")
                put("Context", "Streaming")
                put("AudioCodec", "mp3")
                put("MaxAudioChannels", "2")
            }
        }
        put("ContainerProfiles", JsonArray(emptyList()))
        putJsonArray("CodecProfiles") {
            for (v in caps.videoCodecs) {
                addJsonObject {
                    put("Type", "Video")
                    put("Codec", v.codec)
                    putJsonArray("Conditions") {
                        condition("LessThanEqual", "Width", v.maxWidth.toString())
                        condition("LessThanEqual", "Height", v.maxHeight.toString())
                        condition("LessThanEqual", "VideoBitDepth", v.maxBitDepth.toString())
                        if (v.profiles.isNotEmpty()) condition("EqualsAny", "VideoProfile", v.profiles.joinToString("|"))
                        v.maxLevel?.let { condition("LessThanEqual", "VideoLevel", it.toString()) }
                        // HDR the screen doesn't show is tone mapped by the server.
                        condition("EqualsAny", "VideoRangeType", rangeTypes(v.hdr).joinToString("|"))
                    }
                }
            }
            addJsonObject {
                put("Type", "VideoAudio")
                putJsonArray("Conditions") {
                    condition("LessThanEqual", "AudioChannels", caps.maxAudioChannels.toString())
                }
            }
        }
        putJsonArray("SubtitleProfiles") {
            for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt")) subtitle(f, "External")
            for (f in Capabilities.BITMAP_SUBTITLES) {
                if (f in caps.embeddedSubtitles) subtitle(f, "Embed")
                subtitle(f, "Encode")
            }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.condition(condition: String, property: String, value: String) = addJsonObject {
        put("Condition", condition)
        put("Property", property)
        put("Value", value)
        put("IsRequired", false)
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.subtitle(format: String, method: String) = addJsonObject {
        put("Format", format)
        put("Method", method)
    }

    /** Jellyfin's names for the video ranges a codec may play as they are. */
    internal fun rangeTypes(hdr: Set<HdrFormat>): List<String> = buildList {
        add("SDR")
        if (HdrFormat.HDR10 in hdr) add("HDR10")
        if (HdrFormat.HDR10_PLUS in hdr) {
            add("HDR10Plus")
            if (HdrFormat.HDR10 !in hdr) add("HDR10")
        }
        if (HdrFormat.HLG in hdr) add("HLG")
        if (HdrFormat.DOLBY_VISION in hdr) {
            add("DOVI")
            add("DOVIWithHDR10")
            add("DOVIWithHLG")
            add("DOVIWithSDR")
        } else if (HdrFormat.HDR10 in hdr) {
            // Dolby Vision with an HDR10 base layer plays as HDR10.
            add("DOVIWithHDR10")
        }
    }

    private val VIDEO_CONTAINERS = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "mpegts", "avi")
    private val AUDIO_CONTAINERS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "webm", "mka")

    /** No limit on the connection: higher than any file. */
    const val UNLIMITED = 200_000_000L

    internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content
}
