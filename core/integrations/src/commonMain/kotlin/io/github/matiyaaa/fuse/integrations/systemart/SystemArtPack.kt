package io.github.matiyaaa.fuse.integrations.systemart

import io.github.matiyaaa.fuse.integrations.UrlCoding

/**
 * The artwork sets of Art Book Next. [folder] is the directory under `_inc/systems/`; not every
 * set has art for every system, so [SystemArtPackClient] falls back to [CLASSIC].
 */
enum class SystemArtStyle(val folder: String, val displayName: String) {
    /** The original tall panels (454x1080). */
    CLASSIC("artwork", "Classic"),
    OUTLINE("artwork-outline", "Outline"),
    NOIR("artwork-noir", "Noir"),
    CIRCUIT("artwork-circuit", "Circuit"),
    SCREENSHOTS("artwork-screenshots", "Screenshots"),
}

/** Every URL the pack has for one system, for pickers that show all styles side by side. */
data class SystemArtUrls(
    /** The pack's system name ("snes", "n3ds"). */
    val name: String,
    /** The system logo (SVG). */
    val logo: String,
    /** The `_metadata-global` XML with the system's name, maker, year and colours. */
    val metadata: String,
    val artwork: Map<SystemArtStyle, String>,
) {
    fun artwork(style: SystemArtStyle): String = artwork.getValue(style)
}

/**
 * Art Book Next, Anthony Caccese's ES-DE theme (https://github.com/anthonycaccese/art-book-next-es-de),
 * licensed CC BY-NC-SA 2.0. Fuse fetches its system logos, artwork and metadata at runtime from a
 * pinned commit and caches them on the device; nothing from the pack is bundled with Fuse.
 */
object SystemArtPack {
    const val REPO_URL = "https://github.com/anthonycaccese/art-book-next-es-de"

    /** The commit Fuse reads from (upstream HEAD on 2026-09-30), so a reorganisation upstream cannot break it. */
    const val REF = "d772d07109701d9bd7c9fda305bfef6601105ab8"

    const val BASE_URL = "https://raw.githubusercontent.com/anthonycaccese/art-book-next-es-de/$REF/_inc/systems"

    const val LICENSE = "CC BY-NC-SA 2.0"
    const val LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/2.0/"

    /** Credits as the pack's README lists them, to show wherever its art is offered. */
    const val ATTRIBUTION =
        "System art from Art Book Next, a theme by Anthony Caccese. System logos modified from Dan " +
            "Patrick's console logos. Noir artwork set by tenlevels with help from f8less. Outline " +
            "artwork set by Joppa Fallston. Some artwork by theUnBurn. Licensed under CC BY-NC-SA 2.0. " +
            "Fetched on demand from the pack's GitHub repository, not distributed with Fuse."

    /** Every URL for the pack system [name] under [baseUrl]. */
    fun urls(name: String, baseUrl: String = BASE_URL): SystemArtUrls {
        val file = UrlCoding.encode(name)
        return SystemArtUrls(
            name = name,
            logo = "$baseUrl/logos/$file.svg",
            metadata = "$baseUrl/_metadata-global/$file.xml",
            artwork = SystemArtStyle.entries.associateWith { "$baseUrl/${it.folder}/$file.png" },
        )
    }
}
