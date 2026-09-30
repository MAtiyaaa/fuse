package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ContentKind
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.MediaOwner
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressRing
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
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
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.collectionPicker
import io.github.matiyaaa.fuse.ui.shell.app.emulatorPicker
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import kotlinx.coroutines.launch

private data class DetailAction(val id: String, val label: String?, val icon: ImageVector, val primary: Boolean = false, val run: () -> Unit)

/**
 * Game Info. The room stays lit by the same art you selected, so arriving here feels like stepping
 * closer rather than changing screens. Play is always first and already selected.
 */
@Composable
fun GameScreen(app: AppState, id: GameId) {
    val flow = remember(id) { app.store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val d = detail
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        return
    }
    GameDetailContent(app, d)
}

@Composable
private fun GameDetailContent(app: AppState, d: GameDetail) {
    val c = Fuse.colors
    val game = d.game
    val card = remember(d) { d.toCard() }
    val accent = d.platform.accent.toColor()
    val sel = remember(game.id) { ShelfSelection() }
    val scroll = rememberScrollState()

    val actions = listOf(
        DetailAction("play", "Play", FuseIcons.Play, primary = true) { app.play(card) },
        DetailAction("media", "Media", FuseIcons.Images) { app.go(Route.Media(MediaOwner.OfGame(game.id), game.displayTitle)) },
        DetailAction("emu", d.emulator.selected?.name ?: "Choose emulator", FuseIcons.Chip) { app.emulatorPicker(card) },
        DetailAction("fav", null, FuseIcons.Heart) {
            app.scope.launch { app.store.library.setFavorite(game.id, !game.favorite) }
        },
        DetailAction("col", null, FuseIcons.ListPlus) { app.collectionPicker(game.id, game.displayTitle) },
        DetailAction("more", null, FuseIcons.More) { app.openContextMenu(app.gameMenu(card, fromDetail = true)) },
    )
    val discs = game.discs
    val badges = d.achievements?.achievements.orEmpty().sortedByDescending { it.earnedAt ?: it.earnedHardcoreAt ?: 0 }.take(24)
    val shots = d.media.screenshots
    val rows = buildList {
        add("actions")
        if (discs.size > 1) add("discs")
        if (badges.isNotEmpty()) add("achievements")
        if (shots.isNotEmpty()) add("shots")
    }
    fun sizeOf(key: String) = when (key) {
        "actions" -> actions.size
        "discs" -> discs.size
        "achievements" -> badges.size
        "shots" -> shots.size
        else -> 0
    }
    val row = rows.getOrNull(sel.row) ?: "actions"
    val col = sel.column(row)

    LaunchedEffect(game.id) {
        app.hero = HeroSource(game.id, d.art.hero ?: d.art.grid, accent, d.art.heroFocusX, d.art.heroFocusY, d.art.video)
    }
    LaunchedEffect(row, col) {
        app.hints = when (row) {
            "discs" -> listOf(Hint(HintButton.CONFIRM, "Play this disc"), Hint(HintButton.BACK, "Back"))
            "actions" -> listOf(Hint(HintButton.CONFIRM, if (col == 0) "Play" else "Choose"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.BACK, "Back"))
        }
        // Scroll the page so lower sections come into view as you move down.
        scroll.animateScrollTo(if (sel.row == 0) 0 else (scroll.maxValue * (sel.row.toFloat() / (rows.size - 1).coerceAtLeast(1))).toInt())
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let {
                if (it == NavResult.IGNORED && e.action == NavAction.LEFT) NavResult.BLOCKED else it
            }
            NavAction.SELECT -> {
                when (row) {
                    "actions" -> actions.getOrNull(col)?.run?.invoke()
                    "discs" -> discs.getOrNull(col)?.let { disc ->
                        app.scope.launch { app.store.library.launch(game.id, discPath = disc.path) }
                    }
                    else -> Unit
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { app.openContextMenu(app.gameMenu(card, fromDetail = true)); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 820.dp
        val maxH = maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = Space.gutter),
        ) {
            Spacer(Modifier.height(Size.hudHeight + (maxH * 0.12f)))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Box(Modifier.size(7.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                        FText(d.platform.name.uppercase(), Fuse.type.overline, color = c.textMuted)
                    }
                    Spacer(Modifier.height(Space.m))
                    if (d.art.logo != null && app.store.prefs.value.showLogo) {
                        Artwork(
                            d.art.logo,
                            Modifier.heightIn(max = 140.dp).height(140.dp).fillMaxWidth(0.7f),
                            contentScale = ContentScale.Fit,
                            focusX = 0f,
                            focusY = 1f,
                            fallback = { FText(game.displayTitle, Fuse.type.hero, maxLines = 3) },
                        )
                    } else {
                        FText(game.displayTitle, Fuse.type.hero, maxLines = 3, modifier = Modifier.widthIn(max = 760.dp))
                    }
                    Spacer(Modifier.height(Space.m))
                    val meta = listOfNotNull(
                        game.metadata.releaseYear?.toString(),
                        game.metadata.developer,
                        game.metadata.genres.take(2).joinToString(", ").ifBlank { null },
                        game.metadata.players?.let { "$it players" },
                    )
                    if (meta.isNotEmpty()) FText(meta.joinToString("  ·  "), Fuse.type.body, color = c.textMuted, maxLines = 1)
                    Spacer(Modifier.height(Space.xl))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        actions.forEachIndexed { i, a ->
                            val selected = row == "actions" && col == i && app.focusZone == FocusZone.CONTENT
                            if (a.label != null) {
                                FuseButton(
                                    a.label, selected = selected, icon = a.icon,
                                    kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                    height = if (a.primary) 56.dp else 48.dp,
                                    enabled = !a.primary || d.emulator.canLaunch || d.emulator.selected != null,
                                    onClick = { sel.row = 0; sel.setColumn("actions", i); a.run() },
                                )
                            } else {
                                IconButton(
                                    a.icon, selected = selected,
                                    tint = if (a.id == "fav" && game.favorite) c.accent else c.text,
                                    onClick = { sel.row = 0; sel.setColumn("actions", i); a.run() },
                                )
                            }
                        }
                    }
                    d.emulator.launchSummary?.let {
                        Spacer(Modifier.height(Space.m))
                        FText(it, Fuse.type.caption, color = c.textMuted, maxLines = 2)
                    }
                }
                if (wide) {
                    Spacer(Modifier.width(Space.xxl))
                    Tile(selected = false, showSpark = false, glow = accent, modifier = Modifier.width(200.dp).aspectRatio(Aspect.BOX)) {
                        Artwork(
                            d.art.boxart ?: d.art.grid ?: d.art.icon, Modifier.fillMaxSize(),
                            fallback = { GeneratedArt(game.displayTitle, accent, slot = ArtSlot.BOX, label = d.platform.shortName) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xxl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Stat("Played", if (game.play.totalSeconds > 0) playtimeText(game.play.totalSeconds) else "Not yet")
                Stat("Last played", game.play.lastPlayedAt?.let { agoText(it) } ?: "Never")
                if (d.secondsThisWeek > 0) Stat("This week", playtimeText(d.secondsThisWeek))
                d.achievements?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        Box(contentAlignment = Alignment.Center) {
                            ProgressRing(a.progress, size = 48.dp)
                            FText("${(a.progress * 100).toInt()}", Fuse.type.label)
                        }
                        Column {
                            SectionLabel(if (a.mastered) "Mastered" else "Achievements")
                            FText("${a.earned} of ${a.total}  ·  ${a.pointsEarned} pts", Fuse.type.bodyStrong)
                        }
                    }
                }
            }
            game.metadata.description?.let {
                Spacer(Modifier.height(Space.xl))
                FText(it, Fuse.type.body, color = c.textMuted, maxLines = 5, modifier = Modifier.widthIn(max = 820.dp))
            }
            if (discs.size > 1) {
                Section("Discs") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        discs.forEachIndexed { i, disc ->
                            FuseButton(disc.label, selected = row == "discs" && col == i, icon = FuseIcons.Disc, onClick = {
                                app.scope.launch { app.store.library.launch(game.id, discPath = disc.path) }
                            })
                        }
                    }
                }
            }
            if (badges.isNotEmpty()) {
                Section("Achievements") {
                    val list = rememberLazyListState()
                    FollowSelection(list, { sel.column("achievements") }, anchor = 0.2f)
                    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(Space.m), contentPadding = PaddingValues(end = Space.gutter)) {
                        itemsIndexed(badges, key = { _, a -> a.id }) { i, a ->
                            val selected = row == "achievements" && col == i
                            val lift by animateFloatAsState(if (selected) 1f else 0f, Fuse.motion.focusSpring(), label = "badge")
                            Column(Modifier.width(96.dp).graphicsLayer { val s = 1f + 0.08f * lift; scaleX = s; scaleY = s }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Artwork(if (a.earned) a.badgeUrl else a.badgeLockedUrl, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                                Spacer(Modifier.height(Space.xs))
                                FText(a.title, Fuse.type.caption, color = if (selected) c.text else c.textMuted, maxLines = 2)
                            }
                        }
                    }
                    badges.getOrNull(col.takeIf { row == "achievements" } ?: -1)?.let { a ->
                        Spacer(Modifier.height(Space.s))
                        FText("${a.title}: ${a.description} (${a.points} pts)", Fuse.type.body, color = c.textMuted, maxLines = 2)
                    }
                }
            }
            if (shots.isNotEmpty()) {
                Section("Screenshots") {
                    val list = rememberLazyListState()
                    FollowSelection(list, { sel.column("shots") }, anchor = 0.1f)
                    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        itemsIndexed(shots) { i, s ->
                            Tile(selected = row == "shots" && col == i, modifier = Modifier.height(150.dp).aspectRatio(Aspect.SCREENSHOT)) {
                                Artwork(s.model, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
            ContentSection(d)
            InfoSection(d)
            Spacer(Modifier.height(Size.hintHeight + Space.x4))
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        SectionLabel(label)
        Spacer(Modifier.height(Space.xs))
        FText(value, Fuse.type.titleSmall, maxLines = 1)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(Space.xxl))
    SectionLabel(title)
    Spacer(Modifier.height(Space.m))
    content()
}

@Composable
private fun ContentSection(d: GameDetail) {
    val game = d.game
    if (game.content.isEmpty()) return
    val c = Fuse.colors
    Section("Updates and extras") {
        Panel(Modifier.widthIn(max = 820.dp)) {
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                val groups = game.content.groupBy { it.kind }
                for ((kind, items) in groups) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                        FuseIcon(kindIcon(kind), tint = c.textMuted)
                        FText(kindLabel(kind, items.size), Fuse.type.bodyStrong)
                    }
                    d.contentNotes.firstOrNull { it.kind == kind }?.let {
                        FText(it.message, Fuse.type.caption, color = c.textMuted, maxLines = 3)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(d: GameDetail) {
    val c = Fuse.colors
    val game = d.game
    Section("Details") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.widthIn(max = 820.dp)) {
            Info("File", game.location.path)
            if (game.location.launchPath != game.location.path) Info("Launches", game.location.launchPath)
            Info("Read as", when (game.location.interpretation) {
                io.github.matiyaaa.fuse.model.FolderInterpretation.SINGLE_FILE -> "A single file"
                io.github.matiyaaa.fuse.model.FolderInterpretation.FOLDER_IS_GAME -> "A folder that is the game"
                io.github.matiyaaa.fuse.model.FolderInterpretation.MULTI_FILE_GAME -> "A game folder with extras (RomM layout)"
                io.github.matiyaaa.fuse.model.FolderInterpretation.MULTI_DISC -> "A multi-disc set"
                io.github.matiyaaa.fuse.model.FolderInterpretation.FOLDER_BROWSER -> "A folder you pick from"
            })
            game.metadata.publisher?.let { Info("Publisher", it) }
            if (game.titles.original != game.displayTitle) Info("File name title", game.titles.original)
            game.tags.regions.takeIf { it.isNotEmpty() }?.let { Info("Region", it.joinToString(", ")) }
            if (d.collections.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    d.collections.forEach { Chip(it.name, icon = FuseIcons.Bookmark, color = c.textMuted) }
                }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row {
        FText(label, Fuse.type.label, color = Fuse.colors.textMuted, modifier = Modifier.width(140.dp))
        FText(value, Fuse.type.label, maxLines = 2)
    }
}

private fun kindIcon(kind: ContentKind): ImageVector = when (kind) {
    ContentKind.UPDATE, ContentKind.PATCH -> FuseIcons.Refresh
    ContentKind.DLC -> FuseIcons.Package
    ContentKind.MANUAL, ContentKind.WALKTHROUGH -> FuseIcons.File
    ContentKind.SOUNDTRACK -> FuseIcons.Music
    ContentKind.SCREENSHOT -> FuseIcons.Image
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

