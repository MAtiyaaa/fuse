package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import io.github.matiyaaa.fuse.sync.SaveKind
import io.github.matiyaaa.fuse.sync.SlotReport
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.background
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusDot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.GameReport
import io.github.matiyaaa.fuse.sync.RevisionReason
import io.github.matiyaaa.fuse.sync.VersionReport
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlinx.coroutines.launch

/**
 * One game as the Fuse Sync host keeps it. On top, its cover and title with the figures that matter
 * (play time, sessions, Last Played, what the host keeps). Below, every version of each of its saves
 * (saves, save states, memory cards) on a timeline, newest first: when, on which device, how far in,
 * how big, why it was kept, the one in use marked. A version opens into its files, each with its
 * size and where the host keeps it, and can be kept for good. Play time by device sits beside the
 * timeline on wide screens, and after it on narrower ones.
 */
@Composable
internal fun SyncGameScreen(app: AppState, game: String, name: String) {
    val svc = app.store.sync.service ?: return
    val report = app.syncReport
    val g = report?.games?.firstOrNull { it.game == game }
    val devices by svc.devices.collectAsState()
    val names = devices.associate { it.id to it.name }
    val covers = rememberSyncCovers(app)
    val card = covers.of(game, g?.name ?: name)
    val sel = remember(game) { LinearSelection() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Files"), Hint(HintButton.BACK, "Back"))
    }
    // The timeline: each kind of save under its heading, then its versions newest first.
    val entries = remember(g) {
        buildList {
            g?.slots?.forEach { s ->
                add(TimelineEntry.Heading(s))
                s.versions.forEachIndexed { i, v -> add(TimelineEntry.Version(s, v, last = i == s.versions.lastIndex)) }
            }
        }
    }
    val versions = entries.filterIsInstance<TimelineEntry.Version>()
    sel.clamp(versions.size)
    fun open(i: Int) {
        val v = versions.getOrNull(i) ?: return
        if (g != null && report != null) versionFiles(app, g, v.version, report.storePath)
    }
    // Where every device stands with this game's save, asked of the host now and after each change.
    var convergence by remember(game) { mutableStateOf<io.github.matiyaaa.fuse.sync.Convergence?>(null) }
    val activity by svc.activity.collectAsState()
    LaunchedEffect(game, activity.size) {
        io.github.matiyaaa.fuse.sync.GameKey.parse(game)?.let { k -> convergence = runCatching { svc.convergence(k) }.getOrNull() }
    }
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.UP -> if (sel.index > 0) { sel.index--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> when {
                sel.index < versions.size - 1 -> { sel.index++; NavResult.MOVED }
                list.canScrollForward -> { app.scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }; NavResult.MOVED }
                else -> NavResult.BLOCKED
            }
            NavAction.LEFT, NavAction.RIGHT -> NavResult.BLOCKED
            NavAction.SELECT -> if (versions.isNotEmpty()) { open(sel.index); NavResult.ACTIVATED } else NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }
    // The version chosen stays in view with a little of what is above it.
    LaunchedEffect(sel.index, entries.size) {
        val at = entries.indexOfFirst { it is TimelineEntry.Version && it.version.id == versions.getOrNull(sel.index)?.version?.id }
        if (at >= 0) list.animateScrollToItem((at - 1).coerceAtLeast(0))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 980.dp
        val compact = maxHeight < 560.dp
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val offset = localOffsetMillis(now)
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            GameHeader(g, name, card, compact, now, offset)
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.weight(1f).fillMaxHeight().fadingEdges(list, top = Space.l, bottom = Space.xl),
                    contentPadding = PaddingValues(bottom = Size.hintHeight + Space.m),
                ) {
                    when {
                        g == null -> item("gone") { Quiet(FuseIcons.CloudOff, "This game isn't on the host any more.") }
                        versions.isEmpty() -> item("none") { Quiet(FuseIcons.Save, "No saves kept for it yet. Play it with Fuse Sync on and its save is kept here after it closes.") }
                        else -> {
                            var n = 0
                            entries.forEachIndexed { i, entry ->
                                when (entry) {
                                    is TimelineEntry.Heading -> item("h.${entry.slot.kind.name}") {
                                        SectionTitle(
                                            "${entry.slot.kind.label}s", if (entry.slot.kind == SaveKind.STATE) FuseIcons.Camera else FuseIcons.Save,
                                            trailing = "${count(entry.slot.versions.size, "version")}  ·  ${sizeText(entry.slot.bytes)}",
                                            modifier = Modifier.padding(start = Space.xs, end = Space.xs, top = if (i == 0) 0.dp else Space.l, bottom = Space.s),
                                        )
                                    }
                                    is TimelineEntry.Version -> {
                                        val at = n++
                                        item("v.${entry.version.id}") {
                                            VersionRow(
                                                entry.version, entry.last, now, offset,
                                                selected = focused && sel.index == at, compact = compact,
                                                onClick = { sel.index = at; open(at) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!wide && g != null) {
                        convergence?.takeIf { it.slots.isNotEmpty() }?.let { cv -> item("devices") { DevicesCard(cv, Modifier.padding(top = Space.l)) } }
                        if (g.devicePlay.size > 1) item("play") { Card("Play time by device", FuseIcons.ChartPie, Modifier.padding(top = Space.l)) { PlayBars(g.devicePlay, names) } }
                        if (report != null) item("kept") { KeptCard(g, report.storePath, Modifier.padding(top = Space.s)) }
                    }
                }
                if (wide && g != null) {
                    Column(
                        Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = Size.hintHeight + Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        convergence?.takeIf { it.slots.isNotEmpty() }?.let { DevicesCard(it) }
                        if (g.devicePlay.isNotEmpty()) Card("Play time by device", FuseIcons.ChartPie) { PlayBars(g.devicePlay, names) }
                        if (report != null) KeptCard(g, report.storePath)
                    }
                }
            }
        }
    }
}

/** A row of the timeline: a kind's heading, or one version of it. */
private sealed interface TimelineEntry {
    class Heading(val slot: SlotReport) : TimelineEntry
    class Version(val slot: SlotReport, val version: VersionReport, val last: Boolean) : TimelineEntry
}

/**
 * The game's cover, title and system, and its figures as small tiles (one quiet line on short
 * screens, so the timeline keeps its room).
 */
@Composable
private fun GameHeader(g: GameReport?, name: String, card: io.github.matiyaaa.fuse.ui.shell.store.GameCard?, compact: Boolean, now: Long, offset: Long) {
    val c = Fuse.colors
    val title = card?.title ?: g?.name ?: name
    val system = (card?.platformShort ?: g?.platform?.uppercase())?.takeIf { it.isNotEmpty() }
    val saves = g?.slots?.filter { it.kind != SaveKind.STATE }?.sumOf { it.versions.size } ?: 0
    val states = g?.slots?.filter { it.kind == SaveKind.STATE }?.sumOf { it.versions.size } ?: 0
    val bytes = g?.slots?.sumOf { it.bytes } ?: 0L
    val facts = listOfNotNull(
        g?.playSeconds?.takeIf { it > 0 }?.let { HeroFact(FuseIcons.Clock, playtimeText(it), "played") },
        g?.sessions?.takeIf { it > 0 }?.let { HeroFact(FuseIcons.Gamepad, "$it", if (it == 1) "session" else "sessions") },
        g?.lastPlayed?.let { HeroFact(FuseIcons.CalendarClock, TimeWords.relative(it, now, offset).replaceFirstChar { ch -> ch.uppercase() }, "last played") },
        bytes.takeIf { it > 0 }?.let { HeroFact(FuseIcons.HardDrive, sizeText(it), "on the host") },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            SyncCover(g?.name ?: name, card, if (compact) 64.dp else 104.dp, corner = Fuse.geometry.panel)
        }
        Spacer(Modifier.width(if (compact) Space.m else Space.xl))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FText(title, if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                if (system != null) {
                    Spacer(Modifier.width(Space.m))
                    FText(system, Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.text.copy(alpha = 0.08f)).padding(horizontal = Space.s, vertical = 2.dp))
                }
            }
            FText(
                listOfNotNull(
                    "$saves ${if (saves == 1) "save" else "saves"}".takeIf { saves > 0 },
                    "$states ${if (states == 1) "state" else "states"}".takeIf { states > 0 },
                    "Favourite".takeIf { g?.favorite == true },
                ).plus(
                    if (!compact) emptyList() else listOfNotNull(
                        g?.playSeconds?.takeIf { it > 0 }?.let { "${playtimeText(it)} played" },
                        g?.lastPlayed?.let { "last played ${TimeWords.relative(it, now, offset)}" },
                        bytes.takeIf { it > 0 }?.let { sizeText(it) },
                    ),
                ).joinToString("  ·  ").ifEmpty { "On the host" },
                Fuse.type.body, color = c.textMuted, maxLines = 1,
            )
            if (!compact && facts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.xs)) {
                    for (f in facts) FactTile(f)
                }
            }
        }
    }
}

/** One of the game's figures: an icon, the value, and what it counts, on a soft tile. */
@Composable
private fun FactTile(f: HeroFact) {
    val c = Fuse.colors
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(c.text.copy(alpha = 0.05f)).border(1.dp, c.hairline, RoundedCornerShape(12.dp))
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(f.icon, size = Size.iconS, tint = c.accent)
        Spacer(Modifier.width(Space.s))
        Column {
            FText(f.value, Fuse.type.label, maxLines = 1)
            FText(f.label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/**
 * One version on the timeline: a dot on the line (lit for the one in use, a bookmark for one kept
 * for good), when and on which device, how far in and why it was kept, and its size.
 */
@Composable
private fun VersionRow(v: VersionReport, last: Boolean, now: Long, offset: Long, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    val kept = v.kept || v.reason == RevisionReason.MILESTONE
    val bg by fuselineColor(if (selected) c.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent, Fuse.motion.tween(Durations.FAST), label = "version")
    val line = c.hairline
    Row(
        Modifier.fillMaxWidth().height(if (compact) 56.dp else 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The line through every version of this kind, with this one's dot on it.
        Box(Modifier.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                drawLine(line, Offset(x, 0f), Offset(x, if (last) size.height / 2f else size.height), strokeWidth = 2.dp.toPx())
            }
            when {
                v.current -> Box(Modifier.size(14.dp).clip(CircleShape).background(c.accent).border(3.dp, c.accent.copy(alpha = 0.3f), CircleShape))
                kept -> Box(Modifier.size(20.dp).clip(CircleShape).background(c.ink).border(1.dp, c.hairline, CircleShape), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.Bookmark, size = 11.dp, tint = c.text)
                }
                else -> Box(Modifier.size(10.dp).clip(CircleShape).background(c.ink).border(2.dp, c.textFaint, CircleShape))
            }
        }
        Spacer(Modifier.width(Space.s))
        Row(
            Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp).clip(shape).background({ bg })
                .then(if (selected) Modifier.border(Size.focusStroke, c.focus, shape) else Modifier)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FText(TimeWords.relative(v.at, now, offset).replaceFirstChar { it.uppercase() }, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Space.s))
                    Pill(v.device, FuseIcons.MonitorSmartphone)
                }
                FText(
                    listOfNotNull(
                        v.playSeconds.takeIf { it > 0 }?.let { "${playtimeText(it)} in" },
                        count(v.files.size, "file"),
                        reasonText(v),
                    ).joinToString("  ·  "),
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.m))
            if (v.current) {
                FText("In use", Fuse.type.caption, color = c.accent, maxLines = 1, modifier = Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.14f)).padding(horizontal = Space.s + 2.dp, vertical = 3.dp))
                Spacer(Modifier.width(Space.m))
            }
            FText(sizeText(v.bytes), Fuse.type.label, color = c.textMuted, maxLines = 1)
        }
    }
}

/** What the host keeps of the game: each kind's versions and size, and where its files live. */
@Composable
private fun KeptCard(g: GameReport, storePath: String, modifier: Modifier = Modifier) {
    Card("Kept on the host", FuseIcons.HardDrive, modifier) {
        for (s in g.slots) {
            Row {
                FText("${s.kind.label}s", Fuse.type.label, maxLines = 1, modifier = Modifier.weight(1f))
                FText("${count(s.versions.size, "version")}  ·  ${sizeText(s.bytes)}", Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
            }
        }
        FText("Each file is kept once, by its contents, in $storePath", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 4)
    }
}

private fun reasonText(v: VersionReport): String? = when (v.reason) {
    RevisionReason.PLAYED -> "kept for good".takeIf { v.kept }
    RevisionReason.CONFLICT_COPY -> "the other side of a conflict" + if (v.kept) ", kept for good" else ""
    RevisionReason.BEFORE_RESTORE -> "kept before a restore" + if (v.kept) ", kept for good" else ""
    RevisionReason.MILESTONE -> "kept for good"
}

/** A version's files: each one's name in the save, its size, and where the host keeps it. */
private fun versionFiles(app: AppState, g: GameReport, v: VersionReport, storePath: String) {
    val svc = app.store.sync.service ?: return
    val kept = v.kept || v.reason == RevisionReason.MILESTONE
    app.choice = ChoiceSpec(
        title = "${g.name}, saved on ${v.device}",
        icon = FuseIcons.FileText,
        message = "Kept on the host in $storePath, each file once by its contents",
        options = v.files.map { f ->
            MenuAction("f.${f.path}", f.path, FuseIcons.File, detail = "Kept as ${f.stored}", trailing = Trailing.Value(sizeText(f.bytes)), onSelect = {})
        } + MenuAction(
            "keep", if (kept) "Stop Keeping for Good" else "Keep for Good", FuseIcons.Bookmark,
            detail = if (kept) "It goes back to being tidied away in time" else "Never tidied away, however many come after",
            section = "",
            onSelect = {
                app.choice = null
                app.scope.launch {
                    svc.keepVersion(v.id, !kept)
                        .onSuccess { app.toasts.show(if (kept) "Back to being tidied in time" else "Kept for good", ToastKind.SUCCESS); app.syncReport = svc.report() ?: app.syncReport }
                        .onFailure { app.toasts.show(it.message ?: "Couldn't change that", ToastKind.ERROR) }
                }
            },
        ),
    )
}

/**
 * Where every device that plays this game stands with its save: one line for all ("Synced
 * everywhere", "4 of 5 devices current"), then each device with a dot and a word. Devices that never
 * reported the game (they don't have it) aren't counted.
 */
@Composable
private fun DevicesCard(cv: io.github.matiyaaa.fuse.sync.Convergence, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val slot = cv.slots.firstOrNull { it.kind != SaveKind.STATE } ?: cv.slots.first()
    val playing = slot.playing
    Card("Devices", FuseIcons.MonitorSmartphone, modifier) {
        FText(
            when {
                playing.isEmpty() -> "No device has reported this game yet"
                slot.current == playing.size -> if (playing.size == 1) "On one device" else "Synced everywhere"
                else -> "${slot.current} of ${playing.size} devices current"
            },
            Fuse.type.bodyStrong, maxLines = 1,
        )
        for (d in playing.sortedWith(compareBy({ it.state != io.github.matiyaaa.fuse.sync.AppliedState.CURRENT }, { it.name.lowercase() }))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when (d.state) {
                        io.github.matiyaaa.fuse.sync.AppliedState.CURRENT -> true
                        io.github.matiyaaa.fuse.sync.AppliedState.CONFLICT -> false
                        else -> null
                    },
                )
                Spacer(Modifier.width(Space.s))
                FText(d.name, Fuse.type.body, maxLines = 1, modifier = Modifier.weight(1f))
                FText(
                    when (d.state) {
                        io.github.matiyaaa.fuse.sync.AppliedState.CURRENT -> if (slot.headDevice == d.name) "Newest from here" else "Current"
                        io.github.matiyaaa.fuse.sync.AppliedState.BEHIND -> "Gets it next"
                        io.github.matiyaaa.fuse.sync.AppliedState.CONFLICT -> "Both played: asks at launch"
                        io.github.matiyaaa.fuse.sync.AppliedState.INCOMPATIBLE -> "Another emulator's save"
                        io.github.matiyaaa.fuse.sync.AppliedState.UNAVAILABLE -> "Save folder not reachable"
                        null -> ""
                    },
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
        }
    }
}
