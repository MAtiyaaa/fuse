package io.github.matiyaaa.fuse.integrations.cartridge

import io.github.matiyaaa.fuse.integrations.match.TitleMatcher
import io.github.matiyaaa.fuse.model.PlatformId

/**
 * Decides whether RomM's details for a game Cartridge reports really belong to the Fuse game its path
 * points at, before any of them (name, details, art, the RomM link) is written.
 *
 * A path alone is not enough: Cartridge names a download by its RomM rom id, and when that id comes to
 * mean another rom (a RomM rescan, another server), or a folder rule picks the only game in a folder,
 * the details are another game's. So both have to hold:
 * - the system: RomM's platform slug resolves to the game's platform, or to one that shares its games
 *   ([CLOSE]); a slug Fuse doesn't know is no evidence either way;
 * - the name: RomM's title reads like the game's own file name or cleaned name, at [MIN_TITLE] or
 *   more ([TitleMatcher.titleSimilarity]: a subtitle or region tag is fine, another game is not).
 */
object RommGuard {
    /** Title similarity RomM's name needs with the game's own names. */
    const val MIN_TITLE = 0.8

    /** Platforms whose games are often filed under each other. */
    private val CLOSE: List<Set<String>> = listOf(
        setOf("gb", "gbc"),
        setOf("nes", "famicom"),
        setOf("snes", "sfam"),
        setOf("nds", "nintendo-dsi"),
        setOf("tg16", "supergrafx"),
        setOf("msx", "msx2"),
    )

    /** Why a match was turned down, for logs and tests. */
    enum class Verdict { ACCEPT, OTHER_SYSTEM, OTHER_TITLE }

    /**
     * Checks RomM's [rommTitle] and [rommSlug] against a Fuse game on [platform] whose own names are
     * [ownTitles] (the file name first). [resolveSlug] maps a RomM slug to Fuse's platform, or null.
     */
    fun check(
        platform: PlatformId,
        ownTitles: List<String>,
        rommTitle: String,
        rommSlug: String,
        resolveSlug: (String) -> PlatformId?,
    ): Verdict {
        val slug = rommSlug.trim().lowercase()
        if (slug.isNotEmpty()) {
            val theirs = resolveSlug(slug)?.value ?: slug.takeIf { it == platform.value }
            if (theirs != null && !samePlatform(platform.value, theirs)) return Verdict.OTHER_SYSTEM
        }
        if (rommTitle.isBlank()) return Verdict.OTHER_TITLE
        val best = ownTitles.filter { it.isNotBlank() }.maxOfOrNull { TitleMatcher.titleSimilarity(it, rommTitle).score } ?: 0.0
        return if (best >= MIN_TITLE) Verdict.ACCEPT else Verdict.OTHER_TITLE
    }

    fun accepts(platform: PlatformId, ownTitles: List<String>, rommTitle: String, rommSlug: String, resolveSlug: (String) -> PlatformId?): Boolean =
        check(platform, ownTitles, rommTitle, rommSlug, resolveSlug) == Verdict.ACCEPT

    private fun samePlatform(a: String, b: String): Boolean = a == b || CLOSE.any { a in it && b in it }
}
