package io.github.matiyaaa.fuse.ui.shell.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.ManualPlace
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Spinner
import io.github.matiyaaa.fuse.ui.designsystem.components.Trailing
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.Swap
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.fuseline.togetherWith
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.ChoiceSpec
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone
import io.github.matiyaaa.fuse.ui.shell.app.Route
import io.github.matiyaaa.fuse.ui.shell.platform.ManualDocument
import io.github.matiyaaa.fuse.ui.shell.platform.ManualOpened
import kotlin.math.roundToInt

/**
 * A game's manual, a page at a time: the page as large as the screen allows, turned with LB and RB
 * (or the D-pad, a swipe, or a tap at either side), zoomed in steps with X (or a pinch, or a double
 * tap) and moved about with the D-pad or a drag while zoomed. Y marks the page to come back to, and A
 * lists the marked pages to go to one. It opens where it was left, read straight from the file.
 */
@Composable
fun ManualScreen(app: AppState, route: Route.Manual) {
    val c = Fuse.colors
    val prefs by app.store.prefs.collectAsState()
    val place = prefs.manuals[route.path] ?: ManualPlace()
    var opened by remember(route.path) { mutableStateOf<ManualOpened?>(null) }
    LaunchedEffect(route.path) {
        opened = app.platform.manuals?.open(route.path) ?: ManualOpened.Unreadable("Manuals can't be opened on this device")
    }
    val doc = (opened as? ManualOpened.Ready)?.document
    DisposableEffect(doc) { onDispose { doc?.close() } }

    var page by remember(route.path) { mutableIntStateOf(-1) }
    var direction by remember { mutableIntStateOf(1) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    if (doc != null && page < 0) page = place.page.coerceIn(0, doc.pageCount - 1)

    fun keep(at: Int, bookmarks: List<Int> = place.bookmarks) = app.store.updatePrefs {
        it.copy(manuals = it.manuals + (route.path to ManualPlace(at, bookmarks, kotlin.time.Clock.System.now().toEpochMilliseconds())))
    }
    fun turn(to: Int): Boolean {
        val d = doc ?: return false
        val next = to.coerceIn(0, d.pageCount - 1)
        if (next == page) return false
        direction = if (next > page) 1 else -1
        page = next
        zoom = 1f
        pan = Offset.Zero
        keep(next)
        return true
    }
    fun cycleZoom() {
        zoom = ZOOMS[(ZOOMS.indexOfFirst { it >= zoom - 0.01f } + 1).mod(ZOOMS.size)]
        if (zoom == 1f) pan = Offset.Zero
    }
    fun toggleBookmark() {
        val marked = page in place.bookmarks
        keep(page, if (marked) place.bookmarks - page else (place.bookmarks + page).sorted())
        app.toasts.show(if (marked) "Page ${page + 1} isn't marked any more" else "Page ${page + 1} is marked", icon = FuseIcons.Bookmark)
    }
    fun bookmarks() {
        val d = doc ?: return
        app.choice = ChoiceSpec(
            title = "Go to a page",
            icon = FuseIcons.Bookmark,
            message = if (place.bookmarks.isEmpty()) "Press Y on a page to mark it, and it is listed here." else null,
            options = buildList {
                place.bookmarks.forEach { p ->
                    add(MenuAction("mark.$p", "Page ${p + 1}", FuseIcons.Bookmark, trailing = Trailing.Check(p == page), section = "Marked", onSelect = { app.choice = null; turn(p) }))
                }
                add(MenuAction("first", "The first page", FuseIcons.ChevronsLeft, section = "", onSelect = { app.choice = null; turn(0) }))
                add(MenuAction("last", "The last page", FuseIcons.ChevronsRight, detail = "Page ${d.pageCount}", section = "", onSelect = { app.choice = null; turn(d.pageCount - 1) }))
            },
        )
    }

    // Moving about a zoomed page: a step is a fifth of the screen.
    var room by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun move(dx: Float, dy: Float): Boolean {
        if (zoom <= 1f) return false
        val limit = Offset(room.width * (zoom - 1f) / 2f, room.height * (zoom - 1f) / 2f)
        val next = Offset((pan.x + dx).coerceIn(-limit.x, limit.x), (pan.y + dy).coerceIn(-limit.y, limit.y))
        if (next == pan) return false
        pan = next
        return true
    }

    LaunchedEffect(doc, page, zoom, place.bookmarks) {
        app.hints = when {
            doc == null -> listOf(Hint(HintButton.BACK, "Back"))
            zoom > 1f -> listOf(Hint(HintButton.DPAD, "Move"), Hint(HintButton.OPTIONS, "Zoom"), Hint(HintButton.PREV, "Pages"), Hint(HintButton.BACK, "Back"))
            else -> listOf(
                Hint(HintButton.PREV, "Pages"),
                Hint(HintButton.OPTIONS, "Zoom"),
                Hint(HintButton.SEARCH, if (page in place.bookmarks) "Unmark" else "Mark"),
                Hint(HintButton.CONFIRM, "Go to"),
                Hint(HintButton.BACK, "Back"),
            )
        }
    }
    InputLayer(enabled = app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        if (doc == null) return@InputLayer NavResult.IGNORED
        val step = room.width / 5f
        when (e.action) {
            NavAction.PREVIOUS_SECTION -> if (turn(page - 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.NEXT_SECTION -> if (turn(page + 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.LEFT -> if (zoom > 1f) (if (move(step, 0f)) NavResult.MOVED else NavResult.BLOCKED) else if (turn(page - 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.RIGHT -> if (zoom > 1f) (if (move(-step, 0f)) NavResult.MOVED else NavResult.BLOCKED) else if (turn(page + 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.UP -> if (move(0f, step)) NavResult.MOVED else NavResult.IGNORED
            NavAction.DOWN -> if (move(0f, -step)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.PAGE_UP -> if (turn(page - 10)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.PAGE_DOWN -> if (turn(page + 10)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.CONTEXT -> { cycleZoom(); NavResult.ACTIVATED }
            NavAction.SEARCH -> { toggleBookmark(); NavResult.ACTIVATED }
            NavAction.SELECT -> { bookmarks(); NavResult.ACTIVATED }
            else -> NavResult.IGNORED
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val o = opened) {
            null -> Spinner(Modifier.align(Alignment.Center))
            is ManualOpened.Unreadable -> Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                FuseIcon(FuseIcons.FileWarning, size = Size.iconL, tint = c.textMuted)
                Spacer(Modifier.height(Space.m))
                FText("This manual can't be opened", Fuse.type.title, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
                FText(o.reason, Fuse.type.body, color = c.textMuted, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
            is ManualOpened.Ready -> if (page >= 0) {
                val motion = Fuse.motion
                // Between the top line and the hints, as every page is.
                Column(Modifier.fillMaxSize().padding(top = Size.hudHeight, bottom = Size.hintHeight)) {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().clipToBounds()
                            .pointerInput(o.document) {
                                detectTapGestures(
                                    onDoubleTap = { cycleZoom() },
                                    onTap = { at ->
                                        // A tap at either side turns the page, as in a book.
                                        if (zoom <= 1f) {
                                            if (at.x < size.width * 0.25f) turn(page - 1) else if (at.x > size.width * 0.75f) turn(page + 1)
                                        }
                                    },
                                )
                            }
                            .pointerInput(o.document) {
                                // Two fingers zoom; one drags a zoomed page about, or swipes an unzoomed one to turn it.
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var swipe = 0f
                                    do {
                                        val event = awaitPointerEvent()
                                        val gesture = event.calculateZoom()
                                        val drag = event.calculatePan()
                                        if (gesture != 1f) zoom = (zoom * gesture).coerceIn(1f, ZOOMS.last())
                                        if (zoom > 1f) move(drag.x, drag.y) else { pan = Offset.Zero; swipe += drag.x }
                                        // Once it is a drag, a pinch or a swipe, it is no tap.
                                        if (gesture != 1f || zoom > 1f || kotlin.math.abs(swipe) > viewConfiguration.touchSlop) event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    if (zoom <= 1f && kotlin.math.abs(swipe) > size.width * 0.12f) turn(page + if (swipe < 0) 1 else -1)
                                }
                            },
                    ) {
                        val density = LocalDensity.current
                        val w = with(density) { maxWidth.toPx() }
                        val h = with(density) { maxHeight.toPx() }
                        room = androidx.compose.ui.geometry.Size(w, h)
                        Swap(
                            targetState = page,
                            transitionSpec = {
                                val d = direction
                                (fadeIn(motion.fade(Durations.FAST)) + slideInHorizontally(motion.tween(Durations.BASE)) { it / 10 * d }) togetherWith fadeOut(motion.fade(Durations.FAST))
                            },
                            label = "manual page",
                        ) { shown ->
                            ManualPage(o.document, shown, w, h, zoom, if (shown == page) pan else Offset.Zero)
                        }
                    }
                    ManualBar(route.title, page, o.document.pageCount, place.bookmarks, zoom)
                }
            }
        }
    }
}

/** One page, drawn at the size it is shown (and drawn again sharper when zoomed). */
@Composable
private fun ManualPage(doc: ManualDocument, index: Int, w: Float, h: Float, zoom: Float, pan: Offset) {
    val c = Fuse.colors
    // Drawn at the zoom it is shown at, in steps, so zooming in stays sharp without drawing it at every scale.
    val drawnAt = if (zoom > 1.5f) 2.4f else if (zoom > 1f) 1.6f else 1f
    val pages = remember(doc) { mutableStateMapOf<Pair<Int, Float>, ImageBitmap>() }
    val key = index to drawnAt
    LaunchedEffect(doc, key, w, h) {
        if (key !in pages) {
            val margin = 0.94f
            doc.page(index, (w * margin * drawnAt).roundToInt(), (h * margin * drawnAt).roundToInt())?.let { pages[key] = it }
        }
        // The next page is drawn ahead, so turning is instant; only a few pages are kept.
        if (index + 1 < doc.pageCount && (index + 1 to 1f) !in pages) {
            doc.page(index + 1, (w * 0.94f).roundToInt(), (h * 0.94f).roundToInt())?.let { pages[index + 1 to 1f] = it }
        }
        pages.keys.filter { kotlin.math.abs(it.first - index) > 2 }.forEach { pages.remove(it) }
    }
    val bitmap = pages[key] ?: pages[index to 1f]
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            Spinner()
        } else {
            val density = LocalDensity.current
            val scale = minOf(w * 0.94f / bitmap.width, h * 0.94f / bitmap.height)
            val pw = with(density) { (bitmap.width * scale).toDp() }
            val ph = with(density) { (bitmap.height * scale).toDp() }
            Image(
                bitmap,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(pw, ph)
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
                    .shadow(12.dp, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(androidx.compose.ui.graphics.Color.White),
            )
        }
    }
}

/** Under the page: the manual's name, where you are in it, and its marked pages along a track. */
@Composable
private fun ManualBar(title: String, page: Int, count: Int, bookmarks: List<Int>, zoom: Float) {
    val c = Fuse.colors
    Panel(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.s)) {
        Row(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            FuseIcon(FuseIcons.BookOpen, size = Size.iconS, tint = c.textMuted)
            Spacer(Modifier.width(Space.s))
            FText(title, Fuse.type.bodyStrong, maxLines = 1, modifier = Modifier.widthIn(max = 360.dp))
            Spacer(Modifier.width(Space.l))
            val accent = c.accent
            val track = c.text.copy(alpha = 0.12f)
            val mark = c.text.copy(alpha = 0.55f)
            Canvas(Modifier.weight(1f).height(18.dp).semantics { contentDescription = "Page ${page + 1} of $count" }) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                drawRoundRect(track, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2))
                val f = if (count <= 1) 1f else page / (count - 1f)
                drawRoundRect(accent, Offset(0f, y - h / 2), androidx.compose.ui.geometry.Size(size.width * f, h), CornerRadius(h / 2))
                bookmarks.forEach { b ->
                    val x = if (count <= 1) 0f else size.width * b / (count - 1f)
                    drawRoundRect(mark, Offset(x - 1.5.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height), CornerRadius(1.5.dp.toPx()))
                }
                drawCircle(accent, 6.dp.toPx(), Offset(size.width * f, y))
            }
            Spacer(Modifier.width(Space.l))
            if (page in bookmarks) {
                FuseIcon(FuseIcons.Bookmark, size = Size.iconS, tint = c.accent)
                Spacer(Modifier.width(Space.s))
            }
            FText(
                "Page ${page + 1} of $count" + if (zoom > 1f) "  ·  ${(zoom * 100).roundToInt()}%" else "",
                Fuse.type.label, color = c.textMuted, maxLines = 1,
            )
        }
    }
}

/** The zoom steps X goes through. */
private val ZOOMS = listOf(1f, 1.6f, 2.4f)
