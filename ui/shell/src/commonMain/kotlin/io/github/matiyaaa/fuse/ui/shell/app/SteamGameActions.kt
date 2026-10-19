package io.github.matiyaaa.fuse.ui.shell.app

import io.github.matiyaaa.fuse.library.steam.SteamArt
import io.github.matiyaaa.fuse.library.steam.SteamDirect
import io.github.matiyaaa.fuse.library.steam.SteamGameAction
import io.github.matiyaaa.fuse.library.steam.SteamGameEntry
import io.github.matiyaaa.fuse.library.steam.SteamHealthIssue
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
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

/** Direct entries are chosen for every game, and Steam here can have them. */
private fun AppState.directChosen(): Boolean = store.prefs.value.steam.directEntries && platform.steam?.directEntries == true

/** How [card]'s entry starts: its own choice, else the one for every game. */
private fun AppState.startsDirect(card: GameCard): Boolean =
    platform.steam?.directEntries == true && (store.prefs.value.steam.startOverrides[card.id.value] ?: store.prefs.value.steam.directEntries)

/**
 * Game options, Steam Entry: how this game's entry starts, Fast Start or straight in its emulator,
 * or as for every game. With its entry in Steam already, it is brought up to date after asking.
 */
internal fun AppState.steamStartPicker(card: GameCard) {
    val steam = platform.steam ?: return
    closeOverlays()
    val own = store.prefs.value.steam.startOverrides[card.id.value]
    val every = if (store.prefs.value.steam.directEntries) "Straight in the Emulator" else "Fuse Fast Start"
    fun pick(value: Boolean?) {
        choice = null
        scope.launch {
            store.updatePrefs { p ->
                val next = if (value == null) p.steam.startOverrides - card.id.value else p.steam.startOverrides + (card.id.value to value)
                p.copy(steam = p.steam.copy(startOverrides = next))
            }
            if (card.id.value in steamGameIds) addToSteam(listOf(card), card.title)
        }
    }
    choice = ChoiceSpec(
        title = "Steam Entry",
        icon = FuseIcons.Rocket,
        message = "How ${card.title} starts from Steam. Fast Start runs Fuse with no window, so saves, play time and profiles are as in Fuse; Direct starts its emulator itself, as Play would.",
        options = listOf(
            MenuAction("start.every", "As for Every Game", FuseIcons.Layers, detail = every, trailing = Trailing.Check(own == null), onSelect = { pick(null) }),
            MenuAction("start.fast", "Fuse Fast Start", FuseIcons.Zap, trailing = Trailing.Check(own == false), section = "Just this game", onSelect = { pick(false) }),
            MenuAction("start.direct", "Straight in the Emulator", FuseIcons.Joystick, trailing = Trailing.Check(own == true), section = "Just this game", onSelect = { pick(true) }),
        ),
    )
}

/**
 * Offered once, after the first games the person put in Steam themselves: Keep Steam in Step,
 * which is off until they say yes.
 */
private fun AppState.offerKeepInStep() {
    val prefs = store.prefs.value.steam
    if (prefs.automatic || prefs.automaticOffered) return
    scope.launch { store.updatePrefs { p -> p.copy(steam = p.steam.copy(automaticOffered = true)) } }
    choice = ChoiceSpec(
        title = "Keep Steam in Step?",
        icon = FuseIcons.RefreshCcw,
        message = "Games that join your library from now on can go into Steam by themselves, and Fuse's entries for games that leave it come out. Games already in Fuse aren't added by it. You can change this in Settings, Library, Steam.",
        options = listOf(
            MenuAction("instep.on", "Keep in Step", FuseIcons.RefreshCcw, detail = "New games go into Steam by themselves", onSelect = {
                choice = null
                scope.launch { store.updatePrefs { p -> p.copy(steam = p.steam.copy(automatic = true)) } }
            }),
            MenuAction("instep.no", "Not Now", FuseIcons.Close, detail = "Games go into Steam only when you add them", onSelect = { choice = null }),
        ),
    )
}

/**
 * How the person's own shortcuts start each system's games ([bySystem], by the name its
 * collection has), with each game's files, so a Direct entry can start the same way.
 */
internal class SteamWays(val bySystem: Map<String, io.github.matiyaaa.fuse.library.steam.SteamHabit>, val files: Map<Long, Set<String>>)

/**
 * What [SteamWays] the person's own shortcuts here show, read from them now: null where Steam can't
 * have Direct entries, where they turned Start Them as My Shortcuts Do off, or where none is learned.
 */
internal suspend fun AppState.steamWays(): SteamWays? {
    val steam = platform.steam ?: return null
    if (!steam.directEntries || !store.prefs.value.steam.asTheirShortcuts) return null
    val files = runCatching { store.library.gameFiles() }.getOrDefault(emptyMap()).mapValues { it.value.value }
    if (files.isEmpty()) return null
    val names = store.library.platforms.value.associate { it.platform.id to it.platform.name }
    val systems = store.library.games(GameQuery(includeHidden = true)).first().mapNotNull { c -> names[c.platformId]?.let { c.id.value to it } }.toMap()
    val habits = runCatching { steam.habits(files, systems) }.getOrDefault(emptyList())
    if (habits.isEmpty()) return null
    return SteamWays(habits.associateBy { it.system }, files.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() })
}

/** Fuse's own Frame Generation variables, the only ones of a game's command that go on a Direct entry written the person's way. */
private val FRAME_VARIABLES = io.github.matiyaaa.fuse.launch.FrameGeneration.env(io.github.matiyaaa.fuse.launch.FrameGeneration.MULTIPLIERS.first()).keys

/**
 * [entry] starting the way the person chose: with [direct], its emulator's own command, as Play
 * would start it, or as their own shortcuts start its system's games ([ways]) when Fuse learned
 * that; a game that can't start on its own keeps Fast Start, and [why] gets the reason.
 */
private suspend fun AppState.startingAsChosen(entry: SteamGameEntry, everyGame: Boolean, why: MutableMap<Long, String>? = null, ways: SteamWays? = null): SteamGameEntry {
    // A game's own choice wins over the one for every game, where Steam can have Direct entries.
    val direct = platform.steam?.directEntries == true && (store.prefs.value.steam.startOverrides[entry.gameId] ?: everyGame)
    if (!direct) return entry
    return when (val c = runCatching { store.library.directCommand(io.github.matiyaaa.fuse.model.GameId(entry.gameId)) }.getOrElse { DirectCommand.Unavailable("It can't start here") }) {
        is DirectCommand.Ready -> {
            // Their way needs the game's file in it, as Fuse's own command names it.
            val habit = ways?.bySystem?.get(entry.collection)
            val file = habit?.let { c.argv.drop(1).firstOrNull { a -> a in ways.files[entry.gameId].orEmpty() } }
            entry.copy(
                direct = if (habit != null && file != null) SteamDirect(c.argv, c.workingDir, c.env.filterKeys { it in FRAME_VARIABLES }, habit.forGame(file))
                else SteamDirect(c.argv, c.workingDir, c.env),
            )
        }
        is DirectCommand.Unavailable -> entry.also { why?.set(it.gameId, c.why) }
    }
}

/**
 * Each of [plan]'s new and changed entries as it will start ([starts]: what Steam runs, from where,
 * with which launch options), before anything changes. Back (or choosing one) goes to [back].
 */
private fun AppState.eachEntry(plan: List<io.github.matiyaaa.fuse.library.steam.SteamGamePlanItem>, starts: Map<Long, Triple<String, String, String>>, back: () -> Unit) {
    val shown = plan.filter { it.action == SteamGameAction.CREATE || it.action == SteamGameAction.UPDATE }
    choice = ChoiceSpec(
        title = "Each Entry",
        icon = FuseIcons.ListChecks,
        message = "What Steam starts for each, in which folder and with which launch options. Nothing has changed yet.",
        options = shown.map { item ->
            val (exe, folder, options) = starts[item.entry.gameId] ?: Triple("", "", "")
            MenuAction(
                "steam.each.${item.entry.gameId}", item.entry.name, if (item.entry.direct != null) FuseIcons.Joystick else FuseIcons.Zap,
                detail = listOf("$exe $options".trim(), "In $folder").filter { it.isNotBlank() && it != "In " }.joinToString("\n"),
                onSelect = back,
            )
        },
        onBack = back,
    )
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
        val why = HashMap<Long, String>()
        val ways = steamWays()
        val pairs = games.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, directChosen(), why, ways) to art }
        // Direct for this add when any of them was to start straight in its emulator.
        val direct = games.any { startsDirect(it) }
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
                .onSuccess { toasts.show(it, icon = FuseIcons.Gamepad); if (create.isNotEmpty()) offerKeepInStep() }
                .onFailure { toasts.show(it.message ?: "Couldn't add them to Steam", ToastKind.ERROR) }
            refreshSteamGames()
        }
        val theirWay = plan.count { it.entry.direct?.written != null }
        val how = if (direct) {
            "Each starts straight in its emulator, as Play starts it, with Fuse's own art. Fuse isn't there while it runs, " +
                "so it doesn't put saves in place or send them, count play time or follow profiles for these." +
                if (theirWay > 0) " ${if (theirWay == 1) "One starts" else "$theirWay start"} as your own shortcuts start its system's games." else ""
        } else {
            "Each starts with Fuse Fast Start: no Fuse window, with its emulator, saves and play time as in Fuse, and Fuse's own art."
        }
        val message = (lines + listOf("$how Systems become Steam collections, and Settings, Library, Steam can undo it.")).joinToString("\n\n")
        // How each will start, for the person to see first.
        val starts = runCatching { steam.entryStarts(plan.filter { it.action == SteamGameAction.CREATE || it.action == SteamGameAction.UPDATE }.map { it.entry }) }.getOrDefault(emptyMap())
        lateinit var ask: () -> Unit
        val each = MenuAction("steam.games.each", "See Each Entry", FuseIcons.ListChecks, detail = "What Steam starts for each, from where, with which launch options", onSelect = { eachEntry(plan, starts) { ask() } })
            .takeIf { starts.isNotEmpty() }
        // Steam closed, or open with the Fuse Decky plugin there to put them in through Steam itself.
        if (!open || steam.changesLive) {
            val live = if (open) "\n\nSteam is open, so the Fuse plugin puts them in through Steam now; their collections follow when Steam next closes." else ""
            ask = {
                choice = ChoiceSpec(
                    title = "Add $what to Steam?",
                    message = message + live,
                    icon = FuseIcons.Gamepad,
                    options = listOfNotNull(
                        MenuAction("steam.games.add", "Add", FuseIcons.Plus, detail = if (create.size + update.size == 1) "One entry" else "${create.size + update.size} entries", onSelect = {
                            choice = null
                            add { steam.addGames(games, art, collections = true) }
                        }),
                        each,
                    ),
                )
            }
            ask()
            return@launch
        }
        // Steam is open: it writes its library when it quits. Restart it now (asked here, never
        // during a game), or wait for it to close by itself.
        ask = {
            choice = ChoiceSpec(
                title = "Add $what to Steam?",
                message = message + "\n\nSteam is open." + if (steam.closesFuse) " Restarting it closes Fuse too; Fuse opens again right here." else "",
                icon = FuseIcons.Gamepad,
                options = listOfNotNull(
                    MenuAction("steam.games.restart", "Restart Steam and Add", FuseIcons.RotateCcw, detail = "Never while a game is running", onSelect = {
                        choice = null
                        add { steam.addGamesRestartingSteam(games, art, collections = true) }
                    }),
                    MenuAction("steam.games.wait", "Add When Steam Closes", FuseIcons.Clock, detail = "Steam isn't closed for it", onSelect = {
                        choice = null
                        add { steam.addGames(games, art, collections = true) }
                    }),
                    each,
                ),
            )
        }
        ask()
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

/** What [steamIssues] found, with the library it looked against. */
private class SteamLook(val library: List<GameCard>, val issues: List<SteamHealthIssue>)

/**
 * What is wrong with the entries Fuse made in Steam, against the library now: each Direct entry
 * compared with the entry its game would get written now (its emulator updated or moved, its game
 * moved, its way of starting changed), and games whose files can't be reached now said as such.
 */
private suspend fun AppState.steamIssues(): SteamLook? {
    val steam = platform.steam ?: return null
    val library = store.library.games(GameQuery(includeHidden = true)).first()
    val ids = library.map { it.id.value }.toSet()
    val direct = runCatching { steam.gameStarts() }.getOrDefault(emptyMap()).filterValues { it == io.github.matiyaaa.fuse.library.steam.SteamStart.DIRECT }.keys
    val wanted = if (direct.isEmpty()) emptyMap() else {
        val everyGame = directChosen()
        val ways = steamWays()
        library.filter { it.id.value in direct }.associate { c -> c.id.value to startingAsChosen(steamEntry(c).first, everyGame, ways = ways) }
    }
    val unavailable = library.filter { it.dimmed }.map { it.id.value }.toSet()
    return SteamLook(library, runCatching { steam.gamesHealth(ids, wanted, unavailable) }.getOrDefault(emptyList()))
}

/**
 * Puts what [steamIssues] finds in System health, where Put Them Right opens [checkSteamGames];
 * nothing when all is well (or no entry of Fuse's is in Steam).
 */
internal suspend fun AppState.reportSteamHealth() {
    if (platform.steam == null) return
    val issues = steamIssues()?.issues.orEmpty()
    val fixable = issues.filter { it.kind != SteamHealthIssue.Kind.GAME_UNAVAILABLE }
    val waiting = issues.filter { it.kind == SteamHealthIssue.Kind.GAME_UNAVAILABLE }
    store.health.setFound(
        "steam",
        listOfNotNull(
            fixable.takeIf { it.isNotEmpty() }?.let { found ->
                io.github.matiyaaa.fuse.ui.shell.store.HealthIssue(
                    "steam.entries",
                    io.github.matiyaaa.fuse.ui.shell.store.Problem(
                        title = if (found.size == 1) "A Steam entry needs putting right" else "${found.size} Steam entries need putting right",
                        message = "${found.joinToString(", ") { it.name }}. Each is put right where it is, keeping its art, play time and collections.",
                        reassurance = null,
                        actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.PutSteamRight()),
                    ),
                )
            },
            waiting.takeIf { it.isNotEmpty() }?.let { found ->
                io.github.matiyaaa.fuse.ui.shell.store.HealthIssue(
                    "steam.unavailable",
                    io.github.matiyaaa.fuse.ui.shell.store.Problem(
                        title = if (found.size == 1) "A game in Steam can't start now" else "${found.size} games in Steam can't start now",
                        message = "${found.joinToString(", ") { it.name }}: their files can't be reached, so their Steam entries can't start them until the drive is back.",
                        kind = io.github.matiyaaa.fuse.ui.shell.store.ProblemKind.DRIVE,
                        severity = io.github.matiyaaa.fuse.ui.shell.store.Severity.INFO,
                        reassurance = null,
                        actions = listOf(io.github.matiyaaa.fuse.ui.shell.store.ProblemAction.CheckDrives()),
                    ),
                )
            },
        ),
    )
}

/**
 * Looks over the entries Fuse made in Steam: games no longer in Fuse, entries that start an old
 * copy of Fuse or an emulator that moved, Direct entries that no longer start their game as Play
 * would, and games in there twice. Offers to put right only what it found, each entry where it is.
 */
internal fun AppState.checkSteamGames() {
    val steam = platform.steam ?: return
    scope.launch {
        val look = steamIssues() ?: return@launch
        val library = look.library
        val ids = library.map { it.id.value }.toSet()
        val issues = look.issues
        val waiting = issues.filter { it.kind == SteamHealthIssue.Kind.GAME_UNAVAILABLE }
        if (issues.size == waiting.size) {
            toasts.show(
                if (waiting.isEmpty()) "Fuse's games in Steam are all as they should be"
                else "Fuse's games in Steam are as they should be. ${waiting.joinToString(", ") { it.name }} can't start until their drive is back",
                icon = FuseIcons.CircleCheck,
            )
            return@launch
        }
        val gone = issues.filter { it.kind == SteamHealthIssue.Kind.GAME_GONE }
        val fix = issues.filter { it.kind != SteamHealthIssue.Kind.GAME_GONE && it.kind != SteamHealthIssue.Kind.GAME_UNAVAILABLE }
        fun named(kind: SteamHealthIssue.Kind) = fix.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.joinToString(", ") { i -> i.name }
        val lines = listOfNotNull(
            gone.takeIf { it.isNotEmpty() }?.let { "No longer in Fuse: ${it.joinToString(", ") { i -> i.name }}." },
            named(SteamHealthIssue.Kind.PROGRAM_MOVED)?.let { "Start an older copy of Fuse: $it." },
            named(SteamHealthIssue.Kind.DUPLICATE)?.let { "In Steam twice: $it." },
            named(SteamHealthIssue.Kind.THROUGH_WINDOW)?.let { "Open Fuse's window first, where Fast Start starts the game at once: $it." },
            named(SteamHealthIssue.Kind.EMULATOR_GONE)?.let { "Start an emulator that isn't there any more: $it." },
            named(SteamHealthIssue.Kind.OUT_OF_DATE)?.let { "Don't start the game as Play would now (its emulator updated or moved, the game moved, or how it starts changed): $it." },
            waiting.takeIf { it.isNotEmpty() }?.let { "Can't start until their drive is back, and stay as they are: ${it.joinToString(", ") { i -> i.name }}." },
        )
        confirm = ConfirmSpec(
            title = "Put Steam's entries right?",
            message = (lines + "Only Fuse's own entries change, each where it is, so its art, play time and collections stay. Settings, Library, Steam can undo it.").joinToString("\n\n"),
            confirmLabel = "Put right",
        ) {
            scope.launch {
                // Entries for games Fuse no longer has go, doubles go (the first stays), and entries
                // that start an older Fuse, an older way, a missing emulator or an old command are
                // written again where they are: a Direct entry with the game's command now, else with Fast Start.
                val again = setOf(SteamHealthIssue.Kind.PROGRAM_MOVED, SteamHealthIssue.Kind.THROUGH_WINDOW, SteamHealthIssue.Kind.EMULATOR_GONE, SteamHealthIssue.Kind.OUT_OF_DATE)
                val moved = library.filter { c -> fix.any { it.kind in again && it.gameId == c.id.value } }
                val direct = directChosen()
                val ways = steamWays()
                val pairs = moved.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, direct, ways = ways) to art }
                steam.repairGames(ids, pairs.map { it.first }, pairs.associate { it.first.gameId to it.second })
                    .onSuccess { toasts.show(it, icon = FuseIcons.CircleCheck) }
                    .onFailure { toasts.show(it.message ?: "Couldn't change Steam's library", ToastKind.ERROR) }
                refreshSteamGames()
                reportSteamHealth()
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
                val ways = steamWays()
                val takes = chosen.map { (learned, card) -> learned to startingAsChosen(steamEntry(card).first, direct, ways = ways) }
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
 * Settings, Library, Steam, Learned From Your Shortcuts: how the person's own shortcuts in Steam
 * start each system's games, which Direct entries for those systems copy (their program, folder
 * and launch options as they write them), with a switch to start them as Play would instead.
 * Frame generation their shortcuts add is offered as Fuse's own setting for that system, never copied.
 */
internal fun AppState.learnedWaysSheet() {
    val steam = platform.steam ?: return
    scope.launch {
        val files = runCatching { store.library.gameFiles() }.getOrDefault(emptyMap()).mapValues { it.value.value }
        val names = store.library.platforms.value.associate { it.platform.id to it.platform.name }
        val systems = store.library.games(GameQuery(includeHidden = true)).first().mapNotNull { c -> names[c.platformId]?.let { c.id.value to it } }.toMap()
        val habits = if (files.isEmpty()) emptyList() else runCatching { steam.habits(files, systems) }.getOrDefault(emptyList())
        val ids = names.entries.associate { (id, name) -> name to id }
        val frames = platform.features.frameGeneration && store.library.frameGenerationReady()
        fun useFrames(h: io.github.matiyaaa.fuse.library.steam.SteamHabit, back: () -> Unit) {
            val wrapper = h.frameGeneration ?: return
            val id = ids[h.system] ?: return
            val multiplier = wrapper.multiplier?.takeIf { it in io.github.matiyaaa.fuse.launch.FrameGeneration.MULTIPLIERS } ?: io.github.matiyaaa.fuse.launch.FrameGeneration.MULTIPLIERS.first()
            confirm = ConfirmSpec(
                title = "Frame Generation for ${h.system}?",
                message = "Your shortcuts start these with ${wrapper.words}. Fuse doesn't copy that into its entries: its own Frame Generation does the same, at ${multiplier}x, " +
                    "for ${h.system} games started from Fuse, by Fast Start and by Direct entries. A game can have its own in its options.",
                confirmLabel = "Turn On",
            ) {
                scope.launch {
                    store.settings.set(io.github.matiyaaa.fuse.model.ScopedSettings.FrameGeneration, io.github.matiyaaa.fuse.model.ScopeRef.platform(id), multiplier)
                    toasts.show("Frame Generation is on for ${h.system}", icon = FuseIcons.Layers2)
                    back()
                }
            }
        }
        fun show() {
            val on = store.prefs.value.steam.asTheirShortcuts
            choice = ChoiceSpec(
                title = "Learned From Your Shortcuts",
                icon = FuseIcons.Import,
                message = if (habits.isEmpty()) {
                    "None of your own shortcuts in Steam starts a game you have in Fuse, so there's nothing to learn from yet. Direct entries start as Play would."
                } else {
                    "How your own shortcuts in Steam start each system's games. Direct entries for these systems start the same way, with the program, folder and launch options as you write them."
                },
                options = buildList {
                    if (habits.isEmpty()) return@buildList
                    add(MenuAction(
                        "steam.ways.on", "Start Them as My Shortcuts Do", FuseIcons.Joystick,
                        detail = if (on) "Direct entries for these systems copy your way" else "Off. Direct entries start as Play would",
                        trailing = Trailing.Switch(on),
                        onSelect = {
                            scope.launch {
                                store.updatePrefs { p -> p.copy(steam = p.steam.copy(asTheirShortcuts = !on)) }
                                show()
                            }
                        },
                    ))
                    for (h in habits) {
                        val from = if (h.shortcuts == 1) "one of your shortcuts" else "${h.shortcuts} of your shortcuts"
                        val options = (h.before + "<game>" + h.after).trim()
                        val frame = h.frameGeneration?.let { if (frames) " With frame generation, which Fuse's own setting does: select to turn it on." else " With frame generation, which Fuse's own setting does once lsfg-vk is set up here." }.orEmpty()
                        add(MenuAction(
                            "steam.ways.${h.system}", h.system, FuseIcons.Gamepad,
                            detail = "${h.program} $options, from $from.$frame",
                            section = "By system",
                            onSelect = if (h.frameGeneration != null && frames) { { useFrames(h) { show() } } } else { {} },
                        ))
                    }
                },
            )
        }
        show()
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
                val ways = steamWays()
                val pairs = joined.map { steamEntry(it) }.map { (entry, art) -> startingAsChosen(entry, direct, ways = ways) to art }
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
