package io.github.matiyaaa.fuse.ui.shell.downloads

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.transfer.TransferKind
import io.github.matiyaaa.fuse.transfer.TransferLive
import io.github.matiyaaa.fuse.transfer.TransferPhase
import io.github.matiyaaa.fuse.transfer.TransferStatus
import io.github.matiyaaa.fuse.transfer.WaitReason
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.play
import io.github.matiyaaa.fuse.ui.shell.game.toCard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.matiyaaa.fuse.ui.shell.app.rememberSystems
import io.github.matiyaaa.fuse.ui.shell.components.SystemCardArt
import io.github.matiyaaa.fuse.ui.shell.platform.RommImages
import io.github.matiyaaa.fuse.ui.shell.store.Art
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import io.github.matiyaaa.fuse.ui.shell.store.TransferAction
import io.github.matiyaaa.fuse.ui.shell.store.TransferFilter
import io.github.matiyaaa.fuse.ui.shell.store.TransferRow
import io.github.matiyaaa.fuse.ui.shell.app.TransferSpot
import io.github.matiyaaa.fuse.ui.shell.app.rememberShownSystems
import io.github.matiyaaa.fuse.ui.shell.app.transferRoom

/**
 * Downloads: everything Fuse moves for the person, in one list. Each transfer is one slim row: its
 * system's art at the side (the same art as the Systems page), the game's logo (or its name), what
 * it is doing, how fast, how much and how long is left, and a line of progress along its foot. The
 * title and the filters fold away as you move into the list, so four or more rows stay in view even
 * on the AYN Thor's small screen. Confirm does the obvious thing (pause, resume, try again); Options
 * opens everything else, so no row is crowded with buttons.
 */
@Composable
fun DownloadsScreen(app: AppState) {
    val ops = app.store.transfers
    val all by ops.rows.collectAsState()
    val summary by ops.summary.collectAsState()
    val systems = rememberSystems(app)
    var filter by remember { mutableStateOf(TransferFilter.ALL) }
    val rows = remember(all, filter) { all.filter(filter::shows) }
    // Where the selection is: the actions along the top (0), the filters (1), or the list.
    var zone by remember { mutableIntStateOf(if (all.isEmpty()) 0 else 2) }
    var action by remember { mutableIntStateOf(0) }
    val sel = remember { LinearSelection() }
    sel.clamp(rows.size)
    if (rows.isEmpty() && zone == 2) zone = 1
    val list = rememberLazyListState()
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val anyPaused = all.any { it.item.status == TransferStatus.PAUSED && it.mirrored == null }
    val anyRunning = all.any { (it.item.status == TransferStatus.ACTIVE || it.item.status == TransferStatus.QUEUED || it.item.status == TransferStatus.WAITING) && it.mirrored == null }
    val anyFinished = all.any { it.item.status == TransferStatus.DONE || it.item.status == TransferStatus.CANCELLED }
    val actions = buildList {
        if (anyRunning) add(TopAction("Pause All", FuseIcons.Pause) { ops.pauseAll() })
        if (anyPaused) add(TopAction("Resume All", FuseIcons.Play) { ops.resumeAll() })
        if (anyFinished) add(TopAction("Clear Completed", FuseIcons.CheckCheck) { ops.clearFinished() })
        add(TopAction("Settings", FuseIcons.Settings) { app.go(Route.Settings("downloads")) })
    }
    if (action > actions.lastIndex) action = actions.lastIndex
    val filters = TransferFilter.entries
    val counts = remember(all) { filters.associateWith { f -> all.count(f::shows) } }

    fun openOptions(row: TransferRow) = app.openContextMenu(optionsFor(app, row, systems))
    fun primary(row: TransferRow) {
        // A game that came in: what confirming a game does anywhere (play it, or open its page).
        if (finishedGame(row) != null) { app.openDownloadedGame(row); return }
        val first = row.actions.firstOrNull { it == TransferAction.PAUSE || it == TransferAction.RESUME || it == TransferAction.RETRY || it == TransferAction.OPEN }
        if (first != null) ops.act(row.item.id, first) else openOptions(row)
    }
    val current = rows.getOrNull(sel.index)
    // The second screen shows the transfer the selection is on (the first, before the list is entered).
    val spot = (current ?: rows.firstOrNull())?.item?.id?.let(::TransferSpot)
    val room = spot?.let { transferRoom(app.store, rememberShownSystems(app.store), it) }
    LaunchedEffect(focused, spot, room) { if (focused) app.hero = room }
    PageEffect(focused, zone, current?.item?.status, current?.actions) {
        if (!focused) return@PageEffect
        app.hints = when (zone) {
            2 -> listOfNotNull(
                current?.let { r -> primaryLabel(r, app.store.prefs.value.openGamePage)?.let { Hint(HintButton.CONFIRM, it) } },
                Hint(HintButton.OPTIONS, "Options"),
                Hint(HintButton.BACK, "Back"),
            )
            1 -> listOf(Hint(HintButton.DPAD, "Show"), Hint(HintButton.BACK, "Back"))
            else -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(action)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
        }
    }
    InputLayer(enabled = focused) { e ->
        when (zone) {
            0 -> when (e.action) {
                NavAction.LEFT -> if (action > 0) { action--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (action < actions.lastIndex) { action++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> { zone = 1; NavResult.MOVED }
                NavAction.SELECT -> { actions.getOrNull(action)?.run?.invoke(); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
            1 -> when (e.action) {
                NavAction.LEFT -> if (filter.ordinal > 0) { filter = filters[filter.ordinal - 1]; sel.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (filter.ordinal < filters.lastIndex) { filter = filters[filter.ordinal + 1]; sel.index = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> { zone = 0; NavResult.MOVED }
                NavAction.DOWN -> if (rows.isNotEmpty()) { zone = 2; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> if (rows.isNotEmpty()) { zone = 2; NavResult.MOVED } else NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
            else -> when (e.action) {
                NavAction.UP -> if (sel.index > 0) { sel.index--; NavResult.MOVED } else { zone = 1; NavResult.MOVED }
                NavAction.DOWN -> if (sel.index < rows.lastIndex) { sel.index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.PAGE_DOWN -> { sel.index = (sel.index + 4).coerceAtMost(rows.lastIndex); NavResult.MOVED }
                NavAction.PAGE_UP -> { sel.index = (sel.index - 4).coerceAtLeast(0); NavResult.MOVED }
                NavAction.LEFT, NavAction.RIGHT -> NavResult.BLOCKED
                NavAction.SELECT -> { current?.let(::primary); NavResult.ACTIVATED }
                NavAction.CONTEXT -> { current?.let(::openOptions); NavResult.ACTIVATED }
                else -> NavResult.IGNORED
            }
        }
    }
    // The chosen row keeps the one before it in view, so the title and filters fold away as you go down.
    LaunchedEffect(zone, sel.index, rows.size) {
        if (zone != 2) list.animateScrollToItem(0) else list.animateScrollToItem(HEAD_ITEMS + maxOf(0, sel.index - 1))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("downloads")) {
        val tight = maxHeight < 420.dp
        val compact = maxHeight < 560.dp
        val rowHeight = when {
            tight -> 52.dp
            compact -> 60.dp
            else -> 72.dp
        }
        val wide = maxWidth >= 900.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).padding(horizontal = if (maxWidth < 600.dp) Space.gutterCompact else Space.gutter)
                .fadingEdges(list, top = Space.l, bottom = Size.hintHeight),
            contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            item("head") {
                Header(summary, actions, if (focused && zone == 0) action else -1, compact = compact, labels = wide) { i -> action = i; zone = 0; actions[i].run() }
            }
            item("filters") {
                Filters(filters, counts, filter, focused && zone == 1, Modifier.padding(top = if (tight) Space.xs else Space.s, bottom = Space.xs)) { f ->
                    filter = f
                    sel.index = 0
                    zone = 1
                }
            }
            if (rows.isEmpty()) {
                item("empty") { Empty(filter, all.isEmpty()) }
            } else {
                items(rows, key = { it.item.id }) { row ->
                    val i = rows.indexOf(row)
                    TransferRowView(
                        row, ops.live(row.item.id), system(systems, row.item.platform), queuePosition(rows, row),
                        selected = focused && zone == 2 && sel.index == i, height = rowHeight, wide = wide,
                        onClick = { sel.index = i; zone = 2; openOptions(row) },
                    )
                }
            }
        }
    }
}

private const val HEAD_ITEMS = 2

internal class TopAction(val label: String, val icon: ImageVector, val run: () -> Unit)

/** The page's title line: how many move each way, at what speed, and the few things to do. */
@Composable
private fun Header(summary: io.github.matiyaaa.fuse.transfer.TransferSummary, actions: List<TopAction>, selected: Int, compact: Boolean, labels: Boolean, onAction: (Int) -> Unit) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.Download, size = Size.iconM, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText("Downloads", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
            val words = listOfNotNull(
                "${summary.activeDownloads} downloading".takeIf { summary.activeDownloads > 0 },
                "${summary.activeUploads} uploading".takeIf { summary.activeUploads > 0 },
                "${summary.queued} waiting".takeIf { summary.queued + summary.waiting > 0 }?.let { "${summary.queued + summary.waiting} waiting" },
                "${summary.failed} failed".takeIf { summary.failed > 0 },
            ).joinToString("  ·  ").ifEmpty { "Nothing moving" }
            FText(words, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            actions.forEachIndexed { i, a -> ActionPill(a, i == selected, labels || i == selected) { onAction(i) } }
        }
    }
}

@Composable
internal fun ActionPill(a: TopAction, selected: Boolean, label: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text else c.text.copy(alpha = 0.07f), Fuse.motion.tween(Durations.FAST), label = "dlAction")
    val fg = if (selected) c.ink else c.text
    Row(
        Modifier.height(36.dp).clip(CircleShape).background({ bg })
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = a.label }
            .padding(horizontal = if (label) Space.m else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(a.icon, size = Size.iconS, tint = fg)
        if (label) {
            Spacer(Modifier.width(Space.xs + 2.dp))
            FText(a.label, Fuse.type.label, color = fg, maxLines = 1)
        }
    }
}

/** All, Downloads, Uploads, Completed, Failed: each with how many it holds. */
@Composable
private fun Filters(filters: List<TransferFilter>, counts: Map<TransferFilter, Int>, active: TransferFilter, focused: Boolean, modifier: Modifier, onPick: (TransferFilter) -> Unit) {
    val c = Fuse.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (f in filters) {
            val on = f == active
            val bg by fuselineColor(
                when {
                    on && focused -> c.text
                    on -> c.accent.copy(alpha = 0.18f)
                    else -> Color.Transparent
                },
                Fuse.motion.tween(Durations.FAST), label = "dlFilter",
            )
            val fg = when {
                on && focused -> c.ink
                on -> c.accent
                else -> c.textMuted
            }
            Row(
                Modifier.height(32.dp).clip(CircleShape).background({ bg })
                    .then(if (!on) Modifier.border(1.dp, c.hairline, CircleShape) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onPick(f) }
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FText(f.label, Fuse.type.label, color = fg, maxLines = 1)
                val n = counts[f] ?: 0
                if (n > 0 && f != TransferFilter.ALL) {
                    Spacer(Modifier.width(6.dp))
                    FText("$n", Fuse.type.numericSmall, color = fg.copy(alpha = 0.75f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Empty(filter: TransferFilter, nothingAtAll: Boolean) {
    val c = Fuse.colors
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        FuseIcon(if (filter == TransferFilter.FAILED) FuseIcons.CircleCheck else FuseIcons.Download, size = Size.iconXL, tint = c.textFaint)
        Spacer(Modifier.height(Space.m))
        FText(
            when {
                nothingAtAll -> "Nothing downloading"
                filter == TransferFilter.FAILED -> "Nothing failed"
                filter == TransferFilter.COMPLETED -> "Nothing finished yet"
                filter == TransferFilter.UPLOADS -> "Nothing uploading"
                else -> "Nothing downloading"
            },
            Fuse.type.bodyStrong, color = c.textMuted,
        )
        if (nothingAtAll) {
            Spacer(Modifier.height(Space.xs))
            FText("Games from Fuse RomM, films for offline, apps and BIOS show here while they move.", Fuse.type.caption, color = c.textFaint, maxLines = 2)
        }
    }
}

/** The system's card from the Systems page, or one made for a system the library doesn't have yet. */
private fun system(systems: Map<PlatformId, PlatformCard>, id: String?): PlatformCard? {
    if (id == null) return null
    val pid = PlatformId(id)
    systems[pid]?.let { return it }
    val platform = io.github.matiyaaa.fuse.library.PlatformCatalog.byId(id) ?: return null
    return PlatformCard(platform, 0, Art.None, null, false, 0, io.github.matiyaaa.fuse.model.BiosStatus.NotRequired, platform.defaultLayout, emptyList())
}

/** Its place among those waiting their turn in its direction (1 is next), or null when it isn't waiting. */
private fun queuePosition(rows: List<TransferRow>, row: TransferRow): Int? {
    if (row.item.status != TransferStatus.QUEUED) return null
    val line = rows.filter { it.item.status == TransferStatus.QUEUED && it.item.direction == row.item.direction }
    return line.indexOf(row).takeIf { it >= 0 }?.plus(1)
}

/** The RomM game a finished game download brought in, or null for anything else. */
internal fun finishedGame(row: TransferRow): Long? {
    val t = row.item
    if (t.status != TransferStatus.DONE || t.upload || (t.kind != TransferKind.GAME && t.kind != TransferKind.CONTENT)) return null
    return ROMM_KEY.find(t.key)?.groupValues?.get(1)?.toLongOrNull()
}

private val ROMM_KEY = Regex("^romm:rom:(\\d+)")

/**
 * Opens the game a finished download brought in, as confirming a game does anywhere: plays it, or
 * opens its page when that is the setting. While Fuse is still adding it, its page opens (and turns
 * into the library game's as soon as it is in).
 */
private fun AppState.openDownloadedGame(row: TransferRow) {
    val romId = finishedGame(row) ?: return
    scope.launch {
        val local = store.romm.libraryGame(romId).first()
        val detail = local?.let { store.library.game(it).first() }
        if (detail != null && !store.prefs.value.openGamePage) play(detail.toCard())
        else go(Route.GameInfo(local ?: io.github.matiyaaa.fuse.ui.shell.store.rommGameId(romId)))
    }
}

private fun primaryLabel(row: TransferRow, openPage: Boolean): String? = if (finishedGame(row) != null) (if (openPage) "Open" else "Play") else row.actions.firstOrNull {
    it == TransferAction.PAUSE || it == TransferAction.RESUME || it == TransferAction.RETRY || it == TransferAction.OPEN
}?.let { if (it == TransferAction.OPEN) "Open ${row.mirrored ?: ""}".trim() else it.label } ?: "Options"

/** Everything that can be done with [row], with what it is and where it goes. */
private fun optionsFor(app: AppState, row: TransferRow, systems: Map<PlatformId, PlatformCard>): ContextMenuSpec {
    val t = row.item
    val icon = { a: TransferAction ->
        when (a) {
            TransferAction.PAUSE -> FuseIcons.Pause
            TransferAction.RESUME -> FuseIcons.Play
            TransferAction.RETRY -> FuseIcons.RotateCw
            TransferAction.CANCEL -> FuseIcons.CircleX
            TransferAction.MOVE_UP -> FuseIcons.ArrowUp
            TransferAction.MOVE_DOWN -> FuseIcons.ArrowDown
            TransferAction.MOVE_TO_TOP -> FuseIcons.ChevronsUp
            TransferAction.REMOVE -> FuseIcons.Trash
            TransferAction.OPEN -> FuseIcons.External
        }
    }
    val sys = system(systems, t.platform)
    return ContextMenuSpec(
        title = t.title,
        subtitle = listOfNotNull(t.detail.ifBlank { null }, sys?.platform?.name, row.mirrored?.let { "Through $it" }).joinToString("  ·  ").ifEmpty { null },
        icon = if (t.upload) FuseIcons.Upload else FuseIcons.Download,
        art = RommImages.model(t.art.cover) ?: RommImages.model(t.art.icon),
        accent = sys?.platform?.accent,
        actions = listOfNotNull(
            finishedGame(row)?.let {
                MenuAction("transfer.game", if (app.store.prefs.value.openGamePage) "Open Game" else "Play", if (app.store.prefs.value.openGamePage) FuseIcons.Gamepad else FuseIcons.Play, onSelect = { app.closeOverlays(); app.openDownloadedGame(row) })
            },
        ) + row.actions.map { a ->
            MenuAction(
                "transfer.${a.name}", if (a == TransferAction.OPEN) "Open ${row.mirrored ?: ""}".trim() else a.label, icon(a),
                destructive = a == TransferAction.CANCEL,
                detail = when (a) {
                    TransferAction.CANCEL -> if (t.upload) "Stops sending; nothing on the server changes" else "What was downloaded so far is removed"
                    TransferAction.PAUSE -> "Keeps what is done, to carry on later"
                    else -> null
                },
                onSelect = { app.closeOverlays(); app.store.transfers.act(t.id, a) },
            )
        } + MenuAction(
            "transfer.where", t.target.ifBlank { "This device" }, if (t.upload) FuseIcons.CloudUpload else FuseIcons.HardDrive,
            detail = if (t.upload) "Where it goes" else "Where it lands", enabled = false,
        ),
    )
}

/**
 * One transfer: the system's art anchoring the left, the game's logo (or its name), and in one quiet
 * line what it is doing. Its numbers come from [live], so only this row redraws as bytes move.
 */
@Composable
private fun TransferRowView(
    row: TransferRow,
    liveFlow: kotlinx.coroutines.flow.StateFlow<TransferLive>,
    system: PlatformCard?,
    queuePosition: Int?,
    selected: Boolean,
    height: Dp,
    wide: Boolean,
    onClick: () -> Unit,
) {
    val c = Fuse.colors
    val t = row.item
    val live by liveFlow.collectAsState()
    val accent = system?.platform?.accent?.toColor() ?: c.accent
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val bg by fuselineColor(if (selected) c.surfaceRaised else c.surface.copy(alpha = 0.6f), Fuse.motion.tween(Durations.FAST), label = "dlRow")
    val failed = t.status == TransferStatus.FAILED
    val done = t.status == TransferStatus.DONE
    Box(
        Modifier.fillMaxWidth().height(height).testTag("downloads.row").clip(shape).background({ bg })
            .then(if (selected) Modifier.border(Size.focusStroke, c.focus, shape) else Modifier.border(1.dp, c.hairline, shape))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "${t.title}, ${statusWords(t, live, queuePosition)}" },
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // The system's art anchors the row, fading into it.
            Box(Modifier.fillMaxHeight().width(height * 1.25f)) {
                if (system != null) {
                    Box(Modifier.fillMaxSize()) { SystemCardArt(system) }
                } else {
                    Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                        FuseIcon(kindIcon(t.kind), size = Size.iconM, tint = c.text.copy(alpha = 0.8f))
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, bg))))
            }
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TitleOrLogo(t, height)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectionMark(t.upload, t.status)
                    Spacer(Modifier.width(Space.xs))
                    FText(statusWords(t, live, queuePosition), Fuse.type.caption, color = if (failed) c.danger else c.textMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(Modifier.width(Space.s))
            // The numbers that matter most at a glance, on the right.
            Column(Modifier.padding(end = Space.m), horizontalAlignment = Alignment.End) {
                when {
                    done -> FuseIcon(FuseIcons.CircleCheck, size = Size.iconM, tint = c.success)
                    failed -> FuseIcon(FuseIcons.Warning, size = Size.iconM, tint = c.danger)
                    t.status == TransferStatus.PAUSED -> FuseIcon(FuseIcons.Pause, size = Size.iconM, tint = c.textMuted)
                    t.status == TransferStatus.ACTIVE && live.progress == null -> Spinner(size = 18.dp, color = c.textMuted)
                    else -> live.progress?.let { FText("${(it * 100).toInt()}%", Fuse.type.numeric, maxLines = 1) }
                }
                if (wide && t.status == TransferStatus.ACTIVE && live.speed > 0) {
                    FText(speedText(live.speed), Fuse.type.numericSmall, color = c.textMuted, maxLines = 1)
                }
            }
        }
        // Progress along the foot of the row, in the system's colour.
        val p = live.progress ?: t.progress
        if (p != null && !done && t.status != TransferStatus.CANCELLED) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.text.copy(alpha = 0.08f))) {
                io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar(p, Modifier.fillMaxWidth(), color = if (failed) c.danger else if (t.status == TransferStatus.PAUSED) c.textMuted else accent, height = 3.dp)
            }
        }
    }
}

/** A game's logo where it has one (it says the name better than writing it out), else its name. */
@Composable
private fun TitleOrLogo(t: io.github.matiyaaa.fuse.transfer.TransferItem, height: Dp) {
    val logo = RommImages.model(t.art.logo)
    val title: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(t.title, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (t.detail.isNotBlank()) {
                Spacer(Modifier.width(Space.s))
                FText(t.detail, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
    }
    if (logo == null) {
        title()
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(logo, Modifier.height(height * 0.42f).width(height * 1.9f), contentScale = androidx.compose.ui.layout.ContentScale.Fit, loading = false, fallback = title)
        if (t.detail.isNotBlank()) {
            Spacer(Modifier.width(Space.s))
            FText(t.detail, Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
}

/** Down or up, drawn small beside the status. */
@Composable
private fun DirectionMark(upload: Boolean, status: TransferStatus) {
    val c = Fuse.colors
    val tint = when (status) {
        TransferStatus.ACTIVE -> c.accent
        TransferStatus.FAILED -> c.danger
        else -> c.textFaint
    }
    FuseIcon(if (upload) FuseIcons.ArrowUp else FuseIcons.ArrowDown, size = Size.iconXS, tint = tint)
}

private fun kindIcon(k: TransferKind): ImageVector = when (k) {
    TransferKind.GAME, TransferKind.CONTENT -> FuseIcons.Gamepad
    TransferKind.BIOS, TransferKind.FIRMWARE -> FuseIcons.Chip
    TransferKind.MEDIA -> FuseIcons.Film
    TransferKind.SUBTITLE -> FuseIcons.Captions
    TransferKind.APP -> FuseIcons.Smartphone
    TransferKind.EMULATOR -> FuseIcons.Joystick
    TransferKind.FUSE_UPDATE -> FuseIcons.Sparkles
    TransferKind.SAVES -> FuseIcons.Save
    TransferKind.OTHER -> FuseIcons.File
}

/** What a transfer is doing, in one line: "12.4 MB/s · 2.1 of 4.7 GB · 3 min left · SD card". */
internal fun statusWords(t: io.github.matiyaaa.fuse.transfer.TransferItem, live: TransferLive, queuePosition: Int?): String {
    val total = live.totalBytes ?: t.totalBytes
    val done = live.doneBytes.takeIf { it > 0 } ?: t.doneBytes
    val amount = if (total != null && total > 0 && t.kind != TransferKind.FUSE_UPDATE) "${sizeOf(done)} of ${sizeOf(total)}" else null
    val where = t.target.substringBefore("  ·  ").ifBlank { null }
    return when (t.status) {
        TransferStatus.ACTIVE -> when (t.phase) {
            TransferPhase.STARTING -> listOfNotNull("Starting", where)
            TransferPhase.VERIFYING -> listOfNotNull("Checking it arrived whole", amount)
            TransferPhase.PLACING -> listOfNotNull("Putting it in place", where)
            TransferPhase.FINISHING -> listOfNotNull(if (t.upload) "RomM is adding it" else "Adding it to your library", where)
            else -> listOfNotNull(
                live.speed.takeIf { it > 0 }?.let(::speedText),
                amount,
                live.etaSeconds?.let(::etaText),
                where,
            )
        }.joinToString("  ·  ").ifEmpty { if (t.upload) "Uploading" else "Downloading" }
        TransferStatus.QUEUED -> listOfNotNull(queuePosition?.let { "Next in line".takeIf { _ -> it == 1 } ?: "Waiting, #$it in line" } ?: "Waiting its turn", amount?.let { sizeOf(total ?: 0) }, where).joinToString("  ·  ")
        TransferStatus.WAITING -> when (t.waiting) {
            WaitReason.DRIVE -> "Waiting for ${t.waitingFor ?: "its drive"} to be connected"
            WaitReason.NETWORK -> "Waiting for the connection  ·  tries again by itself"
            WaitReason.PLAYING -> "Paused while you play"
            WaitReason.WIFI -> "Waiting for Wi-Fi"
            null -> "Waiting"
        }.let { w -> listOfNotNull(w, amount).joinToString("  ·  ") }
        TransferStatus.PAUSED -> listOfNotNull("Paused", amount).joinToString("  ·  ")
        TransferStatus.FAILED -> t.error ?: "It didn't work"
        TransferStatus.DONE -> listOfNotNull(if (t.upload) "Sent" else "Done", total?.let(::sizeOf), where).joinToString("  ·  ")
        TransferStatus.CANCELLED -> "Cancelled"
    }
}

internal fun sizeOf(bytes: Long): String = when {
    bytes >= 1L shl 30 -> fixed(bytes / 1073741824.0, 1) + " GB"
    bytes >= 1L shl 20 -> fixed(bytes / 1048576.0, if (bytes >= 100L shl 20) 0 else 1) + " MB"
    bytes >= 1L shl 10 -> (bytes shr 10).toString() + " KB"
    else -> "$bytes B"
}

internal fun speedText(bytesPerSecond: Long): String = sizeOf(bytesPerSecond) + "/s"

internal fun etaText(seconds: Long): String = when {
    seconds < 60 -> "under a minute left"
    seconds < 3600 -> "${(seconds + 59) / 60} min left"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
}

private fun fixed(v: Double, decimals: Int): String {
    if (decimals == 0) return kotlin.math.round(v).toLong().toString()
    val f = kotlin.math.round(v * 10).toLong()
    return "${f / 10}.${f % 10}"
}
