package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.FolderInterpretation
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.LocationKind
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
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
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.collectionPicker
import io.github.matiyaaa.fuse.ui.shell.app.emulatorPicker
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.gameRoom
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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
private enum class InfoCard { STARTS, PLAY, EXTRAS, FILE }

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
fun GameScreen(app: AppState, id: GameId) {
    val flow = remember(id) { app.store.library.game(id).map { d -> if (d == null) GameLoad.Gone else GameLoad.Ready(d) } }
    val load by flow.collectAsState(initial = GameLoad.Loading)
    // A short fade from the placeholder to the page, never a jump.
    Crossfade(load is GameLoad.Ready, animationSpec = Fuse.motion.fade(), label = "gameLoad") { ready ->
        when {
            ready -> (load as? GameLoad.Ready)?.let { GameDetailContent(app, it.detail) }
            load == GameLoad.Gone -> GameGone(app)
            else -> GameSkeleton()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameDetailContent(app: AppState, d: GameDetail) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val game = d.game
    val card = remember(d) { d.toCard() }
    val accent = d.platform.accent.toColor()
    val sel = remember(game.id) { ShelfSelection() }
    val scroll = rememberScrollState()
    val reveal = rememberReveal(game.id)

    // Play first, then the emulator it starts in, then the quick actions, then everything else.
    val actions = listOf(
        DetailAction("play", "Play", FuseIcons.Play, "Play", primary = true) { app.play(card) },
        DetailAction("emu", d.emulator.selected?.name ?: "Choose emulator", if (d.emulator.selected == null) FuseIcons.Warning else FuseIcons.Chip, "Choose emulator") {
            app.emulatorPicker(card)
        },
        DetailAction("fav", null, FuseIcons.Heart, if (game.favorite) "Remove from favourites" else "Add to favourites") {
            app.scope.launch { app.store.library.setFavorite(game.id, !game.favorite) }
        },
        DetailAction("col", null, FuseIcons.ListPlus, "Add to a collection") { app.collectionPicker(game.id, game.displayTitle) },
        DetailAction("media", null, FuseIcons.Images, "Manage media") { app.go(Route.Media(MediaOwner.OfGame(game.id), game.displayTitle)) },
        DetailAction("more", null, FuseIcons.More, "Options") { app.openContextMenu(app.gameMenu(card, fromDetail = true)) },
    )
    val discs = game.discs
    val badges = d.achievements?.achievements.orEmpty().sortedByDescending { it.earnedAt ?: it.earnedHardcoreAt ?: 0 }.take(24)
    val shots = d.media.screenshots
    val cards = buildList {
        add(InfoCard.STARTS)
        add(InfoCard.PLAY)
        if (game.content.isNotEmpty()) add(InfoCard.EXTRAS)
        add(InfoCard.FILE)
    }
    val rows = buildList {
        add("actions")
        if (discs.size > 1) add("discs")
        if (badges.isNotEmpty()) add("achievements")
        if (shots.isNotEmpty()) add("shots")
        add("details")
    }
    fun sizeOf(key: String) = when (key) {
        "actions" -> actions.size
        "discs" -> discs.size
        "achievements" -> badges.size
        "shots" -> shots.size
        "details" -> cards.size
        else -> 0
    }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "actions"
    val col = sel.column(row)
    val focused = app.focusZone == FocusZone.CONTENT

    fun openCard(info: InfoCard) {
        if (info == InfoCard.STARTS) app.emulatorPicker(card)
    }

    val systems = rememberSystems(app)
    val system = systems[d.platform.id]
    LaunchedEffect(game.id, d.art, system?.art) {
        app.hero = gameRoom(game.id, d.art, d.platform.accent, system)
    }
    val confirm = when (row) {
        "actions" -> actions.getOrNull(col)?.name
        "discs" -> "Play this disc"
        "details" -> if (cards.getOrNull(col) == InfoCard.STARTS) "Choose emulator" else null
        else -> null
    }
    // On the More button, confirming already opens the options, so the line says it once.
    val onMore = row == "actions" && actions.getOrNull(col)?.id == "more"
    LaunchedEffect(row, confirm, onMore) {
        app.hints = listOfNotNull(
            confirm?.let { Hint(HintButton.CONFIRM, it) },
            if (onMore) null else Hint(HintButton.OPTIONS, "Options"),
            Hint(HintButton.BACK, "Back"),
        )
    }

    // Where each section starts in the page, so moving down brings the whole section into view.
    val tops = remember(game.id) { mutableStateMapOf<String, Int>() }
    val inset = with(LocalDensity.current) { Space.l.roundToPx() }
    LaunchedEffect(row, tops[row]) {
        val target = if (sel.row == 0) 0 else ((tops[row] ?: 0) - inset).coerceIn(0, scroll.maxValue)
        scroll.animateScrollTo(target, motion.followSpring())
    }

    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let {
                if (it == NavResult.IGNORED && e.action == NavAction.LEFT) NavResult.BLOCKED else it
            }
            NavAction.SELECT -> {
                when (row) {
                    "actions" -> actions.getOrNull(col)?.run?.invoke()
                    "discs" -> discs.getOrNull(col)?.let { disc -> app.play(card, discPath = disc.path) }
                    "details" -> cards.getOrNull(col)?.let(::openCard)
                    else -> Unit
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { app.openContextMenu(app.gameMenu(card, fromDetail = true)); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = GameLayout.of(maxWidth, maxHeight)
        // The page scrolls below the top line, softening into it and into the hint line.
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = Size.hudHeight)
                .fadingEdges(scroll, top = Space.xl, bottom = Size.hintHeight + Space.l)
                .verticalScroll(scroll)
                .padding(horizontal = Space.gutter),
        ) {
            Spacer(Modifier.height(layout.top - Size.hudHeight))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    GameTitle(d, layout, app.store.prefs.value.showLogo, Modifier.reveal(reveal, 0))
                    Spacer(Modifier.height(Space.l))
                    FactChips(d, Modifier.reveal(reveal, 1))
                    Spacer(Modifier.height(Space.xl))
                    FlowRow(
                        Modifier.reveal(reveal, 2),
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        actions.forEachIndexed { i, a ->
                            val selected = row == "actions" && col == i && focused
                            val tap = { sel.row = 0; sel.setColumn("actions", i); a.run() }
                            if (a.label != null) {
                                FuseButton(
                                    a.label, selected = selected, icon = a.icon,
                                    kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                    height = if (a.primary) Size.row else Size.touch,
                                    // The emulator reads as a choice: its name, and a chevron for "pick another".
                                    trailingIcon = if (a.id == "emu") FuseIcons.ChevronDown else null,
                                    // Play stays pressable without an emulator: pressing it explains what to install.
                                    enabled = !a.primary || d.emulator.canLaunch || d.emulator.selected != null,
                                    modifier = Modifier.align(Alignment.CenterVertically)
                                        .then(if (a.primary) Modifier.widthIn(min = Size.touch * 3) else Modifier),
                                    onClick = tap,
                                )
                            } else {
                                IconButton(
                                    a.icon, selected = selected,
                                    tint = if (a.id == "fav" && game.favorite) c.accent else c.text,
                                    contentDescription = a.name,
                                    modifier = Modifier.align(Alignment.CenterVertically),
                                    onClick = tap,
                                )
                            }
                        }
                    }
                    LaunchNote(d, Modifier.reveal(reveal, 3))
                }
                layout.cover?.let { cover ->
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
            game.metadata.description?.takeIf { it.isNotBlank() }?.let {
                Column(Modifier.section("about", 4).padding(bottom = Space.xxl)) {
                    SectionLabel("About")
                    Spacer(Modifier.height(Space.m))
                    FText(it, Fuse.type.body, color = c.textMuted, maxLines = 6, modifier = Modifier.widthIn(max = layout.reading))
                }
            }
            if (discs.size > 1) {
                Section("Discs", Modifier.section("discs", 5), count = discs.size.toString()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        discs.forEachIndexed { i, disc ->
                            FuseButton(disc.label, selected = row == "discs" && col == i && focused, icon = FuseIcons.Disc, onClick = {
                                sel.row = rows.indexOf("discs"); sel.setColumn("discs", i)
                                app.play(card, discPath = disc.path)
                            })
                        }
                    }
                }
            }
            d.achievements?.let { a ->
                Section("Achievements", Modifier.section("achievements", 6)) {
                    AchievementSummary(a.progress, a.earned, a.total, a.pointsEarned, a.points, a.mastered, a.matchedByName)
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        val list = rememberLazyListState()
                        FollowSelection(list, { sel.column("achievements") }, anchor = 0.2f)
                        // Room above and below for a badge's lift and its spark.
                        LazyRow(
                            state = list,
                            horizontalArrangement = Arrangement.spacedBy(Space.l),
                            contentPadding = PaddingValues(top = Space.s, end = Space.gutter, bottom = Space.xs),
                        ) {
                            itemsIndexed(badges, key = { _, b -> b.id }) { i, b ->
                                val selected = row == "achievements" && col == i && focused
                                Column(Modifier.width(Size.thumbL + Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Tile(
                                        selected = selected,
                                        modifier = Modifier.size(Size.thumbL),
                                        onClick = { sel.row = rows.indexOf("achievements"); sel.setColumn("achievements", i) },
                                    ) {
                                        Artwork(if (b.earned) b.badgeUrl else b.badgeLockedUrl, Modifier.fillMaxSize())
                                    }
                                    Spacer(Modifier.height(Size.sparkClearance))
                                    FText(b.title, Fuse.type.caption, color = if (selected) c.text else c.textMuted, maxLines = 2, minLines = 2)
                                }
                            }
                        }
                        badges.getOrNull(col.takeIf { row == "achievements" } ?: -1)?.let { b ->
                            Spacer(Modifier.height(Space.s))
                            FText("${b.title}: ${b.description} (${b.points} points)", Fuse.type.body, color = c.textMuted, maxLines = 2, modifier = Modifier.widthIn(max = layout.reading))
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
                        horizontalArrangement = Arrangement.spacedBy(Space.m),
                        contentPadding = PaddingValues(top = Space.s, end = Space.gutter, bottom = Size.sparkClearance),
                    ) {
                        itemsIndexed(shots) { i, s ->
                            Tile(
                                selected = row == "shots" && col == i && focused,
                                modifier = Modifier.height(LocalTileMetrics.current.icon).aspectRatio(Aspect.SCREENSHOT),
                                onClick = { sel.row = rows.indexOf("shots"); sel.setColumn("shots", i) },
                            ) {
                                Artwork(s.model, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            Section("Details", Modifier.section("details", 8)) {
                val size by produceState(SIZE_LOADING, game.id) { value = app.store.storage.size(game.id) ?: SIZE_UNKNOWN }
                // Cards in a line share one height; a line holds as many as fit a readable width,
                // and four make two even lines rather than three and one.
                val perLine = if (cards.size == 4 && layout.cardsPerLine == 3) 2 else layout.cardsPerLine.coerceAtMost(cards.size)
                cards.chunked(perLine).forEachIndexed { line, chunk ->
                    if (line > 0) Spacer(Modifier.height(Space.l))
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                        chunk.forEach { info ->
                            val i = cards.indexOf(info)
                            val selected = row == "details" && col == i && focused
                            val tap = {
                                sel.row = rows.indexOf("details"); sel.setColumn("details", i)
                                openCard(info)
                            }
                            val m = Modifier.weight(1f).fillMaxHeight()
                            when (info) {
                                InfoCard.STARTS -> StartsCard(d, selected, tap, m)
                                InfoCard.PLAY -> PlayCard(d, selected, tap, m)
                                InfoCard.EXTRAS -> ExtrasCard(d, selected, tap, m)
                                InfoCard.FILE -> FileCard(d, size, selected, tap, m)
                            }
                        }
                        repeat(perLine - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(Size.hintHeight + Space.x4))
        }
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
) {
    companion object {
        fun of(width: Dp, height: Dp): GameLayout {
            val content = width - Space.gutter * 2
            return GameLayout(
                top = Size.hudHeight + (height * 0.1f).coerceIn(Space.l, Space.x5),
                cover = if (width > Size.touch * 17) (height * 0.3f).coerceIn(Size.touch * 2, Size.touch * 5) else null,
                compact = height < Size.touch * 12 || width < Size.touch * 13,
                logoHeight = (height * 0.19f).coerceIn(Size.touch + Space.l, Size.touch * 3),
                reading = Size.touch * 16,
                cardsPerLine = when {
                    content >= Size.touch * 18 -> 3
                    content >= Size.touch * 10 -> 2
                    else -> 1
                },
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

/**
 * What Fuse knows about the game, as a line of quiet chips: its system first (with its colour),
 * then year, players and genre when a source said so, then how much you have played it and when,
 * and the collections it is in. Nothing is guessed: a fact Fuse doesn't have is simply left out.
 * The finer play history (this week, sessions) waits in its card further down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactChips(d: GameDetail, modifier: Modifier) {
    val game = d.game
    val meta = game.metadata
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        FactChip(d.platform.name, dot = d.platform.accent.toColor())
        meta.releaseYear?.let { FactChip(it.toString(), FuseIcons.Calendar) }
        meta.players?.let(::playersLabel)?.let { FactChip(it, FuseIcons.Users) }
        meta.genres.take(2).joinToString(", ").ifBlank { null }?.let { FactChip(it, FuseIcons.Tag) }
        if (game.play.totalSeconds > 0) {
            FactChip("${playtimeText(game.play.totalSeconds)} played", FuseIcons.Clock3)
        } else {
            FactChip("Not played yet", FuseIcons.Sparkle)
        }
        game.play.lastPlayedAt?.let { FactChip("Played ${agoText(it)}", FuseIcons.History) }
        // Updates and DLC found beside it, as a store page would list them; the card below says more.
        val updates = game.content.count { it.kind == ContentKind.UPDATE }
        val dlc = game.content.count { it.kind == ContentKind.DLC }
        listOfNotNull(
            updates.takeIf { it > 0 }?.let { if (it == 1) "1 update" else "$it updates" },
            dlc.takeIf { it > 0 }?.let { "$it DLC" },
        ).joinToString(", ").ifEmpty { null }?.let { FactChip(it, FuseIcons.Package) }
        when (d.collections.size) {
            0 -> Unit
            1, 2 -> d.collections.forEach { FactChip(it.name, FuseIcons.Bookmark) }
            else -> FactChip("In ${d.collections.size} collections", FuseIcons.Bookmark)
        }
    }
}

/** One fact: an icon (or a dot in a colour) and a few words, on a soft pill that reads over art. */
@Composable
private fun FactChip(text: String, icon: ImageVector? = null, dot: Color? = null) {
    val c = Fuse.colors
    Row(
        Modifier
            .height(Size.chipCompact)
            .background(c.surfaceDim.copy(alpha = if (c.isDark) 0.62f else 0.72f), PillShape)
            .border(Size.stroke, c.hairline, PillShape)
            .padding(start = if (dot != null) Space.m else Space.s + Space.xxs, end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s - Space.xxs),
    ) {
        when {
            dot != null -> Box(Modifier.size(Size.dot).background(dot, CircleShape))
            icon != null -> FuseIcon(icon, size = Size.iconXS, tint = c.textMuted)
        }
        FText(text, Fuse.type.label.tabular(), color = c.text, maxLines = 1)
    }
}

/**
 * A line under the buttons when the launch is not simply "starts in X": no emulator yet (a
 * warning), or an emulator that opens a folder or only its own app (how it will go).
 */
@Composable
private fun LaunchNote(d: GameDetail, modifier: Modifier) {
    val c = Fuse.colors
    val summary = launchNote(d) ?: return
    val missing = d.emulator.selected == null
    Row(modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        FuseIcon(if (missing) FuseIcons.Warning else FuseIcons.Info, size = Size.iconS, tint = if (missing) c.warning else c.textMuted)
        FText(summary, Fuse.type.caption, color = if (missing) c.text else c.textMuted, maxLines = 2)
    }
}

/** What [LaunchNote] says under the buttons, or null when the launch is simply "starts in X". */
private fun launchNote(d: GameDetail): String? {
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
            FText("$pointsEarned of $points points", Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
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
        val name = e.selected?.name
        FText(name ?: "No emulator yet", Fuse.type.titleSmall, color = if (name == null) c.warning else c.text, maxLines = 1)
        // Why this one, right under its name.
        val why = when {
            name == null -> null
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
        val others = e.alternatives.map { it.name }.filter { it != name }.distinct()
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
    InfoPanel("Play history", FuseIcons.Clock3, selected, onClick, modifier) {
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

/** Updates, DLC and other extras found next to the game, with what the emulator does with them. */
@Composable
private fun ExtrasCard(d: GameDetail, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    InfoPanel("Updates and extras", FuseIcons.Package, selected, onClick, modifier) {
        for ((kind, items) in d.game.content.groupBy { it.kind }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FuseIcon(kindIcon(kind), size = Size.iconS, tint = c.text)
                FText(kindLabel(kind, items.size), Fuse.type.bodyStrong, maxLines = 1)
            }
            d.contentNotes.firstOrNull { it.kind == kind }?.let {
                FText(it.message, Fuse.type.caption, color = c.textMuted, maxLines = 3, modifier = Modifier.padding(start = Size.iconS + Space.s, bottom = Space.xs))
            }
        }
    }
}

/** Where the game lives on this device and how Fuse reads it. */
@Composable
private fun FileCard(d: GameDetail, size: Long, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val game = d.game
    val loc = game.location
    InfoPanel("On this device", FuseIcons.HardDrive, selected, onClick, modifier) {
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
        game.tags.regions.takeIf { it.isNotEmpty() }?.let { Fact("Region", it.joinToString(", ")) }
        game.metadata.developer?.let { Fact("Developer", it) }
        game.metadata.publisher?.takeIf { it != game.metadata.developer }?.let { Fact("Publisher", it) }
        game.metadata.franchise?.takeIf { it.isNotBlank() }?.let { Fact("Series", it) }
        if (game.titles.original != game.displayTitle) Fact("File title", game.titles.original, lines = 2)
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
    val f by animateFloatAsState(if (selected) 1f else 0f, motion.focusSpring(), label = "card")
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
        val layout = GameLayout.of(maxWidth, maxHeight)
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
