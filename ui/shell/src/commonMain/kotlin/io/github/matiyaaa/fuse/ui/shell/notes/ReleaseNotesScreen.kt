package io.github.matiyaaa.fuse.ui.shell.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.res.Res
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollTo
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import kotlinx.coroutines.launch

/**
 * The notes of the version Fuse is running, bundled with it (a copy of docs/releases/<version>.md),
 * so "What's new" works offline. Null when they can't be read.
 */
internal suspend fun bundledReleaseNotes(): String? =
    runCatching { Res.readBytes("files/release-notes.md").decodeToString() }.getOrNull()?.takeIf { it.isNotBlank() }

/** "The Swap & Clean Update" from notes titled "# Fuse 0.2.7 - The Swap & Clean Update". */
fun releaseNameOf(markdown: String): String? =
    markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.substringAfter(" - ", "")?.trim()?.takeIf { it.isNotEmpty() }

/** "0.2.7" from notes titled "# Fuse 0.2.7 - The Swap & Clean Update". */
internal fun releaseVersionOf(markdown: String): String? =
    markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.let { Regex("""\d+\.\d+\.\d+""").find(it)?.value }

/** Opens the release notes page for the version running now, from the notes bundled with it. */
fun AppState.openInstalledNotes() {
    scope.launch {
        val md = bundledReleaseNotes() ?: return@launch toasts.show("The notes for this version could not be read")
        go(Route.ReleaseNotes(releaseVersionOf(md) ?: store.updates.currentVersion, releaseNameOf(md), md, installed = true))
    }
}

/**
 * Release notes as a page of their own: the version large with its codename, the opening words,
 * then each part (New, Changed, Fixed) as a card with its icon and how many changes it holds, each
 * change with its bold lead as a heading over the rest. Up and Down go card by card, and through a
 * card taller than the screen a step at a time; L2 and R2 page.
 */
@Composable
fun ReleaseNotesScreen(app: AppState, route: Route.ReleaseNotes) {
    val c = Fuse.colors
    val sections = remember(route.markdown) { noteSections(route.markdown) }
    val intro = sections.firstOrNull()?.takeIf { it.title == null }
    val cards = if (intro != null) sections.drop(1) else sections
    var sel by remember { mutableIntStateOf(0) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val margin = with(LocalDensity.current) { Space.xl.toPx() }

    LaunchedEffect(Unit) {
        app.hero = null
        app.hints = listOf(
            Hint(HintButton.DPAD, "Move"),
            Hint(HintButton.PAGE_PREV, "Page up"),
            Hint(HintButton.PAGE_NEXT, "Page down"),
            Hint(HintButton.BACK, "Back"),
        )
    }

    fun scrollTo(y: Float) {
        scope.launch { scroll.fuselineScrollTo(y.toInt().coerceIn(0, scroll.maxValue)) }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val view = scroll.viewportSize.toFloat()
        val v = scroll.value.toFloat()
        val cur = bounds[sel]
        when (e.action) {
            NavAction.DOWN -> when {
                // A card taller than the screen is read through a step at a time before the next.
                cur != null && cur.bottom > v + view - margin && scroll.value < scroll.maxValue -> {
                    scrollTo(minOf(cur.bottom - view + margin, v + view * 0.6f)); NavResult.MOVED
                }
                sel < cards.lastIndex -> {
                    sel++
                    bounds[sel]?.let { next -> scrollTo(if (next.height > view - 2 * margin) next.top - margin else maxOf(v, next.bottom - view + margin)) }
                    NavResult.MOVED
                }
                else -> NavResult.BLOCKED
            }
            NavAction.UP -> when {
                cur != null && cur.top - margin < v -> {
                    scrollTo(maxOf(cur.top - margin, v - view * 0.6f)); NavResult.MOVED
                }
                sel > 0 -> {
                    sel--
                    bounds[sel]?.let { prev -> scrollTo(if (prev.height > view - 2 * margin) prev.bottom - view + margin else minOf(v, prev.top - margin)) }
                    NavResult.MOVED
                }
                // At the top: the heading back in view, then Up goes on to the top line.
                scroll.value > 0 -> { scrollTo(0f); NavResult.MOVED }
                else -> NavResult.IGNORED
            }
            NavAction.PAGE_DOWN -> { scrollTo(v + view * 0.85f); NavResult.CONSUMED }
            NavAction.PAGE_UP -> { scrollTo(v - view * 0.85f); NavResult.CONSUMED }
            NavAction.LEFT, NavAction.RIGHT, NavAction.SELECT -> NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 640.dp
        val short = maxHeight < 560.dp
        val gutter = if (narrow) Space.gutterCompact else Space.gutter
        // Two columns of changes where a card is wide enough to keep each line a good length.
        val twoColumns = maxWidth >= 1100.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = Size.hudHeight, bottom = Size.hintHeight)
                .fadingEdges(scroll, top = Space.l, bottom = Space.xl)
                .verticalScroll(scroll)
                .padding(horizontal = gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth()) {
                Spacer(Modifier.height(if (short) Space.s else Space.l))
                Header(route, cards, short, Modifier.reveal(0))
                intro?.let { part ->
                    Spacer(Modifier.height(Space.l))
                    for (l in part.lines) {
                        FText(l.text, Fuse.type.body, color = c.textMuted, modifier = Modifier.widthIn(max = 760.dp).padding(bottom = Space.xs).reveal(1))
                    }
                }
                Spacer(Modifier.height(if (short) Space.l else Space.xl))
                cards.forEachIndexed { i, part ->
                    SectionCard(
                        part,
                        selected = i == sel && app.focusZone == FocusZone.CONTENT,
                        twoColumns = twoColumns,
                        onClick = { sel = i },
                        modifier = Modifier
                            .padding(bottom = Space.l)
                            .onPlaced { coords ->
                                val p = coords.positionInParent()
                                bounds[i] = Rect(p.x, p.y, p.x + coords.size.width, p.y + coords.size.height)
                            }
                            .reveal(2 + i),
                    )
                }
                Spacer(Modifier.height(Space.xl))
            }
        }
    }
}

/** The version large, its codename in the accent, whether it is running or on offer, and the counts. */
@Composable
private fun Header(route: Route.ReleaseNotes, cards: List<NoteSection>, short: Boolean, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FuseIcon(FuseIcons.Sparkles, size = Size.iconS, tint = c.accent)
            FText("WHAT'S NEW", Fuse.type.overline, color = c.accent, maxLines = 1)
            Spacer(Modifier.width(Space.xs))
            StatusPill(
                if (route.installed) "Running now" else "Update available",
                if (route.installed) FuseIcons.CircleCheck else FuseIcons.Download,
            )
        }
        FText("Fuse ${route.version}", if (short) Fuse.type.display else Fuse.type.hero, maxLines = 1)
        route.name?.let { FText(it, Fuse.type.title, color = c.accent, maxLines = 2) }
        if (cards.isNotEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (part in cards) {
                    val title = part.title ?: continue
                    CountChip(sectionIcon(title), "${part.lines.size} ${title.lowercase()}")
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, icon: ImageVector) {
    val c = Fuse.colors
    Row(
        Modifier.clip(PillShape).background(c.text.copy(alpha = 0.08f)).padding(horizontal = Space.s, vertical = Space.xxs + 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        FuseIcon(icon, size = Size.iconXS, tint = c.textMuted)
        FText(label, Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun CountChip(icon: ImageVector, label: String) {
    val c = Fuse.colors
    Row(
        Modifier.clip(PillShape).border(Size.stroke, c.hairline, PillShape).padding(horizontal = Space.m, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        FuseIcon(icon, size = Size.iconXS, tint = c.accent)
        FText(label, Fuse.type.label, color = c.text, maxLines = 1)
    }
}

/** One part of the notes: its icon, title and count, then its changes (in two columns when wide). */
@Composable
private fun SectionCard(part: NoteSection, selected: Boolean, twoColumns: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.panel)
    val ring by fuselineColor(if (selected) c.focus else c.focus.copy(alpha = 0f), Fuse.motion.tween(Durations.FAST), label = "notesRing")
    val title = part.title.orEmpty()
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(Size.stroke, c.hairline, shape)
            .border(Size.focusStroke, ring, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(Space.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                FuseIcon(sectionIcon(title), size = Size.iconM, tint = c.accent)
            }
            Spacer(Modifier.width(Space.m))
            FText(title, Fuse.type.title, maxLines = 1, modifier = Modifier.weight(1f))
            FText(if (part.lines.size == 1) "1 change" else "${part.lines.size} changes", Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(Space.l))
        if (twoColumns && part.lines.size > 1) {
            // Changes keep their order reading down the first column, then the second, split where
            // the two columns come out about the same length.
            val weights = part.lines.map { it.text.length + (it.lead?.length ?: 0) + 60 }
            val total = weights.sum()
            var run = 0
            val half = weights.indexOfFirst { run += it; run * 2 >= total }.let { if (it < 0) part.lines.size else it + 1 }.coerceIn(1, part.lines.size - 1)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                Changes(part.lines.take(half), Modifier.weight(1f))
                Changes(part.lines.drop(half), Modifier.weight(1f))
            }
        } else {
            Changes(part.lines, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Changes(lines: List<NoteLine>, modifier: Modifier) {
    val c = Fuse.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        for (l in lines) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 8.dp, end = Space.m).size(6.dp).clip(CircleShape).background(c.accent.copy(alpha = if (l.kind == NoteLine.Kind.ITEM) 1f else 0f)))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    l.lead?.let { FText(it, Fuse.type.bodyStrong, maxLines = 3) }
                    if (l.text.isNotEmpty()) FText(l.text, Fuse.type.body, color = if (l.lead != null) c.textMuted else c.text.copy(alpha = 0.9f))
                }
            }
        }
    }
}

/** The icon for a part of the notes, by its title. */
private fun sectionIcon(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "new" in t || "added" in t -> FuseIcons.Sparkles
        "fix" in t -> FuseIcons.Wrench
        "change" in t || "improve" in t || "better" in t -> FuseIcons.Wand
        "known" in t || "issue" in t -> FuseIcons.Alert
        "thank" in t -> FuseIcons.Heart
        "remove" in t -> FuseIcons.Trash
        else -> FuseIcons.FileText
    }
}
