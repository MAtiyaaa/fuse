package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.background.CrtOverlay
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.IconBadge
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.Reveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberReveal
import io.github.matiyaaa.fuse.ui.designsystem.effects.reveal
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.GridSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.squirclePath
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
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
import io.github.matiyaaa.fuse.ui.shell.components.ViewTab
import io.github.matiyaaa.fuse.ui.shell.components.ViewTabs
import kotlinx.coroutines.launch

/** Where people share themes: the Themes part of Fuse's website. */
const val THEMES_URL = "https://matiyaaa.github.io/fuse/#themes"

/** The views of the gallery. */
private enum class ThemeFilter(val label: String) { ALL("All"), DARK("Dark"), BRIGHT("Bright"), YOURS("Yours") }

/** A card in the gallery: a theme, the card that opens the studio, or the card that adds one. */
private sealed interface ThemeItem {
    val key: String

    data class Of(val spec: ThemeSpec, val custom: Boolean) : ThemeItem {
        override val key: String get() = spec.id
    }

    data object Make : ThemeItem {
        override val key: String = "themes.make"
    }

    data object Add : ThemeItem {
        override val key: String = "themes.add"
    }
}

private fun itemsFor(filter: ThemeFilter, customs: List<ThemeSpec>): List<ThemeItem> {
    fun built(list: List<ThemeSpec>) = list.map { ThemeItem.Of(it, custom = false) }
    fun yours(list: List<ThemeSpec>) = list.map { ThemeItem.Of(it, custom = true) }
    return when (filter) {
        ThemeFilter.ALL -> built(ThemePresets.all) + yours(customs) + ThemeItem.Make + ThemeItem.Add
        ThemeFilter.DARK -> built(ThemePresets.dark) + yours(customs.filter { it.palette.dark })
        ThemeFilter.BRIGHT -> built(ThemePresets.bright) + yours(customs.filter { !it.palette.dark })
        ThemeFilter.YOURS -> yours(customs) + ThemeItem.Make + ThemeItem.Add
    }
}

private fun countOf(filter: ThemeFilter, customs: List<ThemeSpec>): Int = when (filter) {
    ThemeFilter.ALL -> ThemePresets.all.size + customs.size
    ThemeFilter.DARK -> ThemePresets.dark.size + customs.count { it.palette.dark }
    ThemeFilter.BRIGHT -> ThemePresets.bright.size + customs.count { !it.palette.dark }
    ThemeFilter.YOURS -> customs.size
}

/**
 * Where the theme page was: the filter, whether focus is on the filters, the focused card (by key,
 * so it survives a change of filter), the studio while one is open, and how many times a theme was
 * put to use (the stage's flourish). Kept by the navigator, so a file picker and back returns here.
 */
@Stable
private class ThemesView(focused: String) {
    var filter by mutableStateOf(ThemeFilter.ALL)
    var inFilters by mutableStateOf(false)
    var focusedKey by mutableStateOf(focused)
    var studio by mutableStateOf<StudioState?>(null)
    var flourish by mutableIntStateOf(0)
    val selection = GridSelection()

    /** Columns of the gallery as last laid out, for moving by controller. */
    var columns = 3

    /** The theme the stage showed last, kept while the card that adds a theme is focused. */
    var lastShown: ThemeSpec? = null
}

/**
 * The theme page: a stage with a large live picture of Fuse in the focused theme, the facts of that
 * theme under it, and beside it the gallery of every theme (filtered by All, Dark, Bright or Yours)
 * ending in the cards that make or add one. Confirm uses a theme at once; Options copies it as a
 * file, removes an added one, or opens it in the studio.
 *
 * The studio is a mode of this page: the gallery gives way to a column of controls (accent,
 * background, corners, focus, motion, sound, glass, CRT) while the stage shows the result live, and
 * Save names it and adds it to your themes, written as a theme file like any other.
 */
@Composable
fun ThemesScreen(app: AppState) {
    val prefs by app.store.prefs.collectAsState()
    val view = rememberRouteState(app.navigator, "themes") { ThemesView(prefs.themeId) }
    val customs = prefs.customThemes
    val items = remember(view.filter, customs) { itemsFor(view.filter, customs) }
    val found = items.indexOfFirst { it.key == view.focusedKey }
    val focusIndex = if (found >= 0) found else items.indexOfFirst { it.key == prefs.themeId }.coerceAtLeast(0)
    val current = items.getOrNull(focusIndex)
    LaunchedEffect(current?.key) { current?.let { if (view.focusedKey != it.key) view.focusedKey = it.key } }
    val studio = view.studio
    val inUseSpec = prefs.theme

    fun use(spec: ThemeSpec) {
        if (spec.id == app.store.prefs.value.themeId) return
        app.store.updatePrefs { it.withTheme(spec) }
        view.flourish++
        app.toasts.show("Now using ${spec.name}", ToastKind.SUCCESS)
    }
    fun openStudio(spec: ThemeSpec, custom: Boolean) {
        view.inFilters = false
        view.studio = StudioState.of(spec, custom, if (custom) app.store.themes.export(spec) else null)
    }
    fun leaveStudio(s: StudioState) {
        if (!s.changed) {
            view.studio = null
            return
        }
        app.confirm = ConfirmSpec(
            "Leave without saving?",
            "Your changes to this theme aren't kept.",
            "Leave",
            destructive = true,
        ) { view.studio = null }
    }
    fun open(item: ThemeItem): NavResult = when (item) {
        is ThemeItem.Of -> if (item.spec.id == prefs.themeId) NavResult.BLOCKED else { use(item.spec); NavResult.ACTIVATED }
        ThemeItem.Make -> { openStudio(inUseSpec, custom = customs.any { it.id == inUseSpec.id }); NavResult.ACTIVATED }
        ThemeItem.Add -> { app.addThemeChoice(); NavResult.ACTIVATED }
    }
    fun options(item: ThemeItem.Of) {
        val spec = item.spec
        val inUse = spec.id == app.store.prefs.value.themeId
        app.openContextMenu(
            ContextMenuSpec(
                title = spec.name,
                subtitle = spec.author?.let { "Theme by $it" } ?: if (item.custom) "Yours" else "Built in",
                icon = FuseIcons.Palette,
                actions = listOfNotNull(
                    if (!inUse) MenuAction("use", "Use this theme", FuseIcons.Check, onSelect = { app.closeOverlays(); use(spec) }) else null,
                    MenuAction(
                        "studio",
                        if (item.custom) "Change it" else "Make your own from it",
                        FuseIcons.Paintbrush,
                        detail = if (item.custom) "Its colour, background, corners and more" else "Start from ${spec.name} and change what you like",
                        onSelect = { app.closeOverlays(); openStudio(spec, item.custom) },
                    ),
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

    val inUse = (current as? ThemeItem.Of)?.spec?.id == prefs.themeId
    LaunchedEffect(current?.key, view.inFilters, inUse, studio == null) {
        app.hero = null
        if (view.studio != null) return@LaunchedEffect
        app.hints = when {
            view.inFilters -> listOf(Hint(HintButton.DPAD, "Filter"))
            current == ThemeItem.Make -> listOf(Hint(HintButton.CONFIRM, "Make your own"))
            current == ThemeItem.Add -> listOf(Hint(HintButton.CONFIRM, "Add a theme"))
            inUse -> listOf(Hint(HintButton.OPTIONS, "Options"))
            else -> listOf(Hint(HintButton.CONFIRM, "Use"), Hint(HintButton.OPTIONS, "Options"))
        }
    }

    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen && studio == null) { e ->
        val filters = ThemeFilter.entries
        if (view.inFilters) {
            val at = view.filter.ordinal
            return@InputLayer when (e.action) {
                NavAction.LEFT -> if (at > 0) { view.filter = filters[at - 1]; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (at < filters.lastIndex) { view.filter = filters[at + 1]; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN, NavAction.SELECT -> { view.inFilters = false; NavResult.MOVED }
                else -> NavResult.IGNORED
            }
        }
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.LEFT, NavAction.RIGHT, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val sel = view.selection
                sel.index = focusIndex
                val r = sel.move(e.action, items.size, view.columns)
                when {
                    r == NavResult.MOVED -> { view.focusedKey = items[sel.index].key; NavResult.MOVED }
                    r == NavResult.IGNORED && e.action == NavAction.UP -> { view.inFilters = true; NavResult.MOVED }
                    r == NavResult.IGNORED -> NavResult.BLOCKED
                    else -> r
                }
            }
            NavAction.SELECT -> current?.let(::open) ?: NavResult.BLOCKED
            NavAction.CONTEXT -> (current as? ThemeItem.Of)?.let { options(it); NavResult.ACTIVATED } ?: NavResult.BLOCKED
            else -> NavResult.IGNORED
        }
    }

    // What the stage shows: the studio's draft, the focused theme, the theme a new one would start
    // from, or (on the card that adds one) whatever it showed last.
    val look = studio?.let { rememberStudioLook(it) }
    val stageSpec: ThemeSpec = when {
        look != null -> look.spec
        current is ThemeItem.Of -> current.spec
        current == ThemeItem.Make -> inUseSpec
        else -> view.lastShown ?: inUseSpec
    }
    view.lastShown = stageSpec.takeIf { studio == null && current is ThemeItem.Of } ?: view.lastShown
    val scene = rememberStageScene(app)
    val reveal = rememberReveal(view)

    val stage: @Composable (Modifier) -> Unit = { m -> ThemeStage(stageSpec, scene, m, flourish = view.flourish, spotlight = studio?.let { spotlightOf(it) }) }
    val facts: @Composable (Modifier, Boolean) -> Unit = { m, compact ->
        val f = when {
            look != null -> factsOf(look.spec, key = "studio")
            current is ThemeItem.Of -> factsOf(
                current.spec,
                line = listOfNotNull(current.spec.author?.let { "by $it" }, current.spec.tagline.takeIf { it.isNotBlank() }).joinToString("  ·  "),
                inUse = current.spec.id == prefs.themeId,
                yours = current.custom,
            )
            current == ThemeItem.Make -> factsOf(
                inUseSpec,
                key = ThemeItem.Make.key,
                title = "Make your own",
                line = "Start from ${inUseSpec.name} and change its colour, background, corners and more",
            )
            else -> Facts(
                key = ThemeItem.Add.key,
                title = "Add a theme",
                line = "A theme is a small file anyone can write. Fuse shows what it is before adding it",
                palette = null,
                secondary = null,
                traits = listOf(Trait(FuseIcons.Link, "A link or text"), Trait(FuseIcons.Import, "A file"), Trait(FuseIcons.Globe, "Fuse's website")),
            )
        }
        ThemeFacts(f, m, compact)
    }
    // Every filter shows its count where there is room; a narrow pane counts the active one only.
    val filters: @Composable (Modifier, Boolean) -> Unit = { m, narrow ->
        ViewTabs(
            items = ThemeFilter.entries.map { f ->
                ViewTab(f.label, badge = countOf(f, customs).takeIf { it > 0 && (!narrow || f == view.filter) }?.toString())
            },
            active = view.filter.ordinal,
            focused = view.filter.ordinal.takeIf { view.inFilters && app.focusZone == FocusZone.CONTENT && studio == null },
            onSelect = { i ->
                app.focusZone = FocusZone.CONTENT
                view.filter = ThemeFilter.entries[i]
                view.inFilters = false
            },
            modifier = m,
            gutter = Space.m,
        )
    }
    val studioHeader: @Composable (StudioState, Modifier) -> Unit = { s, m ->
        Column(m) {
            FText(if (s.editing != null) "Change ${s.editing.name}" else "Make your own", Fuse.type.titleSmall, maxLines = 1)
            Spacer(Modifier.height(Space.xxs))
            FText("Starting from ${s.base.name}. Nothing changes until you save", Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 1)
        }
    }
    val save: (StudioState) -> Unit = { s -> app.saveStudioTheme(s) { id -> view.studio = null; view.filter = ThemeFilter.YOURS; view.focusedKey = id; view.flourish++ } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxWidth < maxHeight
        val short = maxHeight < SHORT_BELOW
        val gutter = if (maxWidth < COMPACT_WIDTH) Space.gutterCompact else Space.gutter
        val contentWidth = maxWidth - gutter * 2
        val top = Size.hudHeight + if (short) Space.s else Space.l
        val title: @Composable (Modifier) -> Unit = { m ->
            Row(m, verticalAlignment = Alignment.Bottom) {
                FText("Themes", if (short || portrait) Fuse.type.title else Fuse.type.display, maxLines = 1)
                Spacer(Modifier.width(Space.l))
                FText(
                    listOfNotNull(
                        "${ThemePresets.all.size} built in",
                        customs.size.takeIf { it > 0 }?.let { "$it yours" },
                    ).joinToString("  ·  "),
                    Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = Space.xxs),
                )
            }
        }
        val grid: @Composable (Modifier, Dp, Int) -> Unit = { m, width, revealFrom ->
            val cardMin = maxOf(CARD_MIN, contentWidth * CARD_SHARE)
            val gap = Space.l
            val columns = ((width + gap) / (cardMin + gap)).toInt().coerceIn(2, 5)
            view.columns = columns
            FilterSwitch(view.filter, m) { f ->
                val shown = f == view.filter
                Gallery(
                    app = app,
                    view = view,
                    filter = f,
                    items = if (shown) items else remember(f, customs) { itemsFor(f, customs) },
                    focusIndex = if (shown) focusIndex else -1,
                    columns = columns,
                    gap = gap,
                    inUseId = prefs.themeId,
                    reveal = reveal,
                    revealFrom = revealFrom,
                    onOpen = { open(it) },
                    onOptions = { options(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(top))
                title(Modifier.padding(horizontal = gutter).reveal(reveal, 0))
                Spacer(Modifier.height(Space.m))
                stage(Modifier.padding(horizontal = gutter).fillMaxWidth().reveal(reveal, 1))
                Spacer(Modifier.height(Space.m))
                facts(Modifier.padding(horizontal = gutter).fillMaxWidth().reveal(reveal, 2), short)
                Spacer(Modifier.height(Space.m))
                PaneSwitch(studio, Modifier.weight(1f)) { s ->
                    if (s == null) {
                        Column {
                            filters(Modifier.offset(x = gutter - Space.m).reveal(reveal, 3), contentWidth < NARROW_TABS)
                            grid(Modifier.weight(1f).padding(horizontal = gutter), contentWidth, 4)
                        }
                    } else {
                        Column(Modifier.padding(horizontal = gutter)) {
                            studioHeader(s, Modifier.padding(start = Space.s))
                            Spacer(Modifier.height(Space.s))
                            StudioPanel(app, s, look ?: rememberStudioLook(s), Modifier.weight(1f).padding(bottom = Size.hintHeight + Space.s), active = s === studio, compact = true, narrow = true, onSave = { save(s) }, onLeave = { leaveStudio(s) })
                        }
                    }
                }
            }
        } else {
            val headerHeight = Space.x3
            val factsHeight = if (short) FACTS_SHORT else FACTS
            val factsGap = if (short) Space.m else Space.l
            val paneGap = if (short) Space.xl else Space.xxl
            val room = maxHeight - top - headerHeight - Space.l - factsGap - factsHeight - Size.hintHeight - Space.s
            val stageWidth = minOf(contentWidth * STAGE_SHARE, room * (16f / 9f)).coerceAtLeast(STAGE_MIN)
            val sideWidth = contentWidth - stageWidth - paneGap
            Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
                Spacer(Modifier.height(top))
                Row(Modifier.fillMaxWidth().height(headerHeight), verticalAlignment = Alignment.Bottom) {
                    title(Modifier.width(stageWidth).reveal(reveal, 0))
                    Spacer(Modifier.width(paneGap))
                    Box(Modifier.weight(1f)) {
                        PaneSwitch(studio, Modifier.fillMaxWidth()) { s ->
                            if (s == null) filters(Modifier.offset(x = -Space.m).reveal(reveal, 1), sideWidth < NARROW_TABS) else studioHeader(s, Modifier.padding(start = Space.s, bottom = Space.xxs))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.width(stageWidth).padding(top = Space.l)) {
                        stage(Modifier.fillMaxWidth().reveal(reveal, 1))
                        Spacer(Modifier.height(factsGap))
                        facts(Modifier.fillMaxWidth().reveal(reveal, 2), short)
                    }
                    Spacer(Modifier.width(paneGap))
                    PaneSwitch(studio, Modifier.weight(1f).fillMaxSize()) { s ->
                        if (s == null) {
                            grid(Modifier.fillMaxSize(), sideWidth, 2)
                        } else {
                            StudioPanel(
                                app, s, look ?: rememberStudioLook(s),
                                Modifier.fillMaxSize().padding(top = Space.l, bottom = Size.hintHeight + Space.s),
                                active = s === studio,
                                compact = short,
                                narrow = sideWidth < STUDIO_WIDE,
                                onSave = { save(s) },
                                onLeave = { leaveStudio(s) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** On short screens (handhelds, phones held sideways) the page tightens. */
private val SHORT_BELOW = 560.dp

/** Below this width the page uses the compact gutter. */
private val COMPACT_WIDTH = 600.dp

/** The stage takes up to half the width; the gallery the rest. */
private const val STAGE_SHARE = 0.52f
private val STAGE_MIN = 200.dp

/** Height kept under the stage for its facts: title, line and chips; short screens drop the line. */
private val FACTS = Size.badge + Space.xxs + Space.xl + Space.m + Size.chipCompact
private val FACTS_SHORT = Size.badge + Space.s + Size.chipCompact

/** Gallery cards are at least this wide, or this share of the content on large screens. */
private val CARD_MIN = 150.dp
private const val CARD_SHARE = 0.12f

/** Below this width the filters count only the one that is active, so all four fit. */
private val NARROW_TABS = 440.dp

/** The studio shows its swatch strip and icon wells from this column width up. */
private val STUDIO_WIDE = 496.dp

/**
 * The right-hand pane: the gallery, or the studio while one is open. Switching slides the new pane
 * in a little from the right as it fades (the studio is a step further in), and only fades under
 * Reduced motion.
 */
@Composable
private fun PaneSwitch(studio: StudioState?, modifier: Modifier, content: @Composable (StudioState?) -> Unit) {
    val motion = Fuse.motion
    AnimatedContent(
        targetState = studio,
        modifier = modifier,
        contentKey = { it != null },
        transitionSpec = {
            val toStudio = targetState != null
            val shift = if (motion.reduced) 0 else 1
            (fadeIn(motion.enter(Durations.BASE)) + slideInHorizontally(motion.enter(Durations.SLOW)) { (if (toStudio) it / 12 else -it / 12) * shift }) togetherWith
                fadeOut(motion.exit(Durations.FAST))
        },
        label = "themesPane",
    ) { s -> content(s) }
}

/**
 * The gallery under a new filter: the cards slide a little the way the filter moved as they fade
 * in, the old ones fade out quicker; only fades under Reduced motion.
 */
@Composable
private fun FilterSwitch(filter: ThemeFilter, modifier: Modifier, content: @Composable (ThemeFilter) -> Unit) {
    val motion = Fuse.motion
    AnimatedContent(
        targetState = filter,
        modifier = modifier,
        transitionSpec = {
            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
            val shift = if (motion.reduced) 0 else dir
            (fadeIn(motion.enter(Durations.BASE)) + slideInHorizontally(motion.enter(Durations.SLOW)) { it / 16 * shift }) togetherWith
                (fadeOut(motion.exit(Durations.FAST)) + slideOutHorizontally(motion.exit(Durations.FAST)) { -it / 16 * shift })
        },
        label = "themesFilter",
    ) { f -> content(f) }
}

/**
 * The gallery: theme cards in a grid that follows the selection, each a small picture of Home in
 * that theme with its name set where Home sets a title, then the cards that make or add one. Cards
 * lift with the spark of the theme in use, like every tile; the one in use carries a check and the
 * ones you made or added a mark of their own. Yours ends with a line on what belongs there.
 */
@Composable
private fun Gallery(
    app: AppState,
    view: ThemesView,
    filter: ThemeFilter,
    items: List<ThemeItem>,
    focusIndex: Int,
    columns: Int,
    gap: Dp,
    inUseId: String,
    reveal: Reveal,
    revealFrom: Int,
    onOpen: (ThemeItem) -> Unit,
    onOptions: (ThemeItem.Of) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grid = remember(filter) { LazyGridState() }
    // A gallery on its way out (the filter changed) has no selection to follow.
    FollowSelection(grid, { focusIndex.coerceAtLeast(0) }, anchor = 0.12f, enabled = { focusIndex >= 0 })
    val bleed = Space.l
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        // The grid reaches past the pane on both sides, so a lifted card's ring and glow are never cut.
        modifier = modifier.bleed(bleed).fadingEdges(grid, top = Space.xl, bottom = Space.xl),
        contentPadding = PaddingValues(start = bleed, end = bleed, top = Space.l, bottom = Size.hintHeight + Space.x4),
        horizontalArrangement = Arrangement.spacedBy(gap),
        // Room under each row for the focused card's spark bar before the next row starts.
        verticalArrangement = Arrangement.spacedBy(Size.sparkClearance),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
            val focused = i == focusIndex && !view.inFilters && view.studio == null
            val selected = focused && app.focusZone == FocusZone.CONTENT
            val tap = {
                app.focusZone = FocusZone.CONTENT
                if (i == focusIndex && !view.inFilters) {
                    onOpen(item)
                } else {
                    view.inFilters = false
                    view.focusedKey = item.key
                }
            }
            val m = Modifier.reveal(reveal, revealFrom + i / columns)
            when (item) {
                is ThemeItem.Of -> ThemeCard(
                    item.spec,
                    selected = selected,
                    inUse = item.spec.id == inUseId,
                    yours = item.custom,
                    onClick = tap,
                    onLongClick = {
                        app.focusZone = FocusZone.CONTENT
                        view.inFilters = false
                        view.focusedKey = item.key
                        onOptions(item)
                    },
                    modifier = m,
                )
                ThemeItem.Make -> CreateCard("Make your own", make = true, selected = selected, onClick = tap, modifier = m)
                ThemeItem.Add -> CreateCard("Add a theme", make = false, selected = selected, onClick = tap, modifier = m)
            }
        }
        if (filter == ThemeFilter.YOURS) {
            item(key = "themes.yours.note", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = Space.xs).reveal(reveal, revealFrom + items.size / columns + 1), verticalAlignment = Alignment.Top) {
                    FuseIcon(FuseIcons.Info, size = Size.iconS, tint = Fuse.colors.textFaint)
                    Spacer(Modifier.width(Space.s))
                    FText(
                        "Make your own from any theme, or add one someone shared. Any theme can be copied as a file to change or pass on.",
                        Fuse.type.caption, color = Fuse.colors.textMuted, maxLines = 3,
                    )
                }
            }
        }
    }
}

/** Measures the element [x] wider on each side than it is given and lets it hang out evenly. */
private fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    val px = x.roundToPx()
    val wide = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = constraints.minWidth + px * 2, maxWidth = constraints.maxWidth + px * 2)
    } else {
        constraints
    }
    val p = measurable.measure(wide)
    layout((p.width - px * 2).coerceAtLeast(0), p.height) { p.place(-px, 0) }
}

/**
 * A theme in the gallery: its picture as a tile, lifting with the spark of the theme in use like
 * every tile. Its marks sit in the top corner, as marks on art do: a check in the accent for the
 * theme in use (it pops in when you choose it), and a person for one you made or added.
 */
@Composable
private fun ThemeCard(
    spec: ThemeSpec,
    selected: Boolean,
    inUse: Boolean,
    yours: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    Tile(
        selected = selected,
        // The card and its name read as one item, selected with the card, for screen readers and the UI audit.
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { this.selected = selected },
        glow = spec.palette.accent.toColor(),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        ThemePreview(spec, animate = false, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopEnd).padding(Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (yours) {
                IconBadge(FuseIcons.UserRound, Modifier.semantics { contentDescription = "Yours" }, tint = c.onArt, size = Size.badge)
            }
            AnimatedVisibility(
                visible = inUse,
                enter = fadeIn(motion.fade(Durations.FAST)) + scaleIn(if (motion.reduced) motion.fade(Durations.FAST) else spring(dampingRatio = 0.55f, stiffness = 520f), initialScale = if (motion.reduced) 1f else 0.4f),
                exit = fadeOut(motion.exit(Durations.FAST)),
            ) {
                IconBadge(FuseIcons.Check, Modifier.semantics { contentDescription = "In use" }, tint = c.onAccent, background = c.accent, size = Size.badge)
            }
        }
    }
}

/**
 * The cards at the end of the gallery, each saying what it does: Make your own over a soft wash of
 * the accent with a few swatches, Add a theme in a dashed frame, a place for something new.
 */
@Composable
private fun CreateCard(label: String, make: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Tile(
        selected = selected,
        modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).semantics { this.selected = selected },
        onClick = onClick,
    ) {
        val frame = c.text.copy(alpha = if (c.isDark) 0.2f else 0.26f)
        val wash = c.accent
        Box(
            Modifier
                .fillMaxSize()
                .background(c.surfaceDim)
                .drawWithCache {
                    val glow = Brush.radialGradient(
                        0f to wash.copy(alpha = if (make) 0.22f else 0.08f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.5f, size.height * 0.4f),
                        radius = size.maxDimension * 0.6f,
                    )
                    val inset = Space.s.toPx()
                    val dash = Space.s.toPx()
                    val stroke = Stroke(Size.stroke.toPx() * 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f)))
                    val r = minOf(size.width, size.height) * 0.12f
                    onDrawBehind {
                        drawRect(glow)
                        if (!make) {
                            drawRoundRect(frame, Offset(inset, inset), size.copy(width = size.width - inset * 2, height = size.height - inset * 2), CornerRadius(r), style = stroke)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(Size.chip).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    FuseIcon(if (make) FuseIcons.Paintbrush else FuseIcons.Plus, size = Size.iconM, tint = c.accent)
                }
                Spacer(Modifier.height(Space.s))
                FText(label, Fuse.type.label, color = if (selected) c.text else c.text.copy(alpha = 0.88f), maxLines = 1)
                // Both cards keep the same block height, so their labels line up across the row.
                Spacer(Modifier.height(Space.s))
                if (make) SwatchDots() else Spacer(Modifier.height(Size.dot))
            }
        }
    }
}

/** A row of little colour dots: the studio's swatches, as a promise of what is inside. */
@Composable
private fun SwatchDots() {
    val dots = remember { listOf(0xFFFF6A3D, 0xFFFF9CC2, 0xFFB79CFF, 0xFF8CCBFF, 0xFF43E3D3, 0xFF86DC5C).map { Color(it) } }
    val edge = Fuse.colors.hairlineStrong
    Spacer(
        Modifier.size(width = Size.dot * dots.size + Space.xs * (dots.size - 1), height = Size.dot).drawBehind {
            val d = size.height
            val step = d + Space.xs.toPx()
            dots.forEachIndexed { i, col ->
                val center = Offset(d / 2 + step * i, d / 2)
                drawCircle(col, d / 2, center)
                drawCircle(edge, d / 2, center, style = Stroke(Size.stroke.toPx()))
            }
        },
    )
}

/**
 * A small picture of Fuse in [spec], drawn in the theme itself: its background (moving only while
 * [animate], and never against the Motion setting or Low Power Mode), the top line, the theme's name
 * where Home sets a game's title, and a shelf of wide tiles with the first one lifted in the theme's
 * own focus style. The layout is Home's, scaled to the width it is given; the name is set larger
 * than scale so it reads on a card. The stage shows the whole of Home; this is its thumbnail.
 */
@Composable
internal fun ThemePreview(spec: ThemeSpec, animate: Boolean, modifier: Modifier = Modifier) {
    val quality = Fuse.quality
    val glyphs = Fuse.glyphs
    val outerMotion = Fuse.motion
    val contrast = Fuse.look.highContrastFocus
    FuseTheme(spec = spec, quality = quality, glyphs = glyphs, highContrastFocus = contrast) {
        val c = Fuse.colors
        val moving = animate && !outerMotion.reduced && quality.animatedBackground && Fuse.motion.ambient
        BoxWithConstraints(modifier.background(c.ink)) {
            val w = maxWidth
            val h = maxHeight
            AmbientBackground(
                if (spec.background == BackgroundStyle.HERO) BackgroundStyle.SOLID else spec.background,
                c.accent,
                Modifier.fillMaxSize(),
                ambient = spec.ambient,
                animate = moving,
                fps = 24,
            )
            MiniChrome(Modifier.fillMaxSize())
            val nameSize = with(LocalDensity.current) { (w * NAME_SHARE).toSp() }.value.coerceIn(10f, 24f).sp
            FText(
                spec.name,
                Fuse.type.titleSmall.copy(fontSize = nameSize, lineHeight = nameSize * 1.15f),
                color = c.text,
                maxLines = 1,
                modifier = Modifier.padding(start = w * GUTTER_SHARE, end = w * GUTTER_SHARE, top = h * TITLE_TOP),
            )
            if (spec.crt.enabled && quality.crtShader) CrtOverlay(spec.crt)
        }
    }
}

/** Proportions of Home on its 1280 x 720 screen, as shares of a preview's width or height. */
private const val GUTTER_SHARE = 40f / 1280f
private const val NAME_SHARE = 0.068f
private const val TITLE_TOP = 0.215f

/**
 * Everything in a preview but its background and name, drawn in one pass from paths built once per
 * size: the top line (mark, tabs with the first one active, status), the title's meta line, the
 * first shelf (its first tile lifted with the theme's focus: a glow and bar, a ring, or a wide bar)
 * and the fade at the foot of the screen. The shelves below and the hint line are left out: on a
 * card they are only noise. Thin marks keep a minimum weight so they read on a small card.
 */
@Composable
private fun MiniChrome(modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val look = Fuse.look
    val style = look.focusStyle
    val ring = style == FocusStyle.RING || look.highContrastFocus
    val fraction = Fuse.geometry.tileCornerFraction
    val accent = c.accent
    val text = c.text
    val muted = c.textMuted
    val raised = c.surfaceRaised
    val surface = c.surface
    val ink = c.ink
    val focus = c.focus
    val dark = c.isDark
    val shadow = c.shadow
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            // One unit is one dp of the 1280 dp wide screen this is a picture of.
            val u = w / 1280f
            val hair = 1.dp.toPx()
            fun thick(v: Float) = maxOf(v * u, hair * 1.5f)
            val gutter = 40f * u

            // Top line.
            val hudY = 32f * u
            val bar = thick(9f)
            val markSize = 22f * u
            val tabs = listOf(78f, 96f, 84f, 120f)
            val tabX = FloatArray(tabs.size)
            var x = 130f * u
            for (i in tabs.indices) {
                tabX[i] = x
                x += (tabs[i] + 44f) * u
            }

            // The title's meta line and the shelf titles.
            val metaY = h * 0.43f
            val label1 = h * 0.463f
            val tileTop = h * 0.49f
            val wideH = 171f * u
            val wideW = 304f * u
            val gap = 24f * u
            val wide = squirclePath(wideW, wideH, minOf(wideW, wideH) * fraction, 0.6f)
            val lift = 1.07f
            val liftedW = wideW * lift
            val liftedH = wideH * lift
            val lifted = squirclePath(liftedW, liftedH, minOf(liftedW, liftedH) * fraction, 0.6f)
            val ringGap = maxOf(3f * u, hair) + maxOf(2f * u, hair * 1.2f) / 2
            val ringPath = squirclePath(liftedW + ringGap * 2, liftedH + ringGap * 2, minOf(liftedW, liftedH) * fraction + ringGap, 0.6f)
            val ringStroke = Stroke(maxOf(2f * u, hair * 1.2f))
            val edge = Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.3f else 0.7f), 0.4f to Color.Transparent, startY = 0f, endY = wideH)
            val edgeStroke = Stroke(hair)
            val focusFill = Brush.verticalGradient(listOf(lerp(raised, accent, 0.62f), lerp(surface, accent, 0.22f)), startY = 0f, endY = liftedH)
            val restFill = Brush.verticalGradient(listOf(lerp(raised, text, if (dark) 0.06f else 0.03f), surface), startY = 0f, endY = wideH)
            val glowColor = if (style == FocusStyle.GLOW) accent else shadow
            val glow = Brush.radialGradient(
                0f to glowColor.copy(alpha = if (style == FocusStyle.GLOW) 0.45f else 0.35f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = liftedW * 0.62f,
            )
            val barH = thick(3f * (if (style == FocusStyle.BAR) 1.3f else 1f) * 1.6f)
            val fade = Brush.verticalGradient(0.86f to Color.Transparent, 0.93f to ink.copy(alpha = 0.78f), 1f to ink.copy(alpha = 0.94f), startY = 0f, endY = h)

            onDrawBehind {
                // Top line: mark, tabs (the first active, with its accent underline) and status.
                drawRoundRect(text.copy(alpha = 0.9f), Offset(gutter, hudY - markSize / 2), androidx.compose.ui.geometry.Size(markSize, markSize), CornerRadius(markSize * 0.28f))
                for (i in tabs.indices) {
                    val tint = if (i == 0) text else text.copy(alpha = 0.38f)
                    drawCircle(tint, maxOf(8f * u, hair * 1.6f), Offset(tabX[i] + 8f * u, hudY))
                    drawRoundRect(tint, Offset(tabX[i] + 26f * u, hudY - bar / 2), androidx.compose.ui.geometry.Size(tabs[i] * u - 26f * u, bar), CornerRadius(bar / 2))
                }
                drawRoundRect(accent, Offset(tabX[0] + 20f * u, hudY + 20f * u), androidx.compose.ui.geometry.Size(30f * u, thick(3f)), CornerRadius(thick(3f) / 2))
                drawRoundRect(text.copy(alpha = 0.55f), Offset(w - gutter - 210f * u, hudY - bar / 2), androidx.compose.ui.geometry.Size(70f * u, bar), CornerRadius(bar / 2))
                drawRoundRect(text, Offset(w - gutter - 110f * u, hudY - bar * 0.65f), androidx.compose.ui.geometry.Size(110f * u, bar * 1.3f), CornerRadius(bar * 0.65f))

                // The meta line under the title, and the first shelf's title.
                drawRoundRect(muted.copy(alpha = 0.75f), Offset(gutter, metaY - bar / 2), androidx.compose.ui.geometry.Size(300f * u, bar), CornerRadius(bar / 2))
                drawRoundRect(muted.copy(alpha = 0.6f), Offset(gutter, label1 - bar / 2), androidx.compose.ui.geometry.Size(150f * u, bar * 0.8f), CornerRadius(bar / 2))

                // The first shelf: the focused tile lifted, its light under it, then the rest.
                for (i in 3 downTo 1) {
                    translate(gutter + (wideW + gap) * i + (liftedW - wideW) / 2, tileTop) {
                        drawPath(wide, restFill)
                        drawPath(wide, edge, style = edgeStroke)
                    }
                }
                val fx = gutter - (liftedW - wideW) / 2
                val fy = tileTop - (liftedH - wideH) / 2
                translate(fx + liftedW / 2, fy + liftedH * 0.78f) {
                    scale(1f, 0.55f, pivot = Offset.Zero) { drawCircle(glow, liftedW * 0.62f, Offset.Zero) }
                }
                translate(fx, fy) {
                    drawPath(lifted, focusFill)
                    drawPath(lifted, edge, style = edgeStroke)
                }
                if (ring) translate(fx - ringGap, fy - ringGap) { drawPath(ringPath, focus, style = ringStroke) }
                if (style != FocusStyle.RING) {
                    val bw = 28f * u * 2.2f * (if (style == FocusStyle.BAR) 1.4f else 1f)
                    drawRoundRect(accent, Offset(gutter + wideW / 2 - bw / 2, fy + liftedH + maxOf(7f * u, hair * 2f)), androidx.compose.ui.geometry.Size(bw, barH), CornerRadius(barH / 2))
                }

                // The fade under the hint line.
                drawRect(fade)
            }
        },
    )
}

// ------------------------------------------------------------------------------- the studio's save

/**
 * Names the studio's theme (the text input overlay) and keeps it: written as a theme file, read back
 * the way an added file is (so its colours are repaired and the result is exactly what was
 * previewed) and added to your themes in use. A name that one of your themes already has replaces
 * it, after asking, unless it is the theme being changed. [saved] gets the new theme's id.
 */
private fun AppState.saveStudioTheme(studio: StudioState, saved: (String) -> Unit) {
    val customs = store.prefs.value.customThemes
    val suggested = studio.editing?.name ?: uniqueName("My ${studio.base.name}", customs.map { it.name })
    textInput = TextInputSpec("Name your theme", suggested, "A name for your theme", doneLabel = "Save") { raw ->
        val name = raw.filter { it >= ' ' }.trim().take(ThemeNameMax)
        if (name.isEmpty()) {
            toasts.show("A theme needs a name", ToastKind.WARNING)
        } else {
            val id = ThemeCodec.CUSTOM_PREFIX + ThemeCodec.slug(name).ifEmpty { "theme" }
            val clash = customs.firstOrNull { it.id == id && it.id != studio.editing?.id }
            val keep = { keepStudioTheme(studio, name, id, saved) }
            if (clash != null) {
                confirm = ConfirmSpec(
                    "Replace ${clash.name}?",
                    "You already have a theme called ${clash.name}. Saving this one replaces it.",
                    "Replace",
                    destructive = true,
                ) { keep() }
            } else {
                keep()
            }
        }
    }
}

/** Theme names are kept to this many characters, as theme files are. */
private const val ThemeNameMax = 40

private fun AppState.keepStudioTheme(studio: StudioState, name: String, id: String, saved: (String) -> Unit) {
    val editing = studio.editing
    val tagline = if (editing != null && editing.author == null && editing.tagline.isNotBlank()) editing.tagline else "Made from ${studio.base.name}"
    val spec = studio.draft().copy(id = id, name = name, tagline = tagline, author = null)
    val json = ThemeCodec.encode(spec, extends = studio.extendsId)
    when (val r = store.themes.parse(json)) {
        is ThemeCodec.Failed -> toasts.show(r.reason, ToastKind.ERROR)
        is ThemeCodec.Imported -> scope.launch {
            store.themes.add(r.spec, json, source = null, apply = true)
            saved(r.spec.id)
            toasts.show("Saved $name. It's in use now", ToastKind.SUCCESS)
        }
    }
}

/** [base], or [base] with the first free number after it when one of [taken] already has it. */
private fun uniqueName(base: String, taken: List<String>): String {
    if (taken.none { it.equals(base, ignoreCase = true) }) return base
    var n = 2
    while (taken.any { it.equals("$base $n", ignoreCase = true) }) n++
    return "$base $n"
}

// ------------------------------------------------------------------------------- adding a theme

/** Offers the ways to add a theme: a link or pasted text, a file, or the website's list. */
fun AppState.addThemeChoice() {
    choice = ChoiceSpec(
        title = "Add a theme",
        message = "A theme is a small file anyone can write. Fuse shows what it is before adding it.",
        icon = FuseIcons.Palette,
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
