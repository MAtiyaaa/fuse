package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.matiyaaa.fuse.model.GameJournal
import io.github.matiyaaa.fuse.model.NoteBook
import io.github.matiyaaa.fuse.model.PlayStatus
import io.github.matiyaaa.fuse.ui.designsystem.components.EditableText
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.KeyboardState
import io.github.matiyaaa.fuse.ui.designsystem.components.OnScreenKeyboard
import io.github.matiyaaa.fuse.ui.designsystem.components.RatingWords
import io.github.matiyaaa.fuse.ui.designsystem.components.StarRating
import io.github.matiyaaa.fuse.ui.designsystem.components.editingCaret
import io.github.matiyaaa.fuse.ui.designsystem.components.editingSelection
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.slideInVertically
import io.github.matiyaaa.fuse.ui.fuseline.slideOutVertically
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.JournalWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A note being written on the second screen: the page it is on, its text and the keys typing it.
 * Shared by the notebook and the keys at the screen's foot, and read by the companion around them
 * (its page dots step aside and its pages stop turning while a note is written).
 */
internal object CompanionTyping {
    var active by mutableStateOf(false)
        private set

    /** The session page is scrolled down to the Journal: the page dots step aside so they never sit on a note. */
    var reading by mutableStateOf(false)
    internal val field = EditableText()
    internal var keys by mutableStateOf(KeyboardState(autoCapitalize = true))
    private var onDone: (() -> Unit)? = null

    fun start(text: String, done: () -> Unit) {
        field.replaceAll(text)
        keys = KeyboardState(autoCapitalize = text.isEmpty())
        onDone = done
        active = true
    }

    fun finish() {
        if (!active) return
        active = false
        onDone?.invoke()
        onDone = null
    }
}

/**
 * The game's Journal on the second screen, under the session: where you are with it (six statuses,
 * touched to choose, touched again to clear), your rating in half stars, and your notes.
 *
 * Notes are a notebook: as many pages as you like, each as long as you like, ruled like paper.
 * Touch a page to write on it with the keys that rise at the foot of the screen; it keeps what you
 * write as you go (a moment after you stop) and again when you're done, with the game and your
 * profile, so it is there on every device. New Page starts the next; the strip above flips between
 * them, the newest first to hand.
 */
@Composable
internal fun SessionJournal(store: FuseStore, game: GameCard, journal: GameJournal, modifier: Modifier, scroll: ScrollState) {
    val c = Fuse.colors
    val scope = rememberCoroutineScope()
    fun write(next: GameJournal) { scope.launch { store.library.setJournal(game.id, next) } }
    // The notebook as kept, read again whenever it changes elsewhere (another device, the main
    // screen), except while a page is being written here.
    var pages by remember(game.id) { mutableStateOf(NoteBook.pages(journal.note)) }
    var page by remember(game.id) { mutableIntStateOf(pages.lastIndex) }
    var writing by remember(game.id) { mutableStateOf<Int?>(null) }
    var saved by remember(game.id) { mutableStateOf(true) }
    LaunchedEffect(journal.note) {
        if (writing == null) {
            val kept = NoteBook.pages(journal.note)
            // A page just begun and not written on yet stays until it is left.
            pages = if (pages.lastOrNull()?.isEmpty() == true && kept.size < pages.size) kept + "" else kept
            page = page.coerceIn(0, pages.lastIndex)
        }
    }
    fun keep(next: List<String>) {
        pages = next
        write(journal.copy(note = NoteBook.join(next)))
        saved = true
    }
    fun startWriting(at: Int) {
        page = at
        writing = at
        CompanionTyping.start(pages[at]) {
            val text = CompanionTyping.field.text
            keep(pages.toMutableList().also { it[at] = text })
            writing = null
        }
    }
    // Kept as it is written: a moment after the last key, so nothing is lost if the game ends.
    val typed = CompanionTyping.field.text
    LaunchedEffect(writing, typed) {
        val at = writing ?: return@LaunchedEffect
        if (typed == pages.getOrNull(at)) return@LaunchedEffect
        saved = false
        delay(AUTOSAVE_MS)
        keep(pages.toMutableList().also { it[at] = typed })
    }
    // Leaving the screen (the game ended, the page turned) finishes the page.
    androidx.compose.runtime.DisposableEffect(game.id) { onDispose { CompanionTyping.finish() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        // The heading: the Journal, and where you are with the game, in a word.
        Row(Modifier.fillMaxWidth().padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                FuseIcon(FuseIcons.NotebookPen, size = 18.dp, tint = c.accent)
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                FText("Journal", Fuse.type.title, maxLines = 1)
                FText(
                    journal.status?.let { JournalWords.label(it) } ?: "Where you are with it, and your notes",
                    Fuse.type.caption, color = c.textMuted, maxLines = 1,
                )
            }
        }
        StatusChoice(journal.status) { picked ->
            val at = kotlin.time.Clock.System.now().toEpochMilliseconds()
            write(journal.copy(status = picked, statusAt = if (journal.status == picked) journal.statusAt else picked?.let { at }))
        }
        // Your rating: the stars set by the half, by touch.
        JournalCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    FText("Your rating", Fuse.type.label, color = c.textMuted, maxLines = 1)
                    FText(RatingWords.stars(journal.rating) ?: "Not rated yet", Fuse.type.bodyStrong, maxLines = 1)
                }
                StarRating(journal.rating, onRate = { r -> write(journal.copy(rating = r)) }, size = 30.dp)
            }
        }
        Notebook(
            pages = pages, page = page, writing = writing, saved = saved,
            onPage = { at -> if (writing != null) CompanionTyping.finish(); page = at },
            onWrite = ::startWriting,
            onNew = {
                if (writing != null) CompanionTyping.finish()
                // A blank page already at the end is the new page.
                val next = if (pages.lastOrNull()?.isBlank() == true) pages else pages + ""
                pages = next
                startWriting(next.lastIndex)
            },
            onDelete = { at ->
                if (writing != null) CompanionTyping.finish()
                val next = pages.filterIndexed { i, _ -> i != at }.ifEmpty { listOf("") }
                page = (at - 1).coerceIn(0, next.lastIndex)
                keep(next)
            },
            scroll = scroll,
        )
        FText(
            "Kept with ${game.title} and your profile, on every device. A game from RomM keeps its note there too.",
            Fuse.type.caption, color = c.textFaint, maxLines = 2,
        )
    }
}

/** A card of the Journal: frosted over the room, edged faintly. */
@Composable
private fun JournalCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Radius.l)
    Box(
        modifier.fillMaxWidth().clip(shape).background(c.ink.copy(alpha = 0.58f)).border(1.dp, c.text.copy(alpha = 0.08f), shape).padding(Space.m),
    ) { content() }
}

/** The six statuses as touch tiles in two rows, the game's own lit; touched again, it clears. */
@Composable
private fun StatusChoice(current: PlayStatus?, onPick: (PlayStatus?) -> Unit) {
    val c = Fuse.colors
    val statuses = JournalWords.order.filterNotNull()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        statuses.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                row.forEach { s ->
                    val on = s == current
                    val bg by io.github.matiyaaa.fuse.ui.fuseline.fuselineColor(if (on) c.accent else c.ink.copy(alpha = 0.58f), Fuse.motion.tween(Durations.FAST), label = "status")
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(PillShape)
                            .background(bg)
                            .border(1.dp, c.text.copy(alpha = if (on) 0f else 0.08f), PillShape)
                            .clickable(remember { MutableInteractionSource() }, null) { onPick(if (on) null else s) }
                            .semantics { selected = on; contentDescription = JournalWords.label(s) }
                            .padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        FuseIcon(statusIcon(s), size = 16.dp, tint = if (on) c.onAccent else c.textMuted)
                        Spacer(Modifier.width(Space.xs))
                        FText(JournalWords.label(s), Fuse.type.label, color = if (on) c.onAccent else c.text, maxLines = 1, fit = true, fitMin = 0.75f)
                    }
                }
            }
        }
    }
}

/**
 * The notebook: its pages along a strip (each by its first words), the page open below it ruled like
 * paper with a margin, its number and whether it is kept, and New Page. Touch the page to write.
 */
@Composable
private fun Notebook(
    pages: List<String>,
    page: Int,
    writing: Int?,
    saved: Boolean,
    onPage: (Int) -> Unit,
    onWrite: (Int) -> Unit,
    onNew: () -> Unit,
    onDelete: (Int) -> Unit,
    scroll: ScrollState,
) {
    val c = Fuse.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        // The pages, and a new one.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FText("Notes", Fuse.type.titleSmall, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            FText(if (pages.size == 1) "1 page" else "${pages.size} pages", Fuse.type.caption, color = c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            NotebookButton(FuseIcons.Plus, "New Page", onClick = onNew)
        }
        if (pages.size > 1) {
            val strip = rememberScrollState()
            Row(Modifier.fillMaxWidth().horizontalScroll(strip), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                pages.forEachIndexed { i, text -> PageTab(i, text, i == page, onClick = { onPage(i) }) }
            }
            // The newest pages are at the end: the strip opens there.
            LaunchedEffect(pages.size) { strip.scrollTo(strip.maxValue) }
        }
        NotePaper(
            text = if (writing == page) CompanionTyping.field.text else pages.getOrElse(page) { "" },
            number = page + 1, of = pages.size,
            writing = writing == page,
            saved = saved,
            onTap = { onWrite(page) },
            onDelete = { onDelete(page) }.takeIf { pages.size > 1 || pages.getOrElse(page) { "" }.isNotBlank() },
            scroll = scroll,
        )
    }
}

/** A page on the notebook's strip: its number and first words, lit while it is open. */
@Composable
private fun PageTab(index: Int, text: String, open: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Radius.m)
    Column(
        Modifier
            .width(PAGE_TAB)
            .clip(shape)
            .background(if (open) c.paperTint() else c.ink.copy(alpha = 0.5f))
            .border(1.dp, if (open) c.accent.copy(alpha = 0.7f) else c.text.copy(alpha = 0.08f), shape)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { selected = open; contentDescription = "Page ${index + 1}" }
            .padding(horizontal = Space.s, vertical = Space.xs + 2.dp),
    ) {
        FText("PAGE ${index + 1}", Fuse.type.overline, color = if (open) c.accent else c.textMuted, maxLines = 1)
        FText(NoteBook.title(text) ?: "Blank", Fuse.type.caption, color = if (open) c.text else c.textMuted, maxLines = 1)
    }
}

/**
 * One page of the notebook, as paper: faint rules every line, a red-ish margin at the left, the text
 * set on the rules, and the page's foot (its number, whether it is kept, and taking it out). It grows
 * with what is written on it, so it never runs out; the page around it scrolls. Touched, it opens for
 * writing; written on, the caret blinks where the next word goes.
 */
@Composable
private fun NotePaper(
    text: String,
    number: Int,
    of: Int,
    writing: Boolean,
    saved: Boolean,
    onTap: () -> Unit,
    onDelete: (() -> Unit)?,
    scroll: ScrollState,
) {
    val c = Fuse.colors
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.xl)
    val line = LINE_HEIGHT
    val style = Fuse.type.body.copy(color = c.text, fontSize = 15.sp, lineHeight = with(density) { line.toSp() })
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink = remember { FuselineValue(1f) }
    val selection = CompanionTyping.field.selection
    LaunchedEffect(writing, text, selection) {
        blink.snapTo(1f)
        if (!writing) return@LaunchedEffect
        delay(BLINK_REST_MS)
        while (true) {
            blink.animateTo(0f, tween(Durations.SLOW))
            blink.animateTo(1f, tween(Durations.SLOW))
        }
    }
    // While writing, the line being written stays in view above the keys: where the text sits on
    // the screen, and how tall the screen is, read from the layout.
    val textTop = remember { floatArrayOf(0f, 0f) }
    LaunchedEffect(writing, layout, selection) {
        val l = layout ?: return@LaunchedEffect
        if (!writing) return@LaunchedEffect
        val caret = l.getCursorRect(selection.max.coerceIn(0, l.layoutInput.text.length))
        val onScreen = textTop[0] + caret.bottom
        val clear = textTop[1] * (1f - KEYS_SHARE)
        if (onScreen > clear) scroll.animateScrollBy(onScreen - clear)
        else if (onScreen < textTop[1] * 0.12f) scroll.animateScrollBy(onScreen - textTop[1] * 0.2f)
    }
    var deleting by remember(number) { mutableStateOf(false) }
    LaunchedEffect(deleting) { if (deleting) { delay(4_000); deleting = false } }
    val paper = c.paperTint()
    val rule = c.text.copy(alpha = if (c.isDark) 0.08f else 0.1f)
    val margin = lerp(c.accent, Color(0xFFE5484D), 0.5f).copy(alpha = 0.42f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(paper, lerp(paper, c.ink, 0.18f))))
            .border(if (writing) 1.5.dp else 1.dp, if (writing) c.accent.copy(alpha = 0.8f) else c.text.copy(alpha = 0.1f), shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = line * PAPER_LINES + Space.l * 2)
                .drawBehind {
                    val step = line.toPx()
                    val top = Space.l.toPx()
                    var y = top + step
                    while (y < size.height - Space.s.toPx()) {
                        drawLine(rule, Offset(Space.l.toPx(), y), Offset(size.width - Space.l.toPx(), y), strokeWidth = 1.dp.toPx())
                        y += step
                    }
                    val x = MARGIN.toPx()
                    drawLine(margin, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                }
                // Touched, the page opens for writing (a button to a screen reader); written on, a
                // touch puts the caret there.
                .then(
                    if (!writing) {
                        Modifier
                            .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Write") { onTap() }
                            .semantics { contentDescription = "Page $number, touch to write" }
                    } else {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures { at ->
                                layout?.let { l ->
                                    val local = at - Offset((MARGIN + Space.m).toPx(), Space.l.toPx())
                                    CompanionTyping.field.setCaret(l.getOffsetForPosition(local))
                                }
                            }
                        }
                    },
                )
                .padding(start = MARGIN + Space.m, end = Space.l, top = Space.l, bottom = Space.l),
        ) {
            if (text.isEmpty() && !writing) {
                FText("Touch to write. Where you got to, what to try next, the code for the door…", style.copy(color = c.textFaint), maxLines = 3)
            }
            BasicText(
                text,
                Modifier.fillMaxWidth()
                    .onGloballyPositioned {
                        textTop[0] = it.positionInRoot().y
                        textTop[1] = it.findRootCoordinates().size.height.toFloat()
                    }
                    .editingSelection({ layout }, if (writing) selection else androidx.compose.ui.text.TextRange.Zero)
                    .editingCaret({ layout }, selection.max, visible = writing && selection.collapsed, alpha = { blink.value }),
                style = style,
                onTextLayout = { layout = it },
            )
        }
        // The page's foot: which page, whether it is kept, and taking it out.
        Row(
            Modifier.fillMaxWidth().background(c.ink.copy(alpha = 0.22f)).padding(horizontal = Space.m, vertical = Space.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FText("Page $number of $of", Fuse.type.caption, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.width(Space.s))
            FuseIcon(if (saved) FuseIcons.CloudCheck else FuseIcons.Pencil, size = 13.dp, tint = c.textFaint)
            Spacer(Modifier.width(Space.xxs + 2.dp))
            FText(if (saved) "Kept" else "Writing", Fuse.type.caption, color = c.textFaint, maxLines = 1, modifier = Modifier.weight(1f))
            if (onDelete != null && !writing) {
                NotebookButton(FuseIcons.Trash, if (deleting) "Tap again to tear it out" else "Tear Out", warn = deleting) {
                    if (deleting) { deleting = false; onDelete() } else deleting = true
                }
            }
            if (writing) NotebookButton(FuseIcons.Check, "Done", strong = true) { CompanionTyping.finish() }
        }
    }
}

/** A small touch button of the notebook. */
@Composable
private fun NotebookButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, strong: Boolean = false, warn: Boolean = false, onClick: () -> Unit) {
    val c = Fuse.colors
    val bg = when {
        warn -> c.danger
        strong -> c.accent
        else -> c.text.copy(alpha = 0.08f)
    }
    val fg = when {
        warn -> Color.White
        strong -> c.onAccent
        else -> c.text
    }
    Row(
        Modifier
            .heightIn(min = 36.dp)
            .clip(PillShape)
            .background(bg)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(icon, size = 15.dp, tint = fg)
        Spacer(Modifier.width(Space.xs + 2.dp))
        FText(label, Fuse.type.label, color = fg, maxLines = 1)
    }
}

/**
 * The keys for writing a note, risen at the foot of the second screen while one is written: Fuse's
 * own keyboard, by touch (the system's would open on the game's screen above), with Done at its end.
 */
@Composable
internal fun NotebookKeys(modifier: Modifier = Modifier) {
    val c = Fuse.colors
    Appear(
        CompanionTyping.active,
        modifier = modifier,
        enter = fadeIn(Fuse.motion.enter(Durations.BASE)) + slideInVertically(Fuse.motion.enter(Durations.BASE)) { it / 3 },
        exit = fadeOut(Fuse.motion.exit(Durations.FAST)) + slideOutVertically(Fuse.motion.exit(Durations.FAST)) { it / 3 },
    ) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.ink.copy(alpha = 0f), c.ink.copy(alpha = 0.92f), c.ink)))
                .padding(start = Space.m, end = Space.m, top = Space.xl, bottom = Space.s),
        ) {
            OnScreenKeyboard(
                CompanionTyping.keys, CompanionTyping.field,
                onDone = { CompanionTyping.finish() },
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().align(Alignment.Center),
                keyHeight = 38.dp,
                doneLabel = "Done",
                showFocus = false,
            )
        }
    }
}

/** The colour of the notebook's paper in this theme: the surface, warmed a little toward the accent. */
@Composable
private fun io.github.matiyaaa.fuse.ui.designsystem.theme.FuseColors.paperTint(): Color =
    lerp(surfaceRaised, accent, if (isDark) 0.05f else 0.03f).copy(alpha = if (isDark) 0.94f else 0.97f)

/** A line of the notebook, the lines a blank page shows, and where its margin is. */
private val LINE_HEIGHT = 26.dp
private const val PAPER_LINES = 7
private val MARGIN = 36.dp

/** A page on the strip. */
private val PAGE_TAB = 112.dp

/** How long after the last key a page is kept, and how long the caret holds before it blinks. */
private const val AUTOSAVE_MS = 1_200L
private const val BLINK_REST_MS = 600L

/** The share of the screen the keys take at its foot: the line being written stays above them. */
private const val KEYS_SHARE = 0.5f
