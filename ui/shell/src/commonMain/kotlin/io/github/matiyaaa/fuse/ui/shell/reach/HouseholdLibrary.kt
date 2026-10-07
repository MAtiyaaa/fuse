package io.github.matiyaaa.fuse.ui.shell.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.focus.SectionedGridSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberPageState
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdState

/** "12 games on Gaming PC and Thor", or what is missing for there to be any. */
fun householdWords(s: HouseholdState): String {
    val withGames = s.devices.filter { it.games > 0 }
    val where = when (withGames.size) {
        0 -> null
        1 -> withGames[0].name
        2 -> "${withGames[0].name} and ${withGames[1].name}"
        else -> "${withGames[0].name} and ${withGames.size - 1} other devices"
    }
    return when {
        !s.supported -> "Update Fuse on the host computer to see your other devices' games"
        s.games == 0 && where == null -> "Your other devices' games show here once they share them"
        s.games == 0 -> "Everything on $where is on this device too"
        else -> "${if (s.games == 1) "1 game" else "${s.games} games"} on $where that this device doesn't have"
    }
}

/**
 * The Sync tab's way into the Remote Library: the newest of the other devices' games as small
 * covers, and how many there are where. Confirming it opens the Remote Library.
 */
@Composable
fun HouseholdCard(app: AppState, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Fuse.colors
    val state by app.store.reach.state.collectAsState()
    val recent by app.store.reach.recent.collectAsState()
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val ring = if (selected) c.focus else c.hairline
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.surface.copy(alpha = 0.72f)).border(if (selected) Size.focusStroke else 1.dp, ring, shape)
            .fuseClickable(shape = shape, onClick = onClick).padding(Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.MonitorSmartphone, size = 16.dp, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText("Remote Library", Fuse.type.bodyStrong, maxLines = 1)
            FText(householdWords(state), Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
        // The newest few, overlapping like a fanned hand.
        val covers = recent.take(4)
        if (covers.isNotEmpty()) {
            Spacer(Modifier.width(Space.m))
            Box(Modifier.width(36.dp * (covers.size - 1) + 48.dp).height(48.dp)) {
                covers.forEachIndexed { i, g ->
                    Box(Modifier.offset(x = 36.dp * i).size(48.dp).clip(RoundedCornerShape(Fuse.geometry.control)).border(1.dp, c.surface, RoundedCornerShape(Fuse.geometry.control))) {
                        Artwork(g.card.art.tile, Modifier.fillMaxSize(), fallback = { GeneratedArt(g.card.title, g.card.accent.toColor(), slot = ArtSlot.BOX, label = g.card.platformShort) })
                    }
                }
            }
        }
        Spacer(Modifier.width(Space.m))
        FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted)
    }
}

/**
 * The Remote Library: the household's other devices' games this device doesn't have, as RomM's
 * page shows a server's: a slim line with where they are and the few things to do, then what was
 * added lately and the systems. Opens at once and stays browsable while a device or the host is
 * away (Fuse keeps what it learned). With RomM set up, the same games also show at the foot of the
 * RomM tab; here they can always be brought or sent, and sent to RomM from where they are.
 */
@Composable
fun HouseholdLibraryScreen(app: AppState) {
    val ops = app.store.reach
    val state by ops.state.collectAsState()
    val recent by ops.recent.collectAsState()
    val systems by ops.systems.collectAsState()
    val romm by app.store.romm.state.collectAsState()
    val library = rememberSystems(app)
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val rows = buildList {
        add("head")
        if (recent.isNotEmpty()) add("recent")
        if (systems.isNotEmpty()) add("systems")
    }
    fun sizeOf(key: String) = when (key) {
        "head" -> 2
        "recent" -> recent.size
        "systems" -> systems.size
        else -> 0
    }
    val sel = rememberPageState(app.navigator, "household") { ShelfSelection() }
    sel.clamp(rows, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: "head"
    val col = sel.column(row)
    val list = rememberLazyListState()
    fun openGame(g: HouseholdGame) = app.go(Route.GameInfo(g.id))
    fun options(g: HouseholdGame) = app.openContextMenu(householdMenu(app, g, romm = romm.canUpload))
    val head = listOf(
        "All Games" to { app.go(Route.HouseholdGames(null, "All games")) },
        "Downloads" to { app.go(Route.Downloads) },
    )
    val shown = if (row == "recent") recent.getOrNull(col) else if (row == "head") recent.firstOrNull() else null
    val shownSystem = if (row == "systems") systems.getOrNull(col)?.let { householdSystemCard(it, library) } else null
    PageEffect(focused, shown?.id, shown?.card?.art, shownSystem?.art) {
        if (!focused) return@PageEffect
        app.hero = when {
            shownSystem != null -> io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource(shownSystem.platform.id, shownSystem.art.hero, shownSystem.platform.accent.toColor())
            shown != null -> shown.card.room(library[shown.card.platformId])
            else -> null
        }
    }
    PageEffect(focused, row, col) {
        if (!focused) return@PageEffect
        app.hints = when (row) {
            "head", "systems" -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, rows, ::sizeOf).let { r ->
                if (r == NavResult.IGNORED && (e.action == NavAction.LEFT || e.action == NavAction.RIGHT)) NavResult.BLOCKED else r
            }
            NavAction.SELECT -> {
                when (row) {
                    "head" -> head.getOrNull(col)?.second?.invoke()
                    "recent" -> recent.getOrNull(col)?.let(::openGame)
                    "systems" -> systems.getOrNull(col)?.let { app.go(Route.HouseholdGames(it.platform, it.name)) }
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> {
                if (row == "recent") recent.getOrNull(col)?.let(::options)
                NavResult.ACTIVATED
            }
            else -> NavResult.IGNORED
        }
    }
    LaunchedEffect(sel.row) { list.animateScrollToItem(sel.row.coerceAtLeast(0)) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("household")) {
        val tile = LocalTileMetrics.current.icon
        val compact = maxHeight < 560.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).fadingEdges(list, top = Space.xl, bottom = Space.xl),
            contentPadding = PaddingValues(top = Space.m, bottom = Size.hintHeight + Space.l),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            item("head") { LibraryHead(state, head.map { it.first }, if (focused && row == "head") col else -1, compact, Modifier.padding(horizontal = Space.gutter)) { i -> sel.row = 0; sel.setColumn("head", i); head[i].second() } }
            if ("recent" in rows) item("recent") {
                HouseholdShelf("Recently Added", FuseIcons.Clock, recent, if (focused && row == "recent") col else -1, tile, null, onOpen = ::openGame, onOptions = ::options)
            }
            if ("systems" in rows) item("systems") {
                HouseholdSystemShelf(systems, library, if (focused && row == "systems") col else -1, tile) { app.go(Route.HouseholdGames(it.platform, it.name)) }
            }
            if (rows.size == 1) item("empty") {
                FText(householdWords(state), Fuse.type.body, color = Fuse.colors.textMuted, modifier = Modifier.padding(horizontal = Space.gutter, vertical = Space.l))
            }
        }
    }
}

/** The Remote Library's line: its name, its devices (each around or away), and the few things to do. */
@Composable
private fun LibraryHead(state: HouseholdState, actions: List<String>, selected: Int, compact: Boolean, modifier: Modifier, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 40.dp else 52.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.MonitorSmartphone, size = if (compact) 20.dp else 26.dp, tint = c.accent)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FText("Remote Library", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    state.devices.take(4).forEach { d -> DeviceChip(d.name, deviceIcon(d.platform), d.online) }
                }
            }
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                actions.forEachIndexed { i, a ->
                    io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton(
                        a, selected = selected == i, icon = if (i == 0) FuseIcons.LayoutList else FuseIcons.Download,
                        kind = if (i == 0) io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind.PRIMARY else io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind.SECONDARY,
                        onClick = { onAction(i) },
                    )
                }
            }
        }
        FText(householdWords(state), Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/** A device, quiet beside the title: its picture, its name, and a dot when it is around. */
@Composable
internal fun DeviceChip(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, online: Boolean) {
    val c = Fuse.colors
    Row(
        Modifier.clip(CircleShape).background(c.text.copy(alpha = 0.06f)).padding(horizontal = Space.s, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 14.dp, tint = c.textMuted)
        Spacer(Modifier.width(4.dp))
        FText(name, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (online) c.success else c.textFaint))
    }
}

/** One system's games on the household's other devices (or every system's), in Fuse's grid. */
@Composable
fun HouseholdGamesScreen(app: AppState, platform: PlatformId?, name: String) {
    val ops = app.store.reach
    val flow = remember(platform) { ops.games(platform) }
    val games by flow.collectAsState(initial = null)
    val list = games.orEmpty()
    val romm by app.store.romm.state.collectAsState()
    val sel = rememberPageState(app.navigator, "household.grid.${platform?.value ?: "all"}") { SectionedGridSelection() }
    val section = GameSection(
        "main", name, FuseIcons.MonitorSmartphone, null,
        list.map { g ->
            SectionItem(
                g.id.value, g.card,
                tile = { selected, size: Dp, onClick, onLong -> HouseholdTile(g, selected, size, onClick = onClick, onLongClick = onLong) },
                open = { app.go(Route.GameInfo(g.id)) },
                options = { app.openContextMenu(householdMenu(app, g, romm = romm.canUpload)) },
            )
        },
    )
    SectionedGameGrid(app, sel, listOf(section), loading = games == null, empty = "No other device has games here this device doesn't.", tag = "household.grid") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.MonitorSmartphone, size = 20.dp, tint = Fuse.colors.accent)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText(name, Fuse.type.title, maxLines = 1)
                val holders = list.flatMap { it.holders }.distinct()
                FText(
                    listOfNotNull("${list.size} ${if (list.size == 1) "game" else "games"} not on this device", holders.takeIf { it.isNotEmpty() }?.let { "on " + it.joinToString(", ") }).joinToString("  ·  "),
                    Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                )
            }
        }
    }
}
