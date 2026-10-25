package io.github.matiyaaa.fuse.ui.shell.store

import io.github.matiyaaa.fuse.model.GameId

/**
 * Lists Fuse keeps from how the games were played, which the Library shows as views and the person
 * pins among its tabs. [UNPLAYED], [BARELY] and [DUSTY] never share a game; [TOGETHER] reads the
 * games' details, [ADDED] when they arrived.
 */
enum class SmartFilter(val label: String, val blurb: String) {
    UNPLAYED("Unplayed", "Games you have never started"),
    BARELY("Barely played", "Started, then left before half an hour"),
    DUSTY("Gathering dust", "Played properly once, not touched for two months"),
    TOGETHER("Play together", "Games for two players or more"),
    ADDED("Added lately", "New to your library in the last two weeks"),
    ;

    /**
     * Whether a game is in this list [now]: its play ([lastPlayedAt], [playSeconds]), when it was
     * added, and, for [TOGETHER], whether its details say more than one can play ([together]).
     */
    fun matches(id: GameId, lastPlayedAt: Long?, playSeconds: Long, addedAt: Long, now: Long, together: Set<GameId>): Boolean {
        val started = lastPlayedAt != null || playSeconds > 0
        return when (this) {
            UNPLAYED -> !started
            BARELY -> started && playSeconds < BARELY_SECONDS
            // Imported time with no date is taken as long ago.
            DUSTY -> playSeconds >= BARELY_SECONDS && now - (lastPlayedAt ?: 0L) >= DUSTY_MS
            TOGETHER -> id in together
            ADDED -> addedAt > 0 && now - addedAt in 0 until ADDED_MS
        }
    }

    fun matches(card: GameCard, now: Long, together: Set<GameId>): Boolean =
        matches(card.id, card.lastPlayedAt, card.playSeconds, card.addedAt, now, together)

    companion object {
        /** Less than this and a game was only tried. */
        const val BARELY_SECONDS = 30 * 60L
        const val DUSTY_MS = 60L * 86_400_000L
        const val ADDED_MS = 14L * 86_400_000L

        /** The filters as they are kept in settings (by name), in Fuse's order; unknown names are dropped. */
        fun of(names: Collection<String>): List<SmartFilter> = entries.filter { it.name in names }
    }
}
