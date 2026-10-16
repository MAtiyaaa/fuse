package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.ExistingSave
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.SaveImportChoice
import io.github.matiyaaa.fuse.sync.SaveImportConfidence
import io.github.matiyaaa.fuse.sync.SaveImportEntry
import io.github.matiyaaa.fuse.sync.SaveImportPlan
import io.github.matiyaaa.fuse.sync.SaveImportResult
import io.github.matiyaaa.fuse.sync.SaveImportTarget
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Saves copied off a Switch (JKSV, Checkpoint), brought in one game at a time: Fuse reads the dump,
 * then for each game asks whether it is that game, then whose save it becomes (one person, several,
 * or everyone: each gets their own copy), then, when any of them already has a save of it, whether
 * to replace theirs or leave it, then shows it all once more before writing anything.
 */
internal class SwitchImport(val source: String, val path: String = "") {
    /**
     * Read straight off a card plugged into this device (Android's storage, a desktop's mounted
     * drives): it should be ejected before it comes out, or the card's own files can be left
     * half-written, the files that tell the Switch's boot loader where its emuMMC is among them.
     */
    val onCard: Boolean get() = path.replace('\\', '/').let { p ->
        (p.startsWith("/storage/") && !p.startsWith("/storage/emulated/")) || p.startsWith("/Volumes/") || p.startsWith("/media/") || p.startsWith("/run/media/") ||
            Regex("^[A-Za-z]:/").containsMatchIn(p) && !p.startsWith("C:", ignoreCase = true)
    }

    enum class Step { SCOPE, READING, GAME, PEOPLE, CHECKING, CONFLICT, REVIEW, IMPORTING, DONE }

    /**
     * One game's backups from the dump, newest first, and what the person chose for it. A game's
     * device save (one for everyone on the console) goes with it, never as a game of its own.
     */
    class Game(
        val key: String, val title: String, val card: GameCard?, val backups: List<Pair<SaveImportEntry, SaveImportTarget>>,
        val device: Pair<SaveImportEntry, SaveImportTarget>? = null,
        /** False: the game is on Fuse One but not on this device; its save is ready when it is played here. */
        val here: Boolean = true,
    ) {
        var chosen by mutableIntStateOf(0)
        var included by mutableStateOf(true)
        var people by mutableStateOf<Set<String>>(emptySet())
        /** The chosen people who already have a save of this game, and where it is. */
        var existing by mutableStateOf<Map<String, ExistingSave>>(emptyMap())
        /** True: their saves are replaced (each kept as a backup first); false: they keep theirs and get no copy. */
        var replace by mutableStateOf<Boolean?>(null)
        /** The people left out because they chose to keep their own save. */
        var kept by mutableStateOf<Set<String>>(emptySet())
        /** The person using Fuse's own save of this game, when they have one (null until known, or none). */
        var mine by mutableStateOf<ExistingSave?>(null)
        val entry get() = backups[chosen].first
        val target get() = backups[chosen].second
        /** What importing this game writes: its chosen backup, and its device save when there is one. */
        fun choices(): List<SaveImportChoice> = listOfNotNull(
            SaveImportChoice(entry.id, target.id, people.toList()),
            device?.takeIf { it.first.id != entry.id }?.let { (e, t) -> SaveImportChoice(e.id, t.id, people.toList()) },
        )
    }

    var step by mutableStateOf(Step.READING)
    var plan by mutableStateOf<SaveImportPlan?>(null)
    var games by mutableStateOf<List<Game>>(emptyList())
    var index by mutableIntStateOf(0)
    var result by mutableStateOf<SaveImportResult?>(null)
    /** Null before an undo; then how it went (the saves the import replaced put back, or why not). */
    var undone by mutableStateOf<String?>(null)
    var problem by mutableStateOf<String?>(null)
    /** The Switch emulator whose save folder Fuse can't reach, when that is why nothing could be imported. */
    var unreachable by mutableStateOf<Unreachable?>(null)
    /** True: every game in the library, here or on Fuse One; false: only the games on this device. */
    var everywhere by mutableStateOf(false)
    /** How many Switch games are on Fuse One but not here, for the choice of which games to look at. */
    var elsewhere by mutableIntStateOf(0)
    /** Starts reading once the choice of games is made; set by [startSwitchImport]. */
    var read: (Boolean) -> Unit = {}
    val game: Game? get() = games.getOrNull(index)

    /** A Switch emulator ([emulator], its save folder setting's key) Fuse has no way into yet, named [name]. */
    class Unreachable(val emulator: String, val name: String)

    /**
     * The games of a plan, from its Switch backups: for each game its backups, newest first. A game is
     * one card however it was found (by its title id, or the same game known twice), with its device
     * save beside its account save. [elsewhere]: the keys of games not on this device.
     */
    fun take(plan: SaveImportPlan, cards: Map<String, GameCard>, me: String?, elsewhere: Set<String> = emptySet()) {
        this.plan = plan
        val picks = plan.entries.filter { it.switch != null }.mapNotNull { e ->
            val t = e.targets.firstOrNull { it.id == e.selectedTarget }
                ?: e.targets.firstOrNull { it.query.game.id !in elsewhere && "eden" in it.query.emulatorId }
                ?: e.targets.firstOrNull { it.query.game.id !in elsewhere && it.confidence == SaveImportConfidence.TITLE_ID }
                ?: e.targets.firstOrNull { it.query.game.id !in elsewhere }
                ?: e.targets.firstOrNull()
            t?.let { e to it }
        }
        games = picks.groupBy { (e, t) -> e.switch?.titleId?.uppercase()?.takeIf(SwitchIdsUi::real)?.let(SwitchIdsUi::program) ?: t.query.game.id }.mapNotNull { (key, list) ->
            val newest = list.sortedByDescending { it.first.switch?.savedAt ?: 0L }
            val accounts = newest.filter { it.first.switch?.device != true }
            val device = newest.firstOrNull { it.first.switch?.device == true }
            val shown = accounts.ifEmpty { listOfNotNull(device) }
            // The game this device has, when it is here as well as on Fuse One.
            val t = shown.firstOrNull { it.second.query.game.id !in elsewhere }?.second ?: shown.firstOrNull()?.second ?: return@mapNotNull null
            Game(key, t.query.title, cards[t.query.game.id], shown, device.takeIf { accounts.isNotEmpty() }, here = t.query.game.id !in elsewhere).also { g ->
                g.people = setOfNotNull(me)
            }
        }.sortedWith(compareBy<Game> { !it.here }.thenBy { it.title.lowercase() })
        index = 0
        step = if (games.isEmpty()) Step.DONE else Step.GAME
    }
}

/** A Switch game's own id for an update's (ending in 800): its saves are kept under the game's. */
private object SwitchIdsUi {
    fun program(id: String): String = if (id.endsWith("800")) id.dropLast(3) + "000" else id

    /** A whole title id (sixteen hex digits, a game's starting 01): only that says two cards are one game. */
    fun real(id: String): Boolean = id.length == 16 && id.startsWith("01") && id.all { it in '0'..'9' || it in 'A'..'F' }
}

/**
 * Starts the import of a Switch dump at [path]. With Switch games on Fuse One that aren't here, it
 * first asks which games to look at: those on this device, or every game (a save for a game not here
 * yet goes where the emulator keeps it, ready when the game is played). Then it reads the dump.
 */
internal fun startSwitchImport(app: AppState, path: String, fromPhone: Boolean = false) {
    val service = app.store.sync.service ?: return
    // A delivery from a phone already open is dropped for the newer one, not stacked on it.
    app.switchImport?.takeIf { it.step == SwitchImport.Step.READING || it.step == SwitchImport.Step.SCOPE }?.let { app.switchImport = null }
    val flow = SwitchImport(if (fromPhone) "From your phone" else path.substringAfterLast('/').substringAfterLast('\\'), path)
    app.switchImport = flow
    app.scope.launch {
        val (cards, queries) = cardsByGame(app, platform = "switch")
        val here = queries.map { it.game.id }.toSet()
        val remote = runCatching { app.store.reach.games(io.github.matiyaaa.fuse.model.PlatformId("switch")).first() }.getOrDefault(emptyList())
            .filter { hg -> io.github.matiyaaa.fuse.sync.GameKey.parse(hg.key)?.id?.let { it !in here } == true }
        // How a Switch game's saves are found here: any Switch game's query, or the library's sample of the system.
        val template = queries.firstOrNull() ?: app.store.sync.saveSamples().firstOrNull { it.platform == "switch" }
        val extra = if (template == null) emptyList() else remote.mapNotNull { hg ->
            io.github.matiyaaa.fuse.sync.GameKey.parse(hg.key)?.let { key -> template.copy(game = key, romPath = "", serial = null, title = hg.card.title) to hg.card }
        }
        flow.elsewhere = extra.size
        flow.read = { everywhere ->
            flow.everywhere = everywhere
            flow.step = SwitchImport.Step.READING
            app.scope.launch {
                val started = kotlin.time.Clock.System.now().toEpochMilliseconds()
                val asked = if (everywhere) queries + extra.map { it.first } else queries
                val allCards = if (everywhere) cards + extra.associate { (q, c) -> q.game.id to c } else cards
                service.previewSaveImport(path, asked)
                    .onSuccess { plan ->
                        // The reading card stays long enough to be read, however quick the dump was.
                        val left = MIN_READING_MS - (kotlin.time.Clock.System.now().toEpochMilliseconds() - started)
                        if (left > 0) delay(left)
                        if (plan.entries.none { it.switch != null }) {
                            service.discardSaveImport(plan)
                            // The usual reason on Android: the Switch emulator keeps its saves in its own
                            // private folder, so no game has anywhere for a save to go until one is chosen.
                            val blocked = runCatching { app.store.sync.saveFolders() }.getOrDefault(emptyList())
                                .firstOrNull { "switch" in it.systems && it.state == io.github.matiyaaa.fuse.sync.EmulatorSaves.State.NOT_FOUND && it.canChoose }
                            flow.unreachable = blocked?.let { SwitchImport.Unreachable(it.emulator, emulatorName(it.emulatorId)) }
                            flow.problem = if (blocked != null) {
                                "Your backups were read, but Fuse can't reach where ${emulatorName(blocked.emulatorId)} keeps its saves, so there is nowhere to put them yet. " +
                                    (blocked.note ?: "Choose the folder it keeps its saves in.")
                            } else if (everywhere) "None of these saves are of a Switch game in your library. Choose the JKSV folder, Checkpoint's saves folder, or one game's folder from your Switch's SD card."
                            else "None of these saves are of a Switch game on this device. Choose Every Game to bring in saves for games on Fuse One too."
                            flow.step = SwitchImport.Step.DONE
                        } else {
                            flow.take(plan, allCards, service.activeProfile.value?.id, elsewhere = if (everywhere) extra.map { it.first.game.id }.toSet() else emptySet())
                            mineFor(app, flow)
                        }
                    }
                    .onFailure { e ->
                        flow.problem = e.message ?: "Couldn't read those saves"
                        flow.step = SwitchImport.Step.DONE
                    }
            }
        }
        if (extra.isEmpty()) flow.read(false) else flow.step = SwitchImport.Step.SCOPE
    }
}

/** Shows a plan already read (from Import Saves' file or folder) as the Switch import. */
internal fun showSwitchImport(app: AppState, plan: SaveImportPlan, cards: Map<String, GameCard>) {
    val service = app.store.sync.service ?: return
    app.switchImport = SwitchImport(plan.sourceName).also { it.take(plan, cards, service.activeProfile.value?.id); mineFor(app, it) }
}

/** Finds, game by game, whether the person using Fuse already has a save of it, so each card can say. */
private fun mineFor(app: AppState, flow: SwitchImport) {
    val service = app.store.sync.service ?: return
    val me = service.activeProfile.value?.id ?: return
    val plan = flow.plan ?: return
    app.scope.launch {
        for (g in flow.games) {
            if (app.switchImport !== flow) return@launch
            val found = runCatching { service.existingSaves(plan, listOf(SaveImportChoice(g.entry.id, g.target.id, listOf(me))), me)[g.entry.id]?.get(me) }.getOrNull()
            g.mine = found
        }
    }
}

/** The library's games (of one [platform], when given) by their save key, and the save queries to look for them with. */
internal suspend fun cardsByGame(app: AppState, platform: String? = null): Pair<Map<String, GameCard>, List<io.github.matiyaaa.fuse.sync.SaveQuery>> {
    val games = app.store.library.games(GameQuery(includeHidden = true)).first().filter { platform == null || it.platformId.value == platform }
    val pairs = games.mapNotNull { g -> app.store.sync.saveQuery(g.id)?.let { q -> q to g } }
    return pairs.associate { (q, g) -> q.game.id to g } to pairs.map { it.first }
}

private const val MIN_READING_MS = 1_400L

@Composable
internal fun SwitchImportOverlay(app: AppState) {
    val flow = app.switchImport
    var shown by remember { mutableStateOf(flow) }
    if (flow != null) shown = flow
    LaunchedEffect(flow != null) { if (flow != null) app.platform.sounds.play(SoundCue.OPEN) }
    val service = app.store.sync.service
    val profiles = service?.profiles?.value.orEmpty()
    val me = service?.activeProfile?.value?.id

    fun close() {
        val f = app.switchImport ?: return
        app.switchImport = null
        if (f.step != SwitchImport.Step.DONE || f.result?.failures?.isNotEmpty() == true) f.plan?.let { p -> app.scope.launch { service?.discardSaveImport(p) } }
    }

    /** After choosing people: when any of them already has a save of this game, asks first; else on to the next. */
    fun check(f: SwitchImport) {
        val g = f.game ?: return
        val plan = f.plan
        if (plan == null || service == null || me == null) { next(f); return }
        // Coming back to a game already settled asks again, from its people as they are now.
        g.people = g.people + g.kept
        g.kept = emptySet()
        g.replace = null
        f.step = SwitchImport.Step.CHECKING
        app.scope.launch {
            val found = service.existingSaves(plan, listOf(SaveImportChoice(g.entry.id, g.target.id, g.people.toList())), me)[g.entry.id].orEmpty()
            g.existing = found.filterKeys { it in g.people }
            if (f.step != SwitchImport.Step.CHECKING) return@launch
            if (g.existing.isEmpty()) next(f) else f.step = SwitchImport.Step.CONFLICT
        }
    }

    /** Chooses the folder the Switch emulator keeps its saves in, then reads the same saves again. */
    fun chooseFolder(f: SwitchImport) {
        val blocked = f.unreachable ?: return
        app.scope.launch {
            val path = app.platform.storage.pickSaveFolder("Where ${blocked.name} keeps its saves") ?: return@launch
            app.store.sync.setSaveFolder(blocked.emulator, path)
            if (app.switchImport !== f) return@launch
            f.problem = null
            f.unreachable = null
            f.read(f.everywhere)
        }
    }

    /** Puts back every save the import replaced, from the backups made just before. */
    fun undo(f: SwitchImport) {
        val replaced = f.result?.replaced.orEmpty()
        if (replaced.isEmpty() || service == null || f.undone != null) return
        f.undone = ""
        app.scope.launch {
            val failed = replaced.mapNotNull { r -> service.restoreImportBackup(r.query, r.kind, r.profile, r.backup).exceptionOrNull()?.message }
            f.undone = if (failed.isEmpty()) "The saves that were replaced are back." else failed.first()
            app.platform.sounds.play(if (failed.isEmpty()) SoundCue.SELECT else SoundCue.ERROR)
        }
    }

    fun import(f: SwitchImport) {
        val plan = f.plan ?: return
        val chosen = f.games.filter { it.included && it.people.isNotEmpty() }
        if (chosen.isEmpty() || me == null) return
        f.step = SwitchImport.Step.IMPORTING
        app.scope.launch {
            service?.importSaves(plan, chosen.flatMap { it.choices() }, me)
                ?.onSuccess { r ->
                    f.result = r
                    f.step = SwitchImport.Step.DONE
                    app.platform.sounds.play(if (r.failures.isEmpty()) SoundCue.SELECT else SoundCue.ERROR)
                }
                ?.onFailure { e ->
                    app.toasts.show(e.message ?: "Couldn't import the saves", ToastKind.ERROR)
                    f.step = SwitchImport.Step.REVIEW
                }
        }
    }

    // Controller: a row of choices per step, moved through with left and right (and up and down
    // between the people and the buttons).
    var focus by remember(flow?.step, flow?.index) { mutableIntStateOf(-1) }
    if (flow != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            val f = flow
            val g = f.game
            val actions = actionsOf(f, profiles, me, ::check, ::import, ::undo, ::chooseFolder, ::close)
            val people = if (f.step == SwitchImport.Step.PEOPLE) profiles.size + 1 else 0
            val count = people + actions.size
            if (focus < 0) focus = people + actions.indexOfFirst { it.primary }.coerceAtLeast(0)
            when (e.action) {
                NavAction.LEFT -> if (focus > 0 && focus != people) { focus--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (focus < count - 1 && focus != people - 1) { focus++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> if (focus >= people && people > 0) { focus = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (focus < people) { focus = people + actions.indexOfFirst { it.primary }.coerceAtLeast(0); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> {
                    if (focus < people && g != null) togglePerson(g, profiles, focus) else actions.getOrNull(focus - people)?.run()
                    NavResult.ACTIVATED
                }
                NavAction.BACK -> { back(f, ::close); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }

    Overlay(visible = flow != null, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        val f = shown ?: return@Overlay
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val compact = maxHeight < 560.dp
            Panel(Modifier.widthIn(min = minOf(420.dp, room), max = minOf(620.dp, room)).heightIn(max = maxHeight - Space.l * 2)) {
                // The buttons stay in view under the content, however long it is (a long Ready to Import list scrolls by itself).
                Column(Modifier.padding(if (compact) Space.l else Space.xl)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Header(f)
                    Spacer(Modifier.height(if (compact) Space.l else Space.xl))
                    // The next games' pictures load while this one is asked about, so each arrives ready.
                    if (f.step == SwitchImport.Step.GAME || f.step == SwitchImport.Step.PEOPLE) {
                        val arts = f.games.map { it.card?.art }
                        PrefetchArt(arts.map { it?.hero ?: it?.screenshot ?: it?.boxart }, f.index, limit = 2, fill = true)
                        PrefetchArt(arts.map { it?.boxart ?: it?.grid }, f.index, limit = 2, fill = true)
                        PrefetchArt(arts.map { it?.logo }, f.index, limit = 2)
                    }
                    when (f.step) {
                        SwitchImport.Step.SCOPE -> Scope(f)
                        SwitchImport.Step.READING -> Reading(f.source)
                        SwitchImport.Step.GAME -> f.game?.let { GameStep(it, compact) }
                        SwitchImport.Step.PEOPLE, SwitchImport.Step.CHECKING -> f.game?.let { g -> PeopleStep(g, profiles, me, focus.takeIf { it in 0..profiles.size }) { i -> togglePerson(g, profiles, i) } }
                        SwitchImport.Step.CONFLICT -> f.game?.let { g -> ConflictStep(g, profiles) }
                        SwitchImport.Step.REVIEW, SwitchImport.Step.IMPORTING -> Review(f, profiles)
                        SwitchImport.Step.DONE -> Done(f, profiles)
                    }
                }
                    val actions = actionsOf(f, profiles, me, ::check, ::import, ::undo, ::chooseFolder, ::close)
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(if (compact) Space.l else Space.xl))
                        val people = if (f.step == SwitchImport.Step.PEOPLE) profiles.size + 1 else 0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            actions.forEachIndexed { i, a ->
                                FuseButton(
                                    a.label, selected = focus == people + i, onClick = a.run, icon = a.icon,
                                    kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.GHOST, enabled = a.enabled,
                                    loading = f.step == SwitchImport.Step.IMPORTING && a.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class Action(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val primary: Boolean = false, val enabled: Boolean = true, val run: () -> Unit)

private fun actionsOf(f: SwitchImport, profiles: List<ProfileInfo>, me: String?, check: (SwitchImport) -> Unit, import: (SwitchImport) -> Unit, undo: (SwitchImport) -> Unit, choose: (SwitchImport) -> Unit, close: () -> Unit): List<Action> {
    val g = f.game
    return when (f.step) {
        SwitchImport.Step.SCOPE -> listOf(
            Action("Cancel", run = close),
            Action("This Device", FuseIcons.Gamepad) { f.read(false) },
            Action("Every Game", FuseIcons.Library, primary = true) { f.read(true) },
        )
        SwitchImport.Step.READING -> listOf(Action("Cancel", run = close))
        SwitchImport.Step.GAME -> buildList {
            if (g == null) return@buildList
            add(Action("Not This Game", FuseIcons.CircleSlash) { g.included = false; next(f) })
            if (g.backups.size > 1) add(Action("Other Backup", FuseIcons.Clock) { g.chosen = (g.chosen + 1) % g.backups.size })
            add(Action("Yes, It Is", FuseIcons.Check, primary = true) { g.included = true; f.step = SwitchImport.Step.PEOPLE })
        }
        SwitchImport.Step.PEOPLE -> listOf(
            Action("Back", FuseIcons.ArrowLeft) { f.step = SwitchImport.Step.GAME },
            Action("Continue", FuseIcons.ArrowRight, primary = true, enabled = g?.people?.isNotEmpty() == true) { check(f) },
        )
        SwitchImport.Step.CHECKING -> listOf(Action("Continue", FuseIcons.ArrowRight, primary = true, enabled = false) {})
        // Leaving theirs is the first choice: nothing is replaced without a yes.
        SwitchImport.Step.CONFLICT -> buildList {
            if (g == null) return@buildList
            add(Action("Back", FuseIcons.ArrowLeft) { f.step = SwitchImport.Step.PEOPLE })
            add(Action("Override", FuseIcons.Save) { g.replace = true; next(f) })
            add(Action("Skip", FuseIcons.CircleSlash, primary = true) {
                g.replace = false
                g.kept = g.existing.keys
                g.people = g.people - g.existing.keys
                next(f)
            })
        }
        SwitchImport.Step.REVIEW -> {
            val count = f.games.count { it.included && it.people.isNotEmpty() }
            listOf(
                Action("Back", FuseIcons.ArrowLeft) { f.index = f.games.lastIndex; f.step = SwitchImport.Step.GAME },
                Action(if (count == 1) "Import 1 Save" else "Import $count Saves", FuseIcons.Save, primary = true, enabled = count > 0 && me != null) { import(f) },
            )
        }
        SwitchImport.Step.IMPORTING -> listOf(Action("Importing", FuseIcons.Save, primary = true, enabled = false) {})
        SwitchImport.Step.DONE -> buildList {
            if (f.result?.replaced?.isNotEmpty() == true && f.undone == null) add(Action("Undo Replacements", FuseIcons.Undo) { undo(f) })
            val blocked = f.unreachable
            add(Action(if (blocked != null) "Not Now" else "Done", FuseIcons.Check, primary = blocked == null, run = close))
            if (blocked != null) add(Action("Choose ${blocked.name}'s Folder", FuseIcons.FolderPlus, primary = true) { choose(f) })
        }
    }
}

private fun next(f: SwitchImport) {
    if (f.index < f.games.lastIndex) {
        f.index++
        f.step = SwitchImport.Step.GAME
    } else f.step = SwitchImport.Step.REVIEW
}

private fun back(f: SwitchImport, close: () -> Unit) = when (f.step) {
    SwitchImport.Step.PEOPLE -> f.step = SwitchImport.Step.GAME
    SwitchImport.Step.CHECKING, SwitchImport.Step.CONFLICT -> f.step = SwitchImport.Step.PEOPLE
    SwitchImport.Step.GAME -> if (f.index > 0) { f.index--; f.step = SwitchImport.Step.PEOPLE } else close()
    SwitchImport.Step.REVIEW -> { f.index = f.games.lastIndex; f.step = SwitchImport.Step.PEOPLE }
    SwitchImport.Step.IMPORTING -> Unit
    else -> close()
}

/** Index [i] among the people: one of them, or (last) everyone. */
private fun togglePerson(g: SwitchImport.Game, profiles: List<ProfileInfo>, i: Int) {
    val all = profiles.map { it.id }.toSet()
    g.people = when {
        i >= profiles.size -> if (g.people == all) emptySet() else all
        else -> profiles[i].id.let { id -> if (id in g.people) g.people - id else g.people + id }
    }
}

@Composable
private fun Header(f: SwitchImport) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.SdCard, size = 18.dp, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText("Switch Saves", Fuse.type.label, color = c.textMuted, maxLines = 1)
            FText(f.source, Fuse.type.bodyStrong, maxLines = 1)
        }
        val n = f.games.size
        if (n > 1 && f.step in setOf(SwitchImport.Step.GAME, SwitchImport.Step.PEOPLE, SwitchImport.Step.CHECKING, SwitchImport.Step.CONFLICT)) {
            Steps(n, f.index)
        }
    }
}

/** Where the person is among the games: a dot per game, the current one long. */
@Composable
private fun Steps(count: Int, at: Int) {
    val c = Fuse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = minOf(count, 9)
        val first = (at - shown / 2).coerceIn(0, count - shown)
        for (i in first until first + shown) {
            val wide by fuselineFloat(if (i == at) 1f else 0f, Fuse.motion.focusSpring(), label = "stepDot")
            Box(Modifier.height(6.dp).width(6.dp + 12.dp * wide).clip(CircleShape)
                .background(if (i <= at) c.accent.copy(alpha = 0.55f + 0.45f * wide) else c.textMuted.copy(alpha = 0.3f)))
        }
    }
}

/** Which games to bring saves in for: the ones on this device, or every game in the library, Fuse One's too. */
@Composable
private fun Scope(f: SwitchImport) {
    val c = Fuse.colors
    Column {
        FText("Which games?", Fuse.type.title, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        FText(
            "Fuse can look for saves of the games on this device only, or of every game in your library. " +
                "${f.elsewhere} Switch ${if (f.elsewhere == 1) "game is" else "games are"} on Fuse One but not here: their saves are put where the emulator looks for them, ready when you install and play them.",
            Fuse.type.body, color = c.textMuted, maxLines = 5,
        )
        Spacer(Modifier.height(Space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            ScopeOption(FuseIcons.Gamepad, "This Device", "Installed games only", Modifier.weight(1f))
            ScopeOption(FuseIcons.Library, "Every Game", "Installed and on Fuse One", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScopeOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier.clip(SquircleShape.fraction(0.16f)).background(c.surfaceRaised.copy(alpha = 0.7f)).padding(Space.l)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = 18.dp, tint = c.accent)
        }
        Spacer(Modifier.height(Space.m))
        FText(title, Fuse.type.bodyStrong, maxLines = 1)
        FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 2)
    }
}

/** The dump being read: a spark running along a fuse, and what Fuse is doing. */
@Composable
private fun Reading(source: String) {
    val c = Fuse.colors
    var t by remember { mutableFloatStateOf(0f) }
    val reduced = Fuse.motion.reduced
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = ((now - start) / 1_000_000_000f) }
    }
    val phase = (t / 1.6f) % 1f
    val words = if (t < 0.9f) "Reading the saves on your Switch's card" else "Matching each save to your games"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val y = size.height / 2
            val pad = 12.dp.toPx()
            val w = size.width - pad * 2
            drawLine(c.textMuted.copy(alpha = 0.18f), Offset(pad, y), Offset(pad + w, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val head = pad + w * (if (reduced) 0.5f else phase)
            val tail = (head - w * 0.35f).coerceAtLeast(pad)
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c.accent), startX = tail, endX = head), Offset(tail, y), Offset(head, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val glow = 0.75f + 0.25f * kotlin.math.sin(t * 9f)
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * glow), Color.Transparent), center = Offset(head, y), radius = 22.dp.toPx()), 22.dp.toPx(), Offset(head, y))
            drawCircle(Color.White.copy(alpha = 0.95f), 4.dp.toPx(), Offset(head, y))
        }
        Spacer(Modifier.height(Space.m))
        FText(words, Fuse.type.title, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        FText("JKSV and Checkpoint backups are both understood. Nothing is changed until you say so.", Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 3)
    }
}

/**
 * "Is this the game?", as its own poster: the game's hero art across the top (its box art, blurred,
 * where it has none), its logo set into it (its name where it has no logo), and its box art lifted
 * onto the banner's edge. Each picture fades in once it has loaded, over the game's own colour, so
 * nothing ever pops; moving to the next game slides the new one in.
 */
@Composable
private fun GameStep(g: SwitchImport.Game, compact: Boolean) {
    val c = Fuse.colors
    val sw = g.entry.switch
    val art = g.card?.art
    val accent = g.card?.accent?.toColor() ?: c.accent
    val banner = if (compact) 136.dp else 176.dp
    val posterWidth = if (compact) 92.dp else 116.dp
    val posterHeight = posterWidth / POSTER_ASPECT
    // How far the poster reaches up into the banner.
    val rise = posterHeight * 0.62f
    val inset = Space.l
    val beside = inset + posterWidth + Space.l
    key(g.key) {
        Appear(visible = true, enter = slideInHorizontally(Fuse.motion.tween(Durations.SLOW, Curves.Enter)) { it / 14 } + fadeIn(Fuse.motion.tween(Durations.BASE, Curves.Fade))) {
            Box(Modifier.fillMaxWidth()) {
                // The banner: the game's own colour first, its art fading in over it.
                val bannerShape = remember { SquircleShape.fraction(0.06f) }
                Box(Modifier.fillMaxWidth().height(banner).clip(bannerShape).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f))))) {
                    val wide = art?.hero ?: art?.screenshot
                    Artwork(
                        model = wide ?: art?.boxart ?: art?.grid,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        focusX = if (wide != null) art?.heroFocusX ?: 0.5f else 0.5f,
                        focusY = if (wide != null) art?.heroFocusY ?: 0.35f else 0.3f,
                        backdrop = wide == null,
                        backdropBlur = if (wide == null) 28.dp else 0.dp,
                        fallback = {},
                    )
                    // A shade from below, so the logo always reads.
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.72f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = beside, end = inset, bottom = Space.m)) {
                        FText(
                            if (sw?.device == true) "A save for everyone on the console. Is it for" else "Is this your save for",
                            Fuse.type.label, color = Color.White.copy(alpha = 0.82f), maxLines = 1,
                        )
                        Spacer(Modifier.height(Space.xs))
                        val title: @Composable () -> Unit = { FText(g.title, Fuse.type.title, color = Color.White, maxLines = 2) }
                        if (art?.logo != null) {
                            Artwork(
                                art.logo,
                                Modifier.height(if (compact) 44.dp else 58.dp).fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                focusX = 0f,
                                focusY = 1f,
                                loading = false,
                                fallback = title,
                            )
                        } else {
                            title()
                        }
                    }
                }
                // The poster, lifted onto the banner's edge.
                Poster(g, posterWidth, accent, Modifier.padding(start = inset, top = banner - rise))
                // What the backup is, beside the poster and under the banner.
                Column(Modifier.padding(start = beside, top = banner + Space.m).fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (!g.here) Chip("Fuse One", icon = FuseIcons.Cloud)
                        sw?.tool?.let { Chip(if (it == "CHECKPOINT") "Checkpoint" else "JKSV", icon = FuseIcons.SdCard) }
                        sw?.user?.let { Chip(it, icon = FuseIcons.UserRound) }
                    }
                    Spacer(Modifier.height(Space.s))
                    FText(listOfNotNull(sw?.savedAt?.let(::whenText), sw?.titleId).joinToString("  ·  ").ifEmpty { g.entry.name }, Fuse.type.caption, color = c.textMuted, maxLines = 2)
                    if (!g.here) {
                        Spacer(Modifier.height(Space.s))
                        FText("On Fuse One, not on this device yet. The save is put where ${emulatorName(g.target.query.emulatorId)} looks for it, ready when you play it here.", Fuse.type.caption, color = c.accent, maxLines = 3)
                    }
                    g.mine?.let { e ->
                        Spacer(Modifier.height(Space.s))
                        FText("You already have a save of this${e.at?.let { "  ·  " + whenText(it / 1000) }.orEmpty()}", Fuse.type.caption, color = c.warning, maxLines = 2)
                    }
                    if (g.target.confidence != SaveImportConfidence.TITLE_ID) {
                        Spacer(Modifier.height(Space.s))
                        FText("Matched by its name, so check it is the right game.", Fuse.type.caption, color = c.warning, maxLines = 2)
                    }
                    if (g.backups.size > 1) {
                        Spacer(Modifier.height(Space.s))
                        FText("Backup ${g.chosen + 1} of ${g.backups.size}${if (g.chosen == 0) ", the newest" else ""}", Fuse.type.caption, color = c.accent, maxLines = 1)
                    }
                }
                // Room for the poster where the details are shorter than it.
                Spacer(Modifier.height(banner - rise + posterHeight))
            }
        }
    }
}

/** The game's box art as a poster: a soft shadow under it, rising a little as it arrives. */
@Composable
private fun Poster(g: SwitchImport.Game, width: Dp, accent: Color, modifier: Modifier = Modifier) {
    val shape = remember { SquircleShape.fraction(0.07f) }
    val reduced = Fuse.motion.reduced
    var shown by remember(g.key) { mutableStateOf(false) }
    LaunchedEffect(g.key) { shown = true }
    val lift by fuselineFloat(if (shown) 1f else 0f, Fuse.motion.tween(Durations.SLOW, Curves.Enter), label = "posterLift")
    Box(
        modifier.width(width).aspectRatio(POSTER_ASPECT)
            .graphicsLayer {
                if (!reduced) {
                    translationY = (1f - lift) * 18.dp.toPx()
                    val s = 0.94f + 0.06f * lift
                    scaleX = s
                    scaleY = s
                }
            }
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(accent.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
    ) {
        val art = g.card?.art
        Artwork(
            model = art?.boxart ?: art?.grid ?: art?.square ?: art?.icon,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            // Its name is right beside it: a made-up poster carries only its monogram.
            fallback = { GeneratedArt(g.title, accent, slot = ArtSlot.ICON) },
        )
    }
}

private const val POSTER_ASPECT = 0.72f

@Composable
private fun Cover(g: SwitchImport.Game, width: Dp) {
    val accent = g.card?.accent?.toColor() ?: Fuse.colors.accent
    val shape = remember { SquircleShape.fraction(0.08f) }
    Box(Modifier.width(width).aspectRatio(POSTER_ASPECT).clip(shape).background(accent.copy(alpha = 0.2f))) {
        val art = g.card?.art
        Artwork(
            model = art?.boxart ?: art?.grid ?: art?.square ?: art?.icon,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            // Small covers take the icon form, so a made-up cover's name never gets squeezed.
            fallback = { GeneratedArt(g.title, accent, slot = if (width < 64.dp) ArtSlot.ICON else ArtSlot.BOX) },
        )
    }
}

/** "Whose save is it?": everyone on this Fuse, as large faces; any number of them. */
@Composable
private fun PeopleStep(g: SwitchImport.Game, profiles: List<ProfileInfo>, me: String?, focused: Int?, onToggle: (Int) -> Unit) {
    val c = Fuse.colors
    Column {
        FText("Whose save is ${g.title}?", Fuse.type.title, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        FText("Choose one person, several, or everyone. Each gets their own copy.", Fuse.type.body, color = c.textMuted, maxLines = 2)
        Spacer(Modifier.height(Space.xl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterHorizontally)) {
            profiles.forEachIndexed { i, p ->
                Person(p.name, p.avatar, here = p.id == me, chosen = p.id in g.people, focused = focused == i) { onToggle(i) }
            }
            Person("Everyone", null, here = false, chosen = profiles.isNotEmpty() && g.people.size == profiles.size, focused = focused == profiles.size) { onToggle(profiles.size) }
        }
        Spacer(Modifier.height(Space.l))
        val names = profiles.filter { it.id in g.people }.map { it.name }
        FText(
            when (names.size) {
                0 -> "Choose at least one person."
                1 -> "Goes to ${names[0]}'s saves in Fuse One."
                else -> "Copied to ${names.dropLast(1).joinToString(", ")} and ${names.last()}. Each keeps their own save."
            },
            Fuse.type.caption, color = if (names.isEmpty()) c.warning else c.textMuted, align = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Person(name: String, avatar: String?, here: Boolean, chosen: Boolean, focused: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val lift by fuselineFloat(if (focused) 1f else 0f, Fuse.motion.focusSpring(), label = "personLift")
    val on by fuselineFloat(if (chosen) 1f else 0f, Fuse.motion.focusSpring(), label = "personOn")
    val nameColor by fuselineColor(if (chosen || focused) c.text else c.textMuted, Fuse.motion.tween(Durations.FAST), label = "personName")
    val size = 72.dp
    val reduced = Fuse.motion.reduced
    Column(
        Modifier.width(size + Space.l)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer { val s = if (reduced) 1f else 1f + 0.06f * lift; scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (avatar != null) {
                ProfileAvatar(avatar, size, ring = if (focused) c.focus else if (chosen) c.accent else null, dim = !chosen && !focused)
            } else {
                Box(
                    Modifier.size(size).clip(CircleShape).background(if (chosen) c.accent.copy(alpha = 0.22f) else c.surfaceRaised)
                        .then(if (focused) Modifier.border(3.dp, c.focus, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    FuseIcon(FuseIcons.Users, size = 30.dp, tint = if (chosen) c.accent else c.textMuted)
                }
            }
            // The tick that says this person gets a copy.
            Box(
                Modifier.align(Alignment.BottomEnd).size(24.dp).graphicsLayer { alpha = on; scaleX = 0.6f + 0.4f * on; scaleY = 0.6f + 0.4f * on }
                    .clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(FuseIcons.Check, size = 14.dp, tint = c.onAccent) }
        }
        Spacer(Modifier.height(Space.s))
        FText(name, Fuse.type.bodyStrong, color = { nameColor }, maxLines = 1, align = TextAlign.Center)
        FText(if (here) "Playing here" else " ", Fuse.type.caption, color = c.accent, maxLines = 1, align = TextAlign.Center)
    }
}

/** Every game once more, whose it becomes and where it goes, before anything is written. */
@Composable
private fun Review(f: SwitchImport, profiles: List<ProfileInfo>) {
    val c = Fuse.colors
    val chosen = f.games.filter { it.included && it.people.isNotEmpty() }
    Column {
        FText(if (chosen.isEmpty()) "Nothing to import" else "Ready to import", Fuse.type.title, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        val replacing = chosen.sumOf { g -> if (g.replace == true) g.existing.keys.count { it in g.people } else 0 }
        FText(
            (if (replacing > 0) "Replaces $replacing ${if (replacing == 1) "save" else "saves"} already here; each is backed up on this device first, and Undo puts it back. " else "") +
                "No one else's save is touched. Your Switch's files stay as they are.",
            Fuse.type.body, color = c.textMuted, maxLines = 4,
        )
        Spacer(Modifier.height(Space.l))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (g in f.games) ReviewRow(g, profiles, done = null)
        }
    }
}

@Composable
private fun ReviewRow(g: SwitchImport.Game, profiles: List<ProfileInfo>, done: Boolean?) {
    val c = Fuse.colors
    val skipped = !g.included || g.people.isEmpty()
    Row(
        Modifier.fillMaxWidth().clip(SquircleShape.fraction(0.18f)).background(c.surfaceRaised.copy(alpha = if (skipped) 0.35f else 0.7f)).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(g, 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(g.title, Fuse.type.bodyStrong, color = if (skipped) c.textMuted else c.text, maxLines = 1)
            val note = when {
                skipped && g.kept.isNotEmpty() -> "Skipped: they keep their own save"
                skipped -> "Left out"
                g.replace == true && g.existing.isNotEmpty() -> "Replaces ${names(profiles, g.existing.keys)}'s save  ·  ${where(g.target)}"
                g.kept.isNotEmpty() -> "${names(profiles, g.kept)} keeps their own  ·  ${where(g.target)}"
                else -> where(g.target)
            }
            FText(note, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (!skipped) {
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                profiles.filter { it.id in g.people }.take(4).forEach { ProfileAvatar(it.avatar, 26.dp) }
            }
        }
        if (done != null) {
            Spacer(Modifier.width(Space.m))
            FuseIcon(if (done) FuseIcons.CircleCheck else FuseIcons.CircleX, size = 20.dp, tint = if (done) c.success else c.danger)
        }
    }
}

@Composable
private fun Done(f: SwitchImport, profiles: List<ProfileInfo>) {
    val c = Fuse.colors
    val r = f.result
    Appear(visible = true, enter = fadeIn(Fuse.motion.tween(Durations.BASE, Curves.Fade))) {
        Column(Modifier.fillMaxWidth()) {
            if (f.onCard && f.step == SwitchImport.Step.DONE) {
                // Before anything else on this screen is read: a card pulled out without ejecting
                // can leave the Switch unable to find its emuMMC.
                Row(
                    Modifier.fillMaxWidth().clip(SquircleShape.fraction(0.18f)).background(c.warning.copy(alpha = 0.14f)).padding(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FuseIcon(FuseIcons.SdCard, size = 20.dp, tint = c.warning)
                    Spacer(Modifier.width(Space.m))
                    FText(
                        "Eject the card before you take it out. Pulled out while this device still writes to it, the card can lose the files your Switch needs to start its emuMMC.",
                        Fuse.type.caption, color = c.text, maxLines = 4,
                    )
                }
                Spacer(Modifier.height(Space.l))
            }
            when {
                f.problem != null -> {
                    FText(f.unreachable?.let { "Choose where ${it.name} keeps its saves" } ?: "Nothing imported", Fuse.type.title, maxLines = 2)
                    Spacer(Modifier.height(Space.xs))
                    FText(f.problem!!, Fuse.type.body, color = c.textMuted, maxLines = 8)
                }
                r == null -> FText("No games from this card are in your library", Fuse.type.title, maxLines = 2)
                else -> {
                    val failed = r.failures.map { it.entry }.toSet()
                    FText(if (failed.isEmpty()) "Saves imported" else "${r.imported} imported, ${failed.size} didn't", Fuse.type.title, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    val emulators = f.games.filter { it.included && it.people.isNotEmpty() }.map { emulatorName(it.target.query.emulatorId) }.distinct()
                    val inEmulators = emulators.joinToString(" and ").ifEmpty { "the emulator" }
                    FText(if (r.queued) "They're in $inEmulators, and go to Fuse One when it's back." else "They're in $inEmulators and in Fuse One.", Fuse.type.body, color = c.textMuted, maxLines = 2)
                    Spacer(Modifier.height(Space.l))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (g in f.games.filter { it.included && it.people.isNotEmpty() }) {
                            ReviewRow(g, profiles, done = g.entry.id !in failed)
                            r.failures.firstOrNull { it.entry == g.entry.id }?.let { FText(it.message, Fuse.type.caption, color = c.danger, maxLines = 3) }
                        }
                    }
                    if (r.replaced.isNotEmpty()) {
                        Spacer(Modifier.height(Space.m))
                        FText(
                            f.undone?.ifEmpty { "Putting them back..." }
                                ?: "${r.replaced.size} ${if (r.replaced.size == 1) "save was" else "saves were"} replaced and backed up on this device. Undo Replacements puts them back; so does the game's save history later.",
                            Fuse.type.caption, color = c.textMuted, maxLines = 3,
                        )
                    }
                }
            }
        }
    }
}

/** "Mo already has a save of this game": Override replaces it (backed up first), Skip leaves it and gives them no copy. */
@Composable
private fun ConflictStep(g: SwitchImport.Game, profiles: List<ProfileInfo>) {
    val c = Fuse.colors
    Column {
        FText("${g.title} already has a save", Fuse.type.title, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        FText(
            "Override puts this backup in its place; the save there now is backed up on this device first, so it can be put back. Skip leaves it exactly as it is.",
            Fuse.type.body, color = c.textMuted, maxLines = 4,
        )
        Spacer(Modifier.height(Space.l))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for ((id, e) in g.existing) {
                val p = profiles.firstOrNull { it.id == id }
                Row(
                    Modifier.fillMaxWidth().clip(SquircleShape.fraction(0.18f)).background(c.surfaceRaised.copy(alpha = 0.7f)).padding(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (p != null) ProfileAvatar(p.avatar, 32.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(p?.name ?: "Someone", Fuse.type.bodyStrong, maxLines = 1)
                        FText(existingText(e), Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    }
                }
            }
        }
        val others = g.people - g.existing.keys
        Spacer(Modifier.height(Space.m))
        FText(
            (if (others.isNotEmpty()) "${names(profiles, others)} ${if (others.size == 1) "has" else "have"} no save of it yet and ${if (others.size == 1) "gets" else "get"} this one either way. " else "") +
                "No one else's save is touched.",
            Fuse.type.caption, color = c.textMuted, maxLines = 3,
        )
    }
}

/** Where a person's save of a game is now, and when it was made, for [ConflictStep]. */
private fun existingText(e: ExistingSave): String {
    val at = e.at?.let { "  ·  " + whenText(it / 1000) }.orEmpty()
    return when (e.where) {
        ExistingSave.Where.IN_FOLDER -> "In the emulator now$at"
        ExistingSave.Where.SET_ASIDE -> "Kept on this device for their next play$at"
        ExistingSave.Where.WAITING -> "Saved here, waiting to go to Fuse One$at"
        ExistingSave.Where.SYNCED -> "In Fuse One$at"
    }
}

/** "Mo", "Mo and Sam", "Mo, Sam and Ali": the names of [ids] among [profiles]. */
private fun names(profiles: List<ProfileInfo>, ids: Collection<String>): String {
    val list = ids.map { id -> profiles.firstOrNull { it.id == id }?.name ?: "Someone" }
    return if (list.size <= 1) list.firstOrNull().orEmpty() else list.dropLast(1).joinToString(", ") + " and " + list.last()
}

/** Where a save goes, short enough for one line: the emulator, then the end of its folder. */
private fun where(t: SaveImportTarget): String {
    val tail = t.destination.replace('\\', '/').trimEnd('/').split('/').takeLast(2).joinToString("/")
    return "${emulatorName(t.query.emulatorId)}  ·  …/$tail"
}

/** An emulator's name from its id ("linux.eden" is Eden). */
private fun emulatorName(id: String): String = when (val base = id.substringAfterLast('.')) {
    "eden", "eden-nightly" -> "Eden"
    "kenji-nx" -> "Kenji-NX"
    "yuzu", "suyu", "torzu" -> base
    else -> base.replaceFirstChar { it.uppercase() }
}

/** "1 Oct 2026, 12:00", in this device's time zone. */
private fun whenText(seconds: Long): String {
    val t = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[t.monthNumber - 1]
    return "${t.dayOfMonth} $month ${t.year}, ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
