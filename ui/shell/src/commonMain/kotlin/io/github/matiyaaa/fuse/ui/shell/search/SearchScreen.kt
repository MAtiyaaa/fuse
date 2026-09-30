package io.github.matiyaaa.fuse.ui.shell.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.EditableText
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardField
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardState
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboard
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.focus.FollowSelection
import io.github.matiyaaa.fuse.ui.designsystem.focus.LinearSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.KeyboardTarget
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.app.activateGame
import io.github.matiyaaa.fuse.ui.shell.app.gameMenu
import io.github.matiyaaa.fuse.ui.shell.app.pasteInto
import io.github.matiyaaa.fuse.ui.shell.store.SearchResults
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private sealed interface Hit {
    val key: String
    val title: String
    val detail: String

    data class Game(val card: io.github.matiyaaa.fuse.ui.shell.store.GameCard) : Hit {
        override val key = "g${card.id.value}"
        override val title = card.title
        override val detail = card.platformShort
    }

    data class System(val card: io.github.matiyaaa.fuse.ui.shell.store.PlatformCard) : Hit {
        override val key = "p${card.platform.id}"
        override val title = card.platform.name
        override val detail = "System · ${card.gameCount} games"
    }

    data class App(val card: io.github.matiyaaa.fuse.ui.shell.store.AppCard) : Hit {
        override val key = "a${card.entry.id}"
        override val title = card.entry.displayTitle
        override val detail = "App"
    }

    data class Collection(val c: io.github.matiyaaa.fuse.model.GameCollection) : Hit {
        override val key = "c${c.id.value}"
        override val title = c.name
        override val detail = "Collection · ${c.gameCount} games"
    }
}

/**
 * Global search: games, systems, apps and collections, entirely on the device (nothing goes online).
 * Type with the on-screen keyboard or any hardware keyboard; results update as you type.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun SearchScreen(app: AppState) {
    val field = remember { EditableText() }
    val query = field.text
    var inResults by remember { mutableStateOf(false) }
    val keyboard = remember { KeyboardState() }
    // Typing brings the focus back to the keyboard.
    LaunchedEffect(query) { inResults = false }
    val sel = remember { LinearSelection() }
    val flow = remember {
        androidx.compose.runtime.snapshotFlow { field.text }
            .debounce(90)
            .flatMapLatest { q -> if (q.isBlank()) flowOf(SearchResults()) else app.store.library.search(q.trim()) }
    }
    val results by flow.collectAsState(initial = SearchResults())
    val hits = remember(results) {
        results.games.map { Hit.Game(it) } + results.platforms.map { Hit.System(it) } +
            results.apps.map { Hit.App(it) } + results.collections.map { Hit.Collection(it) }
    }
    sel.clamp(hits.size)

    DisposableEffect(Unit) {
        app.keyboardTarget = KeyboardTarget(field) { if (hits.isNotEmpty()) inResults = true }
        onDispose { app.keyboardTarget = null }
    }
    LaunchedEffect(inResults) {
        app.hero = null
        app.hints = if (inResults) listOf(Hint(HintButton.CONFIRM, "Open"), Hint(HintButton.OPTIONS, "Options"), Hint(HintButton.BACK, "Back"))
        else listOf(Hint(HintButton.CONFIRM, "Type"), Hint(HintButton.OPTIONS, "Delete"), Hint(HintButton.SEARCH, "Space"), Hint(HintButton.NEXT, "Cursor"), Hint(HintButton.MENU, "Results"))
    }

    fun open(hit: Hit) {
        when (hit) {
            is Hit.Game -> app.activateGame(hit.card)
            is Hit.System -> app.go(Route.PlatformGames(hit.card.platform.id))
            is Hit.App -> app.scope.launch { app.store.apps.launch(hit.card) }
            is Hit.Collection -> app.go(Route.CollectionGames(hit.c.id, hit.c.name))
        }
    }

    InputLayer(
        enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen,
        repeats = if (inResults) emptySet() else setOf(NavAction.CONTEXT, NavAction.PREVIOUS_SECTION, NavAction.NEXT_SECTION),
    ) { e ->
        if (inResults) {
            when (e.action) {
                NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> sel.move(e.action, hits.size, vertical = true).let { if (it == NavResult.IGNORED) NavResult.BLOCKED else it }
                NavAction.LEFT -> { inResults = false; NavResult.MOVED }
                NavAction.SELECT -> { hits.getOrNull(sel.index)?.let(::open); NavResult.ACTIVATED }
                NavAction.CONTEXT -> {
                    (hits.getOrNull(sel.index) as? Hit.Game)?.let { app.openContextMenu(app.gameMenu(it.card)) }
                    NavResult.ACTIVATED
                }
                else -> NavResult.IGNORED
            }
        } else {
            val r = keyboard.handle(e, field, { if (hits.isNotEmpty()) inResults = true }, onPaste = { app.pasteInto(field) })
            if (r == NavResult.BLOCKED && e.action == NavAction.RIGHT && hits.isNotEmpty()) { inResults = true; NavResult.MOVED } else r
        }
    }

    val c = Fuse.colors
    Row(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            KeyboardField(
                field,
                Modifier.fillMaxWidth(),
                placeholder = "Games, systems, apps",
                leading = FuseIcons.Search,
                focused = !inResults,
                onClear = { field.replaceAll("") },
            )
            Spacer(Modifier.height(Space.l))
            OnScreenKeyboard(
                keyboard, field, { if (hits.isNotEmpty()) inResults = true },
                doneLabel = "Results",
                showFocus = !inResults,
                onPaste = { app.pasteInto(field) },
                onKey = { app.platform.haptics.tick() },
            )
        }
        Spacer(Modifier.width(Space.xxl))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Spacer(Modifier.height(Size.hudHeight + Space.l))
            SectionLabel(if (query.isBlank()) "Results" else "${hits.size} ${if (hits.size == 1) "result" else "results"}")
            Spacer(Modifier.height(Space.m))
            if (query.isNotBlank() && hits.isEmpty()) {
                FText("Nothing matches \"$query\".", Fuse.type.body, color = c.textMuted)
            }
            val list = rememberLazyListState()
            FollowSelection(list, { sel.index }, anchor = 0.3f)
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(Space.xxs), modifier = Modifier.weight(1f)) {
                itemsIndexed(hits, key = { _, h -> h.key }) { i, h ->
                    val selected = inResults && i == sel.index && app.focusZone == FocusZone.CONTENT
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Fuse.geometry.control))
                            .background(if (selected) c.text.copy(alpha = 0.1f) else Color.Transparent)
                            .clickable(remember { MutableInteractionSource() }, null) { sel.index = i; inResults = true; open(h) }
                            .padding(horizontal = Space.m, vertical = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(24.dp).background(if (selected) c.accent else Color.Transparent, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(Space.m))
                        when (h) {
                            is Hit.Game -> Artwork(h.card.art.icon ?: h.card.art.boxart, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), fallback = { GeneratedArt(h.title, h.card.accent.toColor(), slot = ArtSlot.ICON) })
                            is Hit.App -> Artwork(h.card.icon, Modifier.size(40.dp))
                            is Hit.System -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(h.card.platform.accent.toColor()), contentAlignment = Alignment.Center) {
                                FText(h.card.platform.shortName.take(4), Fuse.type.caption, color = Color.White)
                            }
                            is Hit.Collection -> Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { FuseIcon(FuseIcons.Bookmark, tint = c.textMuted) }
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            FText(h.title, Fuse.type.bodyStrong, maxLines = 1)
                            FText(h.detail, Fuse.type.caption, color = c.textMuted, maxLines = 1)
                        }
                    }
                }
                item { Spacer(Modifier.height(Size.hintHeight + Space.xl)) }
            }
        }
    }
}
