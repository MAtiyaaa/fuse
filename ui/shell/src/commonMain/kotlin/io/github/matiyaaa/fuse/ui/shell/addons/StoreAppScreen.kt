package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollBy
import io.github.matiyaaa.fuse.ui.shell.notes.NotesWell
import io.github.matiyaaa.fuse.ui.shell.notes.noteLines
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.ReleaseCheck
import io.github.matiyaaa.fuse.ui.shell.store.SourceKind
import io.github.matiyaaa.fuse.ui.shell.store.Standing
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreJob
import io.github.matiyaaa.fuse.ui.shell.store.StoreState
import kotlinx.coroutines.launch

private data class PageButton(val label: String, val icon: ImageVector, val kind: ButtonKind, val busy: Boolean = false, val run: () -> Unit)

/**
 * An app's page in the Store: its mark and name in its colour, who makes it and where it comes
 * from, and one main button that follows what can be done now (Install, Update, Open, Cancel,
 * Allow installs, Try again, or the download page for apps installed by hand). Below: what is
 * installed against the newest release, where the file comes from, the systems it plays, and the
 * newest release's notes. The page reads its newest release when it opens.
 */
@Composable
fun StoreAppScreen(app: AppState, key: String) {
    val ops = app.store.appStore
    val state by ops.state.collectAsState()
    val item = state.app(key)
    LaunchedEffect(key) { ops.check(key) }
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(FuseIcons.Store, "This app isn't in the Store any more", message = "The Store's catalogue changed. Press Back to return to it.")
        }
        LaunchedEffect(Unit) { app.hints = emptyList() }
        return
    }
    StorePage(app, state, item)
}

@Composable
private fun StorePage(app: AppState, state: StoreState, item: StoreApp) {
    val c = Fuse.colors
    val ops = app.store.appStore
    val key = item.key
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableIntStateOf(0) }
    val installed = state.installed[key]
    val job = state.jobs[key]
    val check = state.releases[key]
    val release = (check as? ReleaseCheck.Ready)?.release
    val main = storeAction(app, state, item)
    val buttons = buildList {
        add(PageButton(main.label, main.icon, main.kind, main.busy, main.run))
        if (installed != null && (job == null || !job.active) && main.label != "Open") {
            add(PageButton("Open", FuseIcons.Play, ButtonKind.SECONDARY) { if (!ops.launch(key)) app.toasts.show("${item.name} has nothing to open.") })
        }
        if (installed != null && (job == null || !job.active)) {
            add(PageButton("Uninstall", FuseIcons.Trash, ButtonKind.SECONDARY) {
                app.confirm = ConfirmSpec(
                    title = "Uninstall ${item.name}?",
                    message = if (state.desktop) "Fuse removes the program it put in ${state.folder ?: "place"}. Its own settings and saves, and your games, stay where they are."
                    else "Android asks you to confirm, and removes the app with its own data. Games and files in your folders stay where they are.",
                    confirmLabel = "Uninstall",
                    onConfirm = { ops.uninstall(key) },
                )
            })
        }
        add(PageButton(if (item.sourceKind == SourceKind.GITHUB) "GitHub" else "Website", FuseIcons.External, ButtonKind.GHOST) { app.platform.openUrl(item.sourceUrl) })
        add(PageButton("Check again", FuseIcons.Refresh, ButtonKind.GHOST, busy = check == ReleaseCheck.Checking) { ops.check(key, force = true) })
    }
    if (chosen > buttons.lastIndex) chosen = buttons.lastIndex
    val focused = app.focusZone == FocusZone.CONTENT

    LaunchedEffect(buttons.getOrNull(chosen)?.label, focused) {
        app.hero = null
        app.hints = listOf(Hint(HintButton.CONFIRM, buttons.getOrNull(chosen)?.label ?: "Choose"), Hint(HintButton.BACK, "Store"))
    }
    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT -> if (chosen > 0) { chosen--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (chosen < buttons.lastIndex) { chosen++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> if (scroll.canScrollForward) { scope.launch { scroll.fuselineScrollBy(SCROLL_STEP) }; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP -> if (scroll.value > 0) { scope.launch { scroll.fuselineScrollBy(-SCROLL_STEP) }; NavResult.MOVED } else NavResult.IGNORED
            NavAction.SELECT -> { buttons.getOrNull(chosen)?.takeIf { !it.busy || it.label == "Cancel" }?.run?.invoke(); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val tint = item.tint()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        val wide = maxWidth > 900.dp
        Column(
            Modifier.fillMaxSize().fadingEdges(top = if (scroll.value > 0) Space.xl else 0.dp).verticalScroll(scroll)
                .padding(top = Size.hudHeight + Space.s, bottom = Size.hintHeight + Space.xl)
                .padding(horizontal = Space.gutter),
        ) {
            // The app, lit in its colour.
            val shape = RoundedCornerShape(Fuse.geometry.panel)
            Box(
                Modifier.fillMaxWidth().widthIn(max = 1180.dp).clip(shape)
                    .background(Brush.linearGradient(listOf(lerp(tint, Color.Black, 0.38f), lerp(tint, Color.Black, 0.82f))))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                    .padding(if (compact) Space.l else Space.xl),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppMark(item, ops.iconModel(key), if (compact) 80.dp else 112.dp)
                        Spacer(Modifier.width(if (compact) Space.l else Space.xl))
                        Column(Modifier.weight(1f)) {
                            FText(item.categories.joinToString("  ·  ").uppercase(), Fuse.type.overline, color = c.onArtMuted, maxLines = 1)
                            Spacer(Modifier.height(Space.xxs))
                            FText(item.name, if (compact) Fuse.type.title else Fuse.type.display, color = c.onArt, maxLines = 1)
                            if (item.author.isNotBlank()) FText("by ${item.author}", Fuse.type.body, color = c.onArtMuted, maxLines = 1)
                            Spacer(Modifier.height(Space.s))
                            HeroChips(state, item)
                        }
                    }
                    item.about?.let {
                        Spacer(Modifier.height(Space.m))
                        FText(it, Fuse.type.body, color = c.onArt.copy(alpha = 0.9f), maxLines = 3, modifier = Modifier.widthIn(max = 820.dp))
                    }
                    Spacer(Modifier.height(Space.l))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        buttons.forEachIndexed { i, b ->
                            FuseButton(
                                b.label, selected = focused && chosen == i,
                                onClick = { chosen = i; app.focusZone = FocusZone.CONTENT; if (!b.busy || b.label == "Cancel") b.run() },
                                icon = b.icon, kind = b.kind, loading = b.busy && b.label != "Cancel",
                                height = if (compact) 40.dp else Size.touch,
                            )
                        }
                    }
                    if (job != null) {
                        Spacer(Modifier.height(Space.m))
                        JobStrip(job, item, tint, state.desktop)
                    }
                }
            }

            // Why there is no Install button.
            val note = when {
                item.availability == Availability.TRACK_ONLY ->
                    FuseIcons.Eye to "The pack lists ${item.name} to follow its releases, not to install it here. Its page has the downloads."
                item.availability == Availability.MANUAL ->
                    FuseIcons.External to "${item.name}'s releases come from a source Fuse doesn't read, so it's installed by hand from its page."
                release?.manual != null && job == null -> FuseIcons.External to release.manual
                installed != null && state.standing(key) == Standing.UNKNOWN && release != null && job == null ->
                    FuseIcons.Info to "Fuse can't compare the installed version with the newest release, so it won't claim an update. Installing from here once lets Fuse follow it exactly."
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(Space.l))
                Note(note.first, note.second, Modifier.widthIn(max = 1180.dp))
            }

            Spacer(Modifier.height(Space.xl))
            val details = details(state, item)
            if (wide) {
                Row(Modifier.widthIn(max = 1180.dp), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                    DetailList(details.take((details.size + 1) / 2), Modifier.weight(1f))
                    DetailList(details.drop((details.size + 1) / 2), Modifier.weight(1f))
                }
            } else {
                DetailList(details, Modifier.fillMaxWidth())
            }

            val notes = release?.notes?.let { noteLines(it, max = 28) }.orEmpty()
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(Space.xl))
                SectionLabel("What's new" + (release?.version?.let { " in ${it.removePrefix("v")}" } ?: ""), Modifier.padding(bottom = Space.s))
                NotesWell(notes, Modifier.widthIn(max = 1180.dp))
            }
        }
    }
}

private const val SCROLL_STEP = 320f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroChips(state: StoreState, item: StoreApp) {
    val c = Fuse.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        val onArt = c.onArt
        Chip(item.sourceHost, icon = if (item.sourceKind == SourceKind.GITHUB) FuseIcons.Package else FuseIcons.Globe, color = onArt, background = Color.White.copy(alpha = 0.12f))
        systemNames(item.systems, 6).forEach { Chip(it, icon = FuseIcons.Gamepad, color = onArt, background = Color.White.copy(alpha = 0.12f)) }
        val installed = state.installed[item.key]
        if (installed != null) {
            when (state.standing(item.key)) {
                Standing.UPDATE -> Chip("Update available", icon = FuseIcons.CircleArrowDown, color = Color.White, background = c.accent.copy(alpha = 0.55f))
                else -> Chip("Installed", icon = FuseIcons.Check, color = onArt, background = c.success.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun JobStrip(job: StoreJob, item: StoreApp, tint: Color, desktop: Boolean) {
    val c = Fuse.colors
    if (job is StoreJob.Failed) {
        Note(FuseIcons.Alert, job.message, Modifier.fillMaxWidth(), color = c.danger, onArt = true)
        return
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        ProgressBar(jobProgress(job), Modifier.fillMaxWidth(), color = lerp(tint, Color.White, 0.35f), height = 4.dp)
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(jobLine(job, item.name), Fuse.type.caption, color = c.onArtMuted, maxLines = 2, modifier = Modifier.weight(1f))
            jobProgress(job)?.let { FText("${(it * 100).toInt()}%", Fuse.type.label, color = c.onArt, maxLines = 1) }
        }
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, modifier: Modifier, color: Color = Fuse.colors.textMuted, onArt: Boolean = false) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        modifier.clip(shape).background(if (onArt) Color.Black.copy(alpha = 0.22f) else c.text.copy(alpha = 0.05f))
            .border(1.dp, color.copy(alpha = 0.2f), shape)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = Size.iconM, tint = color)
        Spacer(Modifier.width(Space.m))
        FText(text, Fuse.type.body, color = if (onArt) c.onArt else c.text, maxLines = 4)
    }
}

/** The page's facts, label to value. */
private fun details(state: StoreState, item: StoreApp): List<Pair<String, String>> {
    val installed = state.installed[item.key]
    val check = state.releases[item.key]
    val release = (check as? ReleaseCheck.Ready)?.release
    val newest = when (check) {
        is ReleaseCheck.Ready -> (release?.version?.removePrefix("v") ?: "Not named by its source") + if (check.stale) "  (last known)" else ""
        ReleaseCheck.Checking -> "Checking"
        is ReleaseCheck.Failed -> "Couldn't check. ${check.message}"
        null -> "Not checked yet"
    }
    val catalogue = state.catalogue
    return listOfNotNull(
        "Installed" to when {
            installed == null -> "Not installed"
            installed.versionName != null -> installed.versionName
            installed.versionCode > 0 -> "Version ${installed.versionCode}"
            // Found on this computer rather than put there by Fuse: no version to show.
            else -> "Found on this computer"
        },
        "Newest release" to newest,
        release?.publishedAt?.let { "Released" to dateText(it) },
        release?.file?.let { f -> "Download" to (f.name + (f.sizeBytes?.let { "  ·  ${bytesText(it)}" } ?: "")) },
        "Source" to when (item.sourceKind) {
            SourceKind.GITHUB -> "GitHub  ·  " + item.sourceUrl.substringAfter("github.com/").trimEnd('/')
            SourceKind.WEB -> "Website  ·  ${item.sourceHost}"
            SourceKind.OTHER -> item.sourceHost
        },
        ("Package" to (item.packageName ?: "Learnt when it is first installed")).takeIf { !state.desktop },
        installed?.packageName?.takeIf { state.desktop }?.let { "Installed at" to it },
        systemNames(item.systems, 12).takeIf { it.isNotEmpty() }?.let { "Plays" to it.joinToString(", ") },
        inFuseText(item.inFuse)?.let { "In Fuse" to it },
        when {
            item.custom -> "Listed" to "Added by you"
            state.desktop -> "Listed in" to "Fuse's list of desktop emulators"
            else -> catalogue?.let { "Listed in" to (state.packRepo?.substringAfter("github.com/") ?: "Obtainium Emulation Pack") + ", ${it.variant.title()} edition" + (it.packVersion?.let { v -> " ($v)" } ?: "") }
        },
    )
}

@Composable
private fun DetailList(rows: List<Pair<String, String>>, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
                FText(label, Fuse.type.label, color = c.textMuted, maxLines = 1, modifier = Modifier.width(150.dp))
                FText(value, Fuse.type.body, maxLines = 3, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** "2026-09-12T10:00:00Z" as "12 Sep 2026"; anything else as it is. */
internal fun dateText(iso: String): String {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso) ?: return iso
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .getOrNull((m.groupValues[2].toIntOrNull() ?: 0) - 1) ?: return iso
    return "${m.groupValues[3].trimStart('0')} $month ${m.groupValues[1]}"
}

/** What Fuse does with an app once it is installed, for its page; null when Fuse doesn't use it. */
internal fun inFuseText(inFuse: io.github.matiyaaa.fuse.ui.shell.store.InFuse): String? = when (inFuse) {
    io.github.matiyaaa.fuse.ui.shell.store.InFuse.LAUNCHES_GAMES -> "Starts your games for the systems it plays"
    io.github.matiyaaa.fuse.ui.shell.store.InFuse.OPENS_APP -> "Opens the app; it doesn't let other apps start a game in it"
    io.github.matiyaaa.fuse.ui.shell.store.InFuse.STREAMING -> "Listed under Streaming in Apps"
    io.github.matiyaaa.fuse.ui.shell.store.InFuse.TOOL -> "Listed under Tools in Apps"
    io.github.matiyaaa.fuse.ui.shell.store.InFuse.NOTHING -> null
}
