package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
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
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.platform.StorageState
import io.github.matiyaaa.fuse.ui.shell.store.StorageUsage
import io.github.matiyaaa.fuse.ui.shell.store.VolumeUsage
import kotlinx.coroutines.launch

/**
 * Storage: each drive the library is on (games by system, other files, free space) and every game
 * by what it takes, largest first. Games can be picked and their files deleted, after a
 * confirmation naming what goes. Only here, on the device; Phone Link can't delete anything.
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
    val rows = remember(u, system, picked, canDelete, platforms, wideLayout) {
        storageRows(
            app, u, system, picked, canDelete, wide = wideLayout, drives = volumes, drive = drive,
            onPick = { id -> picked = if (id in picked) picked - id else picked + id },
            onPicked = { picked = emptySet() },
            onFilter = { system = it; sel.index = 0 },
            onDrive = { drive = it; system = null; sel.index = 0 },
        )
    }
    val systemRows = remember(systems, u, system, volumes, drive) {
        val total = u?.games.orEmpty().sumOf { it.bytes }
        val largest = systems.maxOfOrNull { it.bytes }?.coerceAtLeast(1) ?: 1
        fun choose(id: PlatformId?) {
            // A second press on the system shown goes back to all of them.
            system = if (id == null || id == system) null else id
            sel.index = 0
        }
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
                        trailing = if (!v.online) Trailing.Value("Not connected")
                        else Trailing.Level(v.usedFraction(), "${(v.usedFraction() * 100).toInt()}%"),
                        onSelect = { chooseDrive(v.id) },
                    ))
                }
            }
            val systemsSection = if (volumes.size > 1) "Systems" else null
            add(MenuAction(
                "all", "All systems", FuseIcons.Layers, section = systemsSection,
                detail = gamesCount(u?.games?.size ?: 0),
                trailing = Trailing.Value(bytesText(total)),
                onSelect = { choose(null) },
            ))
            systems.forEach { sz ->
                add(MenuAction(
                    "s.${sz.id.value}", sz.name, section = systemsSection,
                    detail = gamesCount(sz.games),
                    trailing = Trailing.Level(sz.bytes.toFloat() / largest, bytesText(sz.bytes)),
                    art = MenuArt(sz.art, square = true, fallbackTitle = sz.short, accent = sz.accent, wide = false),
                    onSelect = { choose(sz.id) },
                ))
            }
        }
    }
    // The row the filters are on: the system shown, else the drive shown, else the first row.
    val filterKey = system?.let { "s.${it.value}" } ?: drive?.let { "d.$it" } ?: systemRows.firstOrNull()?.id
    val filterRow = systemRows.indexOfFirst { it.id == filterKey }.coerceAtLeast(0)

    val shownRows = rows
    sel.clamp(shownRows.size)
    systemSel.clamp(systemRows.size)
    val inSystems = wideLayout && pane == StoragePane.SYSTEMS

    LaunchedEffect(inSystems, picked.isEmpty()) {
        app.hints = if (inSystems) {
            listOf(Hint(HintButton.CONFIRM, "Show these games"), Hint(HintButton.DPAD, "Games"), Hint(HintButton.BACK, "Back"))
        } else {
            listOf(Hint(HintButton.CONFIRM, "Select"), Hint(HintButton.OPTIONS, if (picked.isEmpty()) "Game page" else "Game page or clear"), Hint(HintButton.BACK, "Back"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (inSystems) {
            when (e.action) {
                NavAction.RIGHT -> { pane = StoragePane.GAMES; systemSel.index = filterRow; NavResult.MOVED }
                NavAction.LEFT -> NavResult.BLOCKED
                NavAction.CONTEXT -> NavResult.BLOCKED
                else -> handleMenuAction(e, systemRows, systemSel)
            }
        } else {
            when (e.action) {
                // Left goes over to the systems, starting on the one shown.
                NavAction.LEFT -> if (wideLayout) { pane = StoragePane.SYSTEMS; systemSel.index = filterRow; NavResult.MOVED } else NavResult.IGNORED
                NavAction.CONTEXT -> {
                    // X on a game opens its page; anywhere else it clears the selection.
                    val id = shownRows.getOrNull(sel.index)?.id?.takeIf { it.startsWith("g") }?.removePrefix("g")?.toLongOrNull()
                    if (id != null) app.go(Route.GameInfo(GameId(id))) else picked = emptySet()
                    NavResult.ACTIVATED
                }
                else -> handleMenuAction(e, shownRows, sel)
            }
        }
    }

    val empty = u != null && u.finished && u.games.isEmpty()
    val focused = app.focusZone == FocusZone.CONTENT
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WIDE_FROM
        SideEffect { wideLayout = wide }
        val short = maxHeight < SHORT_BELOW
        Column(Modifier.fillMaxSize().padding(horizontal = if (wide) Space.gutter else Space.gutterCompact)) {
            Spacer(Modifier.height(Size.hudHeight + if (short) Space.s else Space.l))
            SettingsPageHeading("Storage", summaryLine(all), short, Modifier.reveal(0)) {
                if (all != null && !all.finished) {
                    ProgressBar(if (all.total > 0) all.measured.toFloat() / all.total else null, Modifier.width(MEASURE_BAR))
                }
            }
            Spacer(Modifier.height(if (short) Space.m else Space.l))
            val games: @Composable (Modifier, List<MenuAction>, (@Composable () -> Unit)?) -> Unit = { m, list, header ->
                Panel(m) {
                    Column(Modifier.fillMaxSize()) {
                        MenuList(
                            list, sel,
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
                }
            }
            if (wide) {
                // Two panes that scroll on their own: the drives and systems on the left, which filter
                // the games on the right. Left and Right move between them.
                Row(Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                    Panel(Modifier.weight(0.4f).fillMaxHeight().reveal(1)) {
                        MenuList(
                            systemRows, systemSel,
                            showSelection = focused,
                            // Away from this pane, a quiet marker stays on the system shown.
                            dimSelection = !inSystems,
                            header = {
                                Column(Modifier.padding(top = Space.xs, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                                    // One drive, or the drive picked: its card. Several: each is a row below.
                                    Volumes(all, drive, nested = true, bySystem = false)
                                    if (systems.isNotEmpty() && volumes.size <= 1) SectionLabel("Systems", count = systems.size.toString(), rule = true, modifier = Modifier.padding(horizontal = Space.s))
                                }
                            },
                            modifier = Modifier.padding(Space.s),
                            fadeEdges = true,
                        )
                    }
                    games(Modifier.weight(0.6f).fillMaxHeight().reveal(2), shownRows) {
                        ShownHeader(u, systems.firstOrNull { it.id == system }, volumes.firstOrNull { it.id == drive }?.label)
                    }
                }
            } else {
                games(
                    Modifier.fillMaxSize().padding(bottom = Size.hintHeight + Space.s).reveal(1),
                    shownRows,
                ) { Column(Modifier.padding(top = Space.s, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) { Volumes(all, drive, nested = true, bySystem = true, every = true) } }
            }
        }
    }
}

private enum class StoragePane { SYSTEMS, GAMES }

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
 * The games pane's rows: allowing access when deleting needs it, deleting what is picked, on a
 * narrow screen the system filter (the wide layout has the systems pane), then the games shown,
 * largest first.
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
    onPick: (GameId) -> Unit,
    onPicked: () -> Unit,
    onFilter: (PlatformId?) -> Unit,
    onDrive: (String?) -> Unit,
): List<MenuAction> {
    val store = app.store
    val shown = u?.games.orEmpty().filter { system == null || it.card.platformId == system }
    val chosen = u?.games.orEmpty().filter { it.card.id in picked }
    // The rows above the games never come and go while you pick, so the focus stays on its game.
    return buildList {
        if (!canDelete) {
            add(MenuAction(
                "access", "Deleting needs All files access", FuseIcons.Lock,
                detail = "Fuse can show sizes, but deleting files needs permission. Select to allow",
                trailing = Trailing.Chevron,
                onSelect = { app.platform.storage.request() },
            ))
        }
        val bytes = chosen.sumOf { it.bytes }
        add(MenuAction(
            "delete",
            if (chosen.isEmpty()) "Delete selected games" else "Delete ${chosen.size} ${if (chosen.size == 1) "game" else "games"} (${bytesText(bytes)})",
            FuseIcons.Trash,
            destructive = chosen.isNotEmpty(),
            detail = if (chosen.isEmpty()) null else "Their files go from your storage. You'll be asked first. X clears the selection",
            unavailableReason = when {
                chosen.isEmpty() -> "Select games below, then delete their files here"
                !canDelete -> "Allow All files access first"
                else -> null
            },
            onSelect = { confirmDelete(app, chosen.map { it.card.id to it.card.title }, chosen.sumOf { it.files }, bytes, onPicked) },
        ))
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
 * The drive cards: the one drive, or the drive picked. With several drives and none picked, the
 * drives are rows in the list (each with its own bar), so no card adds them up into one meaningless
 * total; [every] (the narrow layout, which has no drive rows) shows each drive's card instead.
 */
@Composable
private fun Volumes(u: StorageUsage?, drive: String?, nested: Boolean, bySystem: Boolean, every: Boolean = false) {
    val volumes = u?.volumes.orEmpty()
    val shown = when {
        drive != null -> volumes.filter { it.id == drive }
        volumes.size == 1 || every -> volumes
        else -> emptyList()
    }
    when {
        u == null -> VolumeSkeleton(nested)
        volumes.isEmpty() -> Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
            Box(Modifier.fillMaxWidth().padding(Space.l), contentAlignment = Alignment.Center) {
                EmptyState(
                    FuseIcons.HardDrive, "Drive space unknown",
                    message = "This system doesn't say how big these drives are. Each game's size is still below.",
                    compact = true,
                )
            }
        }
        else -> shown.forEach { if (it.online) VolumeCard(it, nested, bySystem) else OfflineVolumeCard(it, nested) }
    }
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
private fun OfflineVolumeCard(v: VolumeUsage, nested: Boolean) {
    val c = Fuse.colors
    Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
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
 * A drive: how much is free, a bar of what fills it (games, everything else, free space), then the
 * games on it by system, each with a bar against the largest, in the system's own colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VolumeCard(v: VolumeUsage, nested: Boolean, bySystem: Boolean) {
    val c = Fuse.colors
    val used = (v.totalBytes - v.freeBytes).coerceAtLeast(0)
    val other = (used - v.gamesBytes).coerceAtLeast(0)
    val games = c.accent
    val rest = c.text.copy(alpha = if (c.isDark) 0.34f else 0.3f)
    Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SectionLabel(v.label + if (v.readOnly) "  ·  Read only" else "", icon = v.icon())
            Row(verticalAlignment = Alignment.Bottom) {
                FText(bytesText(v.freeBytes), Fuse.type.title.tabular(), maxLines = 1, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(Space.s))
                FText("free of ${bytesText(v.totalBytes)}", Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            UsageBar(listOf(v.gamesBytes to games, other to rest), v.totalBytes, Modifier.fillMaxWidth().padding(vertical = Space.xxs))
            // The legend wraps on a narrow screen rather than losing its last entry.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Key(games, "Games", v.gamesBytes)
                Key(rest, "Everything else", other)
                Key(null, "Free", v.freeBytes)
            }
            // Beside the systems pane the drive leaves its systems to it.
            if (bySystem && v.systems.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                SectionLabel("Games by system", count = v.systems.size.toString(), rule = true)
                Spacer(Modifier.height(Space.xxs))
                val shown = v.systems.take(SYSTEMS_SHOWN)
                val others = v.systems.drop(SYSTEMS_SHOWN).sumOf { it.bytes }
                val largest = (shown.maxOfOrNull { it.bytes } ?: 0L).coerceAtLeast(others).coerceAtLeast(1)
                shown.forEach { SystemBar(it.accent.toColor(), it.name, it.bytes, largest) }
                if (others > 0) SystemBar(rest, "Other systems", others, largest)
            }
        }
    }
}

/** One system's share of the games on a drive: its name and size over a bar in its colour. */
@Composable
private fun SystemBar(color: Color, name: String, bytes: Long, largest: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xxs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(name, Fuse.type.label, color = Fuse.colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            FText(bytesText(bytes), Fuse.type.numericSmall, color = Fuse.colors.textMuted, maxLines = 1)
        }
        UsageBar(listOf(bytes to color), largest, Modifier.fillMaxWidth())
    }
}

/**
 * A rounded track with [parts] laid along it in order, each [bytes] of [total]. Every part shows at
 * least as a dot, so a few kilobytes of games on a large drive still read as something. The parts
 * ease to new sizes.
 */
@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>, total: Long, modifier: Modifier) {
    val c = Fuse.colors
    val track = c.text.copy(alpha = if (c.isDark) 0.1f else 0.08f)
    val shares = parts.map { (b, _) -> (b.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) }
    val eased = shares.mapIndexed { i, f ->
        animateFloatAsState(f, Fuse.motion.value(), label = "usage$i")
    }
    val colors = parts.map { it.second }
    val height = Size.track * if (parts.size > 1) 2 else 1
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
private fun VolumeSkeleton(nested: Boolean) {
    Panel(Modifier.fillMaxWidth(), raised = nested, shadow = !nested) {
        Column(Modifier.fillMaxWidth().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Skeleton(Modifier.fillMaxWidth(0.4f).height(Space.m))
            Skeleton(Modifier.fillMaxWidth(0.6f).height(Space.xl))
            Skeleton(Modifier.fillMaxWidth().height(Size.track * 2))
            SkeletonText(lines = 3, style = Fuse.type.label, lastLineFraction = 0.5f)
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

/** Systems listed by name on each drive; the rest share one bar. */
private const val SYSTEMS_SHOWN = 6
