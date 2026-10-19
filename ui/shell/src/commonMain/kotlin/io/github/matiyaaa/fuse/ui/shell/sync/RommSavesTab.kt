package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.romm.RommSave
import io.github.matiyaaa.fuse.sync.romm.RommSaveStanding
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragVerticalScroll
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.subTabsRoom
import io.github.matiyaaa.fuse.ui.shell.romm.RommMark
import io.github.matiyaaa.fuse.ui.shell.store.RommSaveGame
import io.github.matiyaaa.fuse.ui.shell.store.RommSaveReport
import kotlinx.coroutines.launch

/**
 * Addons, RomM Saves: RomM's save sync the way Addons, Sync shows Fuse One's. Where things stand
 * with Look Again and Settings; how many games have saves on RomM, how much room they take and when
 * the newest came; every game with its newest save, the device it came from and how this device's
 * save stands beside it. RomM keeps saves only: profiles, play time and settings are Fuse One's.
 */
@Composable
internal fun RommSavesTab(app: AppState, active: Boolean, topPadding: Dp) {
    val rommState by app.store.romm.state.collectAsState()
    var report by remember { mutableStateOf<RommSaveReport?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var asked by remember { mutableIntStateOf(0) }
    LaunchedEffect(asked, rommState.link) {
        loading = true
        runCatching { app.store.romm.saveReport() }
            .onSuccess { report = it; failed = null }
            .onFailure { failed = it.message ?: "RomM didn't answer" }
        loading = false
    }
    var index by remember { mutableIntStateOf(0) }
    var inList by remember { mutableStateOf(false) }
    var inSide by remember { mutableStateOf(false) }
    val sideScroll = rememberScrollState()
    val step = with(androidx.compose.ui.platform.LocalDensity.current) { 160.dp.toPx() }
    val sel = remember { LinearSelection() }
    val focused = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    val reveal = rememberReveal()
    val games = report?.games.orEmpty()
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()

    val waitingHere = report?.waiting ?: 0
    val actions = listOfNotNull<Triple<String, ImageVector, () -> Unit>>(
        Triple("Look Again", FuseIcons.RefreshCcw) { asked++ },
        // Saves that couldn't reach RomM: they go by themselves once it answers, or now.
        if (waitingHere > 0) Triple("Send Them Now", FuseIcons.CloudUpload) {
            app.scope.launch {
                val sent = app.store.romm.sendWaitingSaves()
                app.toasts.show(
                    when {
                        sent == waitingHere -> if (sent == 1) "The save went to RomM" else "All $sent saves went to RomM"
                        sent > 0 -> "$sent saves went to RomM. The rest go once RomM takes them"
                        else -> "RomM didn't take them yet. They go by themselves once it does"
                    },
                    if (sent > 0) ToastKind.SUCCESS else ToastKind.WARNING,
                )
                asked++
            }
        } else null,
        Triple("RomM Settings", FuseIcons.Settings) { app.go(Route.RommSettings) },
    )
    val rows = games.map { g -> gameRow(app, g, now) }
    sel.clamp(rows.size)
    PageEffect(focused, inList, inSide) {
        if (focused) app.hints = if (inSide) listOf(Hint(HintButton.DPAD, "Scroll"), Hint(HintButton.BACK, "Back"))
        else listOf(Hint(HintButton.CONFIRM, if (inList) "Its saves" else "Choose"), Hint(HintButton.BACK, "Back"))
    }
    InputLayer(enabled = focused) { e ->
        if (inSide) {
            when (e.action) {
                NavAction.UP -> if (sideScroll.value > 0) { app.scope.launch { sideScroll.animateScrollBy(-step) }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (sideScroll.value < sideScroll.maxValue) { app.scope.launch { sideScroll.animateScrollBy(step) }; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.LEFT, NavAction.BACK -> { inSide = false; NavResult.MOVED }
                NavAction.RIGHT, NavAction.SELECT -> NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        } else if (inList) {
            when {
                e.action == NavAction.UP && sel.index == 0 -> { inList = false; NavResult.MOVED }
                e.action == NavAction.RIGHT && sideScroll.maxValue > 0 -> { inSide = true; NavResult.MOVED }
                e.action == NavAction.LEFT || e.action == NavAction.RIGHT -> NavResult.BLOCKED
                else -> handleMenuAction(e, rows, sel)
            }
        } else {
            when (e.action) {
                NavAction.LEFT -> if (index > 0) { index--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (index < actions.size - 1) { index++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> { actions.getOrNull(index)?.third?.invoke(); NavResult.ACTIVATED }
                NavAction.DOWN -> if (rows.isNotEmpty()) { inList = true; NavResult.MOVED } else NavResult.BLOCKED
                else -> NavResult.IGNORED
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxWidth >= 820.dp
        val roomy = maxWidth >= 1200.dp
        val compact = maxHeight < 560.dp
        Column(
            Modifier.fillMaxSize().padding(horizontal = Space.gutter)
                .padding(top = topPadding + subTabsRoom() + Space.m, bottom = Size.hintHeight + Space.s),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.s else Space.m),
        ) {
            val newest = games.mapNotNull { rommTime(it.newest.updatedAt) }.maxOrNull()
            val waiting = games.count { it.standing == RommSaveStanding.NEWER_ON_ROMM || it.standing == RommSaveStanding.BOTH_CHANGED }
            AddonHero(
                mark = { RommMark(it) },
                title = "RomM Saves",
                tag = "Saves only",
                tagIcon = FuseIcons.Save,
                tint = Fuse.colors.accent,
                ok = when {
                    failed != null -> false
                    report == null && !loading -> null
                    // Saves waiting aren't lost, nor sent yet.
                    waitingHere > 0 -> null
                    else -> true
                },
                status = when {
                    failed != null -> "RomM isn't answering"
                    report == null && loading -> "Looking at RomM"
                    report == null -> "RomM's save sync is off here"
                    waitingHere > 0 -> if (waitingHere == 1) "1 save waits to go to RomM" else "$waitingHere saves wait to go to RomM"
                    waiting > 0 -> if (waiting == 1) "1 game has a newer save on RomM" else "$waiting games have newer saves on RomM"
                    else -> "In step with RomM"
                },
                detail = report?.server?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/').orEmpty(),
                facts = report?.let { r ->
                    listOfNotNull(
                        HeroFact(FuseIcons.Gamepad, "${r.games.size}", if (r.games.size == 1) "game with saves" else "games with saves"),
                        HeroFact(FuseIcons.Save, "${r.games.sumOf { it.saves.size }}", "saves kept"),
                        HeroFact(FuseIcons.HardDrive, sizeText(r.bytes), "on RomM"),
                        newest?.let { HeroFact(FuseIcons.History, agoText(it, now), "newest") },
                    )
                }.orEmpty(),
                compact = compact,
                modifier = Modifier.reveal(reveal, 0),
            )
            Row(Modifier.reveal(reveal, 1), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                actions.forEachIndexed { i, (label, icon, run) ->
                    FuseButton(
                        label, selected = focused && !inList && index == i, onClick = { index = i; inList = false; run() }, icon = icon,
                        kind = if (i == 0) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                        loading = i == 0 && loading,
                        height = if (compact) 40.dp else Size.touch,
                    )
                }
            }
            Row(Modifier.weight(1f).reveal(reveal, 2), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Column {
                        SectionTitle(
                            "Your saves on RomM", FuseIcons.Gamepad,
                            trailing = games.takeIf { it.isNotEmpty() }?.let { count(it.size, "game") },
                            modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.m, bottom = Space.xs),
                        )
                        when {
                            report == null && loading -> Box(Modifier.padding(Space.xxl)) { Spinner(size = 24.dp, color = Fuse.colors.textMuted) }
                            failed != null -> Quiet(FuseIcons.CloudOff, "RomM didn't answer. Your saves show here once it does.")
                            report == null -> Quiet(FuseIcons.CloudOff, "Choose RomM in Settings, Save Sync, and sign in to RomM, and the saves it keeps show here.")
                            games.isEmpty() -> Quiet(FuseIcons.Save, "No saves on RomM yet. Play a game from RomM, and its save goes up when you close it.")
                            else -> MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = focused && inList)
                        }
                    }
                }
                if (side) {
                    Box(Modifier.width(if (roomy) 380.dp else if (compact) 290.dp else 320.dp).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize().dragVerticalScroll(sideScroll), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Card("How it works", FuseIcons.Info) {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                                    for ((icon, text) in listOf(
                                        FuseIcons.Download to "Before a game from RomM starts, RomM's newer save is put in place.",
                                        FuseIcons.Upload to "After it closes, its save goes up as a new save; earlier ones are kept.",
                                        FuseIcons.ShieldCheck to "When both changed, you choose, and the other is kept aside. A blank save never goes up.",
                                        FuseIcons.Users to "RomM keeps saves only. Profiles, play time and settings are Fuse One's.",
                                    )) Row { Quietly(icon, text) }
                                }
                            }
                        }
                        if (focused && inSide) {
                            Spacer(Modifier.matchParentSize().border(Size.focusStroke, Fuse.colors.focus, RoundedCornerShape(Fuse.geometry.panel)))
                        }
                    }
                }
            }
        }
    }
}

/** One line of the side card: a small icon and what it says. */
@Composable
private fun Quietly(icon: ImageVector, text: String) {
    io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon(icon, size = 16.dp, tint = Fuse.colors.textMuted)
    Spacer(Modifier.width(Space.s))
    FText(text, Fuse.type.caption, color = Fuse.colors.textMuted)
}

/** A game as its row reads: its name, the newest save on RomM and where it came from, and how this device's stands. */
private fun gameRow(app: AppState, g: RommSaveGame, now: Long): MenuAction {
    val newest = g.newest
    val from = newest.originDeviceName ?: "another device"
    val detail = listOfNotNull(
        "Newest from $from" + (rommTime(newest.updatedAt)?.let { ", ${agoText(it, now)}" } ?: ""),
        "${g.saves.size} ${if (g.saves.size == 1) "save" else "saves"}, ${sizeText(g.saves.sumOf { it.sizeBytes })}",
    ).joinToString("  ·  ")
    val (icon, word) = standingLook(g.standing)
    return MenuAction("romm.${g.romId}", g.title, icon, detail = detail, trailing = Trailing.Value(word), onSelect = { savesOf(app, g, now) })
}

/** How a game's save here stands, as an icon and a word. */
private fun standingLook(s: RommSaveStanding): Pair<ImageVector, String> = when (s) {
    RommSaveStanding.IN_STEP -> FuseIcons.CloudCheck to "In step"
    RommSaveStanding.NEWER_HERE -> FuseIcons.Upload to "Newer here"
    RommSaveStanding.NEWER_ON_ROMM -> FuseIcons.Download to "Newer on RomM"
    RommSaveStanding.BOTH_CHANGED -> FuseIcons.GitCompare to "You choose"
    RommSaveStanding.NOT_HERE -> FuseIcons.CloudDownload to "Not here"
    RommSaveStanding.UNKNOWN -> FuseIcons.Save to "On RomM"
}

/** Every save of [g] on RomM, newest first, and the game itself when it is in the library. */
private fun savesOf(app: AppState, g: RommSaveGame, now: Long) {
    app.choice = ChoiceSpec(
        title = g.title,
        icon = FuseIcons.Save,
        message = standingWords(g.standing),
        options = g.saves.mapIndexed { i, s -> saveRow(s, i == 0, now) } + listOfNotNull(
            g.game?.let { id -> MenuAction("open", "Open the Game", FuseIcons.Gamepad, trailing = Trailing.Chevron, onSelect = { app.choice = null; app.go(Route.GameInfo(id)) }) },
        ),
    )
}

private fun saveRow(s: RommSave, newest: Boolean, now: Long): MenuAction = MenuAction(
    "save.${s.id}", "From ${s.originDeviceName ?: "another device"}", if (newest) FuseIcons.CloudCheck else FuseIcons.History,
    detail = listOfNotNull(s.fileName, sizeText(s.sizeBytes), "the newest".takeIf { newest }).joinToString(", "),
    trailing = rommTime(s.updatedAt)?.let { Trailing.Value(agoText(it, now)) } ?: Trailing.None,
    onSelect = {},
)

private fun standingWords(s: RommSaveStanding): String = when (s) {
    RommSaveStanding.IN_STEP -> "This device has the same save as RomM's newest."
    RommSaveStanding.NEWER_HERE -> "This device's save changed since it matched RomM's. It goes up after the game next closes."
    RommSaveStanding.NEWER_ON_ROMM -> "RomM has a newer save. It comes down before the game next starts here."
    RommSaveStanding.BOTH_CHANGED -> "Both this device's save and RomM's changed. You choose when the game next starts; the other is kept."
    RommSaveStanding.NOT_HERE -> "This device hasn't got the game, or a save of it, yet. RomM's comes down when it plays it."
    RommSaveStanding.UNKNOWN -> "Its saves on RomM, newest first."
}

private fun rommTime(text: String): Long? = runCatching { kotlin.time.Instant.parse(text).toEpochMilliseconds() }.getOrNull()
