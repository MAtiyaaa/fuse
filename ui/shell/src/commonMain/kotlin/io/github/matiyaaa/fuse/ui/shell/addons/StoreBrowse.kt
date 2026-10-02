package io.github.matiyaaa.fuse.ui.shell.addons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.EmptyState
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.Tile
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.skeleton
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.ShelfSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ContextMenuSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.store.Availability
import io.github.matiyaaa.fuse.ui.shell.store.StoreApp
import io.github.matiyaaa.fuse.ui.shell.store.StoreState

/** Where the Store page is: its selection and the search being shown. */
internal class StoreBrowseState {
    val sel = ShelfSelection()
    var query by mutableStateOf("")
}

/** One row of the Store page. */
private sealed interface StoreRow {
    val key: String

    /** The page's buttons: search, update everything, check again. */
    data class Top(val buttons: List<TopButton>) : StoreRow {
        override val key = "top"
    }

    /** A shelf of apps under [title]; [featured] shelves are wider cards with a reason. */
    data class Shelf(
        override val key: String,
        val title: String,
        val apps: List<StoreApp>,
        val icon: ImageVector? = null,
        val featured: Map<String, String> = emptyMap(),
    ) : StoreRow
}

private data class TopButton(val label: String, val icon: ImageVector, val primary: Boolean = false, val loading: Boolean = false, val run: () -> Unit)

/**
 * The Store, inside Addons: the first time, the choice of edition; then the catalogue as shelves
 * a controller moves through like any page of Fuse. Updates first when there are any, then apps for
 * the systems in your library that have no emulator yet, what is installed, and every category of
 * the pack in its own order (apps only followed, "Track Only", last). A opens an app's page; X has
 * its options. While the catalogue first loads its shape shows; offline, the saved catalogue does,
 * with a calm note.
 */
@Composable
internal fun StoreContent(app: AppState, active: Boolean, topPadding: Dp) {
    val ops = app.store.appStore
    val state by ops.state.collectAsState()
    LaunchedEffect(state.variant) { ops.open() }
    val variant = state.variant
    if (variant == null) {
        StoreSetup(app, state.recommended, active, topPadding)
        return
    }
    val catalogue = state.catalogue
    if (catalogue == null) {
        StoreWaiting(app, state, active, topPadding)
        return
    }
    StoreShelves(app, state, active, topPadding)
}

@Composable
private fun StoreWaiting(app: AppState, state: StoreState, active: Boolean, topPadding: Dp) {
    val focused = active && app.focusZone == FocusZone.CONTENT
    val failed = !state.refreshing && state.refreshProblem != null
    InputLayer(enabled = focused && failed && !app.overlayOpen) { e ->
        if (e.action == NavAction.SELECT) { app.store.appStore.refresh(); NavResult.ACTIVATED } else NavResult.IGNORED
    }
    LaunchedEffect(focused, failed) { if (focused) app.hints = if (failed) listOf(Hint(HintButton.CONFIRM, "Try again")) else emptyList() }
    if (failed) {
        Box(Modifier.fillMaxSize().padding(top = topPadding), contentAlignment = Alignment.Center) {
            EmptyState(
                FuseIcons.CloudOff, "The Store couldn't load",
                message = (state.refreshProblem ?: "") + " The catalogue is saved the first time it loads, so after that the Store opens offline too.",
                actionLabel = "Try again", actionSelected = focused, actionIcon = FuseIcons.Refresh,
                onAction = { app.store.appStore.refresh() },
            )
        }
    } else {
        StoreSkeleton(Modifier.padding(top = topPadding + Space.l).padding(horizontal = Space.gutter))
    }
}

@Composable
private fun StoreShelves(app: AppState, state: StoreState, active: Boolean, topPadding: Dp) {
    val c = Fuse.colors
    val ops = app.store.appStore
    val catalogue = state.catalogue ?: return
    val page = rememberRouteState(app.navigator, "store") { StoreBrowseState() }
    val sel = page.sel
    val platforms by app.store.library.platforms.collectAsState()
    val focused = active && app.focusZone == FocusZone.CONTENT

    fun search() {
        app.textInput = TextInputSpec(title = "Search the Store", initial = page.query, placeholder = "An app, a system, a developer", capitalize = false, doneLabel = "Search") { q ->
            page.query = q.trim()
            sel.row = 1
            sel.setColumn("results", 0)
        }
    }

    val updates = state.updates
    val rows: List<StoreRow> = remember(state, platforms, page.query) {
        buildList {
            add(
                StoreRow.Top(
                    listOfNotNull(
                        TopButton(if (page.query.isEmpty()) "Search" else "Search: ${page.query}", FuseIcons.Search) { search() },
                        TopButton("Clear search", FuseIcons.SearchX) { page.query = "" }.takeIf { page.query.isNotEmpty() },
                        TopButton("Update all (${updates.size})", FuseIcons.CircleArrowDown, primary = true) { ops.updateAll() }.takeIf { updates.size > 1 },
                        TopButton(if (state.refreshing) "Checking" else "Check for updates", FuseIcons.Refresh, loading = state.refreshing) {
                            ops.refresh()
                            ops.checkInstalled(force = true)
                        },
                    ),
                ),
            )
            if (page.query.isNotEmpty()) add(StoreRow.Shelf("results", "Results", searchApps(catalogue.apps, page.query), FuseIcons.Search))
            if (updates.isNotEmpty()) add(StoreRow.Shelf("updates", "Updates", updates, FuseIcons.CircleArrowDown))
            val wanted = platforms.filter { it.gameCount > 0 && !it.emulatorInstalled }.sortedByDescending { it.gameCount }
            val reasons = LinkedHashMap<String, String>()
            for (p in wanted) {
                for (a in catalogue.apps) {
                    if (a.availability == Availability.INSTALLABLE && a.key !in state.installed && p.platform.id in a.systems && a.key !in reasons) {
                        reasons[a.key] = "For your ${p.platform.shortName} games"
                    }
                }
            }
            if (reasons.isNotEmpty()) {
                add(StoreRow.Shelf("featured", "For your library", reasons.keys.mapNotNull(state::app).take(8), FuseIcons.Sparkles, featured = reasons))
            }
            val installed = catalogue.apps.filter { it.key in state.installed }
            if (installed.isNotEmpty()) add(StoreRow.Shelf("installed", "Installed", installed, FuseIcons.PackageCheck))
            // Each app sits in its first category; apps only followed sit in Track Only, last.
            val (tracked, rest) = catalogue.categories.partition { it.trackOnly }
            fun shelfOf(a: StoreApp): String? =
                if (a.availability == Availability.TRACK_ONLY) tracked.firstOrNull()?.name ?: a.categories.firstOrNull() else a.categories.firstOrNull { c -> rest.any { it.name == c } }
            for (cat in rest + tracked) {
                val apps = catalogue.apps.filter { shelfOf(it) == cat.name }
                if (apps.isNotEmpty()) add(StoreRow.Shelf("cat:${cat.name}", cat.name, apps))
            }
        }
    }
    val keys = rows.map { it.key }
    fun sizeOf(key: String): Int = when (val r = rows.firstOrNull { it.key == key }) {
        is StoreRow.Top -> r.buttons.size
        is StoreRow.Shelf -> r.apps.size
        null -> 0
    }
    sel.clamp(keys, ::sizeOf)
    val row = rows.getOrNull(sel.row) ?: rows.first()
    val col = sel.column(row.key)
    val current = (row as? StoreRow.Shelf)?.apps?.getOrNull(col)

    fun openApp(a: StoreApp) = app.go(Route.StoreApp(a.key))
    fun options(a: StoreApp) {
        val action = storeAction(app, state, a)
        app.openContextMenu(
            ContextMenuSpec(
                title = a.name,
                subtitle = a.author.takeIf { it.isNotBlank() }?.let { "by $it" },
                actions = listOfNotNull(
                    MenuAction("main", action.label, action.icon, onSelect = { app.closeOverlays(); action.run() }).takeIf { !action.busy },
                    MenuAction("page", "Details", FuseIcons.Info, onSelect = { app.closeOverlays(); openApp(a) }),
                    MenuAction("uninstall", "Uninstall", FuseIcons.Trash, destructive = true, onSelect = { app.closeOverlays(); ops.uninstall(a.key) })
                        .takeIf { a.key in state.installed && state.jobs[a.key]?.active != true },
                    MenuAction("source", "View source", FuseIcons.External, detail = a.sourceHost, onSelect = { app.closeOverlays(); app.platform.openUrl(a.sourceUrl) }),
                ),
            ),
        )
    }
    fun tap(key: String, i: Int) {
        app.focusZone = FocusZone.CONTENT
        val r = keys.indexOf(key)
        sel.row = r
        sel.setColumn(key, i)
        when (val target = rows[r]) {
            is StoreRow.Top -> target.buttons.getOrNull(i)?.run?.invoke()
            is StoreRow.Shelf -> target.apps.getOrNull(i)?.let(::openApp)
        }
    }

    LaunchedEffect(row.key, current?.key, focused) {
        if (!focused) return@LaunchedEffect
        app.hero = null
        app.hints = if (current != null) listOf(Hint(HintButton.CONFIRM, "Details"), Hint(HintButton.OPTIONS, "Options")) else listOf(Hint(HintButton.CONFIRM, "Choose"))
        // An app in view has its newest release looked up, from the cache when it is fresh.
        current?.let { ops.check(it.key) }
    }

    InputLayer(enabled = focused && !app.overlayOpen) { e ->
        when (e.action) {
            NavAction.LEFT, NavAction.RIGHT -> sel.move(e.action, keys, ::sizeOf).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
            NavAction.UP, NavAction.DOWN -> sel.move(e.action, keys, ::sizeOf)
            NavAction.SELECT -> {
                when (row) {
                    is StoreRow.Top -> row.buttons.getOrNull(col)?.run?.invoke()
                    is StoreRow.Shelf -> current?.let(::openApp)
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> { current?.let(::options); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    val list = rememberLazyListState()
    FollowSelection(list, { sel.row }, anchor = 0.12f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 600.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(top = topPadding).fadingEdges(top = if (list.canScrollBackward) Space.xl else 0.dp),
            contentPadding = PaddingValues(top = if (compact) Space.s else Space.m, bottom = Size.hintHeight + Space.xl),
            verticalArrangement = Arrangement.spacedBy(if (compact) Space.l else Space.xl),
        ) {
            for ((ri, r) in rows.withIndex()) {
                item(key = r.key) {
                    val chosen = if (focused && sel.row == ri) sel.column(r.key) else -1
                    when (r) {
                        is StoreRow.Top -> StoreHeader(state, r.buttons, chosen, compact) { i -> tap(r.key, i) }
                        is StoreRow.Shelf -> AppShelf(
                            r, state, chosen, sel.column(r.key), compact,
                            iconOf = { ops.iconModel(it.key) },
                            onClick = { i -> tap(r.key, i) },
                            onLongClick = { i -> sel.row = ri; sel.setColumn(r.key, i); r.apps.getOrNull(i)?.let(::options) },
                        )
                    }
                }
            }
            if (page.query.isNotEmpty() && (rows.firstOrNull { it.key == "results" } as? StoreRow.Shelf)?.apps.isNullOrEmpty()) {
                item(key = "noresults") {
                    FText("Nothing in the Store matches \"${page.query}\".", Fuse.type.body, color = c.textMuted, modifier = Modifier.padding(horizontal = Space.gutter))
                }
            }
            item(key = "credit") {
                FText(
                    "Apps and their sources come from the Obtainium Emulation Pack (github.com/RJNY/Obtainium-Emulation-Pack). Fuse installs what each app's own developers publish.",
                    Fuse.type.caption, color = c.textFaint, maxLines = 2,
                    modifier = Modifier.padding(horizontal = Space.gutter).widthIn(max = 880.dp),
                )
            }
        }
    }
}

/** Apps matching [query]: names first (starting with it, then containing it), then authors, descriptions and systems. */
internal fun searchApps(apps: List<StoreApp>, query: String): List<StoreApp> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    fun score(a: StoreApp): Int? {
        val name = a.name.lowercase()
        return when {
            name.startsWith(q) -> 0
            name.split(' ', '-', '(').any { it.startsWith(q) } -> 1
            q in name -> 2
            q in a.author.lowercase() -> 3
            systemNames(a.systems, 99).any { it.lowercase() == q } -> 3
            q in a.about.orEmpty().lowercase() -> 4
            a.categories.any { q in it.lowercase() } -> 5
            else -> null
        }
    }
    return apps.mapNotNull { a -> score(a)?.let { it to a } }.sortedBy { it.first }.map { it.second }
}

/**
 * The Store's top: the pack it follows (edition and release), how fresh the catalogue is or why
 * it couldn't be refreshed, and the page's buttons.
 */
@Composable
private fun StoreHeader(state: StoreState, buttons: List<TopButton>, chosen: Int, compact: Boolean, onClick: (Int) -> Unit) {
    val c = Fuse.colors
    val catalogue = state.catalogue ?: return
    Column(Modifier.padding(horizontal = Space.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 40.dp else 48.dp).clip(SquircleShape.fraction(0.3f)).background(lerp(STORE_TINT.toColor(), Color.Black, 0.25f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.Store, size = Size.iconM, tint = Color.White)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText("${catalogue.apps.size} apps  ·  ${catalogue.variant.title()} edition", Fuse.type.titleSmall, maxLines = 1)
                val line = when {
                    state.refreshing -> "Checking for a newer catalogue"
                    state.refreshProblem != null -> "Offline  ·  catalogue from ${agoText(catalogue.fetchedAt)}"
                    else -> listOfNotNull("Obtainium Emulation Pack", catalogue.packVersion, "updated ${agoText(catalogue.fetchedAt)}").joinToString("  ·  ")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.refreshProblem != null && !state.refreshing) {
                        FuseIcon(FuseIcons.CloudOff, size = 14.dp, tint = c.warning)
                        Spacer(Modifier.width(Space.xs))
                    }
                    FText(line, Fuse.type.caption, color = if (state.refreshProblem != null && !state.refreshing) c.warning else c.textMuted, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            buttons.forEachIndexed { i, b ->
                FuseButton(
                    b.label, selected = chosen == i, onClick = { onClick(i) }, icon = b.icon,
                    kind = if (b.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                    height = if (compact) 40.dp else Size.touch, loading = b.loading,
                )
            }
        }
        if (!state.canInstall) {
            Spacer(Modifier.height(Space.m))
            Row(
                Modifier.clip(RoundedCornerShape(Fuse.geometry.control)).background(c.text.copy(alpha = 0.05f)).padding(horizontal = Space.m, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FuseIcon(FuseIcons.Info, size = Size.iconS, tint = c.textMuted)
                Spacer(Modifier.width(Space.s))
                FText("The first install asks Android to let Fuse install apps. Fuse never installs anything without you confirming it.", Fuse.type.caption, color = c.textMuted, maxLines = 2)
            }
        }
    }
}

@Composable
private fun AppShelf(
    row: StoreRow.Shelf,
    state: StoreState,
    selected: Int,
    remembered: Int,
    compact: Boolean,
    iconOf: (StoreApp) -> Any?,
    onClick: (Int) -> Unit,
    onLongClick: (Int) -> Unit,
) {
    val c = Fuse.colors
    val list = rememberLazyListState()
    FollowSelection(list, { remembered }, anchor = 0f)
    Column {
        SectionLabel(
            row.title, Modifier.padding(start = Space.gutter, bottom = Space.s),
            color = if (selected >= 0) c.text else c.textMuted, count = row.apps.size.toString(), icon = row.icon,
        )
        LazyRow(
            state = list,
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter * 2, top = Space.xs, bottom = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            itemsIndexed(row.apps, key = { _, a -> a.key }) { i, a ->
                val reason = row.featured[a.key]
                if (reason != null) {
                    FeaturedCard(a, reason, state, i == selected, compact, iconOf(a), { onClick(i) }, { onLongClick(i) })
                } else {
                    AppCard(a, state, i == selected, compact, iconOf(a), { onClick(i) }, { onLongClick(i) })
                }
            }
        }
    }
}

/** An app on a shelf: its mark, name, maker, what it is, the systems it plays and its state. */
@Composable
internal fun AppCard(app: StoreApp, state: StoreState, selected: Boolean, compact: Boolean, icon: Any?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val shape = remember { SquircleShape.fraction(0.12f) }
    val job = state.jobs[app.key]
    Tile(
        selected = selected,
        modifier = Modifier.width(if (compact) 272.dp else 312.dp).height(if (compact) 124.dp else 140.dp),
        shape = shape, cornerFraction = 0.12f, glow = app.tint(),
        onClick = onClick, onLongClick = onLongClick,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(c.surfaceRaised, c.surface))))
        Row(Modifier.fillMaxSize().padding(Space.m)) {
            AppMark(app, icon, if (compact) 52.dp else 60.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f).fillMaxSize()) {
                FText(app.name, Fuse.type.bodyStrong, maxLines = 1)
                FText(app.author.ifBlank { app.sourceHost }, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                FText(app.about ?: app.categories.joinToString("  ·  "), Fuse.type.caption, color = c.text.copy(alpha = 0.78f), maxLines = 2)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val systems = systemNames(app.systems)
                    if (systems.isNotEmpty()) {
                        FText(systems.joinToString("  "), Fuse.type.caption, color = c.textFaint, maxLines = 1, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    StateBadge(state, app)
                }
            }
        }
        if (job != null && job.active) {
            ProgressBar(jobProgress(job), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), color = app.tint(), height = 3.dp)
        }
    }
}

/** A suggestion for your library: wider, lit in the app's colour, with why it is suggested. */
@Composable
private fun FeaturedCard(app: StoreApp, reason: String, state: StoreState, selected: Boolean, compact: Boolean, icon: Any?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Fuse.colors
    val tint = app.tint()
    val shape = remember { SquircleShape.fraction(0.1f) }
    Tile(
        selected = selected,
        modifier = Modifier.width(if (compact) 360.dp else 420.dp).height(if (compact) 124.dp else 140.dp),
        shape = shape, cornerFraction = 0.1f, glow = tint,
        onClick = onClick, onLongClick = onLongClick,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(lerp(tint, Color.Black, 0.35f), lerp(tint, Color.Black, 0.78f)))))
        Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.08f), shape))
        Row(Modifier.fillMaxSize().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            AppMark(app, icon, if (compact) 64.dp else 76.dp)
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f)) {
                FText(reason.uppercase(), Fuse.type.overline, color = c.onArtMuted, maxLines = 1)
                Spacer(Modifier.height(Space.xxs))
                FText(app.name, Fuse.type.title, color = c.onArt, maxLines = 1)
                FText(app.about ?: app.author, Fuse.type.caption, color = c.onArtMuted, maxLines = 2)
                val job = state.jobs[app.key]
                if (job != null && job.active) {
                    Spacer(Modifier.height(Space.s))
                    ProgressBar(jobProgress(job), Modifier.fillMaxWidth(), color = lerp(tint, Color.White, 0.3f), height = 3.dp)
                }
            }
        }
    }
}

@Composable
private fun StoreSkeleton(modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(SquircleShape.fraction(0.3f)).skeleton())
            Spacer(Modifier.width(Space.m))
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(Modifier.width(260.dp).height(20.dp).clip(PillShape).skeleton())
                Box(Modifier.width(380.dp).height(12.dp).clip(PillShape).skeleton())
            }
        }
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(Modifier.width(140.dp).height(12.dp).clip(PillShape).skeleton())
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    repeat(4) { Box(Modifier.width(312.dp).height(140.dp).clip(SquircleShape.fraction(0.12f)).skeleton()) }
                }
            }
        }
    }
}
