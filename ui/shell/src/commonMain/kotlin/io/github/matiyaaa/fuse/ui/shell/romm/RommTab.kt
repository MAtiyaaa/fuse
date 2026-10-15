package io.github.matiyaaa.fuse.ui.shell.romm

import io.github.matiyaaa.fuse.ui.shell.reach.sendPicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.github.matiyaaa.fuse.integrations.net.RouteMode
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.integrations.net.NetRoute
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.room
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.RommCollectionCard
import io.github.matiyaaa.fuse.ui.shell.store.RommDownloadWhat
import io.github.matiyaaa.fuse.ui.shell.store.RommGame
import io.github.matiyaaa.fuse.ui.shell.store.RommLink
import io.github.matiyaaa.fuse.ui.shell.store.RommPresence
import io.github.matiyaaa.fuse.ui.shell.store.RommState
import io.github.matiyaaa.fuse.ui.shell.store.RommSystem
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.designsystem.input.dragScroll
import androidx.compose.foundation.gestures.Orientation

/**
 * Room above and below a shelf for its chosen tile: it lifts toward you (it grows and its glow
 * spreads), and without this it would cover the shelf's title above.
 */
@Composable
internal fun liftRoom(tile: Dp): Dp = tile * ((Fuse.motion.focusScale - 1f) / 2f) + Space.s

/** RomM's mark: Fuse's library drawn in its accent, not RomM's own logo (RomM is its own project). */
@Composable
internal fun RommMark(size: Dp) {
    val c = Fuse.colors
    Box(Modifier.size(size).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        FuseIcon(FuseIcons.LibraryBig, size = size * 0.5f, tint = c.accent)
    }
}

@Composable
internal fun ShelfTitle(title: String, icon: ImageVector, trailing: String?) {
    val c = Fuse.colors
    Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
        FuseIcon(icon, size = Size.iconS, tint = c.textMuted)
        Spacer(Modifier.width(Space.s))
        FText(title, Fuse.type.bodyStrong, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            FText(trailing, Fuse.type.caption, color = c.textFaint, maxLines = 1)
        }
    }
}

/** A shelf of RomM games, as Fuse tiles: the same art, the same rules, a quiet mark for what isn't here yet. */
@Composable
internal fun GameShelf(
    title: String,
    icon: ImageVector,
    games: List<RommGame>,
    selected: Int,
    tile: Dp,
    trailing: String?,
    onOpen: (RommGame) -> Unit,
    onOptions: (RommGame) -> Unit,
) {
    val state = rememberLazyListState()
    io.github.matiyaaa.fuse.ui.designsystem.focus.GlideTo(state, if (selected >= 0) maxOf(0, selected - 1) else -1)
    val room = liftRoom(tile)
    Column {
        ShelfTitle(title, icon, trailing)
        LazyRow(
            state = state,
            modifier = Modifier.dragScroll(state, Orientation.Horizontal),
            contentPadding = PaddingValues(horizontal = Space.gutter, vertical = room),
            horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap),
        ) {
            itemsIndexed(games, key = { _, g -> g.romId }) { i, g ->
                RommTile(g, selected == i, tile, onClick = { onOpen(g) }, onLongClick = { onOptions(g) })
            }
        }
        // The chosen game, named under its shelf: what it is and whether it is here.
        games.getOrNull(selected)?.let { g -> SelectedLine(g, Modifier.padding(horizontal = Space.gutter)) }
    }
}

@Composable
private fun SelectedLine(g: RommGame, modifier: Modifier) {
    val c = Fuse.colors
    val where = when (g.presence) {
        RommPresence.ON_SERVER -> "On the server"
        RommPresence.QUEUED -> "Waiting to download"
        RommPresence.DOWNLOADING -> "Downloading"
        RommPresence.INSTALLED -> "On this device"
        RommPresence.PARTLY_INSTALLED -> "Partly on this device"
    }
    Column(modifier) {
        FText(g.card.title, Fuse.type.bodyStrong, maxLines = 1)
        FText(
            listOfNotNull(g.card.platformShort, g.card.year?.toString(), where, io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf(g.sizeBytes).takeIf { g.sizeBytes > 0 }).joinToString("  ·  "),
            Fuse.type.caption, color = c.textMuted, maxLines = 1,
        )
    }
}

/** A RomM game's tile: the Fuse tile, with a small mark saying it is on the server, coming, or here. */
@Composable
internal fun RommTile(g: RommGame, selected: Boolean, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    Box(modifier) {
        GameIconTile(g.card, selected, size = size, onClick = onClick, onLongClick = onLongClick)
        val (icon, tint) = when (g.presence) {
            RommPresence.ON_SERVER -> FuseIcons.Cloud to c.text
            RommPresence.QUEUED -> FuseIcons.Hourglass to c.text
            RommPresence.DOWNLOADING -> FuseIcons.Download to c.accent
            RommPresence.INSTALLED, RommPresence.PARTLY_INSTALLED -> null to c.text
        }
        if (icon != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.surfaceOverlay),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(icon, size = 13.dp, tint = tint) }
        }
        if (g.isNew) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(c.accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                FText("NEW", Fuse.type.overline, color = c.onAccent, maxLines = 1)
            }
        }
    }
}

/** The Systems page's card for a RomM system, or one made from Fuse's catalogue for a system the library hasn't got yet. */
internal fun systemCard(s: RommSystem, library: Map<PlatformId, PlatformCard>): PlatformCard? {
    val id = s.platform ?: return null
    // On RomM's tab a system counts the server's games, not the library's.
    library[id]?.let { return it.copy(gameCount = s.games) }
    val p = io.github.matiyaaa.fuse.library.PlatformCatalog.byId(id) ?: return null
    // A system with no games here yet still has its art and colour, as on the Systems page.
    return PlatformCard(s.accent?.let { p.copy(accent = it) } ?: p, s.games, s.art, null, false, 0, io.github.matiyaaa.fuse.model.BiosStatus.NotRequired, p.defaultLayout, emptyList())
}

@Composable
internal fun Quiet(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = Space.xxl), contentAlignment = Alignment.Center) {
        FText(text, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 3)
    }
}

/**
 * What can be changed about a RomM game Fuse doesn't have, as about any game: its art, its details
 * found again, its name, or what sources said about it forgotten. Kept with RomM.
 */
internal fun rommEditActions(app: AppState, id: io.github.matiyaaa.fuse.model.GameId, title: String, owner: String = "RomM"): List<MenuAction> = listOf(
    MenuAction("media", "Manage Media", FuseIcons.Images, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = {
        app.closeOverlays(); app.go(Route.Media(io.github.matiyaaa.fuse.model.MediaOwner.OfGame(id), title))
    }),
    MenuAction("rescrape", "Find Details and Art", FuseIcons.Wand, detail = "Fills what's missing, including a proper title. Your own art and names stay", onSelect = {
        app.closeOverlays()
        app.store.media.fill(io.github.matiyaaa.fuse.model.MediaFillMode.FILL_MISSING, io.github.matiyaaa.fuse.model.MediaKind.Fillable, game = id)
        app.toasts.show("Looking for details and art for $title")
    }),
    MenuAction("rename", "Rename Display Title", FuseIcons.TextCursor, detail = "$owner keeps its own name", onSelect = {
        app.closeOverlays()
        app.textInput = io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec("Display title", title) { t ->
            app.scope.launch {
                app.store.library.rename(id, t.ifBlank { null })
                if (t.isNotBlank() && t.trim() != title && app.store.media.followRename(id, title)) app.toasts.show("Looking for details for ${t.trim()}")
            }
        }
    }),
    MenuAction("reset", "Reset Name and Details", FuseIcons.RotateCcw, detail = "For a game mixed up with another. Your own name and art stay", onSelect = {
        app.closeOverlays()
        app.confirm = io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec(
            "Reset $title?",
            "Fuse forgets the name, details and art sources gave this game and goes back to $owner's. Your own name and the art you chose stay. Find Details and Art looks again.",
            "Reset",
        ) { app.scope.launch { if (app.store.media.resetDetails(id)) app.toasts.show("Reset. It goes by $owner's name again") } }
    }),
)

/** A RomM game's options: open its page, download it (or what it is missing), or see it in Downloads. */
internal fun gameMenu(app: AppState, g: RommGame): ContextMenuSpec {
    val ops = app.store.romm
    fun queue(what: RommDownloadWhat) {
        app.closeOverlays()
        app.scope.launch {
            val why = ops.download(g.romId, what)
            if (why != null) app.toasts.show(why, io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.WARNING)
            else app.toasts.show("${g.card.title} is on its way", io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind.SUCCESS, icon = FuseIcons.Download)
        }
    }
    return ContextMenuSpec(
        title = g.card.title,
        subtitle = listOfNotNull(g.card.platformShort, g.card.year?.toString(), io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf(g.sizeBytes).takeIf { g.sizeBytes > 0 }).joinToString("  ·  "),
        art = g.card.art.square ?: g.card.art.icon ?: g.card.art.boxart,
        accent = g.card.accent,
        actions = buildList {
            add(MenuAction("romm.open", "Open", FuseIcons.Info, onSelect = { app.closeOverlays(); app.go(Route.GameInfo(g.game ?: g.card.id)) }))
            when (g.presence) {
                RommPresence.ON_SERVER -> {
                    add(MenuAction("romm.download", "Download", FuseIcons.Download, onSelect = { app.closeOverlays(); app.rommDownload(g.romId, g.card.title) }))
                    add(MenuAction("romm.everything", "Download Everything", FuseIcons.CloudDownload, detail = "With its updates and DLC, where RomM has them", onSelect = { queue(RommDownloadWhat.Everything) }))
                }
                RommPresence.DOWNLOADING, RommPresence.QUEUED -> add(MenuAction("romm.downloads", "See in Downloads", FuseIcons.Download, onSelect = { app.closeOverlays(); app.go(Route.Downloads) }))
                else -> add(MenuAction("romm.content", "Download Missing Content", FuseIcons.CloudDownload, onSelect = { queue(RommDownloadWhat.Everything) }))
            }
            // To another of the household's devices, from wherever is best (RomM, or a device that has it).
            if (app.store.reach.state.value.supported) {
                add(MenuAction("romm.send", "Send to Another Device", FuseIcons.Share, trailing = io.github.matiyaaa.fuse.ui.designsystem.components.Trailing.Chevron, onSelect = {
                    app.sendPicker(g.game ?: g.card.id, g.card.title)
                }))
            }
            // Not here yet: its art, details and name are RomM's to change, as for any game.
            if (g.game == null) addAll(rommEditActions(app, g.card.id, g.card.title))
        },
    )
}
