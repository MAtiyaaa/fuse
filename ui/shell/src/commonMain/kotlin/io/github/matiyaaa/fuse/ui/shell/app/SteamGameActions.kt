package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamDirect
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.shell.store.DirectCommand
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Fuse's games in Steam's library: each starts with Fuse Fast Start (`--run`), so it runs with the
 * emulator, settings and saves it has in Fuse, with no window of Fuse's, and its time is counted. On
 * Linux the person can choose Direct entries instead, which start the emulator itself, as Play would
 * start it. Before anything is written Fuse says what will happen; a game another tool already put
 * in Steam is left alone; the last change can be undone from Settings, Library, Steam.
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

/** Direct entries are chosen, and Steam here can have them. */
private fun AppState.directChosen(): Boolean = store.prefs.value.steam.directEntries && platform.steam?.directEntries == true

/**
 * [entry] starting the way the person chose: with [direct], its emulator's own command, as Play
 * would start it; a game that can't start on its own keeps Fast Start, and [why] gets the reason.
 */
private suspend fun AppState.startingAsChosen(entry: SteamGameEntry, direct: Boolean, why: MutableMap<Long, String>? = null): SteamGameEntry {
    if (!direct) return entry
    return when (val c = runCatching { store.library.directCommand(io.github.matiyaaa.fuse.model.GameId(entry.gameId)) }.getOrElse { DirectCommand.Unavailable("It can't start here") }) {
        is DirectCommand.Ready -> entry.copy(direct = SteamDirect(c.argv, c.workingDir, c.env))
        is DirectCommand.Unavailable -> entry.also { why?.set(it.gameId, c.why) }
    }
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
        val direct = directChosen()
        val why = HashMap<Long, String>()
        val pairs = games.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, direct, why) to art }
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
        // Asked for Direct, but going in with Fast Start: no command of its own, or none Steam can start here.
        val fastInstead = if (direct) plan.filter { it.action != SteamGameAction.ELSEWHERE && it.entry.direct == null } else emptyList()
        val lines = listOfNotNull(
            create.takeIf { it.isNotEmpty() }?.let { "New: ${list(it)}." },
            update.takeIf { it.isNotEmpty() }?.let { "Brought up to date: ${list(it)}." },
            elsewhere.takeIf { it.isNotEmpty() }?.let { "Already in Steam from another tool, left as they are: ${list(it)}." },
            fastInstead.takeIf { it.isNotEmpty() }?.let { items ->
                val reasons = items.mapNotNull { why[it.entry.gameId] }.distinct()
                "With Fast Start instead, as they can't start on their own: ${list(items.map { it.entry.name })}" +
                    (reasons.singleOrNull()?.let { " ($it)." } ?: ".")
            },
        )
        val open = runCatching { steam.steamRunning() }.getOrDefault(false)
        // The entries as Steam here will have them (a Direct entry's program found, or Fast Start).
        val games = plan.map { it.entry }
        val art = pairs.associate { it.first.gameId to it.second }
        fun add(block: suspend () -> Result<String>) = scope.launch {
            block()
                .onSuccess { toasts.show(it, icon = FuseIcons.Gamepad) }
                .onFailure { toasts.show(it.message ?: "Couldn't add them to Steam", ToastKind.ERROR) }
            refreshSteamGames()
        }
        val how = if (direct) {
            "Each starts straight in its emulator, as Play starts it, with Fuse's own art. Fuse isn't there while it runs, " +
                "so it doesn't put saves in place or send them, count play time or follow profiles for these."
        } else {
            "Each starts with Fuse Fast Start: no Fuse window, with its emulator, saves and play time as in Fuse, and Fuse's own art."
        }
        val message = (lines + listOf("$how Systems become Steam collections, and Settings, Library, Steam can undo it.")).joinToString("\n\n")
        // Steam closed, or open with the Fuse Decky plugin there to put them in through Steam itself.
        if (!open || steam.changesLive) {
            val live = if (open) "\n\nSteam is open, so the Fuse plugin puts them in through Steam now; their collections follow when Steam next closes." else ""
            confirm = ConfirmSpec(title = "Add $what to Steam?", message = message + live, confirmLabel = "Add") { add { steam.addGames(games, art, collections = true) } }
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
        message = "Only the entries Fuse made leave Steam's library. The games stay in Fuse, and your other shortcuts stay as they are." +
            if (steam.changesLive) "" else " Close Steam first.",
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
        fun named(kind: SteamHealthIssue.Kind) = fix.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.joinToString(", ") { i -> i.name }
        val lines = listOfNotNull(
            gone.takeIf { it.isNotEmpty() }?.let { "No longer in Fuse: ${it.joinToString(", ") { i -> i.name }}." },
            named(SteamHealthIssue.Kind.PROGRAM_MOVED)?.let { "Start an older copy of Fuse: $it." },
            named(SteamHealthIssue.Kind.DUPLICATE)?.let { "In Steam twice: $it." },
            named(SteamHealthIssue.Kind.THROUGH_WINDOW)?.let { "Open Fuse's window first, where Fast Start starts the game at once: $it." },
            named(SteamHealthIssue.Kind.EMULATOR_GONE)?.let { "Start an emulator that isn't there any more: $it." },
        )
        confirm = ConfirmSpec(
            title = "Put Steam's entries right?",
            message = (lines + "Only Fuse's own entries change. Settings, Library, Steam can undo it.").joinToString("\n\n"),
            confirmLabel = "Put right",
        ) {
            scope.launch {
                // Entries for games Fuse no longer has go, doubles go (the first stays), and entries
                // that start an older Fuse, an older way or a missing emulator are written again where
                // they are: a Direct entry with the game's command now, else with Fast Start.
                val again = setOf(SteamHealthIssue.Kind.PROGRAM_MOVED, SteamHealthIssue.Kind.THROUGH_WINDOW, SteamHealthIssue.Kind.EMULATOR_GONE)
                val moved = library.filter { c -> fix.any { it.kind in again && it.gameId == c.id.value } }
                val direct = directChosen()
                val pairs = moved.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, direct) to art }
                steam.repairGames(ids, pairs.map { it.first }, pairs.associate { it.first.gameId to it.second })
                    .onSuccess { toasts.show(it, icon = FuseIcons.CircleCheck) }
                    .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
                refreshSteamGames()
            }
        }
    }
}

/**
 * Looks for shortcuts other tools put in Steam for Fuse's games (Steam ROM Manager, EmuDeck, a
 * script, the person), found by the game's own file in their command, and offers to take them over:
 * Fuse's entry in their place, starting as the person chose, with their name, art and collections
 * kept. Or to leave them, all or one by one.
 */
internal fun AppState.learnSteamShortcuts() {
    val steam = platform.steam ?: return
    scope.launch {
        val files = runCatching { store.library.gameFiles() }.getOrDefault(emptyMap()).mapValues { it.value.value }
        val found = runCatching { steam.learnShortcuts(files) }.getOrDefault(emptyList())
        val cards = if (found.isEmpty()) emptyMap() else store.library.games(GameQuery(includeHidden = true)).first().associateBy { it.id.value }
        val pairs = found.mapNotNull { l -> cards[l.gameId]?.let { l to it } }
        if (pairs.isEmpty()) {
            toasts.show("No shortcuts from other tools start your games in Fuse", icon = FuseIcons.CircleCheck)
            return@launch
        }
        val how = if (directChosen()) "starts straight in its emulator, as Play starts it" else "starts with Fuse Fast Start, with saves and play time as in Fuse"
        fun take(chosen: List<Pair<io.github.matiyaaa.fuse.library.steam.SteamLearned, GameCard>>) {
            if (chosen.isEmpty()) return
            scope.launch {
                val direct = directChosen()
                val takes = chosen.map { (learned, card) -> learned to startingAsChosen(steamEntry(card).first, direct) }
                steam.takeOver(takes)
                    .onSuccess { toasts.show(it, icon = FuseIcons.Gamepad) }
                    .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
                refreshSteamGames()
            }
        }
        fun oneByOne(at: Int, chosen: List<Pair<io.github.matiyaaa.fuse.library.steam.SteamLearned, GameCard>>) {
            if (at >= pairs.size) return take(chosen)
            val (learned, card) = pairs[at]
            choice = ChoiceSpec(
                title = learned.name,
                icon = FuseIcons.Import,
                message = "Started by ${learned.program}, with ${card.title}'s file:\n${learned.path}\n\n${at + 1} of ${pairs.size}.",
                options = listOf(
                    MenuAction("steam.learn.take", "Take Over", FuseIcons.Import, detail = "Fuse's entry in its place. Its name, art and collections stay", onSelect = { choice = null; oneByOne(at + 1, chosen + pairs[at]) }),
                    MenuAction("steam.learn.leave", "Leave It", FuseIcons.ChevronRight, detail = "It stays as it is", onSelect = { choice = null; oneByOne(at + 1, chosen) }),
                    MenuAction("steam.learn.stop", "Stop Here", FuseIcons.Close, detail = if (chosen.isEmpty()) "Nothing changes" else "Only the ones chosen so far change", onSelect = { choice = null; take(chosen) }),
                ),
            )
        }
        fun list(names: List<String>) = names.take(PREVIEW_NAMES).joinToString(", ") + if (names.size > PREVIEW_NAMES) " and ${names.size - PREVIEW_NAMES} more" else ""
        val n = pairs.size
        choice = ChoiceSpec(
            title = "Shortcuts From Other Tools",
            icon = FuseIcons.Import,
            message = "${if (n == 1) "This starts a game" else "These start games"} you have in Fuse, from Steam, without Fuse: ${list(pairs.map { it.first.name })}. " +
                "Taken over, each is Fuse's entry in the same place and $how. Its name, art and collections stay, and Settings, Library, Steam can undo it. Close Steam first.",
            options = listOfNotNull(
                MenuAction("steam.learn.all", if (n == 1) "Take It Over" else "Take Over All $n", FuseIcons.Import, detail = "Fuse's entries in their places", onSelect = { choice = null; take(pairs) }),
                MenuAction("steam.learn.each", "One by One", FuseIcons.ListChecks, detail = "Take over or leave each", onSelect = { choice = null; oneByOne(0, emptyList()) }).takeIf { n > 1 },
                MenuAction("steam.learn.none", "Leave Them", FuseIcons.Close, detail = "Nothing changes", onSelect = { choice = null }),
            ),
        )
    }
}

/**
 * Fuse's recent changes to Steam's games, newest first, each with what it did and when. Choosing one
 * puts Steam's library back as it was before it, and every change after it with it, after asking.
 */
internal fun AppState.steamUndoHistory() {
    val steam = platform.steam ?: return
    scope.launch {
        val steps = runCatching { steam.undoSteps() }.getOrDefault(emptyList())
        if (steps.isEmpty()) {
            toasts.show("There's no change of Fuse's to undo")
            return@launch
        }
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val offset = io.github.matiyaaa.fuse.ui.shell.sync.localOffsetMillis(now)
        fun undo(index: Int) {
            val step = steps[index]
            val later = index
            confirm = ConfirmSpec(
                title = "Undo \"${step.what}\"?",
                message = "Steam's library goes back to how it was before it." +
                    (if (later == 0) "" else if (later == 1) " The change after it is undone too." else " The $later changes after it are undone too.") +
                    " Only Fuse's own entries change. Close Steam first.",
                confirmLabel = "Undo",
            ) {
                scope.launch {
                    steam.undoTo(step.id)
                        .onSuccess { toasts.show(it, icon = FuseIcons.Undo) }
                        .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
                    refreshSteamGames()
                }
            }
        }
        choice = ChoiceSpec(
            title = "Undo a Change",
            icon = FuseIcons.Undo,
            message = "Fuse's recent changes to Steam's games, newest first.",
            options = steps.mapIndexed { i, step ->
                MenuAction(
                    "steam.undo.${step.id}", step.what, FuseIcons.History,
                    detail = io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords.relative(step.at, now, offset).replaceFirstChar { it.uppercase() },
                    onSelect = { choice = null; undo(i) },
                )
            },
        )
    }
}

/**
 * Keep Steam in Step, while the person has it on (off by default): games that join the library go
 * into Steam by themselves, starting as How Steam Starts Them says, with their art and collections,
 * and Fuse's entries for games that leave the library come out. Games already in the library when
 * it is turned on aren't added by it. Steam is never closed for it: with Steam open, the Fuse Decky
 * plugin puts them in, or they go in when Steam next closes; a removal Steam can't take now is made
 * the next time Fuse starts.
 */
internal suspend fun AppState.keepSteamInStep() {
    val steam = platform.steam ?: return
    store.prefs.map { it.steam.automatic }.distinctUntilChanged().collectLatest { on ->
        if (!on) return@collectLatest
        var known: Set<Long>? = null
        store.library.games(GameQuery(includeHidden = true))
            .map { list -> list.filter { !it.isApp }.associateBy { it.id.value } }
            .conflate()
            .collect { cards ->
                val ids = cards.keys
                val before = known
                known = ids
                val inSteam = runCatching { steam.gamesInSteam() }.getOrDefault(emptySet())
                // Entries for games no longer in Fuse, every time (also those left from before).
                val gone = inSteam - ids
                if (gone.isNotEmpty() && (!runCatching { steam.steamRunning() }.getOrDefault(true) || steam.changesLive)) {
                    steam.removeGames(gone).onSuccess { refreshSteamGames() }
                }
                if (before == null) return@collect
                val joined = (ids - before - inSteam).mapNotNull(cards::get).filter { !it.dimmed }
                if (joined.isEmpty()) return@collect
                val direct = directChosen()
                val pairs = joined.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, direct) to art }
                steam.addGames(pairs.map { it.first }, pairs.associate { it.first.gameId to it.second }, collections = true)
                    .onSuccess { toasts.show(if (joined.size == 1) "${joined.first().title} is in Steam too" else "${joined.size} new games are in Steam too", icon = FuseIcons.Gamepad) }
                refreshSteamGames()
                // A burst of new games (a scan) goes in as one change.
                delay(STEP_SETTLE_MS)
            }
    }
}

/** How long Keep Steam in Step lets the library settle between changes it makes. */
private const val STEP_SETTLE_MS = 5_000L

private const val PREVIEW_NAMES = 4
