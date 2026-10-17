package io.github.matiyaaa.fuse.ui.shell.store.impl

import io.github.matiyaaa.fuse.data.repo.GameSummary
import io.github.matiyaaa.fuse.ui.shell.library.sameTitleKey

/**
 * The games whose files are gone and that aren't here under another version. A version whose file
 * went (an older copy replaced, a second copy deleted) while the game itself is still installed is
 * one of that game's versions, not a missing game: listing it as Missing next to the installed game
 * reads as though the game were both. Its record stays, so it comes back if its file does.
 */
internal fun stillMissing(missing: List<GameSummary>, present: List<GameSummary>): List<GameSummary> {
    if (missing.isEmpty()) return missing
    val here = HashSet<String>()
    for (g in present) if (!g.missing && !g.removed && !g.isApp) for (k in keysOf(g)) here += k
    if (here.isEmpty()) return missing
    return missing.filter { m -> keysOf(m).none { it in here } }
}

private fun keysOf(g: GameSummary): Set<String> =
    setOf(g.displayTitle, g.titles.original).mapTo(HashSet()) { g.platformId.value + "|" + sameTitleKey(it) }
