package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconButton
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuArt
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Skeleton
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonRow
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonText
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.expandIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.shrinkOut
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.offerDriveSetup
import io.github.matiyaaa.fuse.ui.shell.app.offersGames
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.store.StorageUsage
import io.github.matiyaaa.fuse.ui.shell.store.VolumeUsage
import kotlinx.coroutines.launch

/**
 * Storage: how full the drive is (a ring of games, everything else and free space), what each
 * system's games take, and every game by size, largest first. Games can be picked, then moved to
 * another drive or deleted from the bar that comes up, after a confirmation naming what goes. Only
 * here, on the device; Phone Link can't delete anything.
 */
@Composable
fun StorageScreen(app: AppState) {
    val store = app.store
    val usage by store.storage.usage.collectAsState()
    val access by app.platform.storage.state.collectAsState()
    val canDelete = access == StorageState.GRANTED || access == StorageState.NOT_NEEDED
    val sel = rememberRouteState(app.navigator, "storage") { LinearSelection() }
    // The systems on the left: Up and Down move along them, A shows only that system's games.
    val systemSel = rememberRouteState(app.navigator, "storage.systems") { LinearSelection() }
    var pane by remember { mutableStateOf(StoragePane.GAMES) }
    var system by remember { mutableStateOf<PlatformId?>(null) }
    // The drive shown, by its id; null shows every drive.
    var drive by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf(setOf<GameId>()) }
    val platforms by store.library.platforms.collectAsState()

    LaunchedEffect(Unit) {
        store.storage.refresh()
        app.hero = null
    }

    val all = usage
    // Picking a drive narrows everything after it (its systems, its games) to what it holds.
    val u = remember(all, drive) { all?.onDrive(drive) }
    val volumes = all?.volumes.orEmpty()
    val systems = remember(u, platforms) { systemSizes(u, platforms) }
    // The rows are built once per change of what they show, never while the selection moves: with
    // hundreds of games, building them on every step is what made the list stutter.
    var wideLayout by remember { mutableStateOf(true) }
    val sourceVolumes by store.sources.volumes.collectAsState()
    val canMove = sourceVolumes.count { !it.readOnly } > 1
    val sourceStatus by store.sources.status.collectAsState()
    // Drives connected without a games folder, which Storage can set up.
    val bare = remember(sourceVolumes, sourceStatus) {
        val holding = sourceStatus.mapNotNull { it.volume?.id ?: it.source.volume?.id }.toSet()
        sourceVolumes.filter { it.offersGames() && it.id !in holding }
    }
    val moving by store.storage.moving.collectAsState()
    // What is picked, on every drive: changing the drive shown never drops a game from the selection.
    val chosen = remember(all, picked) { all?.games.orEmpty().filter { it.card.id in picked } }
    val chosenBytes = chosen.sumOf { it.bytes }
    val clear = { picked = emptySet() }
    val move = {
        // The drive they are all on already isn't offered.
        val on = chosen.mapNotNull { it.volumeId }.toSet().takeIf { it.size == 1 }.orEmpty()
        app.moveGames(chosen.map { it.card.id }, chosen.map { it.card.title }, chosenBytes, from = on, onDone = clear)
    }
    val delete = { confirmDelete(app, chosen.map { it.card.id to it.card.title }, chosen.sumOf { it.files }, chosenBytes, clear) }
    val chooseSystem = { id: PlatformId? ->
        // A second press on the system shown goes back to all of them.
        system = if (id == null || id == system) null else id
        sel.index = 0
    }
    val rows = remember(u, system, picked, canDelete, platforms, wideLayout, bare, drive, volumes) {
        storageRows(
            app, u, system, picked, canDelete, wide = wideLayout, drives = volumes, drive = drive, bare = bare,
            onPick = { id -> picked = if (id in picked) picked - id else picked + id },
            onPickAll = { ids -> picked = if (ids.isNotEmpty() && picked.containsAll(ids)) picked - ids else picked + ids },
            onFilter = { system = it; sel.index = 0 },
            onDrive = { drive = it; system = null; sel.index = 0 },
        )
    }
    val systemRows = remember(systems, u, system, volumes, drive) {
        val total = u?.games.orEmpty().sumOf { it.bytes }
        fun chooseDrive(id: String?) {
            drive = if (id == null || id == drive) null else id
            system = null
            sel.index = 0
        }
        buildList {
            // With more than one drive, each is a filter of its own above the systems.
            if (volumes.size > 1) {
                add(MenuAction(
                    "dall", "All drives", FuseIcons.Layers2, section = "Drives",
                    detail = gamesCount(all?.games?.size ?: 0),
                    trailing = Trailing.Value(bytesText(all?.games.orEmpty().filterNot { it.lastKnown }.sumOf { it.bytes })),
                    onSelect = { chooseDrive(null) },
                ))
                volumes.forEach { v ->
                    add(MenuAction(
                        "d.${v.id}", v.label, v.icon(), section = "Drives",
                        detail = driveLine(v),
                        trailing = Trailing.Value(if (v.online) "${percent(v.usedFraction())} full" else "Not connected"),
                        onSelect = { chooseDrive(v.id) },
                    ))
                }
            }
            add(MenuAction(
                "all", "All systems", FuseIcons.Layers, section = "Systems",
                detail = gamesCount(u?.games?.size ?: 0),
                trailing = Trailing.Value(bytesText(total)),
                onSelect = { chooseSystem(null) },
            ))
            systems.forEach { sz ->
                add(MenuAction(
                    "s.${sz.id.value}", sz.name, section = "Systems",
                    // The share sits in the second line, so a long name never has to wrap beside it.
                    detail = "${gamesCount(sz.games)}  ·  ${percent(sz.bytes.toFloat() / total.coerceAtLeast(1))}",
                    trailing = Trailing.Value(bytesText(sz.bytes)),
                    art = MenuArt(sz.art, square = true, fallbackTitle = sz.short, accent = sz.accent, wide = false),
                    onSelect = { chooseSystem(sz.id) },
                ))
            }
        }
    }
    // The row the filters are on: the system shown, else the drive shown, else the first row.
    val filterKey = system?.let { "s.${it.value}" } ?: drive?.let { "d.$it" } ?: systemRows.firstOrNull()?.id
    val filterRow = systemRows.indexOfFirst { it.id == filterKey }.coerceAtLeast(0)

    sel.keepOn(rows.map { it.id })
    sel.clamp(rows.size)
    systemSel.clamp(systemRows.size)
    val inSystems = wideLayout && pane == StoragePane.SYSTEMS
    val anyPicked = chosen.isNotEmpty()

    // Y on a selection: everything that can be done with it, in one sheet.
    val selectionSheet = {
        val n = chosen.size
        val access = if (!canDelete) "Allow All files access first" else null
        app.choice = ChoiceSpec(
            title = "${n} ${if (n == 1) "game" else "games"} selected",
            message = "${bytesText(chosenBytes)} in all",
            icon = FuseIcons.ListChecks,
            options = listOfNotNull(
                if (canMove) MenuAction(
                    "move", "Move to another drive", FuseIcons.FolderSync,
                    detail = "To an SD card or another drive, with their play time and art",
                    unavailableReason = access,
                    onSelect = { app.choice = null; move() },
                ) else null,
                MenuAction(
                    "delete", "Delete their files", FuseIcons.Trash, destructive = true,
                    detail = "You'll be asked first",
                    unavailableReason = access,
                    onSelect = { app.choice = null; delete() },
                ),
                MenuAction("clear", "Clear the selection", FuseIcons.Close, onSelect = { app.choice = null; clear() }),
            ),
        )
    }

    LaunchedEffect(inSystems, anyPicked, wideLayout) {
        val selection = if (anyPicked) listOf(Hint(HintButton.SEARCH, if (canMove) "Move or delete" else "Delete")) else emptyList()
        app.hints = if (inSystems) {
            listOf(Hint(HintButton.CONFIRM, "Show these games"), Hint(HintButton.DPAD, "Games")) + selection + Hint(HintButton.BACK, "Back")
        } else {
            // A phone held upright has room for three hints: X still opens the game page.
            val page = if (wideLayout) listOf(Hint(HintButton.OPTIONS, "Game page")) else emptyList()
            listOf(Hint(HintButton.CONFIRM, "Select")) + page + selection + Hint(HintButton.BACK, "Back")
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when {
            e.action == NavAction.SEARCH -> if (anyPicked) { selectionSheet(); NavResult.ACTIVATED } else NavResult.IGNORED
            inSystems -> when (e.action) {
                NavAction.RIGHT -> { pane = StoragePane.GAMES; systemSel.index = filterRow; NavResult.MOVED }
                NavAction.LEFT -> NavResult.BLOCKED
                NavAction.CONTEXT -> NavResult.BLOCKED
                else -> handleMenuAction(e, systemRows, systemSel)
            }
            else -> when (e.action) {
                // Left goes over to the systems, starting on the one shown.
                NavAction.LEFT -> if (wideLayout) { pane = StoragePane.SYSTEMS; systemSel.index = filterRow; NavResult.MOVED } else NavResult.IGNORED
                NavAction.CONTEXT -> {
                    // X on a game opens its page.
                    val id = rows.getOrNull(sel.index)?.id?.takeIf { it.startsWith("g") }?.removePrefix("g")?.toLongOrNull()
                    if (id != null) { app.go(Route.GameInfo(GameId(id))); NavResult.ACTIVATED } else NavResult.BLOCKED
                }
                else -> handleMenuAction(e, rows, sel)
            }
        }
    }

    val empty = u != null && u.finished && u.games.isEmpty()
    val focused = app.focusZone == FocusZone.CONTENT
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WIDE_FROM
        SideEffect { wideLayout = wide }
        val short = maxHeight < SHORT_BELOW
        val gutter = if (wide) Space.gutter else Space.gutterCompact
        val inner = minOf(maxWidth, PAGE_MAX) - gutter * 2
        // On a very wide screen the page keeps a readable width, centred.
        Column(
            Modifier.fillMaxHeight().widthIn(max = PAGE_MAX).align(Alignment.TopCenter)
                .padding(horizontal = gutter),
        ) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Storage", summaryLine(all), short, Modifier.reveal(0)) {
                if (all != null && !all.finished) {
                    ProgressBar(if (all.total > 0) all.measured.toFloat() / all.total else null, Modifier.width(MEASURE_BAR))
                }
            }
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            val games: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { m, header ->
                Panel(m) {
                    Column(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f)) {
                            MenuList(
                                rows, sel,
                                showSelection = focused && !inSystems,
                                header = header,
                                fill = !(u == null || empty),
                                modifier = Modifier.padding(Space.s),
                                fadeEdges = true,
                            )
                            when {
                                // Rows shaped like the games still being measured.
                                u == null -> Column(Modifier.padding(horizontal = Space.s)) { repeat(SKELETON_ROWS) { SkeletonRow() } }
                                empty -> Box(Modifier.fillMaxSize().padding(Space.l), contentAlignment = Alignment.Center) {
                                    EmptyState(
                                        FuseIcons.HardDrive, "No games to measure",
                                        message = "Games show here, largest first, once Fuse finds them in your folders.",
                                        compact = true,
                                    )
                                }
                            }
                        }
                        // What is picked stays in view at the foot of the list, wherever the list is scrolled.
                        Appear(anyPicked, enter = fadeIn() + expandIn(expandFrom = Alignment.BottomCenter), exit = shrinkOut(shrinkTowards = Alignment.BottomCenter) + fadeOut()) {
                            SelectionBar(
                                count = chosen.size, bytes = chosenBytes, canMove = canMove, canDelete = canDelete,
                                onMove = move, onDelete = delete, onClear = clear,
                            )
                        }
                    }
                }
            }
            val bottom = Modifier.padding(bottom = Size.hintHeight + Space.s)
            if (wide) {
                // Two panes that scroll on their own: the drive and its systems on the left, which
                // filter the games on the right. Left and Right move between them.
                val left = (inner * LEFT_SHARE).coerceIn(LEFT_MIN, LEFT_MAX)
                Row(Modifier.fillMaxSize().then(bottom), horizontalArrangement = Arrangement.spacedBy(if (short) Space.m else Space.l)) {
                    Panel(Modifier.width(left).fillMaxHeight().reveal(1)) {
                        MenuList(
                            systemRows, systemSel,
                            showSelection = focused,
                            // Away from this pane, a quiet marker stays on the system shown.
                            dimSelection = !inSystems,
                            header = {
                                Overview(all, drive, systems, compact = short, chips = false, shown = system, modifier = Modifier.padding(top = Space.xs, bottom = Space.s))
                            },
                            modifier = Modifier.padding(Space.s),
                            fadeEdges = true,
                        )
                    }
                    games(Modifier.weight(1f).fillMaxHeight().reveal(2)) {
                        Column {
                            moving?.let { MoveCard(app, it, platforms, compact = short) }
                            ShownHeader(u, systems.firstOrNull { it.id == system }, volumes.firstOrNull { it.id == drive }?.label)
                        }
                    }
                }
            } else {
                games(Modifier.fillMaxSize().then(bottom).reveal(1)) {
                    Column(Modifier.padding(top = Space.xs, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        moving?.let { MoveCard(app, it, platforms, compact = true) }
                        Overview(all, drive, systems, compact = short, chips = true, shown = system, onSystem = chooseSystem)
                    }
                }
            }
        }
    }
}

private enum class StoragePane { SYSTEMS, GAMES }

/**
 * Games on their way to another drive, drawn as Cartridge draws a download: the game being copied
 * with its system's art, how far all of them are, and the time left. Selecting it offers to stop
 * after the game being copied.
 */
@Composable
private fun MoveCard(app: AppState, m: io.github.matiyaaa.fuse.ui.shell.store.MoveProgress, platforms: List<io.github.matiyaaa.fuse.ui.shell.store.PlatformCard>, compact: Boolean) {
    val system = m.card?.let { c -> platforms.firstOrNull { it.platform.id == c.platformId } }
    val left = io.github.matiyaaa.fuse.ui.shell.cartridge.rememberTimeLeft(m.doneBytes, m.totalBytes)
    io.github.matiyaaa.fuse.ui.shell.cartridge.TransferCard(
        label = "Moving to ${m.to}",
        icon = FuseIcons.FolderSync,
        title = m.title,
        system = system,
        systemName = system?.platform?.name ?: m.card?.platformShort.orEmpty(),
        slug = null,
        progress = if (m.totalBytes > 0) (m.doneBytes.toFloat() / m.totalBytes).coerceIn(0f, 1f) else null,
        sizes = "${bytesText(m.doneBytes)} of ${bytesText(m.totalBytes)}",
        timeLeft = left,
        waiting = (m.count - m.index - 1).coerceAtLeast(0),
        selected = false,
        compact = compact,
        modifier = Modifier.padding(start = Space.s, end = Space.s, top = Space.s, bottom = Space.m),
    ) {
        app.confirm = ConfirmSpec(
            title = "Stop moving?",
            message = "The game being copied finishes first. Games not moved yet stay where they are.",
            confirmLabel = "Stop after this game",
        ) { app.store.storage.cancelMove() }
    }
}

/** One system's games on every drive together. */
private data class SystemSize(val id: PlatformId, val name: String, val short: String, val accent: Long, val art: Any?, val bytes: Long, val games: Int)

private fun systemSizes(u: StorageUsage?, platforms: List<io.github.matiyaaa.fuse.ui.shell.store.PlatformCard>): List<SystemSize> =
    u?.games.orEmpty().groupBy { it.card.platformId }.map { (id, games) ->
        val p = platforms.firstOrNull { it.platform.id == id }
        val first = games.first().card
        SystemSize(
            id = id,
            name = p?.platform?.name ?: first.platformShort,
            short = p?.platform?.shortName ?: first.platformShort,
            accent = p?.platform?.accent ?: first.accent,
            art = p?.art?.let { it.square ?: it.icon },
            bytes = games.sumOf { it.bytes },
            games = games.size,
        )
    }.sortedByDescending { it.bytes }

private fun gamesCount(n: Int) = "$n ${if (n == 1) "game" else "games"}"

/** The games pane's title: what it shows, how many and how much. */
@Composable
private fun ShownHeader(u: StorageUsage?, shown: SystemSize?, drive: String? = null) {
    val games = u?.games.orEmpty()
    val count = shown?.games ?: games.size
    val bytes = shown?.bytes ?: games.sumOf { it.bytes }
    Row(Modifier.fillMaxWidth().padding(start = Space.s, end = Space.l, top = Space.s, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
        val what = shown?.name ?: if (drive != null) "Everything" else "All systems"
        FText(if (drive != null) "$what on $drive" else what, Fuse.type.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        FText("${gamesCount(count)}  ·  ${bytesText(bytes)}", Fuse.type.label.tabular(), color = Fuse.colors.textMuted, maxLines = 1)
    }
}

/**
 * The games pane's rows: allowing access when deleting needs it, setting up a drive with no games
 * folders, selecting every game shown, on a narrow screen the drive and system filters (the wide
 * layout has its own pane for them), then the games shown, largest first. Moving and deleting are
 * on the selection bar, which stays in view however far the list scrolls.
 */
private fun storageRows(
    app: AppState,
    u: StorageUsage?,
    system: PlatformId?,
    picked: Set<GameId>,
    canDelete: Boolean,
    wide: Boolean,
    drives: List<VolumeUsage>,
    drive: String?,
    bare: List<io.github.matiyaaa.fuse.model.StorageVolume>,
    onPick: (GameId) -> Unit,
    onPickAll: (Set<GameId>) -> Unit,
    onFilter: (PlatformId?) -> Unit,
    onDrive: (String?) -> Unit,
): List<MenuAction> {
    val store = app.store
    val shown = u?.games.orEmpty().filter { system == null || it.card.platformId == system }
    // The rows above the games never come and go while you pick, so the focus stays on its game.
    return buildList {
        if (!canDelete) {
            add(MenuAction(
                "access", "Deleting needs All files access", FuseIcons.Lock,
                detail = "Fuse can show sizes, but moving or deleting files needs permission. Select to allow",
                trailing = Trailing.Chevron,
                onSelect = { app.platform.storage.request() },
            ))
        }
        // A card or drive put in without games folders: set up here, whether or not Fuse asked when it came.
        bare.forEach { v ->
            add(MenuAction(
                "setup.${v.id}", "Set up ${v.label} for games", if (v.kind == io.github.matiyaaa.fuse.model.VolumeKind.SD_CARD) FuseIcons.SdCard else FuseIcons.Usb,
                detail = "An Emulation folder with a folder for each system, in your library",
                trailing = Trailing.Chevron,
                onSelect = { app.offerDriveSetup(v.id, v.label, firstTime = false) },
            ))
        }
        val selectable = shown.filter { it.card.unavailable == null }.map { it.card.id }.toSet()
        if (selectable.size > 1) {
            val all = picked.containsAll(selectable)
            add(MenuAction(
                "pickall",
                if (all) "Clear these ${selectable.size} games" else "Select all ${selectable.size} shown",
                if (all) FuseIcons.Square else FuseIcons.ListChecks,
                detail = if (all) null else "To move or delete them together",
                onSelect = { onPickAll(selectable) },
            ))
        }
        if (!wide && drives.size > 1) {
            add(MenuAction(
                "drive", "Drive", FuseIcons.HardDrive,
                trailing = Trailing.Value(drives.firstOrNull { it.id == drive }?.label ?: "All drives"),
                onSelect = {
                    app.choice = ChoiceSpec(
                        title = "Show games on",
                        icon = FuseIcons.HardDrive,
                        options = listOf(MenuAction("all", "All drives", FuseIcons.Layers2, trailing = Trailing.Check(drive == null), onSelect = { onDrive(null); app.choice = null })) +
                            drives.map { v -> MenuAction("d.${v.id}", v.label, v.icon(), detail = driveLine(v), trailing = Trailing.Check(drive == v.id), onSelect = { onDrive(v.id); app.choice = null }) },
                    )
                },
            ))
        }
        if (!wide) {
            val systems = u?.games.orEmpty().map { it.card.platformId }.distinct()
            add(MenuAction(
                "filter", "Showing", FuseIcons.Filter,
                trailing = Trailing.Value(system?.let { store.library.platforms.value.firstOrNull { p -> p.platform.id == it }?.platform?.shortName ?: it.value } ?: "All systems"),
                onSelect = {
                    app.choice = ChoiceSpec(
                        title = "Show games from",
                        options = listOf(MenuAction("all", "All systems", FuseIcons.Layers, trailing = Trailing.Check(system == null), onSelect = { onFilter(null); app.choice = null })) +
                            systems.map { id ->
                                val name = store.library.platforms.value.firstOrNull { it.platform.id == id }?.platform?.name ?: id.value
                                MenuAction("s.${id.value}", name, null, trailing = Trailing.Check(system == id), onSelect = { onFilter(id); app.choice = null })
                            },
                    )
                },
            ))
        }
        shown.forEach { g ->
            val on = g.card.id in picked
            val away = g.card.unavailable
            add(MenuAction(
                "g${g.card.id.value}", g.card.title, if (away != null) FuseIcons.HardDrive else if (on) FuseIcons.SquareCheck else FuseIcons.Square,
                detail = if (away != null) "${g.card.platformShort}  ·  ${away.label}" else "${g.card.platformShort}  ·  ${g.files} ${if (g.files == 1) "file" else "files"}",
                trailing = Trailing.Value(bytesText(g.bytes)),
                // Square art for every game, so titles line up and the list stays compact.
                art = MenuArt(g.card.art.tile, square = true, fallbackTitle = g.card.title, accent = g.card.accent, wide = false),
                // A game whose drive is out can't be picked: Fuse can't see which files are its own.
                unavailableReason = away?.let { "${g.card.platformShort}  ·  On ${it.driveLabel}, not connected" },
                onSelect = { onPick(g.card.id) },
            ))
        }
    }
}

private fun summaryLine(u: StorageUsage?): String {
    if (u == null) return "Measuring your games"
    if (!u.finished) return "Measuring ${u.measured} of ${u.total} games"
    val here = u.games.filterNot { it.lastKnown }
    val drives = u.volumes.count { it.online }
    val away = u.volumes.count { !it.online }
    return "${here.size} games take ${bytesText(here.sumOf { it.bytes })}" +
        (if (drives > 1) " on $drives drives" else "") +
        (if (away > 0) "  ·  $away ${if (away == 1) "drive" else "drives"} not connected" else "")
}

/**
 * The drive at a glance: the drive picked, the one drive, or every drive together. A ring of what
 * fills it (games, everything else, free space), how much is free, then the games by system as one
 * bar in each system's colour; with [chips] (the narrow layout, which has no systems pane) each
 * system is a chip that shows only its games when tapped.
 */
@Composable
private fun Overview(
    u: StorageUsage?,
    drive: String?,
    systems: List<SystemSize>,
    compact: Boolean,
    chips: Boolean,
    shown: PlatformId?,
    modifier: Modifier = Modifier,
    onSystem: ((PlatformId?) -> Unit)? = null,
) {
    val volumes = u?.volumes.orEmpty()
    val on = if (drive != null) volumes.filter { it.id == drive } else volumes
    val online = on.filter { it.online }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        when {
            u == null -> VolumeSkeleton()
            volumes.isEmpty() -> Panel(Modifier.fillMaxWidth(), raised = true, shadow = false) {
                Box(Modifier.fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.HardDrive, "Drive space unknown",
                        message = "This system doesn't say how big these drives are. Each game's size is still below.",
                        compact = true,
                    )
                }
            }
            online.isEmpty() -> on.forEach { OfflineVolumeCard(it) }
            else -> DriveCard(online, away = on.size - online.size, systems = systems, compact = compact, chips = chips, shown = shown, onSystem = onSystem)
        }
    }
}

/** The ring card for [drives] (one, or several added up), with the games by system under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveCard(
    drives: List<VolumeUsage>,
    away: Int,
    systems: List<SystemSize>,
    compact: Boolean,
    chips: Boolean,
    shown: PlatformId?,
    onSystem: ((PlatformId?) -> Unit)?,
) {
    val c = Fuse.colors
    val one = drives.singleOrNull()
    val total = drives.sumOf { it.totalBytes }
    val free = drives.sumOf { it.freeBytes }
    val used = (total - free).coerceAtLeast(0)
    val gamesBytes = drives.sumOf { it.gamesBytes }
    val other = (used - gamesBytes).coerceAtLeast(0)
    val gamesColor = c.accent
    val rest = c.text.copy(alpha = if (c.isDark) 0.34f else 0.3f)
    Panel(Modifier.fillMaxWidth(), raised = true, shadow = false) {
        Column(Modifier.fillMaxWidth().padding(if (compact) Space.m else Space.l), verticalArrangement = Arrangement.spacedBy(if (compact) Space.m else Space.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UsageRing(
                    listOf(gamesBytes to gamesColor, other to rest), total,
                    diameter = if (compact) RING_COMPACT else RING, stroke = if (compact) RING_STROKE_COMPACT else RING_STROKE,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FText(percent(if (total > 0) used.toFloat() / total else 0f), (if (compact) Fuse.type.bodyStrong else Fuse.type.titleSmall).tabular(), maxLines = 1)
                        FText("full", Fuse.type.caption, color = c.textMuted, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(if (compact) Space.m else Space.l))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    val title = one?.let { it.label + if (it.readOnly) "  ·  Read only" else "" } ?: "${drives.size} drives"
                    SectionLabel(title, icon = one?.icon() ?: FuseIcons.Layers2)
                    Spacer(Modifier.height(Space.xxs))
                    Row(verticalAlignment = Alignment.Bottom) {
                        FText(bytesText(free), (if (compact) Fuse.type.titleSmall else Fuse.type.title).tabular(), maxLines = 1, modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(Space.xs + Space.xxs))
                        FText("free", Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
                    }
                    FText(
                        "of ${bytesText(total)}" + if (away > 0) "  ·  $away not connected" else "",
                        Fuse.type.caption, color = c.textMuted, maxLines = 1,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Key(gamesColor, "Games", gamesBytes)
                    Key(rest, "Everything else", other)
                }
            }
            if (systems.isNotEmpty()) SystemsBreakdown(systems, chips, shown, onSystem)
        }
    }
}

/**
 * What the games take by system: one bar with each system's share in its colour (the largest
 * first, the smallest together as one), and with [chips] a chip for each, which filters the games.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemsBreakdown(systems: List<SystemSize>, chips: Boolean, shown: PlatformId?, onSystem: ((PlatformId?) -> Unit)?) {
    val c = Fuse.colors
    val rest = c.text.copy(alpha = if (c.isDark) 0.34f else 0.3f)
    val total = systems.sumOf { it.bytes }
    val named = systems.take(SYSTEMS_SHOWN)
    val others = systems.drop(SYSTEMS_SHOWN).sumOf { it.bytes }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText("Games by system", Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            FText(bytesText(total), Fuse.type.numericSmall, color = c.text, maxLines = 1)
        }
        UsageBar(named.map { it.bytes to it.accent.toColor() } + listOfNotNull(if (others > 0) others to rest else null), total, Modifier.fillMaxWidth(), height = Size.track * 2)
        if (chips) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                named.forEach { sz ->
                    SystemChip(sz.accent.toColor(), sz.short, sz.bytes, on = sz.id == shown, onClick = onSystem?.let { f -> { f(sz.id) } })
                }
                if (others > 0) SystemChip(rest, "${systems.size - named.size} more", others, on = false, onClick = null)
            }
        }
    }
}

/** A system in the breakdown: its colour, short name and size; the one shown is outlined. */
@Composable
private fun SystemChip(color: Color, name: String, bytes: Long, on: Boolean, onClick: (() -> Unit)?) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Size.chipCompact / 2)
    Row(
        Modifier
            .height(Size.chipCompact)
            .clip(shape)
            .background(if (on) color.copy(alpha = 0.22f) else c.text.copy(alpha = if (c.isDark) 0.06f else 0.05f))
            .then(if (on) Modifier.border(Size.stroke, color.copy(alpha = 0.8f), shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.s + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(Size.dot).drawBehind { drawCircle(color) })
        Spacer(Modifier.width(Space.xs + Space.xxs))
        FText(name, Fuse.type.caption, color = c.text, maxLines = 1)
        Spacer(Modifier.width(Space.xs))
        FText(bytesText(bytes), Fuse.type.numericSmall, color = c.textMuted, maxLines = 1)
    }
}

/**
 * The bar at the foot of the games while any are picked: how many and how much, and Move, Delete
 * and clear by touch (Y offers the same with a controller). Narrow, its buttons go on a row of their
 * own so neither the count nor a label is cut.
 */
@Composable
private fun SelectionBar(count: Int, bytes: Long, canMove: Boolean, canDelete: Boolean, onMove: () -> Unit, onDelete: () -> Unit, onClear: () -> Unit) {
    val c = Fuse.colors
    Panel(Modifier.fillMaxWidth().padding(start = Space.s, end = Space.s, bottom = Space.s), raised = true) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // With Move as well as Delete the row needs more room before it can keep them beside the count.
            val stacked = maxWidth < if (canMove) SELECTION_STACK_BELOW else SELECTION_STACK_BELOW_ONE
            val buttons: @Composable (Modifier) -> Unit = { m ->
                if (canMove) FuseButton("Move", selected = false, onClick = onMove, icon = FuseIcons.FolderSync, height = BAR_BUTTON, enabled = canDelete, modifier = m)
                FuseButton("Delete", selected = false, onClick = onDelete, icon = FuseIcons.Trash, kind = ButtonKind.DANGER, height = BAR_BUTTON, enabled = canDelete, modifier = m)
            }
            Column(Modifier.fillMaxWidth().padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(BAR_BADGE).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                        FText(count.toString(), Fuse.type.label.tabular(), color = c.onAccent, maxLines = 1)
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(if (count == 1) "1 game selected" else "$count games selected", Fuse.type.bodyStrong, maxLines = 1)
                        FText(bytesText(bytes), Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
                    }
                    if (!stacked) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) { buttons(Modifier) }
                        Spacer(Modifier.width(Space.s))
                    }
                    IconButton(FuseIcons.Close, selected = false, onClick = onClear, size = BAR_BUTTON, contentDescription = "Clear the selection")
                }
                if (stacked) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { buttons(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A ring with [parts] laid around it clockwise from the top, each [bytes] of [total], over a faint
 * track. Every part shows at least as a dot, and the parts ease to new sizes. [center] sits inside.
 */
@Composable
private fun UsageRing(parts: List<Pair<Long, Color>>, total: Long, diameter: Dp, stroke: Dp, center: @Composable () -> Unit) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val shares = parts.map { (b, _) -> (b.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }
    val eased = shares.mapIndexed { i, f -> fuselineFloat(f, Fuse.motion.value(), label = "ring$i") }
    val colors = parts.map { it.second }
    Box(
        Modifier.size(diameter).drawBehind {
            val w = stroke.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val at = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = at, size = box, style = Stroke(w))
            // A round cap reaches half the stroke past each end; the gap keeps neighbours apart.
            val radius = (size.minDimension - w) / 2
            val cap = (w / 2 / radius) * (180f / kotlin.math.PI.toFloat())
            val gap = cap * 2 + RING_GAP_DEGREES
            var start = -90f
            eased.forEachIndexed { i, f ->
                if (shares[i] <= 0f) return@forEachIndexed
                val sweep = 360f * f.value
                // A sliver still shows, as a dot the stroke's width.
                val drawn = (sweep - gap).coerceAtLeast(MIN_SWEEP_DEGREES)
                drawArc(colors[i], start + gap / 2, drawn, useCenter = false, topLeft = at, size = box, style = Stroke(w, cap = StrokeCap.Round))
                start += maxOf(sweep, gap)
            }
        },
        contentAlignment = Alignment.Center,
    ) { center() }
}

/** The games, drives and systems of [this] narrowed to the drive [id]; everything when null. */
private fun StorageUsage.onDrive(id: String?): StorageUsage =
    if (id == null) this else copy(games = games.filter { it.volumeId == id })

/** The icon for a kind of drive. */
private fun VolumeUsage.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    io.github.matiyaaa.fuse.model.VolumeKind.SD_CARD -> FuseIcons.SdCard
    io.github.matiyaaa.fuse.model.VolumeKind.USB, io.github.matiyaaa.fuse.model.VolumeKind.EXTERNAL -> FuseIcons.Usb
    io.github.matiyaaa.fuse.model.VolumeKind.NETWORK -> FuseIcons.Network
    io.github.matiyaaa.fuse.model.VolumeKind.OPTICAL -> FuseIcons.Disc
    else -> FuseIcons.HardDrive
}

private fun VolumeUsage.usedFraction(): Float =
    if (totalBytes <= 0) 0f else ((totalBytes - freeBytes).toFloat() / totalBytes).coerceIn(0f, 1f)

/** A drive row's second line: its space and games, or when it was last seen. */
private fun driveLine(v: VolumeUsage): String =
    if (!v.online) {
        gamesCount(v.games) + (v.lastSeenAt?.let { "  ·  seen ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it)}" } ?: "")
    } else {
        "${bytesText(v.freeBytes)} free of ${bytesText(v.totalBytes)}  ·  ${gamesCount(v.games)}"
    }

/**
 * A drive that is out: its name, that its games are kept, and when it was last seen. Calm, not an
 * error; nothing needs doing until the drive is connected again.
 */
@Composable
private fun OfflineVolumeCard(v: VolumeUsage) {
    val c = Fuse.colors
    Panel(Modifier.fillMaxWidth(), raised = true, shadow = false) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SectionLabel(v.label, icon = v.icon())
            FText("Not connected", Fuse.type.title, maxLines = 1)
            FText(
                "${gamesCount(v.games)} kept, with their art and play time. They come back as they were when the drive is connected." +
                    (v.lastSeenAt?.let { " Last seen ${io.github.matiyaaa.fuse.ui.shell.components.agoText(it)}." } ?: ""),
                Fuse.type.caption, color = c.textMuted, maxLines = 4,
            )
        }
    }
}

/**
 * A rounded track with [parts] laid along it in order, each [bytes] of [total]. Every part shows at
 * least as a dot, so a few kilobytes of games on a large drive still read as something. The parts
 * ease to new sizes.
 */
@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>, total: Long, modifier: Modifier, height: Dp = Size.track) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val shares = parts.map { (b, _) -> (b.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }
    val eased = shares.mapIndexed { i, f ->
        fuselineFloat(f, Fuse.motion.value(), label = "usage$i")
    }
    val colors = parts.map { it.second }
    Spacer(
        modifier.height(height).drawBehind {
            val h = size.height
            val r = CornerRadius(h / 2)
            drawRoundRect(track, cornerRadius = r)
            var x = 0f
            eased.forEachIndexed { i, f ->
                val share = f.value
                if (shares[i] <= 0f) return@forEachIndexed
                val w = (size.width * share).coerceAtLeast(h).coerceAtMost(size.width - x)
                if (w <= 0f) return@forEachIndexed
                drawRoundRect(colors[i], Offset(x, 0f), androidx.compose.ui.geometry.Size(w, h), r)
                // Neighbouring parts meet edge to edge, with a hairline of the track between them.
                x += w + if (i < eased.lastIndex) 1.dp.toPx() else 0f
            }
        },
    )
}

/** A legend entry: a dot (a ring for free space) and what it stands for, with its size. */
@Composable
private fun Key(color: Color?, label: String, bytes: Long) {
    val c = Fuse.colors
    val ring = c.text.copy(alpha = 0.4f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier.size(Size.dot).drawBehind {
                if (color != null) drawCircle(color) else drawCircle(ring, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            },
        )
        Spacer(Modifier.width(Space.xs + Space.xxs))
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.width(Space.xs))
        FText(bytesText(bytes), Fuse.type.numericSmall, color = c.text, maxLines = 1)
    }
}

/** A drive card's shape while Fuse looks at the drives. */
@Composable
private fun VolumeSkeleton() {
    Panel(Modifier.fillMaxWidth(), raised = true, shadow = false) {
        Row(Modifier.fillMaxWidth().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Skeleton(Modifier.size(RING_COMPACT).clip(CircleShape))
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Skeleton(Modifier.fillMaxWidth(0.5f).height(Space.m))
                Skeleton(Modifier.fillMaxWidth(0.7f).height(Space.xl))
                SkeletonText(lines = 2, style = Fuse.type.label, lastLineFraction = 0.6f)
            }
        }
    }
}

/** Asks before deleting, naming what goes. */
private fun confirmDelete(app: AppState, games: List<Pair<GameId, String>>, files: Int, bytes: Long, onDone: () -> Unit) {
    val names = games.take(4).joinToString(", ") { it.second } + if (games.size > 4) " and ${games.size - 4} more" else ""
    app.confirm = ConfirmSpec(
        title = "Delete ${games.size} ${if (games.size == 1) "game" else "games"}?",
        message = "$names. Deletes $files ${if (files == 1) "file" else "files"} (${bytesText(bytes)}): every disc, track and folder of " +
            "${if (games.size == 1) "this game" else "these games"}. Saves your emulators keep elsewhere stay. This can't be undone.",
        confirmLabel = "Delete files",
        destructive = true,
    ) {
        app.scope.launch {
            val report = app.store.storage.delete(games.map { it.first })
            onDone()
            app.toasts.show(
                when {
                    report.failed.isEmpty() -> "Deleted ${report.deleted} ${if (report.deleted == 1) "game" else "games"} and freed ${bytesText(report.freedBytes)}"
                    report.deleted == 0 -> "Couldn't delete ${report.failed.joinToString(", ")}. Check that Fuse may change these folders"
                    else -> "Deleted ${report.deleted}; couldn't delete ${report.failed.joinToString(", ")}"
                },
            )
        }
    }
}

/** Drives sit beside the games from this width; the measuring bar's length; rows shown while loading. */
private val WIDE_FROM = 760.dp
private val SHORT_BELOW = 560.dp
private val MEASURE_BAR = 140.dp
private const val SKELETON_ROWS = 6

/** The page's widest; the drive pane's share of it, within its least and most. */
private val PAGE_MAX = 1600.dp
private const val LEFT_SHARE = 0.38f
private val LEFT_MIN = 300.dp
private val LEFT_MAX = 460.dp

/** The ring, and its stroke, at full size and on a short screen; the gap between its parts. */
private val RING = 112.dp
private val RING_STROKE = 12.dp
private val RING_COMPACT = 84.dp
private val RING_STROKE_COMPACT = 9.dp
private const val RING_GAP_DEGREES = 4f
private const val MIN_SWEEP_DEGREES = 0.5f

/** The selection bar: its count badge, its buttons' height, and where its buttons take a row of their own. */
private val BAR_BADGE = 36.dp
private val BAR_BUTTON = 40.dp
private val SELECTION_STACK_BELOW = 480.dp
private val SELECTION_STACK_BELOW_ONE = 360.dp

/** Systems named in the breakdown; the rest share one part. */
private const val SYSTEMS_SHOWN = 6

/** A share as a whole percentage, with a sliver shown as under one. */
private fun percent(f: Float): String = when {
    f <= 0f -> "0%"
    f < 0.01f -> "<1%"
    else -> "${(f * 100).toInt().coerceAtMost(100)}%"
}
