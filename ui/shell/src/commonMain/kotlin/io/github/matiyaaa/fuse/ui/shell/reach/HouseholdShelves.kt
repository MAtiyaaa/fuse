package io.github.matiyaaa.fuse.ui.shell.reach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.GameIconTile
import io.github.matiyaaa.fuse.ui.shell.components.LocalTileMetrics
import io.github.matiyaaa.fuse.ui.shell.components.SystemTile
import io.github.matiyaaa.fuse.ui.shell.romm.ShelfTitle
import io.github.matiyaaa.fuse.ui.shell.romm.liftRoom
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdGame
import io.github.matiyaaa.fuse.ui.shell.store.HouseholdSystem
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard

/** Where another device's game is, in a few words: "On Gaming PC", "On Thor and Deck". */
fun holdersText(g: HouseholdGame): String {
    val names = g.holders.distinct()
    val on = when (names.size) {
        0 -> "On another device"
        1 -> "On ${names[0]}"
        2 -> "On ${names[0]} and ${names[1]}"
        else -> "On ${names[0]} and ${names.size - 1} more"
    }
    return if (g.onlineHolders == 0 && names.isNotEmpty()) "$on, away" else on
}

/** Another device's game as a Fuse tile, with a small mark: on another device, or coming here. */
@Composable
fun HouseholdTile(g: HouseholdGame, selected: Boolean, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    Box(modifier) {
        GameIconTile(g.card, selected, size = size, onClick = onClick, onLongClick = onLongClick)
        val (icon, tint) = when {
            g.downloading -> FuseIcons.Download to c.accent
            g.transfer != null -> FuseIcons.Hourglass to c.text
            else -> FuseIcons.MonitorSmartphone to (if (g.onlineHolders > 0) c.text else c.textMuted)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.surfaceOverlay),
            contentAlignment = Alignment.Center,
        ) { FuseIcon(icon, size = 13.dp, tint = tint) }
    }
}

/** A shelf of other devices' games, with the chosen one named under it and where it is. */
@Composable
fun HouseholdShelf(
    title: String,
    icon: ImageVector,
    games: List<HouseholdGame>,
    selected: Int,
    tile: Dp,
    trailing: String?,
    onOpen: (HouseholdGame) -> Unit,
    onOptions: (HouseholdGame) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle(title, icon, trailing)
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)),
            horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap),
        ) {
            itemsIndexed(games, key = { _, g -> g.id.value }) { i, g ->
                HouseholdTile(g, selected == i, tile, onClick = { onOpen(g) }, onLongClick = { onOptions(g) })
            }
        }
        games.getOrNull(selected)?.let { g -> HouseholdLine(g, Modifier.padding(horizontal = Space.gutter)) }
    }
}

/** The chosen game: its name, then its system, year, where it is and how big. */
@Composable
fun HouseholdLine(g: HouseholdGame, modifier: Modifier = Modifier) {
    Column(modifier) {
        FText(g.card.title, Fuse.type.bodyStrong, maxLines = 1)
        FText(
            listOfNotNull(
                g.card.platformShort, g.card.year?.toString(),
                if (g.downloading) "Coming here" else holdersText(g),
                io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf(g.sizeBytes).takeIf { g.sizeBytes > 0 },
            ).joinToString("  ·  "),
            Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
        )
    }
}

/** The household's systems, with the same art as Fuse's Systems page. */
@Composable
fun HouseholdSystemShelf(systems: List<HouseholdSystem>, library: Map<PlatformId, PlatformCard>, selected: Int, tile: Dp, onOpen: (HouseholdSystem) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { if (selected >= 0) state.animateScrollToItem(maxOf(0, selected - 1)) }
    Column {
        ShelfTitle("Systems", FuseIcons.Chip, "${systems.size}")
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = Space.gutter, vertical = liftRoom(tile)), horizontalArrangement = Arrangement.spacedBy(LocalTileMetrics.current.gap)) {
            itemsIndexed(systems, key = { _, s -> s.platform.value }) { i, s ->
                Column(Modifier.width(tile)) {
                    val card = householdSystemCard(s, library)
                    if (card != null) {
                        SystemTile(card, selected == i, size = tile, onClick = { onOpen(s) })
                    } else {
                        io.github.matiyaaa.fuse.ui.designsystem.components.Tile(selected == i, Modifier.size(tile), onClick = { onOpen(s) }) {
                            Box(Modifier.fillMaxSize().background(Fuse.colors.surface), contentAlignment = Alignment.Center) {
                                FText(s.name, Fuse.type.label, maxLines = 3, modifier = Modifier.padding(Space.s))
                            }
                        }
                    }
                    Spacer(Modifier.height(liftRoom(tile)))
                    // The tile counts the games; under it, where they are.
                    FText(
                        when (s.holders.size) {
                            0 -> "On another device"
                            1 -> "On ${s.holders[0]}"
                            2 -> "On ${s.holders[0]} and ${s.holders[1]}"
                            else -> "On ${s.holders.size} devices"
                        },
                        Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The Systems page's card for a household system (counting the other devices' games). */
fun householdSystemCard(s: HouseholdSystem, library: Map<PlatformId, PlatformCard>): PlatformCard? {
    library[s.platform]?.let { return it.copy(gameCount = s.games) }
    val p = io.github.matiyaaa.fuse.library.PlatformCatalog.byId(s.platform) ?: return null
    return PlatformCard(s.accent?.let { p.copy(accent = it) } ?: p, s.games, s.art, null, false, 0, io.github.matiyaaa.fuse.model.BiosStatus.NotRequired, p.defaultLayout, emptyList())
}

/** Another device's game's options: bring it here, send it elsewhere, send it to RomM, or open its page. */
fun householdMenu(app: AppState, g: HouseholdGame, romm: Boolean): ContextMenuSpec = ContextMenuSpec(
    title = g.card.title,
    subtitle = holdersText(g),
    art = g.card.art.tile,
    accent = g.card.accent,
    actions = listOfNotNull(
        MenuAction("h.info", "Game Info", FuseIcons.Info, onSelect = { app.closeOverlays(); app.go(Route.GameInfo(g.id)) }),
        if (g.transfer != null) MenuAction("h.dls", "Downloads", FuseIcons.Download, detail = "It is coming here", onSelect = { app.closeOverlays(); app.go(Route.Downloads) })
        else MenuAction("h.dl", "Download Here", FuseIcons.Download, detail = "From wherever is best", onSelect = { app.closeOverlays(); app.reachDownload(g.id, g.card.title) }),
        MenuAction("h.send", "Send to Another Device", FuseIcons.Share, trailing = Trailing.Chevron, onSelect = { app.sendPicker(g.id, g.card.title) }),
        if (romm) MenuAction("h.romm", "Upload to RomM", FuseIcons.CloudUpload, detail = "Sent by the device that has it", onSelect = { app.reachUploadToRomm(g.id, g.card.title) }) else null,
    ),
)
