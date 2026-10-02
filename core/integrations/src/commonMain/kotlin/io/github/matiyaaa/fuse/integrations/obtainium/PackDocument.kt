package io.github.matiyaaa.fuse.integrations.obtainium

import io.github.matiyaaa.fuse.integrations.FuseHttp
import io.github.matiyaaa.fuse.integrations.flexBoolean
import io.github.matiyaaa.fuse.integrations.flexLong
import io.github.matiyaaa.fuse.integrations.flexString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Obtainium Emulation Pack as Fuse reads it: the apps it lists, in its order, and the
 * categories it groups them by (with the pack's own colours). Nothing here is Fuse's opinion: the
 * names, descriptions, sources and rules all come from the pack, and the Store only presents them.
 */
data class Pack(val apps: List<PackApp>, val categories: List<PackCategory>)

/** A category of the pack ("Emulator", "Frontend", "Track Only"), in the pack's colour (ARGB). */
data class PackCategory(val name: String, val color: Long?)

/**
 * One app of the pack. [id] is usually its Android package name, but not always: some entries
 * (RetroArch, and the track-only ones) carry a number Obtainium generated instead, which is why
 * [packageName] is only set when [id] really looks like one. [source] says how its releases are
 * found; [rules] are the pack's settings for finding them.
 */
data class PackApp(
    val id: String,
    val url: String,
    val name: String,
    val author: String,
    val categories: List<String>,
    val source: PackSourceKind,
    val allowIdChange: Boolean,
    val preferredApkIndex: Int?,
    val rules: PackRules,
) {
    /** The package name the app installs as, when the pack says so; null for generated ids. */
    val packageName: String? get() = id.takeIf { PACKAGE.matches(it) }

    /** The short line the pack gives to describe the app, if any. */
    val about: String? get() = rules.about?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    }
}

/** Where an app's releases come from. Fuse resolves [GITHUB] and [HTML]; anything else is shown, not installed. */
enum class PackSourceKind { GITHUB, HTML, OTHER }

/** One step of an HTML source that leads to the download page (a version folder, then "android"). */
data class LinkStep(
    val filter: String,
    val filterByLinkText: Boolean = false,
    val skipSort: Boolean = false,
    val reverseSort: Boolean = false,
    val sortByLastLinkSegment: Boolean = false,
    val matchLinksOutsideATags: Boolean = false,
    val autoLinkFilterByArch: Boolean = false,
)

/**
 * The pack's settings for finding an app's releases, as Obtainium reads them. Only the settings
 * that change which release and which file are chosen are kept; the rest (notification and
 * background update preferences of Obtainium itself) don't apply to Fuse.
 */
data class PackRules(
    val about: String? = null,
    val trackOnly: Boolean = false,
    // Which file of a release.
    val apkFilter: String? = null,
    val invertApkFilter: Boolean = false,
    val filterByArch: Boolean = false,
    val includeZips: Boolean = false,
    // Which release (GitHub).
    val includePrereleases: Boolean = false,
    val fallbackToOlderReleases: Boolean = false,
    val releaseTitleFilter: String? = null,
    val releaseNotesFilter: String? = null,
    val verifyLatestTag: Boolean = false,
    val sortMethod: String = "smartname-datefallback",
    val releaseTitleAsVersion: Boolean = false,
    val releaseDateAsVersion: Boolean = false,
    // The version's name.
    val versionPattern: String? = null,
    val versionGroups: String? = null,
    // HTML pages.
    val intermediateLinks: List<LinkStep> = emptyList(),
    val linkFilter: String? = null,
    val filterByLinkText: Boolean = false,
    val matchLinksOutsideATags: Boolean = false,
    val skipSort: Boolean = false,
    val reverseSort: Boolean = false,
    val sortByLastLinkSegment: Boolean = false,
    val versionFromWholePage: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
    // Obtainium's display overrides, used only when the pack has no name or author of its own.
    val appName: String? = null,
    val appAuthor: String? = null,
)

/** Reads the pack's JSON. Tolerant of what Obtainium tolerates: settings as a string or as an object. */
object PackDocument {
    /** The smallest pack Fuse accepts as real: a broken or truncated download never replaces a good one. */
    const val MIN_APPS = 5

    /**
     * Parses [text]. Fails (with a message) when it isn't a pack at all, when it has fewer than
     * [MIN_APPS] usable apps, so a cached catalogue is never replaced by an empty one.
     */
    fun parse(text: String): Result<Pack> {
        val root = try {
            FuseHttp.json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return Result.failure(PackException("The catalogue isn't readable JSON."))
        val apps = (root["apps"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::app) }
            ?: return Result.failure(PackException("The catalogue has no list of apps."))
        val unique = apps.distinctBy { it.id to it.url }
        if (unique.size < MIN_APPS) return Result.failure(PackException("The catalogue lists only ${unique.size} apps."))
        return Result.success(Pack(unique, categories(root["settings"], unique)))
    }

    private fun app(o: JsonObject): PackApp? {
        val id = o["id"].flexString()?.trim().orEmpty()
        val url = o["url"].flexString()?.trim().orEmpty()
        val rules = rules(o["additionalSettings"])
        // The pack's own name reads best ("DuckStation", "RetroArch (AArch64)"); Obtainium's
        // display override is only used when there is none.
        val name = o["name"].flexString()?.trim()?.takeIf { it.isNotEmpty() } ?: rules.appName.orEmpty()
        if (id.isEmpty() || url.isEmpty() || name.isEmpty()) return null
        val source = when (o["overrideSource"].flexString()?.trim()?.lowercase()) {
            "github" -> PackSourceKind.GITHUB
            "html" -> PackSourceKind.HTML
            null, "" -> if (Regex("^https://github\\.com/[^/]+/[^/]+/?$").matches(url)) PackSourceKind.GITHUB else PackSourceKind.OTHER
            else -> PackSourceKind.OTHER
        }
        return PackApp(
            id = id,
            url = url,
            name = name,
            author = o["author"].flexString()?.trim()?.takeIf { it.isNotEmpty() } ?: rules.appAuthor.orEmpty(),
            categories = (o["categories"] as? JsonArray)?.mapNotNull { it.flexString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty(),
            source = source,
            allowIdChange = o["allowIdChange"].flexBoolean() ?: false,
            preferredApkIndex = o["preferredApkIndex"].flexLong()?.toInt(),
            rules = rules,
        )
    }

    /** The settings object, which Obtainium exports as a JSON string inside the JSON. */
    private fun settingsObject(e: JsonElement?): JsonObject? = when (e) {
        is JsonObject -> e
        is JsonPrimitive -> if (e.isString) {
            try {
                FuseHttp.json.parseToJsonElement(e.content) as? JsonObject
            } catch (x: Exception) {
                null
            }
        } else null
        else -> null
    }

    private fun rules(e: JsonElement?): PackRules {
        val s = settingsObject(e) ?: return PackRules()
        fun text(key: String) = s[key].flexString()?.takeIf { it.isNotBlank() }
        fun flag(key: String) = s[key].flexBoolean() ?: false
        return PackRules(
            about = text("about"),
            trackOnly = flag("trackOnly"),
            apkFilter = text("apkFilterRegEx"),
            invertApkFilter = flag("invertAPKFilter"),
            filterByArch = flag("autoApkFilterByArch"),
            includeZips = flag("includeZips"),
            includePrereleases = flag("includePrereleases"),
            fallbackToOlderReleases = flag("fallbackToOlderReleases"),
            releaseTitleFilter = text("filterReleaseTitlesByRegEx"),
            releaseNotesFilter = text("filterReleaseNotesByRegEx"),
            verifyLatestTag = flag("verifyLatestTag"),
            sortMethod = text("sortMethodChoice") ?: "smartname-datefallback",
            releaseTitleAsVersion = flag("releaseTitleAsVersion"),
            releaseDateAsVersion = flag("releaseDateAsVersion"),
            versionPattern = text("versionExtractionRegEx"),
            versionGroups = text("matchGroupToUse"),
            intermediateLinks = (s["intermediateLink"] as? JsonArray).orEmpty().mapNotNull { step ->
                val o = step as? JsonObject ?: return@mapNotNull null
                val filter = o["customLinkFilterRegex"].flexString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LinkStep(
                    filter = filter,
                    filterByLinkText = o["filterByLinkText"].flexBoolean() ?: false,
                    skipSort = o["skipSort"].flexBoolean() ?: false,
                    reverseSort = o["reverseSort"].flexBoolean() ?: false,
                    sortByLastLinkSegment = o["sortByLastLinkSegment"].flexBoolean() ?: false,
                    matchLinksOutsideATags = o["matchLinksOutsideATags"].flexBoolean() ?: false,
                    autoLinkFilterByArch = o["autoLinkFilterByArch"].flexBoolean() ?: false,
                )
            },
            linkFilter = text("customLinkFilterRegex"),
            filterByLinkText = flag("filterByLinkText"),
            matchLinksOutsideATags = flag("matchLinksOutsideATags"),
            skipSort = flag("skipSort"),
            reverseSort = flag("reverseSort"),
            sortByLastLinkSegment = flag("sortByLastLinkSegment"),
            versionFromWholePage = flag("versionExtractWholePage"),
            requestHeaders = (s["requestHeader"] as? JsonArray).orEmpty().mapNotNull { h ->
                val line = (h as? JsonObject)?.get("requestHeader").flexString() ?: return@mapNotNull null
                val name = line.substringBefore(':').trim()
                val value = line.substringAfter(':', "").trim()
                if (name.isEmpty() || value.isEmpty() || !HEADER_NAME.matches(name)) null else name to value
            }.toMap(),
            appName = text("appName")?.trim(),
            appAuthor = text("appAuthor")?.trim(),
        )
    }

    /**
     * The pack's categories, in the order its apps first use them, with the colours from its
     * settings (an Obtainium export keeps them as a JSON string of name to ARGB number).
     */
    private fun categories(settings: JsonElement?, apps: List<PackApp>): List<PackCategory> {
        val colors = settingsObject((settings as? JsonObject)?.get("categories"))
            ?.mapValues { (_, v) -> v.flexLong() }
            .orEmpty()
        return apps.flatMap { it.categories }.distinct().map { PackCategory(it, colors[it]) }
    }

    /** Only plain header names are passed on (never anything that could split a request). */
    private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
}

class PackException(message: String) : Exception(message)
