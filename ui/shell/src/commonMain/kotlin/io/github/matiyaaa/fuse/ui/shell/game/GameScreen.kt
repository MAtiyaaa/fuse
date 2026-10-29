package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.Game
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Crossfade
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.folderPolicyPicker
import io.github.matiyaaa.fuse.ui.shell.romm.rommDownload
import io.github.matiyaaa.fuse.ui.shell.romm.rommDownloadLater
import io.github.matiyaaa.fuse.ui.shell.store.rommOnly
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.GallerySpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.collectionPicker
import io.github.matiyaaa.fuse.ui.shell.app.emulatorPicker
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.gameRoom
import io.github.matiyaaa.fuse.ui.shell.app.journalSheet
import io.github.matiyaaa.fuse.ui.shell.app.statusIcon
import io.github.matiyaaa.fuse.ui.shell.app.statusSince
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.showProblem
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import kotlin.math.roundToInt
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.JournalWords
import io.github.matiyaaa.fuse.ui.shell.store.householdOnly
import io.github.matiyaaa.fuse.ui.shell.reach.copyMenu
import io.github.matiyaaa.fuse.ui.shell.reach.reachDownload
import io.github.matiyaaa.fuse.ui.shell.reach.reachUploadToRomm
import io.github.matiyaaa.fuse.ui.shell.reach.sendPicker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import androidx.compose.foundation.gestures.Orientation
import io.github.matiyaaa.fuse.ui.designsystem.input.dragVerticalScroll

/** One of the buttons under the title. [label] null draws a round icon button named by [name]. */
private data class DetailAction(
    val id: String,
    val label: String?,
    val icon: ImageVector,
    /** What confirming it does, for the hint line ("Add to favourites"). */
    val name: String,
    val primary: Boolean = false,
    val run: () -> Unit,
)

/** Where the game page is while its details load. */
private sealed interface GameLoad {
    data object Loading : GameLoad
    data class Ready(val detail: GameDetail) : GameLoad
    data object Gone : GameLoad
}

/** The cards at the foot of the page, in the order they sit (and the selection walks them). */
private enum class InfoCard { STARTS, PLAY, JOURNAL, EXTRAS, FILE }

/**
 * Game Info: a store page for a game you already own. The room stays lit by the art you selected,
 * so arriving here feels like stepping closer rather than changing screens. The title (or logo)
 * leads, with the facts Fuse really knows as quiet chips under it; Play comes first and is already
 * selected, and the emulator it starts in sits right beside it. Further down: discs,
 * achievements, screenshots, and cards that say how it starts, how much it has been played, what
 * extras it has and where its file is. Every section can be reached with the controller, and the page scrolls to keep the
 * selected one in view.
 */
@Composable
fun GameScreen(app: AppState, id: GameId, via: io.github.matiyaaa.fuse.ui.shell.store.PlayVia = io.github.matiyaaa.fuse.ui.shell.store.PlayVia.DEFAULT) {
    // A RomM game Fuse doesn't have yet opens on the same page, from RomM's mirror; once it is
    // downloaded (or found here) the page is the library game's, with Play instead of Download.
    val romOnly = id.rommOnly
    // The same for another device's game: once it comes here, the page is the library game's.
    val local by remember(id) {
        when {
            romOnly != null -> app.store.romm.libraryGame(romOnly)
            id.householdOnly != null -> app.store.reach.libraryGame(id)
            else -> kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)
    val shown = local ?: id
    val flow = remember(shown, via) { (shown.rommOnly?.let { app.store.romm.detail(it) } ?: app.store.library.game(shown, via)).map { d -> if (d == null) GameLoad.Gone else GameLoad.Ready(d) } }
    val load by flow.collectAsState(initial = GameLoad.Loading)
    // A short fade from the placeholder to the page, never a jump.
    Crossfade(load is GameLoad.Ready, animationSpec = Fuse.motion.fade(), label = "gameLoad") { ready ->
        when {
            ready -> (load as? GameLoad.Ready)?.let { GameDetailContent(app, it.detail, via) }
            load == GameLoad.Gone -> GameGone(app)
            else -> GameSkeleton()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDetailContent(app: AppState, d: GameDetail, via: io.github.matiyaaa.fuse.ui.shell.store.PlayVia) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val game = d.game
    val card = remember(d, via) { d.toCard().copy(via = via) }
    val accent = d.platform.accent.toColor()
    val sel = remember(game.id) { ShelfSelection() }
    val scroll = rememberScrollState()
    val reveal = rememberReveal(game.id)
    // What System health found about this game (a disc its playlist names that's gone, an emulator
    // that isn't installed any more): told under Play, and a button away.
    val issues = io.github.matiyaaa.fuse.ui.shell.settings.rememberHealthIssues(app).filter { it.game == game.id }
    // A PS3 or Vita game's content: installed or not, a licence missing, updates and DLC waiting.
    val content by produceState<io.github.matiyaaa.fuse.ui.shell.store.GameContentView?>(null, game.id, game.content, app.store.content.progress.collectAsState().value == null) {
        value = if (!card.isApp && game.platformId.value in CONTENT_SYSTEMS) app.store.content.view(game.id) else null
    }
    val contentStates = content?.states.orEmpty()
    // RomM: a game only on RomM (downloaded from this page), or what RomM has of a game here.
    val remote = game.id.rommOnly
    val rommFlow = remember(game.id) { if (remote != null) app.store.romm.forRom(remote) else app.store.romm.forGame(game.id) }
    val romm by rommFlow.collectAsState(initial = null)
    val rommButtons = remember(romm, remote) { rommButtonsOf(romm, remote != null) }
    // Its status and rating as RomM has them, when they were changed there (at most every few minutes).
    val onRomm = remote == null && romm != null
    LaunchedEffect(game.id, onRomm) { if (onRomm) runCatching { app.store.romm.freshenMarks(game.id) } }
    // The Fuse Library: every copy of this game (here, on the household's other devices, on RomM).
    val household = game.id.householdOnly != null
    val notHere = remote != null || household
    val reachState by app.store.reach.state.collectAsState()
    val copiesFlow = remember(game.id) { app.store.reach.availability(game.id) }
    val copies by copiesFlow.collectAsState(initial = null)
    val canSend = reachState.supported && !card.isApp
    val canUploadHere by produceState(false, game.id, reachState.supported) { value = app.store.reach.canUploadToRomm(game.id) }
    val rommHasIt = copies?.romm != null || romm != null
    // Updates and DLC another device has for this game and this copy hasn't (Fuse One).
    var extrasAsked by remember(game.id) { mutableIntStateOf(0) }
    val extras by produceState<io.github.matiyaaa.fuse.ui.shell.store.GameExtras?>(null, game.id, copies, extrasAsked) {
        value = if (notHere || !reachState.supported) null else runCatching { app.store.reach.extras(game.id) }.getOrNull()
    }
    val shownCopies = copies?.copies.orEmpty().takeIf { list -> reachState.supported || list.any { it.place == io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.DEVICE } }.orEmpty()
    // Where it was left, when its emulator here can carry on from there: looked at again after each play.
    val resumeAt by produceState<io.github.matiyaaa.fuse.ui.shell.store.ResumeInfo?>(null, game.id, game.play.lastPlayedAt, d.emulator.selected?.id) {
        value = if (notHere || card.isApp || d.unavailable != null || d.missing) null else runCatching { app.store.library.resumePoint(game.id) }.getOrNull()
    }

    // Play first, then what it starts in (the port, when Play starts one from here), then the quick
    // actions, then everything else.
    val actions = if (household) listOfNotNull(
        // Another device's game: Download brings it here from wherever is best.
        DetailAction(
            "download", if (copies?.transfer != null) "Downloading" else "Download", FuseIcons.Download, "Download", primary = true,
        ) { if (copies?.transfer != null) app.go(Route.Downloads) else app.reachDownload(game.id, game.displayTitle) },
        DetailAction("emu", d.emulator.port ?: d.emulator.selected?.name ?: "Choose emulator", if (d.emulator.port == null && d.emulator.selected == null) FuseIcons.Warning else FuseIcons.Chip, "Choose emulator") {
            app.go(Route.PlatformSettings(game.platformId))
        },
        DetailAction("send", null, FuseIcons.Share, "Send to another device") { app.sendPicker(game.id, game.displayTitle) },
        DetailAction("romm.up", null, FuseIcons.CloudUpload, "Upload to RomM from the device that has it") { app.reachUploadToRomm(game.id, game.displayTitle) }
            .takeIf { canUploadHere && !rommHasIt },
    ) else if (remote != null) listOfNotNull(
        // Not here yet: Download is the page's first button, and the emulator says what would play it.
        DetailAction(
            "download", if (romm?.transfer != null) "Downloading" else "Download", FuseIcons.Download, "Download", primary = true,
        ) { if (romm?.transfer != null) app.go(Route.Downloads) else app.rommDownload(remote, game.displayTitle) },
        DetailAction("emu", d.emulator.port ?: d.emulator.selected?.name ?: "Choose emulator", if (d.emulator.port == null && d.emulator.selected == null) FuseIcons.Warning else FuseIcons.Chip, "Choose emulator") {
            app.go(Route.PlatformSettings(game.platformId))
        },
        romm?.takeIf { v -> v.parts.any { it.kind != io.github.matiyaaa.fuse.model.ContentKind.GAME && it.kind.holdsGameData } }?.let {
            DetailAction("everything", null, FuseIcons.CloudDownload, "Download everything") {
                app.rommDownload(remote, game.displayTitle, io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Everything)
            }
        },
        // Kept for when the device is charging on Wi-Fi: a big game never costs the battery.
        DetailAction("later", null, FuseIcons.PlugZap, "Download later, once charging on Wi-Fi") {
            app.rommDownloadLater(remote, game.displayTitle)
        }.takeIf { romm?.transfer == null },
        DetailAction("send", null, FuseIcons.Share, "Send to another device") { app.sendPicker(game.id, game.displayTitle) }.takeIf { canSend },
    ) else listOfNotNull(
        // A game Fuse can't reach right now keeps its Play button (pressing it explains why), drawn
        // as not ready, so the page never promises a start it knows won't happen.
        // Left somewhere it can carry on from: Continue leads, and starting over is a press beside it.
        resumeAt?.let { DetailAction("continue", "Continue", FuseIcons.Play, "Continue", primary = true) { app.play(card, resume = true) } },
        if (resumeAt != null) DetailAction("play", "Start fresh", FuseIcons.RotateCcw, "Start fresh") { app.play(card) }
        else DetailAction("play", "Play", if (d.unavailable != null) FuseIcons.HardDrive else FuseIcons.Play, "Play", primary = d.unavailable == null && !d.missing) { app.play(card) },
        DetailAction("emu", d.emulator.port ?: d.emulator.selected?.name ?: "Choose emulator", if (d.emulator.port == null && d.emulator.selected == null) FuseIcons.Warning else FuseIcons.Chip, "Choose emulator") {
            app.emulatorPicker(card)
        },
        content?.let { v ->
            val install = io.github.matiyaaa.fuse.library.content.ContentState.NEEDS_INSTALL in contentStates && v.mode != io.github.matiyaaa.fuse.ui.shell.store.InstallMode.UNAVAILABLE
            DetailAction("content", if (install) "Install" else null, if (install) FuseIcons.Download else FuseIcons.PackageOpen, "Installed content") {
                app.go(Route.GameContent(game.id))
            }
        },
        extras?.let { x ->
            DetailAction("extras", "Get ${x.words}", FuseIcons.CloudDownload, "Get ${x.words} from ${x.from}") {
                app.scope.launch {
                    val why = app.store.reach.getExtras(game.id)
                    if (why == null) app.toasts.show("Bringing ${x.words} from ${x.from}. The game stays as it is", icon = FuseIcons.Download) else app.toasts.show(why)
                    extrasAsked++
                }
            }
        },
        issues.firstOrNull()?.let { issue ->
            DetailAction("health", null, FuseIcons.BadgeAlert, "What needs attention") { app.showProblem(issue.problem, card) }
        },
        DetailAction("fav", null, if (game.favorite) FuseIcons.HeartFilled else FuseIcons.Heart, if (game.favorite) "Remove from favourites" else "Add to favourites") {
            app.scope.launch { app.store.library.setFavorite(game.id, !game.favorite) }
        },
        // The Journal: its status's mark once it has one, so where you are with it shows at the top.
        DetailAction(
            "journal", null, game.journal.status?.let(::statusIcon) ?: FuseIcons.NotebookPen,
            game.journal.status?.let { "Journal: ${JournalWords.label(it)}" } ?: "Journal",
        ) { app.journalSheet(game.id, game.displayTitle, game.journal) },
        // Collections, sending it and its media are in Options (More, or X), not a row of round buttons.
        DetailAction("more", null, FuseIcons.More, "Options") { app.openContextMenu(app.gameMenu(card, fromDetail = true)) },
    )
    // Games here like this one: the rest of its series, its maker's, then its kinds.
    val similarFlow = remember(game.id, notHere) { if (notHere) kotlinx.coroutines.flow.flowOf(emptyList()) else app.store.library.similar(game.id) }
    val similar by similarFlow.collectAsState(initial = emptyList())
    val discs = game.discs
    val badges = d.achievements?.achievements.orEmpty().sortedByDescending { it.earnedAt ?: it.earnedHardcoreAt ?: 0 }.take(24)
    val shots = d.media.screenshots
    val cards = buildList {
        add(InfoCard.STARTS)
        // A game not here yet has no play time, extras or file of its own to show.
        if (!notHere) {
            add(InfoCard.PLAY)
            add(InfoCard.JOURNAL)
            if (game.content.isNotEmpty()) add(InfoCard.EXTRAS)
            add(InfoCard.FILE)
        }
    }
    // The detail cards wrap into lines on narrower screens; each line is its own row for the
    // controller, so Down from the first line reaches the cards under it.
    var cardsPerLine by remember(game.id) { mutableIntStateOf(3) }
    val perLine = detailsPerLine(cards.size, cardsPerLine)
    val lines = cards.chunked(perLine)
    // The line under the title shows what fits of these; the rest wait behind a "+" chip at its end.
    val facts = remember(d, contentStates) { factsOf(d, contentStates) }
    var factsShown by remember(game.id) { mutableIntStateOf(facts.count { it.inline }) }
    val moreFacts = facts.size > factsShown
    val description = game.metadata.description?.takeIf { it.isNotBlank() }
    // How long it takes to beat, from HowLongToBeat: asked when the page opens, kept a month.
    val hltbOn by remember(app.store) { app.store.prefs.map { it.howLongToBeat } }.collectAsState(app.store.prefs.value.howLongToBeat)
    var beatVersion by remember(game.id) { mutableIntStateOf(0) }
    val beatScope = androidx.compose.runtime.rememberCoroutineScope()
    val beat by produceState<io.github.matiyaaa.fuse.ui.shell.store.HltbView?>(null, game.id, hltbOn, beatVersion) {
        value = if (hltbOn) app.store.library.howLongToBeat(game.id) else null
    }
    // How far along you are, above Play: each part a stop, as many as fit the line.
    val progress = listOfNotNull(
        ProgressPart.PLAYED,
        ProgressPart.BEAT.takeIf { beat?.let { beatGoal(it.game).first > 0 } == true },
        ProgressPart.ACHIEVEMENTS.takeIf { (d.achievements?.total ?: 0) > 0 },
    )
    var progressShown by remember(game.id) { mutableIntStateOf(progress.size) }
    val progressParts = progress.take(progressShown.coerceAtLeast(1))
    val rows = buildList {
        if (moreFacts) add("facts")
        add("progress")
        add("actions")
        if (shownCopies.isNotEmpty()) add("available")
        if (description != null) add("about")
        if (rommButtons.isNotEmpty() || (romm?.parts?.size ?: 0) > 1) add("romm")
        if (discs.size > 1) add("discs")
        if (badges.isNotEmpty()) add("achievements")
        if (shots.isNotEmpty()) add("shots")
        lines.indices.forEach { add("details:$it") }
        if (similar.isNotEmpty()) add("similar")
    }
    fun sizeOf(key: String) = when (key) {
        "facts", "about" -> 1
        "progress" -> progressParts.size
        "actions" -> actions.size
        "available" -> shownCopies.size
        "romm" -> rommButtons.size.coerceAtLeast(1)
        "discs" -> discs.size
        "achievements" -> badges.size
        "shots" -> shots.size
        "similar" -> similar.size
        else -> if (key.startsWith("details:")) lines.getOrNull(key.removePrefix("details:").toInt())?.size ?: 0 else 0
    }
    // The page opens on Play, whatever sits above it.
    val opened = remember(game.id) { booleanArrayOf(false) }
    if (!opened[0]) {
        sel.row = rows.indexOf("actions").coerceAtLeast(0)
        opened[0] = true
    }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "actions"
    // A screenshot opened full screen, by its index.
    var viewing by remember(game.id) { mutableStateOf<Int?>(null) }
    fun showFacts() {
        app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
            title = game.displayTitle,
            icon = FuseIcons.Tags,
            message = "Everything Fuse knows about this game",
            // Each group together, in the order the groups first come (collections sit between the
            // first facts and the rest, so "About" would otherwise show twice).
            options = facts.withIndex().sortedBy { (_, f) -> facts.indexOfFirst { it.group == f.group } }.map { (i, f) ->
                io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("fact.$i", f.text, f.icon ?: FuseIcons.Tag, detail = f.label, section = f.group, onSelect = { app.choice = null })
            },
        )
    }
    /**
     * This copy of the game: where it is, and for a game that is a folder, choosing the file to
     * start and how the folder is read. A single file has nothing to choose from, so no list of the
     * other files beside it.
     */
    fun showCopy() {
        val loc = game.location
        val folderGame = loc.kind == LocationKind.FOLDER
        app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
            title = "This copy",
            icon = FuseIcons.HardDrive,
            message = loc.path,
            options = buildList {
                if (folderGame) {
                    add(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("launch", "Choose What to Launch", FuseIcons.FolderOpen,
                        detail = "Pick the file inside the folder to start", onSelect = { app.choice = null; app.go(Route.FolderBrowser(game.id)) }))
                    add(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("policy", "Folder Behaviour", FuseIcons.Layers,
                        detail = "Read the folder as one game, or each file as its own", onSelect = { app.folderPolicyPicker(card) }))
                }
                if (loc.launchPath != loc.path) add(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("starts", splitPath(loc.launchPath).second, FuseIcons.Play,
                    detail = "The file the emulator is given", onSelect = { app.choice = null }))
                if (game.titles.original != game.displayTitle) add(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("name", game.titles.original, FuseIcons.File,
                    detail = "Its name in the folder", onSelect = { app.choice = null }))
                add(io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("close", "Done", FuseIcons.Check, onSelect = { app.choice = null }))
            },
        )
    }
    /** HowLongToBeat's games named like this one, to choose the right one, or none of them. */
    fun chooseBeat() {
        beatScope.launch {
            val found = app.store.library.howLongToBeatChoices(game.id)
            app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
                title = "Which game is it?",
                icon = FuseIcons.Hourglass,
                message = if (found.isEmpty()) "HowLongToBeat found nothing by this name" else "From HowLongToBeat, by ${game.displayTitle}",
                options = found.take(12).map { h ->
                    io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                        "pick.${h.id}", h.name, FuseIcons.Gamepad,
                        detail = listOfNotNull(h.year?.toString(), h.platforms.takeIf { it.isNotBlank() }).joinToString("  ·  ").ifEmpty { null },
                        trailing = if (h.mainSeconds > 0) io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Value(beatText(h.mainSeconds)) else io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.None,
                        onSelect = { app.choice = null; beatScope.launch { app.store.library.chooseHowLongToBeat(game.id, h); beatVersion++ } },
                    )
                } + io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                    "pick.none", "None of these", FuseIcons.EyeOff, detail = "Don't show how long it takes to beat for this game",
                    onSelect = { app.choice = null; beatScope.launch { app.store.library.chooseHowLongToBeat(game.id, null); beatVersion++ } },
                ),
            )
        }
    }
    fun readAbout() {
        val text = description ?: return
        app.textPreview = io.github.matiyaaa.fuse.ui.shell.app.TextPreviewSpec(
            title = game.displayTitle,
            message = listOfNotNull(game.metadata.developer, game.metadata.releaseYear?.toString()).joinToString("  ·  ").ifEmpty { null },
            text = text,
            actions = listOf(io.github.matiyaaa.fuse.ui.shell.app.PreviewAction("Close", FuseIcons.Close, primary = true) { app.textPreview = null }),
            icon = FuseIcons.BookOpen,
        )
    }
    val col = sel.column(row)
    val focused = app.focusZone == FocusZone.CONTENT

    /** HowLongToBeat's times for the game, with your progress, and the way to say it's another game. */
    fun showBeat(view: io.github.matiyaaa.fuse.ui.shell.store.HltbView) {
        val g = view.game
        val played = game.play.totalSeconds
        fun progress(seconds: Long) = if (played > 0 && seconds > 0) "${((played.toFloat() / seconds).coerceAtMost(1f) * 100).toInt()}% played" else null
        val times = listOf(
            Triple("main", "Main story", g.mainSeconds),
            Triple("plus", "Main story and extras", g.plusSeconds),
            Triple("all", "Completionist", g.completionistSeconds),
        ).filter { it.third > 0 }
        app.choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
            title = g.name,
            icon = FuseIcons.Hourglass,
            message = listOfNotNull(g.year?.toString(), g.platforms.takeIf { it.isNotBlank() }, "HowLongToBeat").joinToString("  ·  "),
            options = times.map { (id, label, seconds) ->
                io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                    "beat.$id", label, FuseIcons.Clock3, detail = progress(seconds),
                    trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Value(beatText(seconds)),
                    section = "Times", onSelect = { app.choice = null },
                )
            } + listOf(
                io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                    "beat.other", "Not this game", FuseIcons.Search, detail = "Choose the right one from HowLongToBeat",
                    section = "Game", onSelect = { app.choice = null; chooseBeat() },
                ),
                io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                    "beat.site", "Open on HowLongToBeat", FuseIcons.ArrowUpRight, detail = "See it on howlongtobeat.com",
                    section = "Game", onSelect = { app.choice = null; app.platform.openUrl(g.url) },
                ),
            ),
        )
    }

    /** A part of the progress line: all play time, HowLongToBeat's times, or the achievements further down. */
    fun openProgress(part: ProgressPart?) {
        when (part) {
            ProgressPart.PLAYED -> app.go(Route.PlayTime)
            ProgressPart.BEAT -> beat?.let { showBeat(it) }
            ProgressPart.ACHIEVEMENTS -> rows.indexOf("achievements").takeIf { it >= 0 }?.let { sel.row = it }
                ?: app.go(Route.Root(io.github.matiyaaa.fuse.model.Destination.ACHIEVEMENTS))
            null -> Unit
        }
    }

    fun openCard(info: InfoCard) {
        when (info) {
            InfoCard.STARTS -> app.emulatorPicker(card)
            InfoCard.FILE -> showCopy()
            InfoCard.PLAY -> app.go(Route.PlayTime)
            InfoCard.JOURNAL -> app.journalSheet(game.id, game.displayTitle, game.journal)
            InfoCard.EXTRAS -> app.readManual(game.displayTitle, game.content)
        }
    }
    /** The detail card at the selection, when the selection is on a line of them. */
    fun cardAt(row: String, col: Int): InfoCard? =
        if (row.startsWith("details:")) lines.getOrNull(row.removePrefix("details:").toInt())?.getOrNull(col) else null

    val systems = rememberSystems(app)
    val system = systems[d.platform.id]
    LaunchedEffect(game.id, d.art, system?.art) {
        app.hero = gameRoom(game.id, d.art, d.platform.accent, system)
    }
    val confirm = when (row) {
        "facts" -> "See every detail"
        "about" -> "Read it all"
        "progress" -> when (progressParts.getOrNull(col)) {
            ProgressPart.BEAT -> "How long to beat"
            ProgressPart.ACHIEVEMENTS -> d.achievements?.source?.noun ?: "Achievements"
            else -> "All play time"
        }
        "shots" -> "View full screen"
        "similar" -> "Open"
        "romm" -> rommButtons.getOrNull(col)?.label ?: "Downloads"
        "available" -> "This copy"
        "actions" -> actions.getOrNull(col)?.name
        "discs" -> "Play this disc"
        else -> when (cardAt(row, col)) {
            InfoCard.STARTS -> "Choose emulator"
            InfoCard.FILE -> "This copy"
            InfoCard.PLAY -> "All play time"
            InfoCard.JOURNAL -> "Journal"
            else -> null
        }
    }
    // On the More button, confirming already opens the options, so the line says it once.
    val onMore = row == "actions" && actions.getOrNull(col)?.id == "more"
    LaunchedEffect(row, confirm, onMore, viewing != null) {
        app.hints = if (viewing != null) {
            // The screenshot viewer: only moving between them and closing.
            listOfNotNull(
                if (shots.size > 1) Hint(HintButton.DPAD, "Previous or next") else null,
                Hint(HintButton.BACK, "Close"),
            )
        } else {
            listOfNotNull(
                confirm?.let { Hint(HintButton.CONFIRM, it) },
                if (onMore) null else Hint(HintButton.OPTIONS, "Options"),
                Hint(HintButton.BACK, "Back"),
            )
        }
    }

    // Where each section starts in the page, so moving down brings the whole section into view.
    val tops = remember(game.id) { mutableStateMapOf<String, Int>() }
    // A section scrolled to stops below the top fade (fadingEdges, Space.xl), so its heading is never faded or cut.
    val inset = with(LocalDensity.current) { (Space.xl + Space.s).roundToPx() }
    // Every line of detail cards scrolls to the Details section, so it stays in view as a whole.
    val section = if (row.startsWith("details:")) "details" else row
    // Where each stop of a row that can wrap is (the buttons, the discs, RomM's downloads): on a
    // narrow screen Down and Up move by place inside such a row first.
    val placed = remember(game.id) { mutableMapOf<String, MutableMap<Int, androidx.compose.ui.geometry.Rect>>() }
    fun rectsOf(key: String) = placed.getOrPut(key) { mutableMapOf() }
    val actionRects = rectsOf("actions")
    var topOfPage by remember { mutableIntStateOf(0) }
    val clearBelow = with(LocalDensity.current) { (Size.hintHeight + Space.xxl).roundToPx() }
    // Where each line of detail cards ends: on a small screen they stack, and each must come fully into view.
    val lineBottoms = remember(game.id) { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(row, tops[section], scroll.maxValue, lineBottoms[row.removePrefix("details:").toIntOrNull() ?: -1]) {
        val line = row.removePrefix("details:").toIntOrNull().takeIf { row.startsWith("details:") }
        val target = when {
            // The title's lines and the buttons: the page's top.
            row == "facts" || row == "progress" || row == "actions" -> 0
            // The last section shows the page's very end, so nothing is left below the stick's reach.
            sel.row == rows.lastIndex -> scroll.maxValue
            // A line of cards: as much of Details as fits above it, and the whole line above the hints.
            line != null -> maxOf((tops[section] ?: 0) - inset, (lineBottoms[line] ?: 0) + clearBelow - scroll.viewportSize).coerceIn(0, scroll.maxValue)
            else -> ((tops[section] ?: 0) - inset).coerceIn(0, scroll.maxValue)
        }
        scroll.fuselineScrollTo(target, motion.followSpring())
    }

    InputLayer(enabled = focused && !app.overlayOpen && viewing == null) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> {
                val from = row
                val fromCol = col
                // A row that wraps on a narrow screen (the buttons, How long to beat under play time):
                // Up and Down reach the stop above or below inside it first.
                placed[row]?.let { rects ->
                    spatialStep(rects.filterKeys { it < sizeOf(row) }, col, e.action)?.let { to ->
                        sel.setColumn(row, to)
                        return@InputLayer NavResult.MOVED
                    }
                }
                sel.move(e.action, rows, ::sizeOf).also {
                    // Between lines of detail cards the column carries over, as in a grid.
                    val to = rows.getOrNull(sel.row)
                    if (it == NavResult.MOVED && to != from && to != null && to.startsWith("details:") && from.startsWith("details:")) {
                        sel.setColumn(to, fromCol.coerceAtMost(sizeOf(to) - 1))
                    }
                }.let { if (it == NavResult.IGNORED && e.action == NavAction.LEFT) NavResult.BLOCKED else it }
            }
            NavAction.SELECT -> {
                when (row) {
                    "facts" -> showFacts()
                    "about" -> readAbout()
                    "progress" -> openProgress(progressParts.getOrNull(col))
                    "shots" -> viewing = col
                    "similar" -> similar.getOrNull(col)?.let { app.go(Route.GameInfo(it.card.id)) }
                    "romm" -> rommButtons.getOrNull(col)?.let { b -> romm?.let { v -> app.rommDownload(v.romId, game.displayTitle, b.what) } } ?: app.go(Route.Downloads)
                    "available" -> shownCopies.getOrNull(col)?.let { cp -> app.copyMenu(game.id, game.displayTitle, cp, hereAlready = !notHere, canUpload = canUploadHere, rommHasIt = rommHasIt) }
                    "actions" -> actions.getOrNull(col)?.run?.invoke()
                    "discs" -> discs.getOrNull(col)?.let { disc -> app.play(card, discPath = disc.path) }
                    else -> cardAt(row, col)?.let(::openCard)
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                // Another device's game: bring it here, send it elsewhere, or to RomM from where it is.
                if (household) {
                    app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec(
                        title = game.displayTitle, subtitle = d.platform.name, art = d.art.tile, accent = d.platform.accent,
                        actions = listOfNotNull(
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("h.dl", "Download Here", FuseIcons.Download, detail = "From wherever is best", onSelect = { app.closeOverlays(); app.reachDownload(game.id, game.displayTitle) }),
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("h.send", "Send to Another Device", FuseIcons.Share, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = { app.sendPicker(game.id, game.displayTitle) }),
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("h.romm", "Upload to RomM", FuseIcons.CloudUpload, detail = "Sent by the device that has it", onSelect = { app.reachUploadToRomm(game.id, game.displayTitle) })
                                .takeIf { canUploadHere && !rommHasIt },
                        ) + io.github.matiyaaa.fuse.ui.shell.romm.rommEditActions(app, game.id, game.displayTitle, owner = "The device that has it"),
                    ))
                } else if (remote != null) {
                    app.openContextMenu(io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec(
                        title = game.displayTitle, subtitle = d.platform.name, art = d.art.tile, accent = d.platform.accent,
                        actions = listOf(
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("r.dl", "Download", FuseIcons.Download, onSelect = { app.closeOverlays(); app.rommDownload(remote, game.displayTitle) }),
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("r.all", "Download Everything", FuseIcons.CloudDownload, detail = "With its updates and DLC, where RomM has them", onSelect = {
                                app.closeOverlays(); app.rommDownload(remote, game.displayTitle, io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Everything)
                            }),
                        ) + listOfNotNull(
                            io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction("r.send", "Send to Another Device", FuseIcons.Share, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = { app.sendPicker(game.id, game.displayTitle) }).takeIf { canSend },
                        ) + io.github.matiyaaa.fuse.ui.shell.romm.rommEditActions(app, game.id, game.displayTitle),
                    ))
                } else if (row == "similar") {
                    similar.getOrNull(col)?.let { app.openContextMenu(app.gameMenu(it.card)) }
                } else {
                    app.openContextMenu(app.gameMenu(card, fromDetail = true))
                }
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = GameLayout.of(maxWidth, maxHeight, Size.hudHeight)
        // The page scrolls below the top line, softening into it and into the hint line.
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = Size.hudHeight)
                .onPlaced { topOfPage = it.positionInRoot().y.toInt() }
                .fadingEdges(scroll, top = Space.xl, bottom = Size.hintHeight + Space.l)
                .dragVerticalScroll(scroll)
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(Modifier.height(layout.top - Size.hudHeight))
            // The cover stands beside the title, level with its top: the column beside it grows down
            // (play time, notes), and a cover held to its foot would sink with it.
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    GameTitle(d, layout, app.store.prefs.value.showLogo, Modifier.reveal(reveal, 0))
                    Spacer(Modifier.height(Space.m))
                    MetaLine(
                        facts, Modifier.reveal(reveal, 1),
                        moreSelected = row == "facts" && focused,
                        onMore = { sel.row = rows.indexOf("facts"); showFacts() },
                        onShown = { if (it != factsShown) factsShown = it },
                    )
                    Spacer(Modifier.height(Space.s))
                    ProgressLine(
                        d, beat, progress, Modifier.reveal(reveal, 1),
                        focusedPart = if (row == "progress" && focused) col else null,
                        onPart = { i -> sel.row = rows.indexOf("progress"); sel.setColumn("progress", i); openProgress(progress.getOrNull(i)) },
                        onShown = { if (it != progressShown) progressShown = it },
                    )
                    Spacer(Modifier.height(Space.l))
                    // One button, drawn the same in either arrangement below.
                    val button: @Composable (Int, Modifier) -> Unit = { i, mod ->
                        // Where each button is, so Up and Down go to the one that is above or below
                        // when the row wraps (More under Play on a narrow screen).
                        val m = mod.onGloballyPositioned { actionRects[i] = it.boundsInRoot() }
                        val a = actions[i]
                        val selected = row == "actions" && col == i && focused
                        val tap = { sel.row = rows.indexOf("actions").coerceAtLeast(0); sel.setColumn("actions", i); a.run() }
                        if (a.label != null) {
                            FuseButton(
                                a.label, selected = selected, icon = a.icon,
                                kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                height = if (a.id == "play") Size.row else Size.touch,
                                // The emulator reads as a choice: its name, and a chevron for "pick another".
                                trailingIcon = if (a.id == "emu") FuseIcons.ChevronDown else null,
                                // Play stays pressable without an emulator: pressing it explains what to install.
                                enabled = !a.primary || d.emulator.canLaunch || d.emulator.selected != null,
                                modifier = m.then(if (a.id == "play") Modifier.widthIn(min = Size.touch * 3) else Modifier),
                                onClick = tap,
                            )
                        } else {
                            IconButton(
                                a.icon, selected = selected,
                                tint = when {
                                    a.id == "fav" && game.favorite -> c.accent
                                    a.id == "journal" && game.journal.status != null -> c.accent
                                    a.id == "content" && io.github.matiyaaa.fuse.library.content.ContentState.MISSING_LICENCE in contentStates -> c.warning
                                    a.id == "health" -> c.warning
                                    else -> c.text
                                },
                                contentDescription = a.name,
                                selectedTint = if ((a.id == "fav" && game.favorite) || (a.id == "journal" && game.journal.status != null)) c.accent else null,
                                modifier = m,
                                onClick = tap,
                            )
                        }
                    }
                    if (layout.stackActions) {
                        // A phone held upright: Play and the emulator span the width, the quick
                        // actions share one row under them, so nothing wraps raggedly.
                        Column(Modifier.reveal(reveal, 2), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            actions.indices.filter { actions[it].label != null }.forEach { button(it, Modifier.fillMaxWidth()) }
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                                actions.indices.filter { actions[it].label == null }.forEach { button(it, Modifier) }
                            }
                        }
                    } else {
                        FlowRow(
                            Modifier.reveal(reveal, 2),
                            horizontalArrangement = Arrangement.spacedBy(Space.m),
                            verticalArrangement = Arrangement.spacedBy(Space.m),
                        ) {
                            actions.indices.forEach { button(it, Modifier.align(Alignment.CenterVertically)) }
                        }
                    }
                    LaunchNote(d, Modifier.reveal(reveal, 3), issues.firstOrNull()?.problem?.title, resumeAt)
                }
                // Beside the game's logo the cover only says the name a second time; it stands in for a missing logo.
                layout.cover?.takeIf { d.art.logo == null || !app.store.prefs.value.showLogo }?.let { cover ->
                    Spacer(Modifier.width(Space.xxl))
                    Tile(
                        selected = false, showSpark = false, glow = accent,
                        modifier = Modifier.width(cover).aspectRatio(Aspect.BOX).reveal(reveal, 1),
                    ) {
                        Artwork(
                            d.art.boxart ?: d.art.grid ?: d.art.square ?: d.art.icon, Modifier.fillMaxSize(),
                            fallback = { GeneratedArt(game.displayTitle, accent, slot = ArtSlot.BOX, label = d.platform.shortName) },
                        )
                    }
                }
            }

            // Below the fold: each section reports where it starts, for the scroll above.
            fun Modifier.section(key: String, index: Int) = this
                .onPlaced { tops[key] = it.positionInParent().y.toInt() }
                .reveal(reveal, index)

            Spacer(Modifier.height(Space.x3))
            if (shownCopies.isNotEmpty()) {
                Section("Available on", Modifier.section("available", 4), count = availableSummary(shownCopies)) {
                    AvailableOn(
                        shownCopies, selected = if (row == "available" && focused) col else -1,
                        onCopy = { i ->
                            sel.row = rows.indexOf("available"); sel.setColumn("available", i)
                            app.copyMenu(game.id, game.displayTitle, shownCopies[i], hereAlready = !notHere, canUpload = canUploadHere, rommHasIt = rommHasIt)
                        },
                    )
                }
            }
            description?.let {
                Column(Modifier.section("about", 4).padding(bottom = Space.xxl)) {
                    SectionLabel("About")
                    Spacer(Modifier.height(Space.m))
                    AboutBlock(
                        it, selected = row == "about" && focused, reading = layout.reading,
                        onClick = { sel.row = rows.indexOf("about"); readAbout() },
                    )
                }
            }
            romm?.takeIf { "romm" in rows }?.let { v ->
                Section(if (remote != null) "On your RomM server" else "Content on RomM", Modifier.section("romm", 5), count = v.matchedBy) {
                    RommPartsBlock(
                        v, rommButtons, selected = if (row == "romm" && focused) col else -1,
                        onButton = { i -> sel.row = rows.indexOf("romm"); sel.setColumn("romm", i); app.rommDownload(v.romId, game.displayTitle, rommButtons[i].what) },
                        onPlaced = { i, r -> rectsOf("romm")[i] = r },
                    )
                }
            }
            if (discs.size > 1) {
                Section("Discs", Modifier.section("discs", 5), count = discs.size.toString()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        discs.forEachIndexed { i, disc ->
                            FuseButton(disc.label, modifier = Modifier.onGloballyPositioned { rectsOf("discs")[i] = it.boundsInRoot() }, selected = row == "discs" && col == i && focused, icon = FuseIcons.Disc, onClick = {
                                sel.row = rows.indexOf("discs"); sel.setColumn("discs", i)
                                app.play(card, discPath = disc.path)
                            })
                        }
                    }
                }
            }
            d.achievements?.let { a ->
                Section(a.source.noun, Modifier.section("achievements", 6), count = a.source.label.takeIf { a.source != io.github.matiyaaa.fuse.model.AchievementSource.RETRO_ACHIEVEMENTS }) {
                    AchievementSummary(a.progress, a.earned, a.total, a.pointsEarned, a.points.takeIf { a.source.hasPoints } ?: 0, a.mastered, a.matchedByName)
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        val list = rememberLazyListState()
                        FollowSelection(list, { sel.column("achievements") }, anchor = 0.2f)
                        // Room above and below for a badge's lift and its spark.
                        LazyRow(
                            state = list,
                            modifier = Modifier.bleedStart(Space.m).dragScroll(list, Orientation.Horizontal),
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                            contentPadding = PaddingValues(start = Space.m, top = Space.s, end = Space.gutter, bottom = Space.xs),
                        ) {
                            itemsIndexed(badges, key = { _, b -> b.id }) { i, b ->
                                val selected = row == "achievements" && col == i && focused
                                Column(Modifier.width(Size.thumbL + Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Tile(
                                        selected = selected,
                                        modifier = Modifier.size(Size.thumbL),
                                        onClick = { sel.row = rows.indexOf("achievements"); sel.setColumn("achievements", i) },
                                    ) {
                                        // A locked one without a picture of its own for that (trophies, Xbox 360) is
                                        // shaded over; a secret one shows a lock until it is earned. Both stay opaque.
                                        Artwork(
                                            (if (b.earned) b.badgeUrl else b.badgeLockedUrl).ifEmpty { null },
                                            Modifier.fillMaxSize(),
                                            fallback = {
                                                Box(Modifier.fillMaxSize().background(c.surfaceRaised), contentAlignment = Alignment.Center) {
                                                    FuseIcon(if (b.earned) FuseIcons.Trophy else FuseIcons.Lock, size = Size.iconXL, tint = c.textMuted)
                                                }
                                            },
                                        )
                                        if (!b.earned && b.badgeLockedUrl.isNotEmpty() && b.badgeLockedUrl == b.badgeUrl) {
                                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)))
                                        }
                                    }
                                    Spacer(Modifier.height(Size.sparkClearance))
                                    FText(b.title, Fuse.type.caption, color = if (selected) c.text else c.textMuted, maxLines = 2, minLines = 2)
                                }
                            }
                        }
                        badges.getOrNull(col.takeIf { row == "achievements" } ?: -1)?.let { b ->
                            Spacer(Modifier.height(Space.s))
                            FText("${b.title}: ${b.description}" + (if (b.points > 0) " (${b.points} points)" else ""), Fuse.type.body, color = c.textMuted, maxLines = 2, modifier = Modifier.widthIn(max = layout.reading))
                            // When it was earned, and how rare it is where its platform says.
                            val earnedAt = (b.earnedHardcoreAt ?: b.earnedAt)?.takeIf { it > 0 }
                            val facts = listOfNotNull(
                                when {
                                    earnedAt != null -> "Unlocked ${agoText(earnedAt)}"
                                    b.earned -> "Unlocked"
                                    else -> null
                                },
                                b.rarity?.let { r -> "${if (r < 10f) ((r * 10).roundToInt() / 10f).toString() else r.roundToInt().toString()}% of players have it" },
                            )
                            if (facts.isNotEmpty()) FText(facts.joinToString("  ·  "), Fuse.type.caption, color = c.textFaint, maxLines = 1)
                        }
                    }
                }
            }
            if (shots.isNotEmpty()) {
                Section("Screenshots", Modifier.section("shots", 7), count = shots.size.toString()) {
                    val list = rememberLazyListState()
                    FollowSelection(list, { sel.column("shots") }, anchor = 0.1f)
                    LazyRow(
                        state = list,
                        modifier = Modifier.bleedStart(Space.m).dragScroll(list, Orientation.Horizontal),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        contentPadding = PaddingValues(start = Space.m, top = Space.s, end = Space.gutter, bottom = Size.sparkClearance),
                    ) {
                        itemsIndexed(shots) { i, s ->
                            Tile(
                                selected = row == "shots" && col == i && focused,
                                modifier = Modifier.height(LocalTileMetrics.current.icon).aspectRatio(Aspect.SCREENSHOT),
                                onClick = { sel.row = rows.indexOf("shots"); sel.setColumn("shots", i); viewing = i },
                            ) {
                                Artwork(s.model, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            Section("Details", Modifier.section("details", 8)) {
                val size by produceState(SIZE_LOADING, game.id) { value = app.store.storage.size(game.id) ?: SIZE_UNKNOWN }
                // A PlayStation disc's own serial (and PCSX2's CRC), read from the image once.
                val disc by produceState<io.github.matiyaaa.fuse.library.disc.DiscIdentity?>(null, game.id) { value = app.store.library.discIdentity(game.id) }
                // Cards in a line share one height; a line holds as many as fit a readable width,
                // and four make two even lines rather than three and one.
                SideEffect { cardsPerLine = layout.cardsPerLine }
                lines.forEachIndexed { line, chunk ->
                    if (line > 0) Spacer(Modifier.height(Space.l))
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .onPlaced { lineBottoms[line] = (it.positionInRoot().y + it.size.height).toInt() - topOfPage + scroll.value },
                        horizontalArrangement = Arrangement.spacedBy(Space.l),
                    ) {
                        chunk.forEachIndexed { i, info ->
                            val key = "details:$line"
                            val selected = row == key && col == i && focused
                            val tap = {
                                sel.row = rows.indexOf(key); sel.setColumn(key, i)
                                openCard(info)
                            }
                            val m = Modifier.weight(1f).fillMaxHeight()
                            when (info) {
                                InfoCard.STARTS -> StartsCard(d, selected, tap, m)
                                InfoCard.PLAY -> PlayCard(d, selected, tap, m)
                                InfoCard.JOURNAL -> JournalCard(d, selected, tap, m, onRate = { r ->
                                    app.scope.launch { app.store.library.setJournal(game.id, game.journal.copy(rating = r)) }
                                })
                                InfoCard.EXTRAS -> ExtrasCard(d, selected, tap, m)
                                InfoCard.FILE -> FileCard(d, size, disc, selected, tap, m)
                            }
                        }
                        repeat(perLine - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            // Where it is, quietly, under the cards: the folder it is in and the name it has there.
            game.location.path.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { path ->
                val (folder, name) = splitPath(path)
                FText(
                    listOf(shortFolder(folder), name).filter { it.isNotEmpty() }.joinToString("  /  "),
                    Fuse.type.caption, color = c.textFaint, maxLines = 1,
                    modifier = Modifier.padding(top = Space.l),
                )
            }
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(Space.xxl))
                Section("More like this", Modifier.section("similar", 9), count = similar.size.toString()) {
                    SimilarShelf(
                        similar, selected = if (row == "similar" && focused) col else -1,
                        onGame = { i -> sel.row = rows.indexOf("similar"); sel.setColumn("similar", i); app.go(Route.GameInfo(similar[i].card.id)) },
                        onMenu = { i -> app.openContextMenu(app.gameMenu(similar[i].card)) },
                    )
                }
            }
            Spacer(Modifier.height(Size.hintHeight + Space.x4))
        }
        // The viewer covers the whole app, top bar included, so the app hosts it.
        LaunchedEffect(viewing) {
            app.gallery = viewing?.let { start -> GallerySpec(shots.map { it.model }, start, onIndex = { sel.setColumn("shots", it) }, onClose = { viewing = null }) }
        }
        DisposableEffect(game.id) { onDispose { app.gallery = null } }
    }
}

/** Sizes of the game page for the window it is in. */
private data class GameLayout(
    /** Space above the title, where the room's art shows. */
    val top: Dp,
    /** Width of the cover on the right; null where there is no room for it. */
    val cover: Dp?,
    /** The title's face: the hero face where there is height for it. */
    val compact: Boolean,
    val logoHeight: Dp,
    /** The widest a paragraph should run. */
    val reading: Dp,
    val cardsPerLine: Int,
    /** Narrow screens set the actions in full-width rows instead of one wrapping line. */
    val stackActions: Boolean,
) {
    companion object {
        fun of(width: Dp, height: Dp, hud: Dp = Size.hudLine): GameLayout {
            val content = width - Space.gutter * 2
            // A handheld's upper screen at its largest text (about 360dp tall): the title, its two
            // lines and Play must all show above the hint line, so the art above gets less.
            val short = height < Size.touch * 9
            return GameLayout(
                top = hud + if (short) Space.s else (height * 0.1f).coerceIn(Space.l, Space.x5),
                cover = if (width > Size.touch * 17) (height * 0.3f).coerceIn(Size.touch * 2, Size.touch * 5) else null,
                compact = height < Size.touch * 12 || width < Size.touch * 13,
                logoHeight = if (short) Size.touch + Space.s else (height * 0.19f).coerceIn(Size.touch + Space.l, Size.touch * 3),
                reading = Size.touch * 16,
                cardsPerLine = when {
                    // A 6 inch handheld (about 850dp) fits all three side by side.
                    content >= Size.touch * 15 -> 3
                    content >= Size.touch * 10 -> 2
                    else -> 1
                },
                stackActions = content < Size.touch * 10,
            )
        }
    }
}

/** The game's logo where it has one and logos are on, else its title in the display face. */
@Composable
private fun GameTitle(d: GameDetail, layout: GameLayout, showLogo: Boolean, modifier: Modifier) {
    val style = if (layout.compact) Fuse.type.display else Fuse.type.hero
    val title: @Composable () -> Unit = { FText(d.game.displayTitle, style, maxLines = 2) }
    Box(modifier) {
        if (d.art.logo != null && showLogo) {
            Artwork(
                d.art.logo,
                Modifier.height(layout.logoHeight).fillMaxWidth(0.7f),
                contentScale = ContentScale.Fit,
                focusX = 0f,
                focusY = 1f,
                fallback = title,
            )
        } else {
            title()
        }
    }
}

/** How a fact sits in the line under the title: the system's badge, a state of its content, or plain words. */
private enum class FactKind { SYSTEM, STATE, PLAIN }

/** One thing Fuse knows about a game: what it is ([label]), the words, and where it is listed. */
private data class GameFact(
    val text: String,
    val icon: ImageVector?,
    val label: String,
    val group: String,
    val kind: FactKind = FactKind.PLAIN,
    /** The system's colour, for its badge. */
    val dot: Color? = null,
    /** May sit in the line under the title; the rest only wait behind "+". */
    val inline: Boolean = false,
    /** Fewer words for that line ("Role-playing" for "Role-playing (RPG)"). */
    val short: String = text,
    val state: io.github.matiyaaa.fuse.library.content.ContentState? = null,
)

/** The most facts the line under the title holds, the system's badge included, even on a wide screen. */
private const val SHOWN_FACTS = 4

/**
 * Every fact Fuse has about the game. First the few that may sit in the line under the title: its
 * system, what its content needs most (a PS3 game to install), the year, its genre, players and
 * extras. Then everything else (collections, every genre, series, makers, the file's regions,
 * languages and version). Nothing is guessed: what Fuse doesn't know is left out. How far along you
 * are has its own line under it.
 */
private fun factsOf(d: GameDetail, states: List<io.github.matiyaaa.fuse.library.content.ContentState> = emptyList()): List<GameFact> {
    val game = d.game
    val meta = game.metadata
    val tags = game.tags
    fun state(s: io.github.matiyaaa.fuse.library.content.ContentState, inline: Boolean) =
        GameFact(s.label, stateIcon(s), "Content", "Content", FactKind.STATE, inline = inline, state = s)
    return buildList {
        add(GameFact(d.platform.name, null, "System", "About", FactKind.SYSTEM, dot = d.platform.accent.toColor(), inline = true))
        states.firstOrNull()?.let { add(state(it, inline = true)) }
        meta.releaseYear?.let { add(GameFact(it.toString(), FuseIcons.Calendar, "Released", "About", inline = true)) }
        meta.genres.firstOrNull()?.let { add(GameFact(it, FuseIcons.Tag, "Genre", "About", inline = true, short = shortGenre(it))) }
        meta.players?.let(::playersLabel)?.let { add(GameFact(it, FuseIcons.Users, "Players", "About", inline = true)) }
        val updates = game.content.count { it.kind == ContentKind.UPDATE }
        val dlc = game.content.count { it.kind == ContentKind.DLC }
        listOfNotNull(
            updates.takeIf { it > 0 }?.let { if (it == 1) "1 update" else "$it updates" },
            dlc.takeIf { it > 0 }?.let { "$it DLC" },
        ).joinToString(", ").ifEmpty { null }?.let { add(GameFact(it, FuseIcons.Package, "Extras", "About", inline = true)) }
        states.drop(1).forEach { add(state(it, inline = false)) }
        d.collections.forEach { add(GameFact(it.name, FuseIcons.Bookmark, "Collection", "Collections")) }
        meta.genres.drop(1).forEach { add(GameFact(it, FuseIcons.Tag, "Genre", "About")) }
        meta.franchise?.let { add(GameFact(it, FuseIcons.Layers, "Series", "About")) }
        meta.developer?.let { add(GameFact(it, FuseIcons.Wrench, "Developer", "About")) }
        meta.publisher?.takeIf { it != meta.developer }?.let { add(GameFact(it, FuseIcons.Store, "Publisher", "About")) }
        meta.rating?.let { add(GameFact("$it out of 100", FuseIcons.Star, "Rating", "About")) }
        tags.regions.takeIf { it.isNotEmpty() }?.let { add(GameFact(it.joinToString(", "), FuseIcons.Globe, "Region", "This copy")) }
        tags.languages.takeIf { it.isNotEmpty() }?.let { add(GameFact(it.joinToString(", "), FuseIcons.Type, "Languages", "This copy")) }
        listOfNotNull(tags.version?.let { "Version $it" }, tags.revision?.let { "Revision $it" }).joinToString(", ").ifEmpty { null }
            ?.let { add(GameFact(it, FuseIcons.Hash, "Version", "This copy")) }
        tags.flags.forEach { add(GameFact(it, FuseIcons.Info, "Marked", "This copy")) }
    }
}

/** A genre without the scraper's abbreviation after it: "Role-playing (RPG)" reads "Role-playing". */
internal fun shortGenre(genre: String): String = genre.substringBefore(" (").trim().ifEmpty { genre }

/**
 * One line, never two: the items side by side while they fit the width, then [more] holding how
 * many didn't (plus [extra] that never sit in the line). The first item always shows, shortened if
 * it must be. [onShown] hears how many fit, so the controller's stops match what is drawn.
 */
@Composable
private fun OneLine(
    count: Int,
    modifier: Modifier = Modifier,
    spacing: Dp = Space.m,
    extra: Int = 0,
    /** The "+" at the end; [live] false for the copy only measured, which takes no presses. */
    more: (@Composable (hidden: Int, live: Boolean) -> Unit)? = null,
    onShown: (Int) -> Unit = {},
    item: @Composable (index: Int) -> Unit,
) {
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val max = constraints.maxWidth
        val gap = spacing.roundToPx()
        val parts = (0 until count).map { i -> subcompose(i) { item(i) }.firstOrNull() }
        val widths = parts.map { it?.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) ?: 0 }
        fun fits(n: Int, reserve: Int) = widths.take(n).sum() + gap * (n - 1).coerceAtLeast(0) + reserve <= max
        // Room for "+" at its widest, kept only when something is left out.
        val probe = if (more != null && count + extra > 0) {
            (subcompose("probe") { more(count + extra, false) }.firstOrNull()?.maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) ?: 0) + gap
        } else 0
        var shown = count
        if (!fits(count, if (extra > 0) probe else 0)) {
            shown = count.coerceAtMost(1)
            while (shown < count && fits(shown + 1, probe)) shown++
        }
        val hidden = count - shown + extra
        val moreChip = if (more != null && hidden > 0) subcompose("more") { more(hidden, true) }.firstOrNull()?.measure(loose) else null
        val reserve = moreChip?.let { it.width + gap } ?: 0
        val placed = parts.take(shown).mapIndexed { i, m ->
            m?.measure(if (i == 0) loose.copy(maxWidth = (max - reserve).coerceAtLeast(0)) else loose)
        }
        val height = (placed.mapNotNull { it?.height } + listOfNotNull(moreChip?.height)).maxOrNull() ?: 0
        val width = (placed.sumOf { it?.width ?: 0 } + gap * (placed.size - 1).coerceAtLeast(0) + (if (moreChip != null && placed.isNotEmpty()) gap else 0) + (moreChip?.width ?: 0))
            .coerceAtMost(max)
        layout(width, height) {
            onShown(shown)
            var x = 0
            placed.forEach { p ->
                if (p != null) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
            moreChip?.let { it.placeRelative(x, (height - it.height) / 2) }
        }
    }
}

/**
 * The line under the title, never more than one: the system as a badge in its colour, what the
 * game's content needs most, then a few plain words (the year, its genre, players), as many as fit.
 * The rest wait behind a "+" chip at its end, which lists everything ([onMore]) and is reached from
 * the buttons with Up. Outlined chips with an icon each said the same in twice the space and wrapped
 * onto a second line.
 */
@Composable
private fun MetaLine(facts: List<GameFact>, modifier: Modifier, moreSelected: Boolean, onMore: () -> Unit, onShown: (Int) -> Unit) {
    val inline = facts.filter { it.inline }.take(SHOWN_FACTS)
    OneLine(
        inline.size, modifier,
        spacing = Space.m,
        extra = facts.size - inline.size,
        more = { n, live -> MoreChip(n, live && moreSelected, onMore.takeIf { live }) },
        onShown = onShown,
    ) { i ->
        val f = inline[i]
        MetaItem(f, separated = f.kind == FactKind.PLAIN && inline.getOrNull(i - 1)?.kind == FactKind.PLAIN)
    }
}

/** One item of [MetaLine]: the system's badge, a content state in its colour, or quiet words (after a small dot when they follow others). */
@Composable
private fun MetaItem(f: GameFact, separated: Boolean) {
    val c = Fuse.colors
    when (f.kind) {
        FactKind.SYSTEM -> {
            val tone = f.dot ?: c.accent
            Row(
                Modifier
                    .height(Size.chipCompact)
                    .clip(PillShape)
                    .background(tone.copy(alpha = if (c.isDark) 0.2f else 0.14f))
                    .border(Size.stroke, tone.copy(alpha = 0.5f), PillShape)
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Box(Modifier.size(Size.dot).clip(PillShape).background(tone))
                FText(f.short, Fuse.type.label, color = c.text, maxLines = 1)
            }
        }
        FactKind.STATE -> {
            val tone = f.state?.let { stateColor(it) } ?: c.text
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                FuseIcon(f.icon ?: FuseIcons.Info, size = Size.iconS, tint = tone)
                FText(f.short, Fuse.type.bodyStrong, color = tone, maxLines = 1)
            }
        }
        FactKind.PLAIN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            if (separated) Box(Modifier.size(Space.xs).clip(PillShape).background(c.textFaint))
            FText(f.short, Fuse.type.bodyStrong, color = c.textMuted, maxLines = 1)
        }
    }
}

/** "+3": the facts that didn't fit the line, a press away. Lit like a button when selected; without [onClick] it is only measured. */
@Composable
private fun MoreChip(count: Int, selected: Boolean, onClick: (() -> Unit)?) {
    val c = Fuse.colors
    val fill by io.github.matiyaaa.fuse.ui.fuseline.fuselineColor(if (selected) c.text else c.surfaceDim.copy(alpha = if (c.isDark) 0.62f else 0.72f), Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST), label = "moreChip")
    val tint = if (selected) c.ink else c.text
    Row(
        Modifier
            .height(Size.chipCompact)
            .clip(PillShape)
            .background(fill)
            .border(Size.stroke, if (selected) c.text else c.hairlineStrong, PillShape)
            .then(if (onClick != null) Modifier.fuseClickable(shape = PillShape, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Every detail", onClick = onClick) else Modifier)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        FuseIcon(FuseIcons.Plus, size = Size.iconXS, tint = tint)
        FText(count.toString(), Fuse.type.label.tabular(), color = tint, maxLines = 1)
    }
}

/**
 * The game's description, four lines of it, with "Read it all" under it. Selected, it lifts onto a
 * soft panel with a focus edge, so the controller can reach it like everything else.
 */
@Composable
private fun AboutBlock(text: String, selected: Boolean, reading: Dp, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val lit by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST), label = "about")
    Column(
        Modifier
            .widthIn(max = reading + Space.l * 2)
            .offset(x = -Space.l)
            .clip(shape)
            .background(c.text.copy(alpha = 0.06f * lit))
            .border(Size.focusStroke, c.focus.copy(alpha = lit), shape)
            .fuseClickable(shape = shape, scale = false, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Read it all", onClick = onClick)
            .padding(Space.l),
    ) {
        FText(text, Fuse.type.body, color = c.textMuted, maxLines = 4)
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FText("Read it all", Fuse.type.label, color = if (selected) c.text else c.accent, maxLines = 1)
            FuseIcon(FuseIcons.ChevronRight, size = Size.iconXS, tint = if (selected) c.text else c.accent)
        }
    }
}

/** A part of the progress line, in the order the line gives them up when it is short. */
private enum class ProgressPart { PLAYED, BEAT, ACHIEVEMENTS }

/** The HowLongToBeat time to measure against (the main story, else the longer runs) and what it is "to" do. */
private fun beatGoal(g: io.github.matiyaaa.fuse.integrations.hltb.HltbGame): Pair<Long, String> = when {
    g.mainSeconds > 0 -> g.mainSeconds to "to beat"
    g.plusSeconds > 0 -> g.plusSeconds to "with extras"
    else -> g.completionistSeconds to "to finish it all"
}

/**
 * How far along you are, in one line between the facts and Play, so it shows on the shortest
 * screen: the time played, how much of HowLongToBeat's story that is (a slim bar filling with it),
 * and the achievements unlocked. Each part is a stop (and a button): all play time, HowLongToBeat's
 * times, the achievements. What doesn't fit the width is left out rather than wrapped; Play history
 * and the achievements are further down the page.
 */
@Composable
private fun ProgressLine(
    d: GameDetail,
    beat: io.github.matiyaaa.fuse.ui.shell.store.HltbView?,
    parts: List<ProgressPart>,
    modifier: Modifier,
    focusedPart: Int?,
    onPart: (Int) -> Unit,
    onShown: (Int) -> Unit,
) {
    BoxWithConstraints(modifier) {
        // A phone held upright says each part in fewer words, so the story's bar still fits.
        val compact = maxWidth < Size.touch * 10
        // The stops' padding hangs past the left edge, so their words line up with the title's.
        OneLine(parts.size, Modifier.bleedStart(Space.m), spacing = Space.xs, onShown = onShown) { i ->
            val part = parts[i]
            ProgressStop(
                focusedPart == i,
                when (part) {
                    ProgressPart.PLAYED -> "All play time"
                    ProgressPart.BEAT -> "How long to beat"
                    ProgressPart.ACHIEVEMENTS -> d.achievements?.source?.noun ?: "Achievements"
                },
                onClick = { onPart(i) },
            ) { ProgressWords(part, d, beat, compact) }
        }
    }
}

/** One stop of [ProgressLine]: lit when it has the focus, a button for touch and the mouse. */
@Composable
private fun ProgressStop(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = Fuse.colors
    val lit by fuselineFloat(if (selected) 1f else 0f, Fuse.motion.tween(io.github.matiyaaa.fuse.ui.fuseline.Durations.FAST), label = "progressStop")
    Row(
        Modifier
            .height(Size.thumb)
            .clip(PillShape)
            .background(c.text.copy(alpha = 0.08f * lit))
            .border(Size.focusStroke, c.focus.copy(alpha = lit), PillShape)
            .fuseClickable(shape = PillShape, scale = false, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

/** What a part of [ProgressLine] says: its mark (an icon, or the bar), the number, then quietly what it counts. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ProgressWords(part: ProgressPart, d: GameDetail, beat: io.github.matiyaaa.fuse.ui.shell.store.HltbView?, compact: Boolean) {
    val c = Fuse.colors
    val played = d.game.play.totalSeconds
    /** The number, then quietly what it counts; [full] only where there is room for it. */
    @Composable
    fun words(value: String, what: String, full: String = "") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FText(value, Fuse.type.bodyStrong.tabular(), maxLines = 1)
            val said = if (compact) what else listOf(what, full).filter { it.isNotEmpty() }.joinToString(" ")
            if (said.isNotEmpty()) FText(said, Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
    }
    when (part) {
        ProgressPart.PLAYED -> if (played > 0) {
            FuseIcon(FuseIcons.Clock3, size = Size.iconS, tint = c.textMuted)
            words(playtimeText(played), "", full = "played")
        } else {
            FuseIcon(FuseIcons.Sparkle, size = Size.iconS, tint = c.textMuted)
            FText("Not played yet", Fuse.type.body, color = c.textMuted, maxLines = 1)
        }
        ProgressPart.BEAT -> {
            val (seconds, what) = beat?.let { beatGoal(it.game) } ?: (0L to "")
            if (played > 0 && seconds > 0) {
                val fill = (played.toFloat() / seconds).coerceIn(0f, 1f)
                io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar(fill, Modifier.width(Size.touch), color = c.accent)
                words("${(fill * 100).toInt()}%", "of ${beatText(seconds)}", full = what)
            } else {
                FuseIcon(FuseIcons.Hourglass, size = Size.iconS, tint = c.textMuted)
                words(beatText(seconds), what)
            }
        }
        ProgressPart.ACHIEVEMENTS -> {
            val a = d.achievements ?: return
            FuseIcon(if (a.mastered) FuseIcons.Crown else FuseIcons.Trophy, size = Size.iconS, tint = if (a.mastered) c.warning else c.textMuted)
            words("${a.earned} of ${a.total}", "", full = a.source.noun.lowercase())
        }
    }
}

/** A HowLongToBeat time as play time is written ("22 h", "1 h 30 min"), to the nearest five minutes, or the hour past ten hours. */
internal fun beatText(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    val rounded = if (minutes >= 600) ((minutes + 30) / 60) * 60 else ((minutes + 2) / 5) * 5
    return playtimeText(rounded.coerceAtLeast(1) * 60)
}

/**
 * A screenshot full screen over the page: Left and Right (or a swipe) move between them, Back or a
 * tap on the dark edge closes. Where it is shows under it, "3 of 8".
 */
@Composable
internal fun PictureViewer(shots: List<Any?>, start: Int, onIndex: (Int) -> Unit, onClose: () -> Unit) {
    val c = Fuse.colors
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start.coerceIn(0, (shots.size - 1).coerceAtLeast(0))) { shots.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { onIndex(pager.currentPage) }
    InputLayer(priority = io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority.DIALOG, modal = true) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.PREVIOUS_SECTION -> { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; NavResult.MOVED }
            NavAction.RIGHT, NavAction.NEXT_SECTION -> { if (pager.currentPage < shots.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; NavResult.MOVED }
            NavAction.BACK, NavAction.SELECT -> { onClose(); NavResult.CONSUMED }
            else -> NavResult.CONSUMED
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .fuseClickable(scale = false, onClickLabel = "Close", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize().dragScroll(pager, Orientation.Horizontal, flingBehavior = androidx.compose.foundation.pager.PagerDefaults.flingBehavior(pager)), pageSpacing = Space.l) { i ->
            Box(Modifier.fillMaxSize().padding(start = Space.gutter, end = Space.gutter, top = Space.xl, bottom = Size.hudHeight), contentAlignment = Alignment.Center) {
                Artwork(shots[i], Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = Space.xl).clip(PillShape).background(c.ink.copy(alpha = 0.7f)).padding(horizontal = Space.l, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            FuseIcon(FuseIcons.ChevronLeft, size = Size.iconS, tint = if (pager.currentPage > 0) c.text else c.textFaint)
            FText("${pager.currentPage + 1} of ${shots.size}", Fuse.type.label.tabular(), maxLines = 1)
            FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (pager.currentPage < shots.lastIndex) c.text else c.textFaint)
        }
    }
}

/**
 * The line under the buttons: what Play will do (its emulator, core, frame generation and saves),
 * no emulator yet (a warning), or an emulator that opens a folder or only its own app.
 */
@Composable
private fun LaunchNote(d: GameDetail, modifier: Modifier, attention: String? = null, resume: io.github.matiyaaa.fuse.ui.shell.store.ResumeInfo? = null) {
    val c = Fuse.colors
    // Where it was left, in the accent: the moment Continue carries on from, and on which device.
    if (resume != null && attention == null) {
        val left = listOfNotNull(
            resume.at?.let { "Left ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it)}" } ?: "Left mid-game",
            resume.device?.let { "on $it" },
        ).joinToString(" ")
        Row(modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FuseIcon(FuseIcons.History, size = Size.iconS, tint = c.accent)
            FText("$left. Continue carries on from there in ${resume.emulator}.", Fuse.type.caption, color = c.text, maxLines = 2)
        }
        return
    }
    if (attention != null && d.unavailable == null) {
        Row(modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FuseIcon(FuseIcons.BadgeAlert, size = Size.iconS, tint = c.warning)
            FText(attention, Fuse.type.caption, color = c.text, maxLines = 2)
        }
        return
    }
    // Where the game can't be reached, that comes first, calmly: it isn't broken, it's away.
    val away = d.unavailable
    if (away != null || d.missing) {
        Row(modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FuseIcon(if (away != null) FuseIcons.HardDrive else FuseIcons.FileQuestion, size = Size.iconS, tint = if (away != null) c.textMuted else c.warning)
            FText(
                when {
                    away != null && away.state == io.github.matiyaaa.fuse.model.SourceState.OFFLINE ->
                        "Stored on ${away.driveLabel}, which isn't connected. Connect it to play."
                    away != null -> "${away.label}. Your game and everything about it are kept."
                    else -> "The last scan didn't find this game's file. Scan again once it's back."
                },
                Fuse.type.caption, color = c.text, maxLines = 2,
            )
        }
        return
    }
    val summary = launchNote(d) ?: return
    val missing = d.emulator.selected == null
    Row(modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(if (missing) FuseIcons.Warning else FuseIcons.Info, size = Size.iconS, tint = if (missing) c.warning else c.textMuted)
        FText(summary, Fuse.type.caption, color = if (missing) c.text else c.textMuted, maxLines = 2)
    }
}

/**
 * What [LaunchNote] says under the buttons: what Play does, in one line, before it is pressed
 * (where it starts, with what, and the newest save first); or, where nothing starts it, why.
 */
private fun launchNote(d: GameDetail): String? {
    d.emulator.playLine?.takeIf { d.emulator.selected != null }?.let { return it }
    val summary = d.emulator.launchSummary ?: return null
    return summary.takeIf { d.emulator.selected == null || !summary.startsWith("Starts") }
}

/** A titled section of the page's lower half. */
@Composable
private fun Section(title: String, modifier: Modifier, count: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(bottom = Space.xxl)) {
        SectionLabel(title, count = count, rule = true)
        Spacer(Modifier.height(Space.l))
        content()
    }
}

/** Achievement progress: a ring with the share unlocked, then counts and points. */
@Composable
private fun AchievementSummary(progress: Float, earned: Int, total: Int, pointsEarned: Int, points: Int, mastered: Boolean, matchedByName: Boolean) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        ProgressRing(progress, size = Size.thumbL, color = if (mastered) c.warning else c.accent) {
            FText("${(progress * 100).toInt()}%", Fuse.type.numericSmall, maxLines = 1)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (mastered) FuseIcon(FuseIcons.Crown, size = Size.iconS, tint = c.warning)
                FText(if (mastered) "Mastered, $earned of $total" else "$earned of $total unlocked", Fuse.type.titleSmall.tabular(), maxLines = 1)
            }
            if (points > 0) FText("$pointsEarned of $points points", Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
            // Found by name: the set is right, but only the version RetroAchievements knows unlocks it.
            if (matchedByName) FText("Matched by name. Unlocks need a supported ROM version", Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
    }
}

/** Which emulator the game starts in, and why that one. Confirming it changes the emulator. */
@Composable
private fun StartsCard(d: GameDetail, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    val e = d.emulator
    InfoPanel("Starts in", FuseIcons.Chip, selected, onClick, modifier, actionable = true) {
        // A port chosen to play it with (Play With) starts in the emulator's place.
        val name = e.port ?: e.selected?.name
        FText(name ?: "No emulator yet", Fuse.type.titleSmall, color = if (name == null) c.warning else c.text, maxLines = 1)
        // Why this one, right under its name.
        val why = when {
            name == null -> null
            e.port != null -> "A Fuse Ports port, chosen for this game"
            e.source == "Game" -> "Chosen for this game"
            e.source == "Platform" -> "Chosen for every ${d.platform.shortName} game"
            e.source == "Automatic" -> "Fuse's first choice of the emulators you have"
            else -> null
        }
        if (why != null) FText(why, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        // How it starts, unless that only repeats the name ("Starts in RetroArch") or the note
        // under the buttons already says it.
        e.launchSummary?.takeIf { it != "Starts in $name" && launchNote(d) == null }?.let {
            FText(it, Fuse.type.caption, color = if (name == null) c.text else c.textMuted, maxLines = 3, modifier = Modifier.padding(top = Space.xs))
        }
        // The other emulators that could run it, so the chevron's promise is concrete.
        // Runners of their own kind of file (shell scripts, desktop shortcuts) aren't emulators for it.
        val others = e.alternatives.filter { it.id.value !in NOT_EMULATORS }.map { it.name }.filter { it != name }.distinct()
        if (others.isNotEmpty()) {
            Spacer(Modifier.weight(1f))
            FText(
                "Also installed: ${others.take(3).joinToString(", ")}${if (others.size > 3) " and ${others.size - 3} more" else ""}",
                Fuse.type.caption, color = c.textFaint, maxLines = 2, modifier = Modifier.padding(top = Space.s),
            )
        }
    }
}

/** How much the game has been played, as Fuse recorded it (imported time says where it came from). */
@Composable
private fun PlayCard(d: GameDetail, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    val play = d.game.play
    InfoPanel("Play history", FuseIcons.Clock3, selected, onClick, modifier, actionable = true) {
        Fact("Played", if (play.totalSeconds > 0) playtimeText(play.totalSeconds) else "Not yet", numeric = true)
        Fact("Last played", play.lastPlayedAt?.let { agoText(it).replaceFirstChar(Char::uppercase) } ?: "Never")
        if (d.secondsThisWeek > 0) Fact("This week", playtimeText(d.secondsThisWeek), numeric = true)
        if (play.sessions > 0) Fact("Sessions", play.sessions.toString(), numeric = true)
        if (d.game.addedAt > 0) Fact("Added", agoText(d.game.addedAt).replaceFirstChar(Char::uppercase))
        if (play.importedSeconds > 0) {
            FText(
                "Includes ${playtimeText(play.importedSeconds)} imported${play.importedSource?.let { " from $it" } ?: ""}",
                Fuse.type.caption, color = c.textFaint, maxLines = 2, modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/**
 * The person's Journal for it: where they are with it and since when, their stars and a glimpse of
 * their note. The stars are set right here by touch or the mouse, half a star at a time ([onRate]);
 * confirming the card opens the whole Journal, the stars among it for the controller.
 */
@Composable
private fun JournalCard(d: GameDetail, selected: Boolean, onClick: () -> Unit, modifier: Modifier, onRate: (Int) -> Unit) {
    val c = Fuse.colors
    val j = d.game.journal
    InfoPanel("Journal", FuseIcons.NotebookPen, selected, onClick, modifier, actionable = true) {
        val s = j.status
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (s != null) FuseIcon(statusIcon(s), size = Size.iconS, tint = c.accent)
            FText(s?.let(JournalWords::label) ?: "No status yet", Fuse.type.titleSmall, color = if (s != null) c.text else c.textMuted, maxLines = 1)
        }
        if (s != null) j.statusAt?.let { at ->
            FText(statusSince(s, at, kotlin.time.Clock.System.now().toEpochMilliseconds()), Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        // What a pointer over the stars would give, said beside them as it moves.
        var preview by remember { mutableStateOf<Int?>(null) }
        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            io.github.matiyaaa.fuse.ui.designsystem.components.StarRating(j.rating, onRate, size = Size.iconL, onPreview = { preview = it })
            FText(
                io.github.matiyaaa.fuse.ui.designsystem.components.RatingWords.stars(preview ?: j.rating) ?: "Not rated",
                Fuse.type.caption.tabular(), color = if (preview != null || j.rating > 0) c.text else c.textMuted, maxLines = 1,
            )
        }
        val glimpse = JournalWords.glimpse(j, NOTE_GLIMPSE)
        FText(
            glimpse?.let { "\u201C$it\u201D" } ?: "No note yet. Press to keep one, or to say where you are with it",
            Fuse.type.caption, color = if (glimpse != null) c.textMuted else c.textFaint, maxLines = 3, modifier = Modifier.padding(top = Space.s),
        )
    }
}

/** Updates, DLC and other extras found next to the game, with what the emulator does with them. */
@Composable
private fun ExtrasCard(d: GameDetail, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    val manuals = d.game.content.count { it.kind == ContentKind.MANUAL }
    InfoPanel("Updates and extras", FuseIcons.Package, selected, onClick, modifier, actionable = manuals > 0) {
        for ((kind, items) in d.game.content.groupBy { it.kind }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FuseIcon(kindIcon(kind), size = Size.iconS, tint = c.text)
                FText(kindLabel(kind, items.size), Fuse.type.bodyStrong, maxLines = 1)
            }
            d.contentNotes.firstOrNull { it.kind == kind }?.let {
                FText(it.message, Fuse.type.caption, color = c.textMuted, maxLines = 3, modifier = Modifier.padding(start = Size.iconS + Space.s, bottom = Space.xs))
            }
            if (kind == ContentKind.MANUAL) {
                FText(if (manuals == 1) "Press to read it here" else "Press to choose one to read", Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.padding(start = Size.iconS + Space.s, bottom = Space.xs))
            }
        }
    }
}

/**
 * Opens [title]'s manual among its [content]: straight away when it has one, from a list when it has
 * several (each by its file's name).
 */
internal fun AppState.readManual(title: String, content: List<io.github.matiyaaa.fuse.model.ChildContent>) {
    val manuals = content.filter { it.kind == ContentKind.MANUAL && !it.isDirectory }
    when (manuals.size) {
        0 -> Unit
        1 -> go(Route.Manual(manuals[0].path, "$title manual"))
        else -> choice = io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec(
            title = "$title manuals",
            icon = FuseIcons.BookOpen,
            options = manuals.map { m ->
                io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction(
                    "manual.${m.path}", m.name.substringBeforeLast('.'), FuseIcons.BookOpen,
                    detail = m.name.substringAfterLast('.').uppercase(),
                    onSelect = { choice = null; go(Route.Manual(m.path, m.name.substringBeforeLast('.'))) },
                )
            },
        )
    }
}

/** Where the game lives on this device and how Fuse reads it. */
@Composable
private fun FileCard(d: GameDetail, size: Long, disc: io.github.matiyaaa.fuse.library.disc.DiscIdentity?, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val game = d.game
    val loc = game.location
    InfoPanel("On this device", FuseIcons.HardDrive, selected, onClick, modifier, actionable = true) {
        val (folder, name) = splitPath(loc.path)
        Fact(if (loc.kind == LocationKind.FOLDER) "Folder" else "File", name, lines = 2)
        if (folder.isNotEmpty()) Fact("In", shortFolder(folder))
        when (size) {
            SIZE_LOADING -> Fact("Size", null)
            SIZE_UNKNOWN -> Fact("Size", "Unknown")
            // Every disc, track and folder file together, as Settings, Storage counts it.
            else -> Fact("Size", bytesText(size), numeric = true)
        }
        if (loc.launchPath != loc.path) Fact("Launches", splitPath(loc.launchPath).second)
        Fact("Read as", when (loc.interpretation) {
            FolderInterpretation.SINGLE_FILE -> "A single file"
            FolderInterpretation.FOLDER_IS_GAME -> "A folder that is the game"
            FolderInterpretation.MULTI_FILE_GAME -> "A game folder with extras (RomM layout)"
            FolderInterpretation.MULTI_DISC -> "A multi-disc set"
            FolderInterpretation.FOLDER_BROWSER -> "A folder you pick from"
        }, lines = 2)
        (disc?.serial ?: game.tags.serial)?.let { Fact("Serial", it, numeric = true) }
        disc?.crcText?.let { Fact("PCSX2 CRC", it, numeric = true) }
        // The game's makers, series and region are among its facts at the top; this card is the copy.
    }
}

/** One labelled value in a card. A null [value] is still on its way and shows a placeholder. */
@Composable
private fun Fact(label: String, value: String?, lines: Int = 1, numeric: Boolean = false) {
    val c = Fuse.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xxs)) {
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.width(Size.touch * 2).padding(top = Space.xxs))
        if (value == null) {
            Skeleton(Modifier.padding(top = Space.xs).width(Size.touch).height(Space.m), shape = PillShape)
        } else {
            FText(value, if (numeric) Fuse.type.label.tabular() else Fuse.type.label, maxLines = lines, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * A card at the foot of the page: an icon and a small title, then its content. Selected with the
 * controller it lifts a little and takes the focus outline; [actionable] cards show a chevron, as
 * confirming them does something.
 */
@Composable
private fun InfoPanel(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    actionable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val f by fuselineFloat(if (selected) 1f else 0f, motion.focusSpring(), label = "card")
    val corner = Fuse.geometry.panel
    val shape = RoundedCornerShape(corner)
    val focus = c.focus
    val ring = Size.focusStroke
    val gap = Size.focusGap
    Panel(
        modifier
            .graphicsLayer {
                val s = if (motion.reduced) 1f else 1f + 0.015f * f
                scaleX = s
                scaleY = s
            }
            // The outline sits just outside the card, so focusing never moves anything.
            .drawBehind {
                if (f > 0.01f) {
                    val g = (gap + ring / 2).toPx()
                    val r = corner.toPx() + g
                    drawRoundRect(
                        focus,
                        topLeft = Offset(-g, -g),
                        size = androidx.compose.ui.geometry.Size(size.width + g * 2, size.height + g * 2),
                        cornerRadius = CornerRadius(r),
                        alpha = f.coerceIn(0f, 1f),
                        style = Stroke(ring.toPx()),
                    )
                }
            }
            .semantics { this.selected = selected; contentDescription = title }
            .fuseClickable(shape = shape, scale = false, onClick = onClick),
        shape = shape,
        shadow = false,
    ) {
        Column(Modifier.fillMaxSize().padding(Space.l + Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FuseIcon(icon, size = Size.iconS, tint = c.textMuted)
                Spacer(Modifier.width(Space.s))
                SectionLabel(title, Modifier.weight(1f))
                if (actionable) FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted)
            }
            Spacer(Modifier.height(Space.s))
            content()
        }
    }
}

/** While the game's details load: the page's own shapes, quietly shimmering. */
@Composable
private fun GameSkeleton() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = GameLayout.of(maxWidth, maxHeight, Size.hudHeight)
        val style = if (layout.compact) Fuse.type.display else Fuse.type.hero
        val titleHeight = with(LocalDensity.current) { style.lineHeight.toDp() }
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(layout.top))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Skeleton(Modifier.fillMaxWidth(0.46f).height(titleHeight), shape = RoundedCornerShape(Radius.s))
                    Spacer(Modifier.height(Space.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (w in listOf(3f, 3.5f, 2.5f)) Skeleton(Modifier.width(Size.touch * w).height(Size.chipCompact), shape = PillShape)
                    }
                    Spacer(Modifier.height(Space.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Skeleton(Modifier.width(Size.touch * 3).height(Size.row), shape = PillShape)
                        Skeleton(Modifier.width(Size.touch * 3).height(Size.touch), shape = PillShape)
                        repeat(4) { Skeleton(Modifier.size(Size.touch), shape = CircleShape) }
                    }
                }
                layout.cover?.let { cover ->
                    Spacer(Modifier.width(Space.xxl))
                    Skeleton(Modifier.width(cover).aspectRatio(Aspect.BOX), shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction))
                }
            }
            Spacer(Modifier.height(Space.x3))
            SectionLabel("Details", rule = true)
            Spacer(Modifier.height(Space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                repeat(layout.cardsPerLine.coerceAtMost(3)) {
                    Panel(Modifier.weight(1f), shadow = false) {
                        Column(Modifier.padding(Space.l + Space.xs), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Skeleton(Modifier.width(Size.touch * 2).height(Space.m - Space.xxs), shape = PillShape)
                            SkeletonText(lines = 3, style = Fuse.type.label)
                        }
                    }
                }
            }
        }
    }
}

/** The game is no longer in the library (removed, or its file went away while the page was open). */
@Composable
private fun GameGone(app: AppState) {
    LaunchedEffect(Unit) { app.hints = listOf(Hint(HintButton.CONFIRM, "Back"), Hint(HintButton.BACK, "Back")) }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (e.action == NavAction.SELECT) { app.back(); NavResult.ACTIVATED } else NavResult.IGNORED
    }
    Box(Modifier.fillMaxSize().padding(horizontal = Space.gutter), contentAlignment = Alignment.Center) {
        EmptyState(
            FuseIcons.FileQuestion,
            "This game is no longer in your library",
            message = "It was removed, or its file moved. A rescan finds it again if the file is still on this device.",
            actionLabel = "Back",
            actionIcon = FuseIcons.ArrowLeft,
            actionSelected = app.focusZone == FocusZone.CONTENT,
            onAction = { app.back() },
        )
    }
}

/** A path split into its folder and its last part, whichever separator the system uses. */
private fun splitPath(path: String): Pair<String, String> {
    val cut = path.trimEnd('/', '\\').lastIndexOfAny(charArrayOf('/', '\\'))
    return if (cut < 0) "" to path else path.substring(0, cut) to path.substring(cut + 1).trimEnd('/', '\\')
}

/**
 * A folder by its last two parts ("…/roms/snes"): the end of a path is what tells folders apart,
 * and the whole of a long one would only wrap into noise.
 */
internal fun shortFolder(folder: String): String {
    val parts = folder.split('/', '\\').filter { it.isNotEmpty() }
    if (parts.size <= 2) return folder
    val sep = if (folder.contains('\\') && !folder.contains('/')) "\\" else "/"
    return "\u2026" + sep + parts.takeLast(2).joinToString(sep)
}

private const val SIZE_LOADING = -1L
private const val SIZE_UNKNOWN = -2L

private fun kindIcon(kind: ContentKind): ImageVector = when (kind) {
    ContentKind.UPDATE, ContentKind.PATCH -> FuseIcons.Refresh
    ContentKind.DLC -> FuseIcons.Package
    ContentKind.MANUAL, ContentKind.WALKTHROUGH -> FuseIcons.BookOpen
    ContentKind.SOUNDTRACK -> FuseIcons.Music
    ContentKind.SCREENSHOT -> FuseIcons.Image
    ContentKind.CHEAT -> FuseIcons.Key
    ContentKind.TRANSLATION -> FuseIcons.Earth
    ContentKind.HACK, ContentKind.MOD -> FuseIcons.Wrench
    else -> FuseIcons.Layers
}

private fun kindLabel(kind: ContentKind, n: Int): String {
    val word = when (kind) {
        ContentKind.GAME -> "Base game"
        ContentKind.DLC -> "DLC"
        ContentKind.UPDATE -> if (n == 1) "Update" else "Updates"
        ContentKind.PATCH -> if (n == 1) "Patch" else "Patches"
        ContentKind.HACK -> if (n == 1) "Hack" else "Hacks"
        ContentKind.MOD -> if (n == 1) "Mod" else "Mods"
        ContentKind.TRANSLATION -> if (n == 1) "Translation" else "Translations"
        ContentKind.DEMO -> if (n == 1) "Demo" else "Demos"
        ContentKind.PROTOTYPE -> if (n == 1) "Prototype" else "Prototypes"
        ContentKind.MANUAL -> if (n == 1) "Manual" else "Manuals"
        ContentKind.WALKTHROUGH -> if (n == 1) "Walkthrough" else "Walkthroughs"
        ContentKind.CHEAT -> "Cheats"
        ContentKind.SOUNDTRACK -> "Soundtrack"
        ContentKind.SCREENSHOT -> "Screenshots"
    }
    return if (kind == ContentKind.GAME || n <= 1) word else "$n $word"
}

/** A GameCard for menus and launching, built from the full detail. */
fun GameDetail.toCard(): GameCard = GameCard(
    id = game.id,
    platformId = game.platformId,
    title = game.displayTitle,
    platformShort = platform.shortName,
    accent = platform.accent,
    art = art,
    favorite = game.favorite,
    lastPlayedAt = game.play.lastPlayedAt,
    playSeconds = game.play.totalSeconds,
    addedAt = game.addedAt,
    year = game.metadata.releaseYear,
    updates = game.content.count { it.kind == ContentKind.UPDATE },
    dlc = game.content.count { it.kind == ContentKind.DLC },
    discs = game.discs.size,
    rommRomId = game.links.rommRomId,
    missing = missing,
    unavailable = unavailable,
    customTitle = game.titles.custom != null,
    pinned = pinned,
)

/** How many can play: "1 player", "1-4 players", or a source's own words ("Single player"). */
internal fun playersLabel(players: String): String? {
    val p = players.trim()
    return when {
        p.isEmpty() -> null
        p == "1" -> "1 player"
        p.any(Char::isLetter) -> p
        else -> "$p players"
    }
}

/** How many detail cards share a line: four make two even lines rather than three and one. */
/** The most of a note the Journal card shows. */
private const val NOTE_GLIMPSE = 120

private fun detailsPerLine(cards: Int, fits: Int): Int = (if (cards == 4 && fits == 3) 2 else fits.coerceAtMost(cards)).coerceAtLeast(1)

/** Systems whose games can come as packages that Fuse installs into the emulator. */
private val CONTENT_SYSTEMS = setOf("ps3", "psvita", "3ds", "new-nintendo-3ds")


/** A download the game page offers for what RomM has of a game. */
private class RommButton(val label: String, val what: io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat)

/** What can be brought in from RomM: missing discs, updates, DLC, or all of it. */
private fun rommButtonsOf(v: io.github.matiyaaa.fuse.ui.shell.store.RommGameView?, remote: Boolean): List<RommButton> {
    if (v == null || v.transfer != null) return emptyList()
    val missing = v.missing
    if (remote || missing.isEmpty()) return emptyList()
    val out = ArrayList<RommButton>()
    if (v.missingDiscs.isNotEmpty()) out += RommButton("Download Missing Discs", io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.MissingDiscs)
    for (p in missing.filter { it.kind == ContentKind.UPDATE }) out += RommButton("Download Update ${p.label}", io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Files(p.fileIds, "Update ${p.label}"))
    for (p in missing.filter { it.kind == ContentKind.DLC }) out += RommButton("Download ${p.label}", io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Files(p.fileIds, p.label))
    for (p in missing.filter { it.kind != ContentKind.UPDATE && it.kind != ContentKind.DLC && it.kind != ContentKind.GAME }) out += RommButton("Download ${p.label}", io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Files(p.fileIds, p.label))
    if (out.size > 1) out += RommButton("Download All Available Content", io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat.Everything)
    return out
}

/**
 * A game's parts as RomM has them, grouped as the game page groups content (the game or its discs,
 * then updates, DLC and the rest), each marked Here, on RomM, or coming; then what can be brought in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommPartsBlock(
    v: io.github.matiyaaa.fuse.ui.shell.store.RommGameView,
    buttons: List<RommButton>,
    selected: Int,
    onButton: (Int) -> Unit,
    onPlaced: (Int, androidx.compose.ui.geometry.Rect) -> Unit = { _, _ -> },
) {
    val c = Fuse.colors
    val groups = v.parts.groupBy { it.kind }
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        for ((kind, parts) in groups) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FText(kindLabel(kind, parts.size), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    parts.forEach { p ->
                        val (word, color) = when {
                            p.here -> "Here" to c.success
                            p.downloading -> "Coming" to c.accent
                            else -> "RomM" to c.textMuted
                        }
                        io.github.matiyaaa.fuse.ui.designsystem.components.Chip(
                            "${p.label}  ·  $word", icon = if (p.here) FuseIcons.CircleCheck else if (p.downloading) FuseIcons.Download else FuseIcons.Cloud, color = color,
                        )
                    }
                }
            }
        }
        if (buttons.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                buttons.forEachIndexed { i, b ->
                    FuseButton(b.label, modifier = Modifier.onGloballyPositioned { onPlaced(i, it.boundsInRoot()) }, selected = selected == i, icon = FuseIcons.CloudDownload, onClick = { onButton(i) })
                }
            }
        } else if (v.transfer != null) {
            FText("Downloading now. Follow it in Downloads.", Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/**
 * Games here like this one, as box art with why each is here under it ("Also in the Metroid
 * series"), the chosen one's title lit. Confirming one opens its page; Options gives its own menu.
 */
@Composable
private fun SimilarShelf(games: List<io.github.matiyaaa.fuse.ui.shell.store.Suggestion>, selected: Int, onGame: (Int) -> Unit, onMenu: (Int) -> Unit) {
    val c = Fuse.colors
    val list = rememberLazyListState()
    FollowSelection(list, { selected.coerceAtLeast(0) }, anchor = 0.1f)
    val width = LocalTileMetrics.current.icon * SIMILAR_COVER
    LazyRow(
        state = list,
        modifier = Modifier.bleedStart(Space.m).dragScroll(list, Orientation.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        contentPadding = PaddingValues(start = Space.m, top = Space.s, end = Space.gutter, bottom = Space.xs),
    ) {
        itemsIndexed(games, key = { _, g -> g.card.id.value }) { i, g ->
            val lit = selected == i
            Column(Modifier.width(width)) {
                io.github.matiyaaa.fuse.ui.shell.components.GameCoverTile(
                    g.card, selected = lit, width = width,
                    onClick = { onGame(i) }, onLongClick = { onMenu(i) },
                )
                Spacer(Modifier.height(Size.sparkClearance))
                FText(g.card.title, Fuse.type.label, color = if (lit) c.text else c.textMuted, maxLines = 1)
                FText(g.reason, Fuse.type.caption, color = c.textFaint, maxLines = 1)
            }
        }
    }
}

/** A cover in More like this, as a share of the page's tile size: box art a little smaller than a shelf's. */
private const val SIMILAR_COVER = 0.82f

/** "3 places, all the same", "On 2 devices and RomM": the count beside "Available on". */
private fun availableSummary(copies: List<io.github.matiyaaa.fuse.ui.shell.store.CopyView>): String {
    val n = copies.size
    val same = copies.size > 1 && copies.all { it.check == io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.VERIFIED }
    return (if (n == 1) "1 place" else "$n places") + if (same) ", all the same" else ""
}

/**
 * Every copy of the game, side by side: this device, each of the household's devices that has it,
 * and RomM. Each says whether its device is around, how big the game is there, whether its files
 * are the same as the others', and when it came. Confirming one shows what can be done with it.
 */
/**
 * The game's versions, between its facts and Play: each copy Fuse knows (this device's, another
 * device's, RomM's) as a chip with where it is, its size and, for this device's, its updates and
 * DLC. Choosing one offers what can be done with it (download it here too, send it, open it), so
 * two versions of a game are one game with a choice, not two games.
 */
/** A version's chip: where it is, how big, and this device's updates and DLC. */
internal fun versionLabel(copy: io.github.matiyaaa.fuse.ui.shell.store.CopyView, game: Game): String {
    val here = copy.place == io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.HERE
    val place = when (copy.place) {
        io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.HERE -> "This device"
        io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.ROMM -> "RomM"
        else -> copy.name
    }
    val version = if (here) listOfNotNull(game.tags.version?.let { "v$it" }, game.tags.revision?.let { "Rev $it" }).joinToString(" ").ifEmpty { null } else null
    val extras = if (here) {
        val updates = game.content.count { it.kind == ContentKind.UPDATE }
        val dlc = game.content.count { it.kind == ContentKind.DLC }
        listOfNotNull(
            updates.takeIf { it > 0 }?.let { if (it == 1) "1 update" else "$it updates" },
            dlc.takeIf { it > 0 }?.let { "$it DLC" },
        ).joinToString(", ").ifEmpty { null }
    } else null
    val size = copy.sizeBytes.takeIf { it > 0 }?.let { io.github.matiyaaa.fuse.ui.shell.home.bytesText(it) }
    return listOfNotNull(place, version, size, extras).joinToString("  ·  ")
}

@Composable
private fun AvailableOn(copies: List<io.github.matiyaaa.fuse.ui.shell.store.CopyView>, selected: Int, onCopy: (Int) -> Unit) {
    val list = rememberLazyListState()
    FollowSelection(list, { selected.coerceAtLeast(0) }, anchor = 0.1f)
    val now = io.github.matiyaaa.fuse.ui.shell.reach.nowMs()
    LazyRow(
        state = list,
        // Room on every side for the chosen card's lift and focus ring, the first card still lined up
        // with the heading above it.
        modifier = Modifier.offset(x = -Space.m).dragScroll(list, Orientation.Horizontal),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        contentPadding = PaddingValues(start = Space.m, top = Space.m, end = Space.gutter, bottom = Space.m),
    ) {
        itemsIndexed(copies, key = { _, cp -> cp.place.name + cp.id }) { i, cp ->
            CopyCard(cp, now, selected == i, onClick = { onCopy(i) }, modifier = Modifier.width(Size.touch * 5).height(IntrinsicSize.Min))
        }
    }
}

@Composable
private fun CopyCard(copy: io.github.matiyaaa.fuse.ui.shell.store.CopyView, now: Long, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    val here = copy.place == io.github.matiyaaa.fuse.ui.shell.store.CopyPlace.HERE
    InfoPanel(if (here) "This device" else copy.name, io.github.matiyaaa.fuse.ui.shell.reach.copyIcon(copy), selected, onClick, modifier, actionable = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Around now, or away (and since when).
            Box(Modifier.size(Space.s).clip(androidx.compose.foundation.shape.CircleShape).background(if (copy.online) c.success else c.textFaint))
            Spacer(Modifier.width(Space.s))
            FText(
                when {
                    here -> copy.name
                    copy.online -> "Online"
                    copy.lastSeen > 0 -> "Away, seen ${agoText(copy.lastSeen, now)}"
                    else -> "Away"
                },
                Fuse.type.label, maxLines = 1,
            )
        }
        val check = copy.check
        Row(verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(
                when (check) {
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.VERIFIED -> FuseIcons.CircleCheck
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.CHECKING -> FuseIcons.Hourglass
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.DIFFERENT -> FuseIcons.GitCompare
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.UNKNOWN -> FuseIcons.CircleHelp
                },
                size = Size.iconS,
                tint = when (check) {
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.VERIFIED -> c.success
                    io.github.matiyaaa.fuse.ui.shell.store.CopyCheck.DIFFERENT -> c.warning
                    else -> c.textMuted
                },
            )
            Spacer(Modifier.width(Space.s))
            FText(io.github.matiyaaa.fuse.ui.shell.reach.checkWords(check), Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        FText(
            listOfNotNull(copy.sizeBytes.takeIf { it > 0 }?.let(::bytesText), io.github.matiyaaa.fuse.ui.shell.reach.copyDate(copy, now)).joinToString("  ·  ").ifEmpty { " " },
            Fuse.type.caption, color = c.textFaint, maxLines = 1, modifier = Modifier.padding(top = Space.s),
        )
    }
}

/** Launchers Fuse lists among emulators that only run files of their own kind, never a game's ROM. */
private val NOT_EMULATORS = setOf("linux.script", "linux.desktop")

/**
 * The button [from] moves to in [direction] by where the buttons are, when they wrap onto more than
 * one line: Up or Down to the nearest one on the line above or below, Left or Right only along its
 * own line. Null leaves the move to the row's own order (one line, or nothing there).
 */
internal fun spatialStep(rects: Map<Int, androidx.compose.ui.geometry.Rect>, from: Int, direction: NavAction): Int? {
    val here = rects[from] ?: return null
    if (rects.values.map { it.center.y.toInt() / 4 }.distinct().size < 2) return null
    fun sameLine(r: androidx.compose.ui.geometry.Rect) = r.center.y in here.top..here.bottom
    val candidates = rects.filter { (i, r) ->
        i != from && when (direction) {
            NavAction.DOWN -> r.top >= here.bottom - 1f
            NavAction.UP -> r.bottom <= here.top + 1f
            NavAction.RIGHT -> sameLine(r) && r.left >= here.right - 1f
            NavAction.LEFT -> sameLine(r) && r.right <= here.left + 1f
            else -> false
        }
    }
    if (candidates.isEmpty()) return null
    return candidates.minByOrNull { (_, r) ->
        val dy = kotlin.math.abs(r.center.y - here.center.y)
        val dx = kotlin.math.abs(r.center.x - here.center.x)
        if (direction == NavAction.UP || direction == NavAction.DOWN) dy * 4 + dx else dx + dy * 4
    }?.key
}

/**
 * A row reaching [x] past its left edge, with that much padding inside: its first item sits where it
 * did, and has room to grow when selected instead of being cut off at the edge.
 */
private fun Modifier.bleedStart(x: Dp): Modifier = layout { measurable, constraints ->
    val px = x.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + px, maxWidth = constraints.maxWidth + px) else constraints
    val p = measurable.measure(wide)
    layout((p.width - px).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}
