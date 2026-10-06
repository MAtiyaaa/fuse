package io.github.matiyaaa.fuse.ui.shell.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.PageDots
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fuselineDp
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ConfirmSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.rememberRouteState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which of Home's pages shows, kept with the route so coming back to Home finds the same page. */
internal class HomePageState {
    var page by mutableIntStateOf(0)
}

/**
 * What a board needs to know about Home's pages: how many there are, and how to turn to another,
 * add one or take one away. [turn] returns false when there is no page that way.
 */
internal class HomePaging(
    val count: Int,
    val current: Int,
    private val onTurn: (Int, Boolean) -> Boolean,
    private val onAdd: () -> Unit,
    private val onRemove: (Int) -> Unit,
) {
    /** To the page [step] away (1 the next, -1 the previous); [fromEdge] when the D-pad ran off the board's side. */
    fun turn(step: Int, fromEdge: Boolean = false): Boolean = onTurn(step, fromEdge)
    fun add() = onAdd()
    fun remove(page: Int) = onRemove(page)
}

/**
 * Channel Mode's Home as pages, like a phone's home screens: each page is a board of its own,
 * arranged on its own. The right stick (or [ and ] on a keyboard), a swipe, or the D-pad run off a
 * board's side turns the page; the dots under the board say which one shows and go to one by touch.
 * Arranging, New page adds one at the end and goes to it; a page after the first can be removed,
 * and an empty one says how to fill it. With one page, Home is exactly the board it always was.
 */
@Composable
fun ChannelHome(app: AppState) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val feed by store.homeFeed.collectAsState()

    // Without a single game, system or app the board would be a wall of empty widgets: Home says
    // how to begin instead (after a moment for the library to load, shown as the board's outline).
    val nothing = feed.systems.isEmpty() && feed.pinnedApps.isEmpty() && feed.continuePlaying.isEmpty()
    val loading = rememberHomeLoading(app, feed, nothing)
    if (nothing) {
        if (loading) HomeSkeleton(app, channels = true) else HomeEmpty(app)
        return
    }
    BoardPages(app, rememberHomeSpace(app))
}

/**
 * A board in pages ([space]'s: Home's widgets or the Systems page's systems), each page a board of
 * its own, arranged on its own. See [ChannelHome].
 */
@Composable
internal fun BoardPages(app: AppState, space: BoardSpace) {
    val store = app.store
    val prefs by store.prefs.collectAsState()
    val home = space.config(prefs)
    val count = home.pageCount
    val keys = listOf("first") + home.pages.map { it.id }
    val state = rememberRouteState(app.navigator, "${space.key}.pages") { HomePageState() }
    if (state.page >= count) state.page = count - 1
    val pager = rememberPagerState(initialPage = state.page) { count }
    val scope = rememberCoroutineScope()
    // One editor per page, so a page keeps what it was doing while it is turned past.
    val editors = remember { HashMap<String, BoardEditor>() }
    fun editorOf(key: String) = editors.getOrPut(key) { BoardEditor() }
    val currentKey = keys.getOrElse(pager.currentPage) { "first" }
    val arranging = editorOf(currentKey).arranging

    fun go(page: Int) {
        if (page !in 0 until count || page == pager.currentPage) return
        // Arranging carries over to the page turned to; a widget in hand is put back first.
        val from = editorOf(currentKey)
        val wasArranging = from.arranging
        from.cancel()
        from.arranging = false
        editorOf(keys[page]).arranging = wasArranging
        state.page = page
        scope.launch { pager.animateScrollToPage(page) }
        app.platform.haptics.tick()
    }
    fun turn(step: Int, fromEdge: Boolean): Boolean {
        val next = pager.currentPage + step
        if (next !in 0 until count) return false
        // Off the board's side, only while not arranging: arranging, a widget pushed there bumps.
        if (fromEdge && arranging) return false
        go(next)
        return true
    }
    fun add() {
        val page = count
        store.updatePrefs { p -> space.keep(p, space.config(p).addPage()) }
        // The new page exists after the next composition; turn to it then.
        scope.launch {
            snapshotFlow { pager.pageCount }.first { it > page }
            go(page)
        }
        app.toasts.show("A new page. Add ${space.item}s to it, or turn back with the right stick")
    }
    fun remove(page: Int) {
        if (page <= 0) return
        val gone = { store.updatePrefs { p -> space.keep(p, space.config(p).removePage(page)) }; if (state.page >= page) state.page = page - 1 }
        if (home.boardWidgets(page).isEmpty()) {
            gone()
            scope.launch { pager.animateScrollToPage(page - 1) }
            return
        }
        app.confirm = ConfirmSpec(
            title = "Remove this page?",
            message = space.removePageMessage,
            confirmLabel = "Remove page",
            destructive = true,
        ) {
            gone()
            scope.launch { pager.animateScrollToPage(page - 1) }
            app.toasts.show("Page removed")
        }
    }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { state.page = it } }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // Arranging, a drag moves a widget, never the page.
            userScrollEnabled = count > 1 && !arranging,
            key = { keys.getOrElse(it) { "first" } },
        ) { page ->
            val key = keys.getOrElse(page) { "first" }
            val paging = HomePaging(count, page, ::turn, ::add, ::remove)
            Box(Modifier.fillMaxSize()) {
                ChannelBoard(app, space, page, key, active = page == pager.currentPage, editor = editorOf(key), paging = paging)
                if (page > 0 && space.shown(home, page).isEmpty() && !editorOf(key).arranging) {
                    EmptyPage(
                        text = space.emptyPage,
                        item = space.item,
                        onAdd = { editorOf(key).arranging = true },
                        onRemove = { remove(page) },
                    )
                }
            }
        }
        // Which page shows, over the board's foot: above the arranging bar while it is up.
        if (count > 1) {
            val lift by fuselineDp(if (arranging) ARRANGE_BAR + Space.m else 0.dp, Fuse.motion.tween(Durations.BASE), label = "dotsLift")
            PageDots(
                count = count,
                current = pager.currentPage,
                onSelect = { app.focusZone = FocusZone.CONTENT; go(it) },
                labels = List(count) { "Page ${it + 1} of $count" },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Size.hintHeight + Space.xs + lift),
            )
        }
    }
}

/** A page with nothing on it yet: what it is for, and how to fill it or take it away. */
@Composable
private fun EmptyPage(text: String, item: String, onAdd: () -> Unit, onRemove: () -> Unit) {
    val c = Fuse.colors
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = Size.hudHeight, bottom = Size.hintHeight + Space.xl), contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 460.dp).padding(horizontal = Space.gutter), raised = true) {
            Column(Modifier.padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    FuseIcon(FuseIcons.CopyPlus, size = 28.dp, tint = c.accent)
                }
                Spacer(Modifier.height(Space.m))
                FText("A page of its own", Fuse.type.title, maxLines = 1, align = TextAlign.Center)
                Spacer(Modifier.height(Space.xs))
                FText(
                    text,
                    Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 4,
                )
                Spacer(Modifier.height(Space.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FuseButton("Add ${item}s", selected = true, onClick = onAdd, kind = ButtonKind.PRIMARY, icon = FuseIcons.Plus)
                    FuseButton("Remove", selected = false, onClick = onRemove, kind = ButtonKind.SECONDARY, icon = FuseIcons.Trash)
                }
            }
        }
    }
}
