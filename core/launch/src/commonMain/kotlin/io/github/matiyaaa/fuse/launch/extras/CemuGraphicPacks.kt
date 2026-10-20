package io.github.matiyaaa.fuse.launch.extras

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Cemu's community graphic packs, fetched as Cemu's own Download Community Graphic Packs does
 * (src/gui/DownloadGraphicPacksWindow.cpp): the latest release of cemu-project/cemu_graphic_packs
 * from GitHub's API, its first asset (a zip), unpacked into `graphicPacks/downloadedGraphicPacks`
 * after everything already there is taken out, with the release's name (which carries its version)
 * written to `version.txt` in that folder. An entry whose path climbs out (`../`) is left out, and
 * so is any single file over 128 MB. Pure: the release text in, what to do out.
 */
object CemuGraphicPacks {
    /** GitHub's latest release of the community packs, as Cemu asks for it when its own lookup fails. */
    const val RELEASE_URL = "https://api.github.com/repos/cemu-project/cemu_graphic_packs/releases/latest"

    /** Below Cemu's `graphicPacks`, where downloaded packs go (and only those). */
    const val FOLDER = "downloadedGraphicPacks"
    const val VERSION_FILE = "version.txt"

    /** The largest single file Cemu takes from the zip. */
    const val MAX_FILE = 128L * 1024 * 1024

    /** The largest zip Fuse fetches. */
    const val MAX_ZIP = 512L * 1024 * 1024

    /** A release: its name (the version Cemu notes), where its zip is, and how big it is when said. */
    data class Release(val name: String, val url: String, val size: Long?)

    /** The release in GitHub's answer [text], or null when it isn't one Cemu would take. */
    fun release(text: String): Release? = runCatching {
        val o = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val asset = (o["assets"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") } ?: return null
        Release(name, url, asset["size"]?.jsonPrimitive?.longOrNull)
    }.getOrNull()

    /** The packs here are [release] already: `version.txt`'s first line, as Cemu compares it (case aside). */
    fun upToDate(versionText: String?, release: Release): Boolean =
        versionText?.lineSequence()?.firstOrNull()?.trim()?.equals(release.name, ignoreCase = true) == true

    /**
     * The zip's [entries] (with their sizes, null when unknown) that go into the folder, each with its
     * path there; folders and empty files are left out, as Cemu leaves them.
     */
    fun files(entries: List<Pair<String, Long?>>): List<String> = entries.mapNotNull { (name, size) ->
        when {
            name.isEmpty() || name.endsWith("/") -> null
            "../" in name || "..\\" in name || name.startsWith("/") || name.startsWith("\\") || ":" in name -> null
            size == 0L -> null
            size != null && size > MAX_FILE -> null
            else -> name
        }
    }
}
