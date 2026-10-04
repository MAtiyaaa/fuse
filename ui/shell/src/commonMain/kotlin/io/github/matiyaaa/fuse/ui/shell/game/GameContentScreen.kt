package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.content.ContentState
import io.github.matiyaaa.fuse.library.content.ItemRole
import io.github.matiyaaa.fuse.library.content.ItemStatus
import io.github.matiyaaa.fuse.library.content.LicenceSource
import io.github.matiyaaa.fuse.library.content.Licences
import io.github.matiyaaa.fuse.library.content.PlanItem
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuList
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SkeletonRow
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.components.handleMenuAction
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Crossfade
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FilePurpose
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.LicencePick
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.GameContentView
import io.github.matiyaaa.fuse.ui.shell.store.InstallMode
import io.github.matiyaaa.fuse.ui.shell.store.InstallProgress
import io.github.matiyaaa.fuse.ui.shell.store.InstallReport
import kotlinx.coroutines.launch

/**
 * A PS3 or Vita game's content, and installing it: what is on disk (the game's package, its
 * updates, DLC and licences, on any drive), what the emulator already holds, and what is left, in
 * the order it goes in. Missing licences show before anything is attempted, each a button away
 * from being picked. While an install runs the steps count up beside the list; nothing is called
 * installed until Fuse has read it back from the emulator's storage.
 *
 * On Android the emulators only install from their own screens, so the same list is the guide:
 * the files, in order, and the emulator to open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameContentScreen(app: AppState, id: GameId) {
    val content = app.store.content
    val prefix = "${id.value}|"
    val picks = app.contentPicks.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    val keys = app.contentKeys.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    var view by remember(id) { mutableStateOf<GameContentView?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    var report by remember(id) { mutableStateOf<InstallReport?>(null) }
    val progress by content.progress.collectAsState()
    val running = progress?.gameId == id
    val sel = remember(id) { LinearSelection() }
    val reveal = rememberReveal(id)

    LaunchedEffect(id, refresh, picks, keys, running) {
        if (!running) {
            view = content.view(id, picks, keys)
            loaded = true
        }
    }
    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, "Select"), Hint(HintButton.BACK, "Back"))
    }

    val v = view
    val emulator = v?.emulatorName ?: "the emulator"

    fun install() {
        report = null
        app.scope.launch {
            val r = content.install(id, picks, keys)
            report = r
            refresh++
            when {
                r.cancelled -> app.toasts.show("Install stopped. Run it again to carry on where it left off")
                r.ok && r.installed.isNotEmpty() -> app.toasts.show("Installed ${countText(r.installed.size)} into $emulator", ToastKind.SUCCESS)
                !r.ok -> app.toasts.show(r.message ?: "The install didn't finish", ToastKind.ERROR)
            }
        }
    }

    fun chooseLicence(item: PlanItem) {
        val cid = item.contentId ?: return
        val vita = v?.plan?.emulator == io.github.matiyaaa.fuse.library.content.ContentEmulator.VITA3K
        val pick = { app.choice = null; app.go(Route.PickFile(FilePurpose.LICENCE, licence = LicencePick(id, cid, vita))) }
        if (!vita) {
            pick()
            return
        }
        app.choice = ChoiceSpec(
            icon = FuseIcons.Key,
            title = "Licence for ${item.fileName}",
            message = "Vita3K needs the zRIF for $cid. Pick the file it came in (a .rif, work.bin, or a text file with the key), or paste it. Fuse hands it only to Vita3K and keeps no copy.",
            options = listOf(
                MenuAction("file", "Choose a file", FuseIcons.FileSearch, detail = ".rif, work.bin, .zrif, .txt or .tsv", trailing = Trailing.Chevron, onSelect = pick),
                MenuAction("paste", "Paste the zRIF", FuseIcons.ClipboardPaste, detail = "It starts with KO5i", onSelect = {
                    app.choice = null
                    app.textInput = TextInputSpec("zRIF for ${item.fileName}", "", placeholder = "KO5i...", secret = true, capitalize = false, doneLabel = "Use it") { text ->
                        val key = text.trim()
                        val forId = Licences.zrifContentId(key)
                        when {
                            key.isEmpty() -> Unit
                            forId == null -> app.toasts.show("That isn't a zRIF. It starts with KO5i and is about 160 letters long", ToastKind.ERROR)
                            !forId.equals(cid, ignoreCase = true) && item.role != ItemRole.UPDATE -> app.toasts.show("That zRIF is for $forId, not this content", ToastKind.ERROR)
                            else -> {
                                app.contentKeys["$prefix${cid.uppercase()}"] = key
                                app.toasts.show("zRIF added. It stays in memory until Fuse closes", ToastKind.SUCCESS)
                            }
                        }
                    }
                }),
            ),
        )
    }

    fun details(item: PlanItem) {
        app.choice = ChoiceSpec(
            icon = roleIcon(item.role),
            title = item.fileName,
            message = listOfNotNull(
                statusSentence(item, emulator),
                item.contentId?.let { "Content id $it" },
                item.path,
            ).joinToString("\n\n"),
            options = listOf(MenuAction("close", "Close", FuseIcons.Check, onSelect = { app.choice = null })),
        )
    }

    val rows = buildList {
        if (v != null) {
            val todo = v.plan.toInstall
            if (running) {
                add(MenuAction("stop", "Stop installing", FuseIcons.CircleX, detail = "What is already in stays; the next run carries on", onSelect = { content.cancel() }))
            } else if (v.mode == InstallMode.FUSE && todo.isNotEmpty() && v.plan.storageReadable) {
                add(MenuAction("install", "Install ${countText(todo.size)}", FuseIcons.Download, detail = orderText(todo), trailing = Trailing.Value(bytesText(todo.sumOf { it.sizeBytes })), onSelect = ::install))
            }
            if (v.mode == InstallMode.GUIDED && v.emulatorId != null) {
                add(MenuAction("open", "Open $emulator", FuseIcons.External, detail = "Install the files below from its menu, top to bottom", onSelect = {
                    app.scope.launch { app.store.emulators.openEmulator(v.emulatorId) }
                }))
            }
            if (!running) add(MenuAction("check", "Check again", FuseIcons.Refresh, detail = "Reads the files and $emulator's storage again", onSelect = { refresh++ }))
            for (item in v.plan.missingLicences) {
                add(MenuAction("lic.${item.path}", "Licence for ${item.fileName}", FuseIcons.Key, detail = item.why, trailing = Trailing.Chevron, section = "Missing licences", onSelect = { chooseLicence(item) }))
            }
            for (role in listOf(ItemRole.LICENCE, ItemRole.GAME, ItemRole.UPDATE, ItemRole.DLC)) {
                val items = v.plan.items.filter { it.role == role }
                for (item in items) {
                    val step = progress?.takeIf { running && it.item.path == item.path }
                    add(
                        MenuAction(
                            "item.${item.path}", item.fileName, statusIcon(item),
                            detail = itemDetail(item, v),
                            trailing = when {
                                step != null -> Trailing.Progress(null, "Installing")
                                // In the emulator: a calm check, not a badge asking for attention.
                                item.status == ItemStatus.INSTALLED -> Trailing.Check(true)
                                else -> Trailing.Badge(statusText(item))
                            },
                            section = roleSection(role, items.size),
                            onSelect = { if (item.status == ItemStatus.NEEDS_LICENCE) chooseLicence(item) else details(item) },
                        ),
                    )
                }
            }
        }
    }
    sel.clamp(rows.size)
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e -> handleMenuAction(e, rows, sel) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 900.dp
        val compact = maxHeight < Size.touch * 12
        Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.xl))
            Column(Modifier.reveal(reveal, 0), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FText(v?.title ?: " ", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.PackageOpen, size = Size.iconS, tint = Fuse.colors.textMuted)
                    Spacer(Modifier.width(Space.s))
                    FText("Installed content", Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1)
                    if (v != null) {
                        FText("  ·  ", Fuse.type.body, color = Fuse.colors.textFaint, maxLines = 1)
                        FText("${v.platformName} with $emulator", Fuse.type.body, color = Fuse.colors.textFaint, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(if (compact) Space.m else Space.l))
            val bottom = Modifier.padding(bottom = Size.hintHeight + Space.s)
            when {
                !loaded -> Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f).then(bottom)) {
                    Column(Modifier.padding(Space.s)) { repeat(5) { SkeletonRow(detail = it % 2 == 1) } }
                }
                v == null -> Box(Modifier.fillMaxWidth().weight(1f).then(bottom), contentAlignment = Alignment.Center) {
                    EmptyState(
                        FuseIcons.PackageOpen,
                        "Nothing to install",
                        message = "None of this game's files is a package, an update, DLC or a licence. Disc images and game folders play as they are.",
                        compact = compact,
                    )
                }
                wide -> Row(Modifier.weight(1f).then(bottom), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    StatusCard(v, progress.takeIf { running }, report, Modifier.width(Size.touch * 8).reveal(reveal, 1))
                    Panel(Modifier.widthIn(max = Size.touch * 18).weight(1f).fillMaxHeight().reveal(reveal, 2)) {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                    }
                }
                else -> Column(Modifier.weight(1f).then(bottom), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    StatusCard(v, progress.takeIf { running }, report, Modifier.fillMaxWidth().reveal(reveal, 1), compact = true)
                    Panel(Modifier.fillMaxWidth().weight(1f).reveal(reveal, 2)) {
                        MenuList(rows, sel, modifier = Modifier.padding(Space.s), showSelection = app.focusZone == FocusZone.CONTENT)
                    }
                }
            }
        }
    }
}

/**
 * Where the game stands, at a glance: one line that says it, the states as chips, and while an
 * install runs, which step it is on with what the installer last said. After a run that didn't
 * take, why, with the end of the installer's own output.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(v: GameContentView, progress: InstallProgress?, report: InstallReport?, modifier: Modifier, compact: Boolean = false) {
    val c = Fuse.colors
    val plan = v.plan
    val failed = report?.takeIf { !it.ok && !it.cancelled }
    val (icon, tint, headline) = when {
        progress != null -> Triple(FuseIcons.Download, c.accent, "Installing ${progress.step} of ${progress.of}")
        failed != null -> Triple(FuseIcons.Warning, c.warning, failed.failed?.let { "Stopped at ${it.fileName}" } ?: "Not installed")
        // The game's own licence missing stops everything; a DLC's only stops that DLC.
        plan.missingLicences.any { it.role == ItemRole.GAME } -> Triple(FuseIcons.Key, c.warning, "Licence needed")
        plan.toInstall.isNotEmpty() && ContentState.NEEDS_INSTALL in plan.states -> Triple(FuseIcons.Download, c.accent, "Needs installation")
        ContentState.READY in plan.states && plan.toInstall.isEmpty() -> Triple(FuseIcons.CircleCheck, c.success, "Ready to play")
        plan.toInstall.isNotEmpty() -> Triple(FuseIcons.PackagePlus, c.accent, "${countText(plan.toInstall.size)} to add")
        ContentState.MISSING_LICENCE in plan.states -> Triple(FuseIcons.Key, c.warning, "Licence needed")
        else -> Triple(FuseIcons.Info, c.textMuted, "Nothing to install yet")
    }
    Panel(modifier) {
        Column(Modifier.padding(if (compact) Space.l else Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(if (compact) 40.dp else 56.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(Fuse.geometry.control)),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(icon, animationSpec = Fuse.motion.fade(), label = "contentIcon") { FuseIcon(it, size = if (compact) 22.dp else 28.dp, tint = tint) }
                }
                if (compact) {
                    Spacer(Modifier.width(Space.m))
                    FText(headline, Fuse.type.titleSmall, maxLines = 1)
                }
            }
            if (!compact) FText(headline, Fuse.type.titleSmall, maxLines = 2)
            if (plan.states.isNotEmpty() && progress == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    plan.states.forEach { s -> Chip(s.label, icon = stateIcon(s), color = stateColor(s)) }
                }
            }
            Appear(progress != null) {
                val p = progress ?: return@Appear
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    FText(p.item.fileName, Fuse.type.label, maxLines = 2)
                    ProgressBar((p.step - 1).toFloat() / p.of.coerceAtLeast(1) + 0.5f / p.of.coerceAtLeast(1), Modifier.fillMaxWidth())
                    p.line?.takeIf { it.isNotBlank() }?.let { FText(it, Fuse.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.textFaint, maxLines = 2) }
                }
            }
            val message = failed?.message ?: v.note
            if (progress == null && message != null) {
                FText(message, Fuse.type.body, color = c.textMuted, maxLines = if (compact) 3 else 6)
            } else if (progress == null && !compact) {
                FText(explain(v), Fuse.type.body, color = c.textMuted, maxLines = 6)
            }
            failed?.details?.takeIf { !compact && it.isNotBlank() }?.let { out ->
                Box(Modifier.fillMaxWidth().background(c.text.copy(alpha = 0.05f), RoundedCornerShape(Fuse.geometry.control)).padding(Space.m)) {
                    FText(out, Fuse.type.caption.copy(fontFamily = FontFamily.Monospace), color = c.textMuted, maxLines = 8)
                }
            }
            if (v.unreadable.isNotEmpty() && progress == null && !compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(FuseIcons.FileWarning, size = Size.iconS, tint = c.warning)
                    Spacer(Modifier.width(Space.s))
                    FText("${countText(v.unreadable.size, "file")} couldn't be read (an unfinished download?)", Fuse.type.caption, color = c.textMuted, maxLines = 2)
                }
            }
        }
    }
}

/** What the plan means, in a sentence or two. */
private fun explain(v: GameContentView): String {
    val plan = v.plan
    val emulator = v.emulatorName ?: "the emulator"
    return when {
        plan.toInstall.isEmpty() && plan.missingLicences.isEmpty() -> "Everything here is in $emulator. Play starts the installed game by its title id."
        v.mode == InstallMode.GUIDED -> "Install these in $emulator, top to bottom: licences, the game, its updates oldest first, then DLC."
        else -> "Fuse installs licences first, then the game, its updates oldest first, then DLC, each with $emulator's own installer, and checks every step took."
    }
}

private fun countText(n: Int, word: String = "item") = if (n == 1) "1 $word" else "$n ${word}s"

private fun orderText(items: List<PlanItem>): String =
    listOf(ItemRole.LICENCE to "licences", ItemRole.GAME to "game", ItemRole.UPDATE to "updates", ItemRole.DLC to "DLC")
        .filter { (r, _) -> items.any { it.role == r } }.joinToString(", then ") { it.second }.replaceFirstChar { it.uppercase() }

private fun roleSection(role: ItemRole, n: Int): String = when (role) {
    ItemRole.LICENCE -> if (n == 1) "Licence" else "Licences"
    ItemRole.GAME -> "Game"
    ItemRole.UPDATE -> if (n == 1) "Update" else "Updates, oldest first"
    ItemRole.DLC -> "DLC"
}

private fun itemDetail(item: PlanItem, v: GameContentView): String = listOfNotNull(
    item.version?.let { "Version $it" },
    item.sizeBytes.takeIf { it > 0 && item.role != ItemRole.LICENCE }?.let(::bytesText),
    v.placeOf(item.path),
    when (item.licence?.source) {
        LicenceSource.INSTALLED -> "Licence in ${v.emulatorName}"
        LicenceSource.RENAMED -> "Goes in as ${item.installAs ?: "its content id"}"
        LicenceSource.ZRIF, LicenceSource.RIF -> "zRIF found"
        LicenceSource.PICKED -> "Licence chosen"
        else -> null
    },
    item.why,
).joinToString("  ·  ")

private fun statusText(item: PlanItem): String = when (item.status) {
    ItemStatus.INSTALLED -> "Installed"
    ItemStatus.READY -> "To install"
    ItemStatus.NEEDS_LICENCE -> "Needs licence"
    ItemStatus.SUPERSEDED -> "Duplicate"
    ItemStatus.UNSUPPORTED -> "Not supported"
}

private fun statusSentence(item: PlanItem, emulator: String): String = when (item.status) {
    ItemStatus.INSTALLED -> "In $emulator already."
    ItemStatus.READY -> "Goes in with the next install."
    ItemStatus.NEEDS_LICENCE -> "Waiting for its licence. ${item.why.orEmpty()}"
    ItemStatus.SUPERSEDED -> "The same content as another file here; installed once."
    ItemStatus.UNSUPPORTED -> item.why ?: "$emulator doesn't install this kind of package."
}

private fun statusIcon(item: PlanItem): ImageVector = when (item.status) {
    ItemStatus.INSTALLED -> FuseIcons.PackageCheck
    ItemStatus.NEEDS_LICENCE -> FuseIcons.Key
    ItemStatus.SUPERSEDED -> FuseIcons.Copy
    ItemStatus.UNSUPPORTED -> FuseIcons.CircleSlash
    ItemStatus.READY -> roleIcon(item.role)
}

private fun roleIcon(role: ItemRole): ImageVector = when (role) {
    ItemRole.LICENCE -> FuseIcons.Key
    ItemRole.GAME -> FuseIcons.Package
    ItemRole.UPDATE -> FuseIcons.Refresh
    ItemRole.DLC -> FuseIcons.PackagePlus
}

internal fun stateIcon(s: ContentState): ImageVector = when (s) {
    ContentState.READY -> FuseIcons.CircleCheck
    ContentState.NEEDS_INSTALL -> FuseIcons.Download
    ContentState.MISSING_LICENCE -> FuseIcons.Key
    ContentState.UPDATE_AVAILABLE -> FuseIcons.Refresh
    ContentState.DLC_AVAILABLE -> FuseIcons.PackagePlus
}

@Composable
internal fun stateColor(s: ContentState): Color = when (s) {
    ContentState.READY -> Fuse.colors.success
    ContentState.MISSING_LICENCE -> Fuse.colors.warning
    else -> Fuse.colors.accent
}
