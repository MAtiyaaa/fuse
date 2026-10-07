package io.github.matiyaaa.fuse.ui.shell.jellyfin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.jellyfin.MediaItem
import io.github.matiyaaa.fuse.jellyfin.MediaType
import io.github.matiyaaa.fuse.jellyfin.OfflineEntry
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
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
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.player.FusePlayer
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.downloads.ActionPill
import io.github.matiyaaa.fuse.ui.shell.downloads.TopAction
import io.github.matiyaaa.fuse.ui.shell.downloads.sizeOf
import kotlinx.coroutines.launch

/** Whether [type] can be kept offline: a film, an episode, or every episode of a season or show. */
internal fun downloadable(type: MediaType) = type == MediaType.MOVIE || type == MediaType.EPISODE || type == MediaType.VIDEO || type == MediaType.SEASON || type == MediaType.SERIES

/** The kept copy of [item], when it is here to play. */
internal fun AppState.keptCopy(item: MediaItem): OfflineEntry? = store.offlineMedia.entries.value.firstOrNull { it.itemId == item.id && it.here }

/** The Download, Play Downloaded and Remove entries for an item's options. */
internal fun AppState.offlineActions(item: MediaItem, close: () -> Unit): List<MenuAction> {
    val ops = store.offlineMedia
    if (!ops.supported || !downloadable(item.type)) return emptyList()
    val kept = ops.entries.value.firstOrNull { it.itemId == item.id }
    return listOfNotNull(
        MenuAction("offline.play", "Play Downloaded", FuseIcons.Play, detail = "From this device, no server needed", onSelect = { close(); playDownloaded(kept!!) })
            .takeIf { kept?.here == true },
        MenuAction(
            "offline.get", if (item.type == MediaType.SERIES || item.type == MediaType.SEASON) "Download Episodes" else "Download", FuseIcons.Download,
            detail = "Keep it on this device to watch offline", onSelect = { close(); downloadMedia(item) },
        ).takeIf { kept == null },
        MenuAction("offline.remove", "Remove Download", FuseIcons.Trash, detail = if (kept?.here == true) "Deletes the copy on this device" else "Its drive isn't connected: Fuse forgets it, the files stay", onSelect = {
            close()
            removeDownloads(listOf(kept!!))
        }).takeIf { kept != null },
    )
}

/**
 * Download for offline: works out what it would bring (one film, or every episode not already
 * here), says how much and where, and queues it in Downloads once the person agrees.
 */
internal fun AppState.downloadMedia(item: MediaItem) {
    val ops = store.offlineMedia
    scope.launch {
        toasts.show("Looking at ${item.name}...", icon = FuseIcons.Download)
        val plan = ops.plan(item)
        plan.problem?.let {
            toasts.show(it, ToastKind.WARNING)
            return@launch
        }
        if (plan.items.isEmpty()) {
            toasts.show(if (plan.already > 0) "Already downloaded or on its way" else "Nothing here to download", ToastKind.INFO, icon = FuseIcons.CircleCheck)
            return@launch
        }
        val count = plan.items.size
        val size = if (plan.totalBytes > 0) "About ${sizeOf(plan.totalBytes)}" else "Its size isn't known yet"
        val where = plan.folder.trimEnd('/').split('/').takeLast(2).joinToString("/")
        choice = ChoiceSpec(
            title = if (count == 1) "Download ${plan.items.first().name}?" else "Download $count episodes?",
            message = listOfNotNull(
                "$size, into $where.",
                "${plan.already} already here are left as they are.".takeIf { plan.already > 0 },
                "It keeps going while you play, and carries on after a lost connection.",
            ).joinToString(" "),
            icon = FuseIcons.Download,
            options = listOf(
                MenuAction("offline.go", if (count == 1) "Download" else "Download $count", FuseIcons.Download, onSelect = {
                    choice = null
                    scope.launch {
                        val n = ops.start(plan)
                        toasts.show(if (n == 1) "Downloading ${plan.items.first().name}" else "Downloading $n episodes", ToastKind.SUCCESS, icon = FuseIcons.Download)
                    }
                }),
                MenuAction("offline.where", "Change Folder", FuseIcons.FolderOpen, detail = plan.folder, onSelect = {
                    choice = null
                    scope.launch {
                        val path = platform.storage.pickFolder("Where films and shows are kept") ?: return@launch
                        store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(offlineFolder = path)) }
                        downloadMedia(item)
                    }
                }),
            ),
        )
    }
}

/** Plays a kept film or episode from this device, from where it was left. */
internal fun AppState.playDownloaded(entry: OfflineEntry, fromStart: Boolean = false) {
    val resolver = store.offlineMedia.resolver() ?: return
    if (!FusePlayer.available) {
        toasts.show("This build of Fuse has no player")
        return
    }
    if (!entry.here) {
        toasts.show("${entry.driveLabel ?: "The drive"} with this download isn't connected", ToastKind.WARNING)
        return
    }
    val session = FusePlayer.session
    val j = store.prefs.value.jellyfin
    session.settings = j.toPlayerSettings()
    io.github.matiyaaa.fuse.ui.player.PlayerPlacement.withMenus = true
    val item = entry.toPlayItem()
    session.start(item, resolver, if (fromStart) 0 else item.resumeMs, listOf(item))
    if (j.rememberSpeed && j.speed != 1f) session.changeSpeed(j.speed)
    playerOpen = true
}

/** Deletes kept copies after asking (or forgets the ones on a drive that isn't connected). */
internal fun AppState.removeDownloads(entries: List<OfflineEntry>, after: () -> Unit = {}) {
    if (entries.isEmpty()) return
    val here = entries.count { it.here }
    val bytes = entries.filter { it.here }.sumOf { it.meta.sizeBytes }
    confirm = ConfirmSpec(
        if (entries.size == 1) "Remove ${entries.first().meta.name}?" else "Remove ${entries.size} downloads?",
        listOfNotNull(
            "Frees ${sizeOf(bytes)} on this device. They stay on your server.".takeIf { here > 0 },
            "${entries.size - here} on a drive that isn't connected are only forgotten: their files stay on the drive.".takeIf { here < entries.size },
        ).joinToString(" "),
        "Remove",
        destructive = true,
    ) {
        scope.launch {
            val n = store.offlineMedia.remove(entries.map { it.key })
            toasts.show(if (n == 1) "Download removed" else "$n downloads removed", ToastKind.SUCCESS, icon = FuseIcons.Trash)
            after()
        }
    }
}

/** Moves kept copies to a drive or folder the person picks, through Downloads. */
internal fun AppState.moveDownloads(entries: List<OfflineEntry>, after: () -> Unit = {}) {
    val movable = entries.filter { it.here }
    if (movable.isEmpty()) {
        toasts.show("Connect the drive they're on first", ToastKind.WARNING)
        return
    }
    val volumes = store.sources.volumes.value.filter { !it.readOnly && it.mountPath.isNotEmpty() }
    fun go(folder: String) {
        choice = null
        scope.launch {
            val n = store.offlineMedia.move(movable.map { it.key }, folder)
            if (n == 0) toasts.show("They're already there") else toasts.show(if (n == 1) "Moving it in Downloads" else "Moving $n in Downloads", ToastKind.SUCCESS, icon = FuseIcons.FolderSync)
            after()
        }
    }
    val bytes = movable.sumOf { it.meta.sizeBytes }
    choice = ChoiceSpec(
        title = if (movable.size == 1) "Move ${movable.first().meta.name}" else "Move ${movable.size} downloads",
        message = "${sizeOf(bytes)}. Each stays where it is until its copy is whole on the new drive.",
        icon = FuseIcons.FolderSync,
        options = volumes.map { v ->
            MenuAction(
                "move.${v.id}", v.label, if (v.removable) FuseIcons.Usb else FuseIcons.HardDrive,
                detail = if (v.freeBytes > 0) "${sizeOf(v.freeBytes)} free" else v.mountPath,
                enabled = v.freeBytes <= 0 || v.freeBytes > bytes,
                onSelect = { go(io.github.matiyaaa.fuse.library.FsPath.join(v.mountPath, "Fuse Offline")) },
            )
        } + MenuAction("move.pick", "Choose a Folder", FuseIcons.FolderOpen, onSelect = {
            choice = null
            scope.launch { platform.storage.pickFolder("Move downloads to")?.let(::go) }
        }),
    )
}

/**
 * Downloaded: every film and episode kept on this device, grouped by show, with where each one is
 * and how far in you are. Plays without the server. Options handles one; Select several picks more
 * than one to delete or move together. A download on a drive that isn't connected stays listed,
 * dimmed, saying which drive it waits on.
 */
@Composable
fun OfflineMediaScreen(app: AppState) {
    val ops = app.store.offlineMedia
    val all by ops.entries.collectAsState()
    val folder by ops.folder.collectAsState()
    val entries = remember(all) {
        all.sortedWith(compareBy<OfflineEntry>({ if (it.meta.isEpisode) 1 else 0 }, { (it.meta.seriesName ?: it.meta.name).lowercase() }, { it.meta.season ?: 0 }, { it.meta.episode ?: 0 }))
    }
    val sel = remember { LinearSelection() }
    sel.clamp(entries.size)
    var zone by remember { mutableIntStateOf(if (entries.isEmpty()) 0 else 1) }
    var action by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    picked.retainAll(entries.map { it.key }.toSet())
    if (entries.isEmpty()) zone = 0
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val chosen = entries.filter { it.key in picked }
    fun done() { choosing = false; picked.clear() }
    val actions = buildList {
        if (choosing) {
            add(TopAction(if (chosen.isEmpty()) "Delete" else "Delete ${chosen.size}", FuseIcons.Trash) { app.removeDownloads(chosen, ::done) })
            add(TopAction(if (chosen.isEmpty()) "Move" else "Move ${chosen.size}", FuseIcons.FolderSync) { app.moveDownloads(chosen, ::done) })
            add(TopAction("Done", FuseIcons.Check) { done() })
        } else {
            if (entries.isNotEmpty()) add(TopAction("Select Several", FuseIcons.ListChecks) { choosing = true; zone = 1 })
            add(TopAction("Folder", FuseIcons.FolderOpen) {
                app.scope.launch {
                    val path = app.platform.storage.pickFolder("Where films and shows are kept") ?: return@launch
                    app.store.updatePrefs { it.copy(jellyfin = it.jellyfin.copy(offlineFolder = path)) }
                }
            })
            add(TopAction("Downloads", FuseIcons.Download) { app.go(Route.Downloads) })
        }
    }
    if (action > actions.lastIndex) action = actions.lastIndex.coerceAtLeast(0)
    val current = entries.getOrNull(sel.index)

    fun options(e: OfflineEntry) = app.openContextMenu(
        ContextMenuSpec(
            title = if (e.meta.isEpisode) e.meta.seriesName ?: e.meta.name else e.meta.name,
            subtitle = if (e.meta.isEpisode) listOfNotNull(e.meta.episodeLabel, e.meta.name).joinToString("  ·  ") else e.meta.year?.toString(),
            art = e.meta.poster?.let { e.file(it) },
            actions = listOfNotNull(
                MenuAction("play", if ((e.meta.unsentMs ?: e.meta.resumeMs) > 0) "Resume" else "Play", FuseIcons.Play, enabled = e.here, onSelect = { app.closeOverlays(); app.playDownloaded(e) }),
                MenuAction("start", "Play from the Start", FuseIcons.RotateCcw, onSelect = { app.closeOverlays(); app.playDownloaded(e, fromStart = true) }).takeIf { e.here && (e.meta.unsentMs ?: e.meta.resumeMs) > 0 },
                MenuAction("page", "Details", FuseIcons.Info, onSelect = { app.closeOverlays(); app.go(Route.MediaPage(e.itemId)) }).takeIf { app.jellyfin?.state?.value?.offline == false },
                MenuAction("move", "Move to Another Drive", FuseIcons.FolderSync, enabled = e.here, onSelect = { app.closeOverlays(); app.moveDownloads(listOf(e)) }),
                MenuAction("several", "Select Several", FuseIcons.ListChecks, onSelect = { app.closeOverlays(); choosing = true; picked.add(e.key) }),
                MenuAction("remove", "Remove Download", FuseIcons.Trash, onSelect = { app.closeOverlays(); app.removeDownloads(listOf(e)) }),
            ),
        ),
    )
    fun activate(e: OfflineEntry) {
        if (choosing) {
            if (e.key in picked) picked.remove(e.key) else picked.add(e.key)
        } else if (e.here) {
            app.playDownloaded(e)
        } else {
            options(e)
        }
    }

    PageEffect(focused, zone, choosing, current?.key, current?.here) {
        if (!focused) return@PageEffect
        app.hints = when {
            zone == 0 -> listOf(Hint(HintButton.CONFIRM, actions.getOrNull(action)?.label ?: "Choose"), Hint(HintButton.BACK, "Back"))
            choosing -> listOf(Hint(HintButton.CONFIRM, if (current?.key in picked) "Untick" else "Tick"), Hint(HintButton.OPTIONS, "Done"), Hint(HintButton.BACK, "Done"))
            else -> listOfNotNull(
                Hint(HintButton.CONFIRM, if (current?.here == true) "Play" else "Options"),
                Hint(HintButton.OPTIONS, "Options"),
                Hint(HintButton.BACK, "Back"),
            )
        }
    }
    InputLayer(enabled = focused) { e ->
        when (zone) {
            0 -> when (e.action) {
                NavAction.LEFT -> if (action > 0) { action--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (action < actions.lastIndex) { action++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (entries.isNotEmpty()) { zone = 1; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(action)?.run?.invoke(); NavResult.ACTIVATED }
                NavAction.BACK -> if (choosing) { done(); NavResult.ACTIVATED } else NavResult.IGNORED
                else -> NavResult.IGNORED
            }
            else -> when (e.action) {
                NavAction.UP -> if (sel.index > 0) { sel.index--; NavResult.MOVED } else { zone = 0; NavResult.MOVED }
                NavAction.DOWN -> if (sel.index < entries.lastIndex) { sel.index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.PAGE_DOWN -> { sel.index = (sel.index + 4).coerceAtMost(entries.lastIndex); NavResult.MOVED }
                NavAction.PAGE_UP -> { sel.index = (sel.index - 4).coerceAtLeast(0); NavResult.MOVED }
                NavAction.LEFT, NavAction.RIGHT -> NavResult.BLOCKED
                NavAction.SELECT -> { current?.let(::activate); NavResult.ACTIVATED }
                NavAction.CONTEXT -> { if (choosing) { zone = 0; action = 0 } else current?.let(::options); NavResult.ACTIVATED }
                NavAction.BACK -> if (choosing) { done(); NavResult.ACTIVATED } else NavResult.IGNORED
                else -> NavResult.IGNORED
            }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(zone, sel.index, entries.size) {
        if (zone == 0) list.animateScrollToItem(0) else list.animateScrollToItem(1 + maxOf(0, sel.index - 1))
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("offline")) {
        val compact = maxHeight < 560.dp
        val rowHeight = if (maxHeight < 420.dp) 60.dp else if (compact) 68.dp else 84.dp
        val wide = maxWidth >= 900.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = Size.hudHeight).padding(horizontal = if (maxWidth < 600.dp) Space.gutterCompact else Space.gutter)
                .fadingEdges(list, top = Space.l, bottom = Size.hintHeight),
            contentPadding = PaddingValues(top = Space.s, bottom = Size.hintHeight + Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            item("head") {
                val bytes = entries.filter { it.here }.sumOf { it.meta.sizeBytes }
                val away = entries.count { !it.here }
                Row(Modifier.padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(if (compact) 36.dp else 44.dp).clip(RoundedCornerShape(Fuse.geometry.control)).background(Fuse.colors.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.Clapperboard, size = Size.iconM, tint = Fuse.colors.accent)
                    }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        FText(if (choosing) "${chosen.size} chosen" else "Downloaded", if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
                        FText(
                            listOfNotNull(
                                if (entries.isEmpty()) "Nothing kept yet" else "${entries.size} kept  ·  ${sizeOf(bytes)}",
                                "$away on a drive that isn't connected".takeIf { away > 0 },
                                folder.trimEnd('/').split('/').takeLast(2).joinToString("/").takeIf { wide },
                            ).joinToString("  ·  "),
                            Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(Space.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        actions.forEachIndexed { i, a -> ActionPill(a, focused && zone == 0 && i == action, wide || (focused && zone == 0 && i == action) || choosing) { action = i; zone = 0; a.run() } }
                    }
                }
            }
            if (entries.isEmpty()) {
                item("empty") {
                    EmptyState(
                        FuseIcons.Download, "Nothing downloaded yet", Modifier.fillMaxWidth().padding(vertical = Space.xl),
                        message = "On a film or episode in Jellyfin, press Options and choose Download. It plays from here without the server.",
                    )
                }
            }
            itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
                val seriesStart = e.meta.isEpisode && entries.getOrNull(i - 1)?.meta?.seriesId != e.meta.seriesId
                if (seriesStart) {
                    FText(e.meta.seriesName ?: "Shows", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1, modifier = Modifier.padding(top = Space.m, bottom = Space.xs))
                }
                OfflineRow(
                    e, focused && zone == 1 && sel.index == i, rowHeight, choosing, e.key in picked,
                    onClick = { sel.index = i; zone = 1; activate(e) },
                    onLongClick = { sel.index = i; zone = 1; options(e) },
                )
            }
        }
    }
}

/** One kept film or episode: its picture, its name, how far in, its size and where it is. */
@Composable
private fun OfflineRow(e: OfflineEntry, selected: Boolean, height: androidx.compose.ui.unit.Dp, choosing: Boolean, picked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val bg by fuselineColor(if (selected) c.text.copy(alpha = 0.12f) else c.surfaceRaised.copy(alpha = 0.55f), Fuse.motion.tween(Durations.FAST), label = "offlineRow")
    val m = e.meta
    val position = m.unsentMs ?: m.resumeMs
    val progress = m.runtimeMs?.takeIf { it > 0 && position > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
    Row(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(Fuse.geometry.control)).background({ bg })
            .then(if (selected) Modifier.border(2.dp, c.focus, RoundedCornerShape(Fuse.geometry.control)) else Modifier)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(end = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The episode's still or the film's backdrop, wide; its poster on a narrow row.
        val pic = (if (m.isEpisode) m.thumb ?: m.backdrop else m.backdrop ?: m.thumb) ?: m.poster
        Box(Modifier.fillMaxHeight().width(height * 1.7f)) {
            Artwork(pic?.let { e.file(it) }, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = {
                Box(Modifier.fillMaxSize().background(c.surfaceOverlay), contentAlignment = Alignment.Center) { FuseIcon(if (m.isEpisode) FuseIcons.Tv else FuseIcons.Film, size = Size.iconM, tint = c.textMuted) }
            })
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(c.text.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(c.accent))
                }
            }
            if (choosing) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(Space.xs).size(22.dp).clip(CircleShape)
                        .background(if (picked) c.accent else c.surfaceOverlay.copy(alpha = 0.85f))
                        .border(1.5.dp, if (picked) c.accent else c.text.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (picked) FuseIcon(FuseIcons.Check, size = 14.dp, tint = c.onAccent) }
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FText(if (m.isEpisode) listOfNotNull(m.episodeLabel, m.name).joinToString("  ") else m.name, Fuse.type.bodyStrong, maxLines = 1, color = if (e.here) c.text else c.textMuted)
            FText(
                listOfNotNull(
                    m.year?.toString()?.takeIf { !m.isEpisode },
                    m.runtimeMs?.let { runLength(it) },
                    when {
                        !e.here -> "Waits for ${e.driveLabel ?: "its drive"}"
                        m.played && (m.unsentMs ?: 0L) == 0L -> "Watched"
                        progress != null -> "${runLength((m.runtimeMs ?: 0) - position)} left"
                        else -> null
                    },
                ).joinToString("  ·  "),
                Fuse.type.caption, color = if (e.here) c.textMuted else c.warning, maxLines = 1,
            )
        }
        Spacer(Modifier.width(Space.s))
        Column(horizontalAlignment = Alignment.End) {
            FText(sizeOf(m.sizeBytes), Fuse.type.caption, color = c.textMuted, maxLines = 1)
            FText(e.driveLabel ?: "This device", Fuse.type.caption, color = c.textFaint, maxLines = 1)
        }
    }
}

private fun runLength(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
