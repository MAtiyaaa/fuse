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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.library.PlatformCatalog
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.input.dragVerticalScroll
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Aspect
import io.github.matiyaaa.fuse.ui.designsystem.theme.Elevation
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.PageEffect
import io.github.matiyaaa.fuse.ui.fuseline.fuselineScrollBy
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.madeLine
import io.github.matiyaaa.fuse.ui.shell.app.word
import io.github.matiyaaa.fuse.ui.shell.home.bytesText
import io.github.matiyaaa.fuse.ui.shell.store.AndroidPortView
import io.github.matiyaaa.fuse.ui.shell.store.CopyPlace
import io.github.matiyaaa.fuse.ui.shell.store.PortJob
import kotlinx.coroutines.launch

/** One of a port page's buttons: what it says, its mark, how strong it is, and what it does. */
private class PortButton(val label: String, val icon: ImageVector, val kind: ButtonKind, val run: () -> Unit)

/**
 * An Android port's own page, opened from the Ports grid: its game's box art large, the game and the
 * port that rebuilt it, how it stands here, whose word its entry is, and every button for it, the
 * next thing to do first (Install, Attach, Play, Get the Game). Below, what the port is, what it asks
 * for on its first start, and where your copies of its game are. Nothing installs until asked.
 */
@Composable
fun AndroidPortScreen(app: AppState, id: String) {
    val ops = app.store.ports
    val state by ops.android.collectAsState()
    LaunchedEffect(Unit) { ops.open() }
    val v = state.ports.firstOrNull { it.port.id == id }
    if (v == null) {
        PageEffect(state.loaded) { app.hints = listOf(Hint(HintButton.BACK, "Ports")) }
        Box(Modifier.fillMaxSize().padding(top = Size.hudHeight, bottom = Size.hintHeight), contentAlignment = Alignment.Center) {
            EmptyState(
                FuseIcons.Boxes,
                if (state.loaded) "This port isn't in the lists any more" else "Reading the lists of ports",
                message = if (state.loaded) "The lists changed since. Press Back for Ports." else null,
            )
        }
        return
    }
    PortPage(app, v)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortPage(app: AppState, v: AndroidPortView) {
    val c = Fuse.colors
    val ops = app.store.ports
    val p = v.port
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableIntStateOf(0) }
    val job = v.job
    val buttons = buildList {
        next(v)?.let { (label, icon) -> add(PortButton(label, icon, ButtonKind.PRIMARY) { app.doNext(v) }) }
        if (v.installed && job?.active != true) {
            if (next(v)?.first != "Open") add(PortButton("Open ${p.name}", FuseIcons.External, ButtonKind.SECONDARY) { ops.openAndroid(p.id) })
            if (next(v)?.first != "Attach") {
                add(PortButton(if (v.attachedTo.isEmpty()) "Attach to a Game" else "Attach to Another", FuseIcons.Link, ButtonKind.SECONDARY) { app.attachPort(v) })
            }
            val appId = v.appId
            if (appId != null && v.attachedTo.isNotEmpty()) {
                add(PortButton("Detach", FuseIcons.Unlink, ButtonKind.SECONDARY) { scope.launch { ops.detach(appId) } })
            }
            if (p.installable) add(PortButton("Newest Release", FuseIcons.Download, ButtonKind.GHOST) { ops.installAndroid(p.id, v.attachedTo.firstOrNull()?.id) })
        }
        // Its game on RomM or another device, brought here from the one chosen.
        if (v.bringing == null && !v.here && next(v)?.first != "Get the Game") {
            v.elsewhere.take(2).forEach { copy ->
                add(PortButton(if (copy.place == CopyPlace.ROMM) "Get It From RomM" else "Get It From ${copy.from}", FuseIcons.CloudDownload, ButtonKind.SECONDARY) { app.offerFrom(v, copy) })
            }
        }
        add(PortButton("Project Page", FuseIcons.Globe, ButtonKind.GHOST) { app.platform.openUrl(p.repo) })
        if (v.installed && job?.active != true) {
            add(PortButton("Uninstall", FuseIcons.Trash, ButtonKind.GHOST) {
                app.confirm = ConfirmSpec("Uninstall ${p.name}?", "Android removes the app and what it keeps, its saves too. Your game's own files stay where they are.", "Uninstall", destructive = true) {
                    ops.uninstallAndroid(p.id)
                }
            })
        }
    }
    if (chosen > buttons.lastIndex) chosen = buttons.lastIndex.coerceAtLeast(0)
    val focused = app.focusZone == FocusZone.CONTENT && !app.overlayOpen
    PageEffect(focused, buttons.getOrNull(chosen)?.label) {
        if (!focused) return@PageEffect
        app.hints = listOfNotNull(
            Hint(HintButton.CONFIRM, buttons.getOrNull(chosen)?.label ?: "Choose"),
            Hint(HintButton.OPTIONS, "More"),
            Hint(HintButton.BACK, "Ports"),
        )
    }
    PageEffect(p.id, v.cover) {
        val game = (v.attachedTo.firstOrNull() ?: v.games.firstOrNull())?.id
        app.hero = io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource(game ?: "port:${p.id}", v.cover, Color(0xFF7C8CF8), blurred = true)
    }
    InputLayer(enabled = focused) { e ->
        when (e.action) {
            NavAction.LEFT -> if (chosen > 0) { chosen--; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.RIGHT -> if (chosen < buttons.lastIndex) { chosen++; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.DOWN -> if (scroll.canScrollForward) { scope.launch { scroll.fuselineScrollBy(SCROLL_STEP) }; NavResult.MOVED } else NavResult.BLOCKED
            NavAction.UP -> if (scroll.value > 0) { scope.launch { scroll.fuselineScrollBy(-SCROLL_STEP) }; NavResult.MOVED } else NavResult.IGNORED
            NavAction.SELECT -> { buttons.getOrNull(chosen)?.run?.invoke(); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { app.openContextMenu(app.portMenu(v)); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("android-port")) {
        val short = maxHeight < 480.dp
        val narrow = maxWidth < 600.dp
        val gutter = if (short || narrow) Space.gutterCompact else Space.gutter
        val cover = when {
            short -> (maxHeight - Size.hudHeight - Size.hintHeight - Space.xl * 2).coerceIn(112.dp, 168.dp)
            // Narrow (a screen held upright): a smaller case, the rest of the page under it.
            narrow -> (maxWidth * 0.3f / Aspect.BOX).coerceIn(140.dp, 200.dp)
            else -> 240.dp
        }
        // Narrow, the chips and buttons take the whole width under the case and its name.
        val stacked = narrow && !short
        Column(
            // The edges follow the scroll without composing the page again as it moves; the foot fades
            // out above the hints.
            Modifier.fillMaxSize().fadingEdges(scroll, top = Space.xl, bottom = Size.hintHeight + Space.l).dragVerticalScroll(scroll)
                .padding(top = Size.hudHeight + Space.s, bottom = Size.hintHeight + Space.xl)
                .padding(horizontal = gutter),
        ) {
            val shape = RoundedCornerShape(Fuse.geometry.panel)
            Box(
                Modifier.fillMaxWidth().widthIn(max = 1180.dp).clip(shape)
                    .background(Brush.linearGradient(listOf(c.surfaceRaised.copy(alpha = 0.86f), c.surface.copy(alpha = 0.72f))))
                    .border(1.dp, c.hairline, shape)
                    .padding(if (short || stacked) Space.m else Space.xl),
            ) {
              Column {
                Row(verticalAlignment = if (stacked) Alignment.CenterVertically else Alignment.Top) {
                    PortCover(app, v, cover)
                    Spacer(Modifier.width(if (short || stacked) Space.l else Space.xl))
                    Column(Modifier.weight(1f)) {
                        val systems = p.systems.mapNotNull { PlatformCatalog.byId(PlatformId(it))?.shortName }.distinct().joinToString(" / ")
                        val eyebrow = listOfNotNull(systems.ifEmpty { null }, p.year?.toString(), p.publisher, p.genre?.let(::genreWord)).joinToString("  ·  ")
                        if (eyebrow.isNotEmpty()) FText(eyebrow.uppercase(), Fuse.type.overline, color = c.textMuted, maxLines = 1)
                        Spacer(Modifier.height(Space.xxs))
                        FText(p.game, if (short || stacked) Fuse.type.title else Fuse.type.display, maxLines = 2)
                        Spacer(Modifier.height(Space.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FuseIcon(FuseIcons.Boxes, size = Size.iconS, tint = c.accent)
                            Spacer(Modifier.width(Space.s))
                            FText(listOfNotNull(p.name, "${p.madeLine()}, ${p.status.word()}").joinToString("  ·  "), Fuse.type.label, color = c.text, maxLines = if (stacked) 2 else 1)
                        }
                        Spacer(Modifier.height(Space.s))
                        val (status, tone) = status(v)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            Box(Modifier.size(Size.dot).clip(CircleShape).background(toneOf(tone)))
                            FText(status, Fuse.type.body, color = if (tone == Tone.QUIET) c.textMuted else c.text, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        }
                        if (!stacked) {
                            Spacer(Modifier.height(Space.s))
                            PortActions(app, v, job, buttons, focused, chosen, short) { chosen = it }
                        }
                    }
                }
                if (stacked) {
                    Spacer(Modifier.height(Space.m))
                    PortActions(app, v, job, buttons, focused, chosen, short) { chosen = it }
                }
              }
            }
            // What the port is, what it asks for, and where your copies of its game are.
            p.about?.let { about ->
                Spacer(Modifier.height(Space.xl))
                SectionLabel("About ${p.name}", Modifier.padding(bottom = Space.s))
                FText(about, Fuse.type.body, color = c.textMuted, maxLines = 8, modifier = Modifier.widthIn(max = 820.dp))
            }
            p.needs?.let { needs ->
                Spacer(Modifier.height(Space.l))
                SectionLabel("It asks for", Modifier.padding(bottom = Space.s))
                DetailRow(FuseIcons.FolderOpen, needs)
            }
            val yours = (v.attachedTo + v.games).distinctBy { it.id }
            if (yours.isNotEmpty() || v.elsewhere.isNotEmpty() || v.bringing != null) {
                Spacer(Modifier.height(Space.l))
                SectionLabel("Your copies", Modifier.padding(bottom = Space.s))
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    yours.forEach { g ->
                        DetailRow(
                            if (g in v.attachedTo) FuseIcons.Play else FuseIcons.Gamepad,
                            "${g.title}  ·  ${g.system}",
                            if (g in v.attachedTo) "Plays with ${p.name}" else "On this device",
                        )
                    }
                    v.bringing?.let { b -> DetailRow(FuseIcons.CloudDownload, b.title, "Coming here from ${b.from}") }
                    v.elsewhere.forEach { e ->
                        DetailRow(
                            if (e.place == CopyPlace.ROMM) FuseIcons.Server else io.github.matiyaaa.fuse.ui.shell.reach.deviceIcon("", e.from),
                            e.title,
                            listOfNotNull("On ${e.from}", e.sizeBytes.takeIf { it > 0 }?.let(::bytesText)).joinToString("  ·  "),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.xl))
            FText(
                "${io.github.matiyaaa.fuse.integrations.ports.OpenPorts.CREDIT}. The app comes from its own project's releases and takes your game's files itself.",
                Fuse.type.caption, color = c.textFaint, maxLines = 3, modifier = Modifier.widthIn(max = 820.dp),
            )
        }
    }
}

/** A port's marks (checked, installed, its licence), a download's progress, and its buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortActions(
    app: AppState,
    v: AndroidPortView,
    job: PortJob?,
    buttons: List<PortButton>,
    focused: Boolean,
    chosen: Int,
    short: Boolean,
    onChoose: (Int) -> Unit,
) {
    val c = Fuse.colors
    val p = v.port
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        when {
            p.verified != null -> Chip(FuseIcons.ShieldCheck, "Verified ${p.verified}", c.success)
            p.checked -> Chip(FuseIcons.ShieldCheck, "Checked by Fuse", c.accent)
            else -> Chip(FuseIcons.ShieldAlert, "Unchecked", c.warning)
        }
        v.installedVersion?.let { Chip(FuseIcons.Check, "Installed $it", c.success) }
        p.licence?.let { Chip(FuseIcons.FileText, it, c.textMuted) }
    }
    (job as? PortJob.Downloading)?.progress?.let { pr ->
        Spacer(Modifier.height(Space.s))
        Box(Modifier.widthIn(max = 420.dp)) { Progress(pr) }
    }
    Spacer(Modifier.height(if (short) Space.m else Space.l))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        buttons.forEachIndexed { i, b ->
            FuseButton(
                b.label, selected = focused && chosen == i,
                onClick = { onChoose(i); app.focusZone = FocusZone.CONTENT; b.run() },
                icon = b.icon, kind = b.kind,
                height = if (short) 36.dp else 44.dp,
            )
        }
    }
}

/** The port's game's box art, large, standing on the page like a case. */
@Composable
private fun PortCover(app: AppState, v: AndroidPortView, height: Dp) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Radius.m)
    val shadow = c.shadow
    Box(
        Modifier
            .height(height)
            .aspectRatio(Aspect.BOX)
            .graphicsLayer {
                this.shape = shape
                clip = true
                shadowElevation = Elevation.raised.shadow.toPx()
                spotShadowColor = shadow
                ambientShadowColor = shadow.copy(alpha = shadow.alpha * 0.5f)
            },
    ) {
        Artwork(portArt(app, v), Modifier.fillMaxSize(), contentScale = ContentScale.Crop, focusY = 0.3f, fallback = { GeneratedArt(v.port.game, c.accent, slot = ArtSlot.BOX) })
    }
}

/** One fact on the page: its mark, what it is, and a word under it. */
@Composable
private fun DetailRow(icon: ImageVector, text: String, detail: String? = null) {
    val c = Fuse.colors
    Row(
        Modifier.widthIn(max = 820.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.m)).background(c.text.copy(alpha = if (c.isDark) 0.05f else 0.04f)).padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            FuseIcon(icon, size = Size.iconS, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(text, Fuse.type.label, maxLines = 2)
            if (detail != null) FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
    }
}

/** How far Up and Down scroll the page. */
private const val SCROLL_STEP = 280f
