package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.drawSpark
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FilePurpose
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import kotlinx.coroutines.launch

/** Where people share themes: the Themes part of Fuse's website. */
const val THEMES_URL = "https://matiyaaa.github.io/fuse/#themes"

/** A card in the gallery: a theme, or the card that adds one. */
private sealed interface ThemeItem {
    data class Of(val spec: ThemeSpec, val custom: Boolean) : ThemeItem
    data object Add : ThemeItem
}

/**
 * Every theme as a live picture of Fuse in it: the built-in ones, then the ones you added, then a
 * card to add another from a link, a file or pasted text. Confirm uses a theme at once; Options
 * copies it as a file to edit or share, and removes an added one.
 */
@Composable
fun ThemesScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val items: List<ThemeItem> = ThemePresets.all.map { ThemeItem.Of(it, custom = false) } +
        prefs.customThemes.map { ThemeItem.Of(it, custom = true) } + ThemeItem.Add
    val sel = rememberRouteState(app.navigator, "themes") {
        GridSelection(items.indexOfFirst { it is ThemeItem.Of && it.spec.id == prefs.themeId }.coerceAtLeast(0))
    }
    sel.clamp(items.size)
    var columns = 4
    val current = items.getOrNull(sel.index)

    LaunchedEffect(current) {
        app.hero = null
        app.hints = when (current) {
            ThemeItem.Add -> listOf(Hint(HintButton.CONFIRM, "Add a theme"))
            else -> listOf(Hint(HintButton.CONFIRM, "Use"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    fun use(spec: ThemeSpec) {
        if (spec.id != app.store.prefs.value.themeId) {
            app.store.updatePrefs { it.withTheme(spec) }
            app.toasts.show("Theme: ${spec.name}")
        }
    }
    fun open(item: ThemeItem) = when (item) {
        is ThemeItem.Of -> use(item.spec)
        ThemeItem.Add -> app.addThemeChoice()
    }
    fun options(item: ThemeItem) {
        if (item !is ThemeItem.Of) return
        val spec = item.spec
        app.openContextMenu(
            ContextMenuSpec(
                title = spec.name,
                subtitle = spec.author?.let { "Theme by $it" } ?: if (item.custom) "Your theme" else "Built in",
                icon = FuseIcons.Palette,
                actions = listOfNotNull(
                    MenuAction("use", "Use this theme", FuseIcons.Check, onSelect = { app.closeOverlays(); use(spec) }),
                    MenuAction(
                        "copy", "Copy as a theme file", FuseIcons.Copy,
                        detail = "Paste it into a .json file to change it, or share it as it is",
                        onSelect = {
                            app.closeOverlays()
                            app.scope.launch {
                                val ok = app.platform.writeClipboardText(app.store.themes.export(spec))
                                app.toasts.show(if (ok) "Copied ${spec.name} as a theme file" else "This device can't copy text", if (ok) ToastKind.SUCCESS else ToastKind.WARNING)
                            }
                        },
                    ),
                    if (item.custom) {
                        MenuAction("remove", "Remove", FuseIcons.Trash, destructive = true, detail = "From this device only", onSelect = {
                            app.closeOverlays()
                            app.confirm = ConfirmSpec(
                                "Remove ${spec.name}?",
                                "It goes from this device only. Add it again any time from its link or file.",
                                "Remove",
                                destructive = true,
                            ) { app.scope.launch { app.store.themes.remove(spec.id) } }
                        })
                    } else null,
                ),
            ),
        )
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN ->
                sel.move(e.action, items.size, columns).let { if (it == NavResult.IGNORED && e.action != NavAction.UP) NavResult.BLOCKED else it }
            NavAction.SELECT -> { current?.let(::open); NavResult.ACTIVATED }
            NavAction.CONTEXT -> { current?.let(::options); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 560.dp
        val gap = Space.l
        val usable = maxWidth - Space.gutter * 2
        columns = ((usable + gap) / (300.dp + gap)).toInt().coerceIn(2, 5)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(Size.hudHeight + if (compact) Space.s else Space.l))
            Row(Modifier.padding(horizontal = Space.gutter), verticalAlignment = Alignment.Bottom) {
                FText("Themes", if (compact) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Spacer(Modifier.width(Space.l))
                FText(
                    listOfNotNull(
                        "${ThemePresets.all.size} built in",
                        prefs.customThemes.size.takeIf { it > 0 }?.let { "$it yours" },
                    ).joinToString("  ·  "),
                    Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
            }
            val grid = rememberLazyGridState()
            FollowSelection(grid, { sel.index }, anchor = 0.15f)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = grid,
                modifier = Modifier.weight(1f).fadingEdges(top = if (grid.canScrollBackward) 24.dp else 0.dp),
                contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = Size.hintHeight + Space.x4),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(Space.xl),
            ) {
                itemsIndexed(items, key = { _, item -> if (item is ThemeItem.Of) item.spec.id else "add" }) { i, item ->
                    val selected = i == sel.index && app.focusZone == FocusZone.CONTENT
                    val tap = {
                        app.focusZone = FocusZone.CONTENT
                        sel.index = i
                        open(item)
                    }
                    when (item) {
                        is ThemeItem.Of -> ThemeCard(item.spec, selected, active = item.spec.id == prefs.themeId, custom = item.custom, onClick = tap) {
                            sel.index = i
                            options(item)
                        }
                        ThemeItem.Add -> AddThemeCard(selected, tap)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(spec: ThemeSpec, selected: Boolean, active: Boolean, custom: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    Column {
        Tile(
            selected = selected,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f),
            glow = spec.palette.accent.toColor(),
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            ThemePreview(spec, animate = selected, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText(spec.name, Fuse.type.label, color = if (selected || active) c.text else c.text.copy(alpha = 0.88f), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (active) {
                Spacer(Modifier.width(Space.s))
                Row(
                    Modifier.clip(PillShape).background(c.accent.copy(alpha = 0.16f)).padding(horizontal = Space.s, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    FuseIcon(FuseIcons.Check, size = 12.dp, tint = c.accent)
                    FText("In use", Fuse.type.caption, color = c.accent, maxLines = 1)
                }
            }
        }
        FText(
            when {
                spec.author != null -> "by ${spec.author}"
                spec.tagline.isNotBlank() -> spec.tagline
                custom -> "Your theme"
                else -> ""
            },
            Fuse.type.caption, color = c.textMuted, maxLines = 1,
        )
    }
}

@Composable
private fun AddThemeCard(selected: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    Column {
        Tile(selected = selected, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f), onClick = onClick) {
            Box(
                Modifier.fillMaxSize().background(c.text.copy(alpha = 0.05f)).drawBehind {
                    // A dashed frame: a place for something new.
                    val inset = 10.dp.toPx()
                    val dash = 8.dp.toPx()
                    val r = CornerRadius(14.dp.toPx())
                    drawRoundRect(
                        c.text.copy(alpha = 0.18f),
                        topLeft = Offset(inset, inset),
                        size = size.copy(width = size.width - inset * 2, height = size.height - inset * 2),
                        cornerRadius = r,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f)),
                        ),
                    )
                },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        FuseIcon(FuseIcons.Plus, size = 22.dp, tint = c.accent)
                    }
                    Spacer(Modifier.height(Space.s))
                    FText("Add a theme", Fuse.type.label, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        FText("From a link, a file or text", Fuse.type.label, color = c.text.copy(alpha = 0.88f), maxLines = 1)
        FText("Make your own from any theme's file", Fuse.type.caption, color = c.textMuted, maxLines = 1)
    }
}

/**
 * A small picture of Fuse in [spec]: its background, the top bar, a title and a row of tiles with
 * the middle one chosen. Drawn in the theme itself, so colours, corners and focus are its own.
 * The background moves only while [animate].
 */
@Composable
internal fun ThemePreview(spec: ThemeSpec, animate: Boolean, modifier: Modifier = Modifier) {
    FuseTheme(spec = spec) {
        val c = Fuse.colors
        val accent = spec.palette.accent.toColor()
        Box(modifier.background(c.ink)) {
            AmbientBackground(
                if (spec.background == BackgroundStyle.HERO) BackgroundStyle.SOLID else spec.background,
                accent,
                Modifier.fillMaxSize(),
                ambient = spec.ambient,
                animate = animate && Fuse.motion.ambient,
                fps = 24,
            )
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                // The top bar: a mark, the sections (the first one chosen) and the clock.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(c.text.copy(alpha = 0.9f)))
                    Spacer(Modifier.width(10.dp))
                    for (i in 0 until 3) {
                        Box(
                            Modifier
                                .padding(end = 7.dp)
                                .size(width = if (i == 0) 22.dp else 18.dp, height = 4.dp)
                                .clip(PillShape)
                                .background(if (i == 0) c.text else c.text.copy(alpha = 0.35f)),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(width = 16.dp, height = 4.dp).clip(PillShape).background(c.text.copy(alpha = 0.6f)))
                }
                Spacer(Modifier.weight(1f))
                FText(spec.name, Fuse.type.titleSmall.copy(fontSize = 15.sp), color = c.text, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Box(Modifier.size(width = 54.dp, height = 3.dp).clip(PillShape).background(c.textMuted.copy(alpha = 0.7f)))
                Spacer(Modifier.height(9.dp))
                // Three tiles; the middle one is chosen, lifted over its spark.
                val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
                    for (i in 0 until 3) {
                        val chosen = i == 1
                        Box(
                            Modifier
                                .size(if (chosen) 30.dp else 26.dp)
                                .drawBehind {
                                    if (chosen) {
                                        val y = size.height + 5.dp.toPx()
                                        drawRoundRect(accent, topLeft = Offset(size.width * 0.3f, y - 1.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width * 0.4f, 2.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()))
                                        drawSpark(Offset(size.width / 2, y), 1.4.dp.toPx(), accent, 0.9f)
                                    }
                                }
                                .clip(shape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            if (chosen) accent.copy(alpha = 0.55f) else c.surfaceRaised,
                                            if (chosen) c.surfaceRaised else c.surface,
                                        ),
                                    ),
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (spec.glass.enabled) {
                // Glass themes: a frosted panel at the right.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(width = 46.dp, height = 58.dp)
                        .clip(RoundedCornerShape(Fuse.geometry.panel))
                        .background(Color.White.copy(alpha = 0.10f)),
                )
            }
        }
    }
}

/** Offers the ways to add a theme: a link or pasted text, a file, or the website's list. */
fun AppState.addThemeChoice() {
    choice = ChoiceSpec(
        title = "Add a theme",
        message = "A theme is a small file anyone can write. Fuse shows what it is before adding it.",
        options = listOf(
            MenuAction("link", "From a link or text", FuseIcons.Link, detail = "Paste a link to a theme file, or the theme itself", onSelect = {
                choice = null
                textInput = TextInputSpec("A theme's link or text", "", "https://, or the theme itself", capitalize = false, doneLabel = "Add") { importThemeText(it) }
            }),
            MenuAction("file", "From a file", FuseIcons.Import, detail = "A .json theme file on this device", onSelect = {
                choice = null
                go(Route.PickFile(FilePurpose.THEME))
            }),
            MenuAction("find", "Find themes", FuseIcons.Globe, detail = "Themes people made, on Fuse's website", onSelect = {
                choice = null
                platform.openUrl(THEMES_URL)
            }),
        ),
    )
}

/** Adds a theme from [input]: a link to fetch, or the theme's own text. */
fun AppState.importThemeText(input: String) {
    val text = input.trim()
    if (text.isEmpty()) return
    scope.launch {
        if (text.startsWith("{")) {
            offerTheme(text, source = null)
        } else {
            toasts.show("Getting the theme")
            store.themes.fetch(text).fold({ offerTheme(it, source = text) }, { toasts.show(it.message ?: "Couldn't get the theme", ToastKind.ERROR) })
        }
    }
}

/** Adds the theme in the file at [path]. */
fun AppState.importThemeFile(path: String) {
    scope.launch {
        store.themes.readFile(path).fold({ offerTheme(it, source = path) }, { toasts.show(it.message ?: "Couldn't read that file", ToastKind.ERROR) })
    }
}

/** Shows what a theme is (and what Fuse repaired) and adds it when confirmed. */
private fun AppState.offerTheme(text: String, source: String?) {
    when (val r = store.themes.parse(text)) {
        is ThemeCodec.Failed -> toasts.show(r.reason, ToastKind.ERROR)
        is ThemeCodec.Imported -> {
            val spec = r.spec
            val exists = store.prefs.value.customThemes.any { it.id == spec.id }
            confirm = ConfirmSpec(
                title = if (exists) "Replace ${spec.name}?" else "Add ${spec.name}?",
                message = listOfNotNull(
                    spec.author?.let { "A theme by $it." },
                    spec.tagline.takeIf { it.isNotBlank() },
                    r.notes.takeIf { it.isNotEmpty() }?.joinToString(" "),
                ).joinToString("\n\n").ifEmpty { "A theme for Fuse." },
                confirmLabel = if (exists) "Replace and use" else "Add and use",
            ) {
                scope.launch {
                    store.themes.add(spec, text, source, apply = true)
                    toasts.show("Theme: ${spec.name}", ToastKind.SUCCESS)
                    if (navigator.current is Route.PickFile) back()
                }
            }
        }
    }
}
