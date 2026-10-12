package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Fuse's games in Steam's library: each starts through Fuse (`--play`), so it runs with the emulator,
 * settings and saves it has in Fuse, and its time is counted. Before anything is written Fuse says
 * what will happen; a game another tool already put in Steam is left alone; the last change can be
 * undone from Settings, Library, Steam.
 */

/** A game as Steam's library would have it, and the pictures Fuse already keeps for it. */
internal fun AppState.steamEntry(card: GameCard): Pair<SteamGameEntry, SteamArt> {
    val system = store.library.platforms.value.firstOrNull { it.platform.id == card.platformId }?.platform?.name
    // Only files Fuse keeps: nothing is fetched again for Steam.
    fun file(model: Any?): String? = (model as? String)?.takeIf { !it.contains("://") }
    val art = SteamArt(
        portrait = file(card.art.boxart),
        wide = file(card.art.grid),
        hero = file(card.art.hero ?: card.art.screenshot),
        logo = file(card.art.logo),
        icon = file(card.art.icon ?: card.art.square),
    )
    return SteamGameEntry(card.id.value, card.title, system) to art
}

/** The games Fuse put in Steam here, by id, as last read. */
internal suspend fun AppState.refreshSteamGames() {
    steamGameIds = platform.steam?.let { runCatching { it.gamesInSteam() }.getOrNull() } ?: emptySet()
}

/**
 * Puts [cards] in Steam's library, after saying what will happen to them: which are new, which are
 * brought up to date and which another tool already put there (those stay as they are). [what]
 * names them in the question ("Metroid Fusion", "your Game Boy Advance games").
 */
internal fun AppState.addToSteam(cards: List<GameCard>, what: String) {
    val steam = platform.steam ?: return
    val games = cards.filter { !it.isApp && !it.dimmed }
    if (games.isEmpty()) {
        toasts.show("Nothing here can go into Steam")
        return
    }
    scope.launch {
        val pairs = games.map { steamEntry(it) }
        val plan = runCatching { steam.planGames(pairs.map { it.first }) }.getOrNull()
        if (plan == null) {
            toasts.show("Steam's library couldn't be read. Sign in to Steam once, then try again.", ToastKind.ERROR)
            return@launch
        }
        fun of(a: SteamGameAction) = plan.filter { it.action == a }.map { it.entry.name }
        val create = of(SteamGameAction.CREATE)
        val update = of(SteamGameAction.UPDATE)
        val elsewhere = of(SteamGameAction.ELSEWHERE)
        if (create.isEmpty() && update.isEmpty()) {
            toasts.show(if (elsewhere.isNotEmpty()) "Already in Steam from another tool. Left as it is" else "Already in Steam's library", icon = FuseIcons.Gamepad)
            return@launch
        }
        fun list(names: List<String>) = names.take(PREVIEW_NAMES).joinToString(", ") + if (names.size > PREVIEW_NAMES) " and ${names.size - PREVIEW_NAMES} more" else ""
        val lines = listOfNotNull(
            create.takeIf { it.isNotEmpty() }?.let { "New: ${list(it)}." },
            update.takeIf { it.isNotEmpty() }?.let { "Brought up to date: ${list(it)}." },
            elsewhere.takeIf { it.isNotEmpty() }?.let { "Already in Steam from another tool, left as they are: ${list(it)}." },
        )
        val open = runCatching { steam.steamRunning() }.getOrDefault(false)
        val games = pairs.map { it.first }
        val art = pairs.associate { it.first.gameId to it.second }
        fun add(block: suspend () -> Result<String>) = scope.launch {
            block()
                .onSuccess { toasts.show(it, icon = FuseIcons.Gamepad) }
                .onFailure { toasts.show(it.message ?: "Couldn't add them to Steam", ToastKind.ERROR) }
            refreshSteamGames()
        }
        val message = (
            lines + listOf(
                "Each starts through Fuse, with its emulator, saves and play time as in Fuse, and Fuse's own art. " +
                    "Systems become Steam collections, and Settings, Library, Steam can undo it.",
            )
            ).joinToString("\n\n")
        if (!open) {
            confirm = ConfirmSpec(title = "Add $what to Steam?", message = message, confirmLabel = "Add") { add { steam.addGames(games, art, collections = true) } }
            return@launch
        }
        // Steam is open: it writes its library when it quits. Restart it now (asked here, never
        // during a game), or wait for it to close by itself.
        choice = ChoiceSpec(
            title = "Add $what to Steam?",
            message = message + "\n\nSteam is open." + if (steam.closesFuse) " Restarting it closes Fuse too; Fuse opens again right here." else "",
            icon = FuseIcons.Gamepad,
            options = listOf(
                MenuAction("steam.games.restart", "Restart Steam and Add", FuseIcons.RotateCcw, detail = "Never while a game is running", onSelect = {
                    choice = null
                    add { steam.addGamesRestartingSteam(games, art, collections = true) }
                }),
                MenuAction("steam.games.wait", "Add When Steam Closes", FuseIcons.Clock, detail = "Steam isn't closed for it", onSelect = {
                    choice = null
                    add { steam.addGames(games, art, collections = true) }
                }),
            ),
        )
    }
}

/** Takes Fuse's entries for [cards] out of Steam's library, after asking. Other shortcuts stay. */
internal fun AppState.removeFromSteam(cards: List<GameCard>, what: String) {
    val steam = platform.steam ?: return
    confirm = ConfirmSpec(
        title = "Remove $what from Steam?",
        message = "Only the entries Fuse made leave Steam's library. The games stay in Fuse, and your other shortcuts stay as they are. Close Steam first.",
        confirmLabel = "Remove",
        destructive = true,
    ) {
        scope.launch {
            steam.removeGames(cards.map { it.id.value }.toSet())
                .onSuccess { toasts.show(it) }
                .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
            refreshSteamGames()
        }
    }
}

/**
 * Looks over the entries Fuse made in Steam: games no longer in Fuse, entries that start an old
 * copy of Fuse and games in there twice. Offers to put right only what it found.
 */
internal fun AppState.checkSteamGames() {
    val steam = platform.steam ?: return
    scope.launch {
        val library = store.library.games(GameQuery(includeHidden = true)).first()
        val ids = library.map { it.id.value }.toSet()
        val issues = runCatching { steam.gamesHealth(ids) }.getOrDefault(emptyList())
        if (issues.isEmpty()) {
            toasts.show("Fuse's games in Steam are all as they should be", icon = FuseIcons.CircleCheck)
            return@launch
        }
        val gone = issues.filter { it.kind == SteamHealthIssue.Kind.GAME_GONE }
        val fix = issues.filter { it.kind != SteamHealthIssue.Kind.GAME_GONE }
        val lines = listOfNotNull(
            gone.takeIf { it.isNotEmpty() }?.let { "No longer in Fuse: ${it.joinToString(", ") { i -> i.name }}." },
            fix.filter { it.kind == SteamHealthIssue.Kind.PROGRAM_MOVED }.takeIf { it.isNotEmpty() }?.let { "Start an older copy of Fuse: ${it.joinToString(", ") { i -> i.name }}." },
            fix.filter { it.kind == SteamHealthIssue.Kind.DUPLICATE }.takeIf { it.isNotEmpty() }?.let { "In Steam twice: ${it.joinToString(", ") { i -> i.name }}." },
        )
        confirm = ConfirmSpec(
            title = "Put Steam's entries right?",
            message = (lines + "Only Fuse's own entries change. Settings, Library, Steam can undo it.").joinToString("\n\n"),
            confirmLabel = "Put right",
        ) {
            scope.launch {
                // Entries for games Fuse no longer has go, doubles go (the first stays), and entries
                // that start an older Fuse are brought up to date where they are.
                val moved = library.filter { c -> fix.any { it.kind == SteamHealthIssue.Kind.PROGRAM_MOVED && it.gameId == c.id.value } }
                val pairs = moved.map { steamEntry(it) }
                steam.repairGames(ids, pairs.map { it.first }, pairs.associate { it.first.gameId to it.second })
                    .onSuccess { toasts.show(it, icon = FuseIcons.CircleCheck) }
                    .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
                refreshSteamGames()
            }
        }
    }
}

private const val PREVIEW_NAMES = 4
