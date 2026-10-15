package io.github.matiyaaa.fuse.ui.shell.sync

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import io.github.matiyaaa.fuse.ui.designsystem.media.PrefetchArt
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.sync.ProfileInfo
import io.github.matiyaaa.fuse.sync.SaveImportChoice
import io.github.matiyaaa.fuse.sync.SaveImportConfidence
import io.github.matiyaaa.fuse.sync.SaveImportEntry
import io.github.matiyaaa.fuse.sync.SaveImportPlan
import io.github.matiyaaa.fuse.sync.SaveImportResult
import io.github.matiyaaa.fuse.sync.SaveImportTarget
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.ProfileAvatar
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastKind
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Appear
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.Durations
import io.github.matiyaaa.fuse.ui.fuseline.fadeIn
import io.github.matiyaaa.fuse.ui.fuseline.fadeOut
import io.github.matiyaaa.fuse.ui.fuseline.fuselineColor
import io.github.matiyaaa.fuse.ui.fuseline.fuselineFloat
import io.github.matiyaaa.fuse.ui.fuseline.slideInHorizontally
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Saves copied off a Switch (JKSV, Checkpoint), brought in one game at a time: Fuse reads the dump,
 * then for each game asks whether it is that game, then whose save it becomes (one person, several,
 * or everyone: each gets their own copy), then shows it all once more before writing anything.
 */
internal class SwitchImport(val source: String) {
    enum class Step { READING, GAME, PEOPLE, REVIEW, IMPORTING, DONE }

    /** One game's backups from the dump, newest first, and what the person chose for it. */
    class Game(val key: String, val title: String, val card: GameCard?, val backups: List<Pair<SaveImportEntry, SaveImportTarget>>) {
        var chosen by mutableIntStateOf(0)
        var included by mutableStateOf(true)
        var people by mutableStateOf<Set<String>>(emptySet())
        val entry get() = backups[chosen].first
        val target get() = backups[chosen].second
    }

    var step by mutableStateOf(Step.READING)
    var plan by mutableStateOf<SaveImportPlan?>(null)
    var games by mutableStateOf<List<Game>>(emptyList())
    var index by mutableIntStateOf(0)
    var result by mutableStateOf<SaveImportResult?>(null)
    var problem by mutableStateOf<String?>(null)
    val game: Game? get() = games.getOrNull(index)

    /** The games of a plan, from its Switch backups: for each game its backups, newest first. */
    fun take(plan: SaveImportPlan, cards: Map<String, GameCard>, me: String?) {
        this.plan = plan
        val picks = plan.entries.filter { it.switch != null }.mapNotNull { e ->
            val t = e.targets.firstOrNull { it.id == e.selectedTarget }
                ?: e.targets.firstOrNull { "eden" in it.query.emulatorId }
                ?: e.targets.firstOrNull { it.confidence == SaveImportConfidence.TITLE_ID }
                ?: e.targets.firstOrNull()
            t?.let { e to it }
        }
        games = picks.groupBy { (e, t) -> t.query.game.id + if (e.switch?.device == true) "|device" else "" }.map { (key, list) ->
            val (_, t) = list.first()
            Game(key, t.query.title, cards[t.query.game.id], list.sortedByDescending { it.first.switch?.savedAt ?: 0L }).also { g ->
                g.people = setOfNotNull(me)
            }
        }.sortedBy { it.title.lowercase() }
        index = 0
        step = if (games.isEmpty()) Step.DONE else Step.GAME
    }
}

/** Starts reading a Switch dump at [path] and shows the import as it goes. */
internal fun startSwitchImport(app: AppState, path: String) {
    val service = app.store.sync.service ?: return
    val flow = SwitchImport(path.substringAfterLast('/').substringAfterLast('\\'))
    app.switchImport = flow
    app.scope.launch {
        val cards = cardsByGame(app, platform = "switch")
        val started = kotlin.time.Clock.System.now().toEpochMilliseconds()
        service.previewSaveImport(path, cards.second)
            .onSuccess { plan ->
                // The reading card stays long enough to be read, however quick the dump was.
                val left = MIN_READING_MS - (kotlin.time.Clock.System.now().toEpochMilliseconds() - started)
                if (left > 0) delay(left)
                if (plan.entries.none { it.switch != null }) {
                    service.discardSaveImport(plan)
                    flow.problem = "No Switch saves were found here. Choose the JKSV folder, Checkpoint's saves folder, or one game's folder from your Switch's SD card."
                    flow.step = SwitchImport.Step.DONE
                } else flow.take(plan, cards.first, service.activeProfile.value?.id)
            }
            .onFailure { e ->
                flow.problem = e.message ?: "Couldn't read those saves"
                flow.step = SwitchImport.Step.DONE
            }
    }
}

/** Shows a plan already read (from Import Saves' file or folder) as the Switch import. */
internal fun showSwitchImport(app: AppState, plan: SaveImportPlan, cards: Map<String, GameCard>) {
    val service = app.store.sync.service ?: return
    app.switchImport = SwitchImport(plan.sourceName).also { it.take(plan, cards, service.activeProfile.value?.id) }
}

/** The library's games (of one [platform], when given) by their save key, and the save queries to look for them with. */
internal suspend fun cardsByGame(app: AppState, platform: String? = null): Pair<Map<String, GameCard>, List<io.github.matiyaaa.fuse.sync.SaveQuery>> {
    val games = app.store.library.games(GameQuery(includeHidden = true)).first().filter { platform == null || it.platformId.value == platform }
    val pairs = games.mapNotNull { g -> app.store.sync.saveQuery(g.id)?.let { q -> q to g } }
    return pairs.associate { (q, g) -> q.game.id to g } to pairs.map { it.first }
}

private const val MIN_READING_MS = 1_400L

@Composable
internal fun SwitchImportOverlay(app: AppState) {
    val flow = app.switchImport
    var shown by remember { mutableStateOf(flow) }
    if (flow != null) shown = flow
    LaunchedEffect(flow != null) { if (flow != null) app.platform.sounds.play(SoundCue.OPEN) }
    val service = app.store.sync.service
    val profiles = service?.profiles?.value.orEmpty()
    val me = service?.activeProfile?.value?.id

    fun close() {
        val f = app.switchImport ?: return
        app.switchImport = null
        if (f.step != SwitchImport.Step.DONE || f.result?.failures?.isNotEmpty() == true) f.plan?.let { p -> app.scope.launch { service?.discardSaveImport(p) } }
    }

    fun import(f: SwitchImport) {
        val plan = f.plan ?: return
        val chosen = f.games.filter { it.included && it.people.isNotEmpty() }
        if (chosen.isEmpty() || me == null) return
        f.step = SwitchImport.Step.IMPORTING
        app.scope.launch {
            service?.importSaves(plan, chosen.map { g -> SaveImportChoice(g.entry.id, g.target.id, g.people.toList()) }, me)
                ?.onSuccess { r ->
                    f.result = r
                    f.step = SwitchImport.Step.DONE
                    app.platform.sounds.play(if (r.failures.isEmpty()) SoundCue.SELECT else SoundCue.ERROR)
                }
                ?.onFailure { e ->
                    app.toasts.show(e.message ?: "Couldn't import the saves", ToastKind.ERROR)
                    f.step = SwitchImport.Step.REVIEW
                }
        }
    }

    // Controller: a row of choices per step, moved through with left and right (and up and down
    // between the people and the buttons).
    var focus by remember(flow?.step, flow?.index) { mutableIntStateOf(-1) }
    if (flow != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            val f = flow
            val g = f.game
            val actions = actionsOf(f, profiles, me, ::import, ::close)
            val people = if (f.step == SwitchImport.Step.PEOPLE) profiles.size + 1 else 0
            val count = people + actions.size
            if (focus < 0) focus = people + actions.indexOfFirst { it.primary }.coerceAtLeast(0)
            when (e.action) {
                NavAction.LEFT -> if (focus > 0 && focus != people) { focus--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (focus < count - 1 && focus != people - 1) { focus++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.UP -> if (focus >= people && people > 0) { focus = 0; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.DOWN -> if (focus < people) { focus = people + actions.indexOfFirst { it.primary }.coerceAtLeast(0); NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> {
                    if (focus < people && g != null) togglePerson(g, profiles, focus) else actions.getOrNull(focus - people)?.run()
                    NavResult.ACTIVATED
                }
                NavAction.BACK -> { back(f, ::close); NavResult.CONSUMED }
                else -> NavResult.CONSUMED
            }
        }
    }

    Overlay(visible = flow != null, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        val f = shown ?: return@Overlay
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val compact = maxHeight < 560.dp
            Panel(Modifier.widthIn(min = minOf(420.dp, room), max = minOf(620.dp, room)).heightIn(max = maxHeight - Space.l * 2)) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(if (compact) Space.l else Space.xl)) {
                    Header(f)
                    Spacer(Modifier.height(if (compact) Space.l else Space.xl))
                    // The next games' pictures load while this one is asked about, so each arrives ready.
                    if (f.step == SwitchImport.Step.GAME || f.step == SwitchImport.Step.PEOPLE) {
                        val arts = f.games.map { it.card?.art }
                        PrefetchArt(arts.map { it?.hero ?: it?.screenshot ?: it?.boxart }, f.index, limit = 2, fill = true)
                        PrefetchArt(arts.map { it?.boxart ?: it?.grid }, f.index, limit = 2, fill = true)
                        PrefetchArt(arts.map { it?.logo }, f.index, limit = 2)
                    }
                    when (f.step) {
                        SwitchImport.Step.READING -> Reading(f.source)
                        SwitchImport.Step.GAME -> f.game?.let { GameStep(it, compact) }
                        SwitchImport.Step.PEOPLE -> f.game?.let { g -> PeopleStep(g, profiles, me, focus.takeIf { it in 0..profiles.size }) { i -> togglePerson(g, profiles, i) } }
                        SwitchImport.Step.REVIEW, SwitchImport.Step.IMPORTING -> Review(f, profiles)
                        SwitchImport.Step.DONE -> Done(f)
                    }
                    val actions = actionsOf(f, profiles, me, ::import, ::close)
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(if (compact) Space.l else Space.xl))
                        val people = if (f.step == SwitchImport.Step.PEOPLE) profiles.size + 1 else 0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            actions.forEachIndexed { i, a ->
                                FuseButton(
                                    a.label, selected = focus == people + i, onClick = a.run, icon = a.icon,
                                    kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.GHOST, enabled = a.enabled,
                                    loading = f.step == SwitchImport.Step.IMPORTING && a.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class Action(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val primary: Boolean = false, val enabled: Boolean = true, val run: () -> Unit)

private fun actionsOf(f: SwitchImport, profiles: List<ProfileInfo>, me: String?, import: (SwitchImport) -> Unit, close: () -> Unit): List<Action> {
    val g = f.game
    return when (f.step) {
        SwitchImport.Step.READING -> listOf(Action("Cancel", run = close))
        SwitchImport.Step.GAME -> buildList {
            if (g == null) return@buildList
            add(Action("Not This Game", FuseIcons.CircleSlash) { g.included = false; next(f) })
            if (g.backups.size > 1) add(Action("Other Backup", FuseIcons.Clock) { g.chosen = (g.chosen + 1) % g.backups.size })
            add(Action("Yes, It Is", FuseIcons.Check, primary = true) { g.included = true; f.step = SwitchImport.Step.PEOPLE })
        }
        SwitchImport.Step.PEOPLE -> listOf(
            Action("Back", FuseIcons.ArrowLeft) { f.step = SwitchImport.Step.GAME },
            Action("Continue", FuseIcons.ArrowRight, primary = true, enabled = g?.people?.isNotEmpty() == true) { next(f) },
        )
        SwitchImport.Step.REVIEW -> {
            val count = f.games.count { it.included && it.people.isNotEmpty() }
            listOf(
                Action("Back", FuseIcons.ArrowLeft) { f.index = f.games.lastIndex; f.step = SwitchImport.Step.GAME },
                Action(if (count == 1) "Import 1 Save" else "Import $count Saves", FuseIcons.Save, primary = true, enabled = count > 0 && me != null) { import(f) },
            )
        }
        SwitchImport.Step.IMPORTING -> listOf(Action("Importing", FuseIcons.Save, primary = true, enabled = false) {})
        SwitchImport.Step.DONE -> listOf(Action("Done", FuseIcons.Check, primary = true, run = close))
    }
}

private fun next(f: SwitchImport) {
    if (f.index < f.games.lastIndex) {
        f.index++
        f.step = SwitchImport.Step.GAME
    } else f.step = SwitchImport.Step.REVIEW
}

private fun back(f: SwitchImport, close: () -> Unit) = when (f.step) {
    SwitchImport.Step.PEOPLE -> f.step = SwitchImport.Step.GAME
    SwitchImport.Step.GAME -> if (f.index > 0) { f.index--; f.step = SwitchImport.Step.PEOPLE } else close()
    SwitchImport.Step.REVIEW -> { f.index = f.games.lastIndex; f.step = SwitchImport.Step.PEOPLE }
    SwitchImport.Step.IMPORTING -> Unit
    else -> close()
}

/** Index [i] among the people: one of them, or (last) everyone. */
private fun togglePerson(g: SwitchImport.Game, profiles: List<ProfileInfo>, i: Int) {
    val all = profiles.map { it.id }.toSet()
    g.people = when {
        i >= profiles.size -> if (g.people == all) emptySet() else all
        else -> profiles[i].id.let { id -> if (id in g.people) g.people - id else g.people + id }
    }
}

@Composable
private fun Header(f: SwitchImport) {
    val c = Fuse.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            FuseIcon(FuseIcons.SdCard, size = 18.dp, tint = c.accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText("Switch Saves", Fuse.type.label, color = c.textMuted, maxLines = 1)
            FText(f.source, Fuse.type.bodyStrong, maxLines = 1)
        }
        val n = f.games.size
        if (n > 1 && (f.step == SwitchImport.Step.GAME || f.step == SwitchImport.Step.PEOPLE)) {
            Steps(n, f.index)
        }
    }
}

/** Where the person is among the games: a dot per game, the current one long. */
@Composable
private fun Steps(count: Int, at: Int) {
    val c = Fuse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = minOf(count, 9)
        val first = (at - shown / 2).coerceIn(0, count - shown)
        for (i in first until first + shown) {
            val wide by fuselineFloat(if (i == at) 1f else 0f, Fuse.motion.focusSpring(), label = "stepDot")
            Box(Modifier.height(6.dp).width(6.dp + 12.dp * wide).clip(CircleShape)
                .background(if (i <= at) c.accent.copy(alpha = 0.55f + 0.45f * wide) else c.textMuted.copy(alpha = 0.3f)))
        }
    }
}

/** The dump being read: a spark running along a fuse, and what Fuse is doing. */
@Composable
private fun Reading(source: String) {
    val c = Fuse.colors
    var t by remember { mutableFloatStateOf(0f) }
    val reduced = Fuse.motion.reduced
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t = ((now - start) / 1_000_000_000f) }
    }
    val phase = (t / 1.6f) % 1f
    val words = if (t < 0.9f) "Reading the saves on your Switch's card" else "Matching each save to your games"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val y = size.height / 2
            val pad = 12.dp.toPx()
            val w = size.width - pad * 2
            drawLine(c.textMuted.copy(alpha = 0.18f), Offset(pad, y), Offset(pad + w, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val head = pad + w * (if (reduced) 0.5f else phase)
            val tail = (head - w * 0.35f).coerceAtLeast(pad)
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c.accent), startX = tail, endX = head), Offset(tail, y), Offset(head, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            val glow = 0.75f + 0.25f * kotlin.math.sin(t * 9f)
            drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.55f * glow), Color.Transparent), center = Offset(head, y), radius = 22.dp.toPx()), 22.dp.toPx(), Offset(head, y))
            drawCircle(Color.White.copy(alpha = 0.95f), 4.dp.toPx(), Offset(head, y))
        }
        Spacer(Modifier.height(Space.m))
        FText(words, Fuse.type.title, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        FText("JKSV and Checkpoint backups are both understood. Nothing is changed until you say so.", Fuse.type.body, color = c.textMuted, align = TextAlign.Center, maxLines = 3)
    }
}

/**
 * "Is this the game?", as its own poster: the game's hero art across the top (its box art, blurred,
 * where it has none), its logo set into it (its name where it has no logo), and its box art lifted
 * onto the banner's edge. Each picture fades in once it has loaded, over the game's own colour, so
 * nothing ever pops; moving to the next game slides the new one in.
 */
@Composable
private fun GameStep(g: SwitchImport.Game, compact: Boolean) {
    val c = Fuse.colors
    val sw = g.entry.switch
    val art = g.card?.art
    val accent = g.card?.accent?.toColor() ?: c.accent
    val banner = if (compact) 136.dp else 176.dp
    val posterWidth = if (compact) 92.dp else 116.dp
    val posterHeight = posterWidth / POSTER_ASPECT
    // How far the poster reaches up into the banner.
    val rise = posterHeight * 0.62f
    val inset = Space.l
    val beside = inset + posterWidth + Space.l
    key(g.key) {
        Appear(visible = true, enter = slideInHorizontally(Fuse.motion.tween(Durations.SLOW, Curves.Enter)) { it / 14 } + fadeIn(Fuse.motion.tween(Durations.BASE, Curves.Fade))) {
            Box(Modifier.fillMaxWidth()) {
                // The banner: the game's own colour first, its art fading in over it.
                val bannerShape = remember { SquircleShape.fraction(0.06f) }
                Box(Modifier.fillMaxWidth().height(banner).clip(bannerShape).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.18f))))) {
                    val wide = art?.hero ?: art?.screenshot
                    Artwork(
                        model = wide ?: art?.boxart ?: art?.grid,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        focusX = if (wide != null) art?.heroFocusX ?: 0.5f else 0.5f,
                        focusY = if (wide != null) art?.heroFocusY ?: 0.35f else 0.3f,
                        backdrop = wide == null,
                        backdropBlur = if (wide == null) 28.dp else 0.dp,
                        fallback = {},
                    )
                    // A shade from below, so the logo always reads.
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.72f))))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = beside, end = inset, bottom = Space.m)) {
                        FText(
                            if (sw?.device == true) "A save for everyone on the console. Is it for" else "Is this your save for",
                            Fuse.type.label, color = Color.White.copy(alpha = 0.82f), maxLines = 1,
                        )
                        Spacer(Modifier.height(Space.xs))
                        val title: @Composable () -> Unit = { FText(g.title, Fuse.type.title, color = Color.White, maxLines = 2) }
                        if (art?.logo != null) {
                            Artwork(
                                art.logo,
                                Modifier.height(if (compact) 44.dp else 58.dp).fillMaxWidth(),
                                contentScale = ContentScale.Fit,
                                focusX = 0f,
                                focusY = 1f,
                                loading = false,
                                fallback = title,
                            )
                        } else {
                            title()
                        }
                    }
                }
                // The poster, lifted onto the banner's edge.
                Poster(g, posterWidth, accent, Modifier.padding(start = inset, top = banner - rise))
                // What the backup is, beside the poster and under the banner.
                Column(Modifier.padding(start = beside, top = banner + Space.m).fillMaxWidth()) {
                    if (art?.logo != null) {
                        FText(g.title, Fuse.type.bodyStrong, maxLines = 2)
                        Spacer(Modifier.height(Space.s))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        sw?.tool?.let { Chip(if (it == "CHECKPOINT") "Checkpoint" else "JKSV", icon = FuseIcons.SdCard) }
                        sw?.user?.let { Chip(it, icon = FuseIcons.UserRound) }
                    }
                    Spacer(Modifier.height(Space.s))
                    FText(listOfNotNull(sw?.savedAt?.let(::whenText), sw?.titleId).joinToString("  ·  ").ifEmpty { g.entry.name }, Fuse.type.caption, color = c.textMuted, maxLines = 2)
                    if (g.target.confidence != SaveImportConfidence.TITLE_ID) {
                        Spacer(Modifier.height(Space.s))
                        FText("Matched by its name, so check it is the right game.", Fuse.type.caption, color = c.warning, maxLines = 2)
                    }
                    if (g.backups.size > 1) {
                        Spacer(Modifier.height(Space.s))
                        FText("Backup ${g.chosen + 1} of ${g.backups.size}${if (g.chosen == 0) ", the newest" else ""}", Fuse.type.caption, color = c.accent, maxLines = 1)
                    }
                }
                // Room for the poster where the details are shorter than it.
                Spacer(Modifier.height(banner - rise + posterHeight))
            }
        }
    }
}

/** The game's box art as a poster: a soft shadow under it, rising a little as it arrives. */
@Composable
private fun Poster(g: SwitchImport.Game, width: Dp, accent: Color, modifier: Modifier = Modifier) {
    val shape = remember { SquircleShape.fraction(0.07f) }
    val reduced = Fuse.motion.reduced
    var shown by remember(g.key) { mutableStateOf(false) }
    LaunchedEffect(g.key) { shown = true }
    val lift by fuselineFloat(if (shown) 1f else 0f, Fuse.motion.tween(Durations.SLOW, Curves.Enter), label = "posterLift")
    Box(
        modifier.width(width).aspectRatio(POSTER_ASPECT)
            .graphicsLayer {
                if (!reduced) {
                    translationY = (1f - lift) * 18.dp.toPx()
                    val s = 0.94f + 0.06f * lift
                    scaleX = s
                    scaleY = s
                }
            }
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(accent.copy(alpha = 0.35f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
    ) {
        val art = g.card?.art
        Artwork(
            model = art?.boxart ?: art?.grid ?: art?.square ?: art?.icon,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            // Its name is right beside it: a made-up poster carries only its monogram.
            fallback = { GeneratedArt(g.title, accent, slot = ArtSlot.ICON) },
        )
    }
}

private const val POSTER_ASPECT = 0.72f

@Composable
private fun Cover(g: SwitchImport.Game, width: Dp) {
    val accent = g.card?.accent?.toColor() ?: Fuse.colors.accent
    val shape = remember { SquircleShape.fraction(0.08f) }
    Box(Modifier.width(width).aspectRatio(POSTER_ASPECT).clip(shape).background(accent.copy(alpha = 0.2f))) {
        val art = g.card?.art
        Artwork(
            model = art?.boxart ?: art?.grid ?: art?.square ?: art?.icon,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            // Small covers take the icon form, so a made-up cover's name never gets squeezed.
            fallback = { GeneratedArt(g.title, accent, slot = if (width < 64.dp) ArtSlot.ICON else ArtSlot.BOX) },
        )
    }
}

/** "Whose save is it?": everyone on this Fuse, as large faces; any number of them. */
@Composable
private fun PeopleStep(g: SwitchImport.Game, profiles: List<ProfileInfo>, me: String?, focused: Int?, onToggle: (Int) -> Unit) {
    val c = Fuse.colors
    Column {
        FText("Whose save is ${g.title}?", Fuse.type.title, maxLines = 2)
        Spacer(Modifier.height(Space.xs))
        FText("Choose one person, several, or everyone. Each gets their own copy.", Fuse.type.body, color = c.textMuted, maxLines = 2)
        Spacer(Modifier.height(Space.xl))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterHorizontally)) {
            profiles.forEachIndexed { i, p ->
                Person(p.name, p.avatar, here = p.id == me, chosen = p.id in g.people, focused = focused == i) { onToggle(i) }
            }
            Person("Everyone", null, here = false, chosen = profiles.isNotEmpty() && g.people.size == profiles.size, focused = focused == profiles.size) { onToggle(profiles.size) }
        }
        Spacer(Modifier.height(Space.l))
        val names = profiles.filter { it.id in g.people }.map { it.name }
        FText(
            when (names.size) {
                0 -> "Choose at least one person."
                1 -> "Goes to ${names[0]}'s saves in Fuse One."
                else -> "Copied to ${names.dropLast(1).joinToString(", ")} and ${names.last()}. Each keeps their own save."
            },
            Fuse.type.caption, color = if (names.isEmpty()) c.warning else c.textMuted, align = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Person(name: String, avatar: String?, here: Boolean, chosen: Boolean, focused: Boolean, onClick: () -> Unit) {
    val c = Fuse.colors
    val lift by fuselineFloat(if (focused) 1f else 0f, Fuse.motion.focusSpring(), label = "personLift")
    val on by fuselineFloat(if (chosen) 1f else 0f, Fuse.motion.focusSpring(), label = "personOn")
    val nameColor by fuselineColor(if (chosen || focused) c.text else c.textMuted, Fuse.motion.tween(Durations.FAST), label = "personName")
    val size = 72.dp
    val reduced = Fuse.motion.reduced
    Column(
        Modifier.width(size + Space.l)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .graphicsLayer { val s = if (reduced) 1f else 1f + 0.06f * lift; scaleX = s; scaleY = s },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (avatar != null) {
                ProfileAvatar(avatar, size, ring = if (focused) c.focus else if (chosen) c.accent else null, dim = !chosen && !focused)
            } else {
                Box(
                    Modifier.size(size).clip(CircleShape).background(if (chosen) c.accent.copy(alpha = 0.22f) else c.surfaceRaised)
                        .then(if (focused) Modifier.border(3.dp, c.focus, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    FuseIcon(FuseIcons.Users, size = 30.dp, tint = if (chosen) c.accent else c.textMuted)
                }
            }
            // The tick that says this person gets a copy.
            Box(
                Modifier.align(Alignment.BottomEnd).size(24.dp).graphicsLayer { alpha = on; scaleX = 0.6f + 0.4f * on; scaleY = 0.6f + 0.4f * on }
                    .clip(CircleShape).background(c.accent),
                contentAlignment = Alignment.Center,
            ) { FuseIcon(FuseIcons.Check, size = 14.dp, tint = c.onAccent) }
        }
        Spacer(Modifier.height(Space.s))
        FText(name, Fuse.type.bodyStrong, color = { nameColor }, maxLines = 1, align = TextAlign.Center)
        FText(if (here) "Playing here" else " ", Fuse.type.caption, color = c.accent, maxLines = 1, align = TextAlign.Center)
    }
}

/** Every game once more, whose it becomes and where it goes, before anything is written. */
@Composable
private fun Review(f: SwitchImport, profiles: List<ProfileInfo>) {
    val c = Fuse.colors
    val chosen = f.games.filter { it.included && it.people.isNotEmpty() }
    Column {
        FText(if (chosen.isEmpty()) "Nothing to import" else "Ready to import", Fuse.type.title, maxLines = 1)
        Spacer(Modifier.height(Space.xs))
        FText("Saves already here are kept in their owner's history first. Your Switch's files stay as they are.", Fuse.type.body, color = c.textMuted, maxLines = 3)
        Spacer(Modifier.height(Space.l))
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (g in f.games) ReviewRow(g, profiles, done = null)
        }
    }
}

@Composable
private fun ReviewRow(g: SwitchImport.Game, profiles: List<ProfileInfo>, done: Boolean?) {
    val c = Fuse.colors
    val skipped = !g.included || g.people.isEmpty()
    Row(
        Modifier.fillMaxWidth().clip(SquircleShape.fraction(0.18f)).background(c.surfaceRaised.copy(alpha = if (skipped) 0.35f else 0.7f)).padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(g, 40.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(g.title, Fuse.type.bodyStrong, color = if (skipped) c.textMuted else c.text, maxLines = 1)
            FText(if (skipped) "Left out" else where(g.target), Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (!skipped) {
            Spacer(Modifier.width(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                profiles.filter { it.id in g.people }.take(4).forEach { ProfileAvatar(it.avatar, 26.dp) }
            }
        }
        if (done != null) {
            Spacer(Modifier.width(Space.m))
            FuseIcon(if (done) FuseIcons.CircleCheck else FuseIcons.CircleX, size = 20.dp, tint = if (done) c.success else c.danger)
        }
    }
}

@Composable
private fun Done(f: SwitchImport) {
    val c = Fuse.colors
    val r = f.result
    Appear(visible = true, enter = fadeIn(Fuse.motion.tween(Durations.BASE, Curves.Fade))) {
        Column(Modifier.fillMaxWidth()) {
            when {
                f.problem != null -> {
                    FText("Nothing imported", Fuse.type.title, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    FText(f.problem!!, Fuse.type.body, color = c.textMuted, maxLines = 5)
                }
                r == null -> FText("No games from this card are in your library", Fuse.type.title, maxLines = 2)
                else -> {
                    val failed = r.failures.map { it.entry }.toSet()
                    FText(if (failed.isEmpty()) "Saves imported" else "${r.imported} imported, ${failed.size} didn't", Fuse.type.title, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    val emulators = f.games.filter { it.included && it.people.isNotEmpty() }.map { emulatorName(it.target.query.emulatorId) }.distinct()
                    val inEmulators = emulators.joinToString(" and ").ifEmpty { "the emulator" }
                    FText(if (r.queued) "They're in $inEmulators, and go to Fuse One when it's back." else "They're in $inEmulators and in Fuse One.", Fuse.type.body, color = c.textMuted, maxLines = 2)
                    Spacer(Modifier.height(Space.l))
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (g in f.games.filter { it.included && it.people.isNotEmpty() }) {
                            ReviewRow(g, emptyList(), done = g.entry.id !in failed)
                            r.failures.firstOrNull { it.entry == g.entry.id }?.let { FText(it.message, Fuse.type.caption, color = c.danger, maxLines = 3) }
                        }
                    }
                }
            }
        }
    }
}

/** Where a save goes, short enough for one line: the emulator, then the end of its folder. */
private fun where(t: SaveImportTarget): String {
    val tail = t.destination.replace('\\', '/').trimEnd('/').split('/').takeLast(2).joinToString("/")
    return "${emulatorName(t.query.emulatorId)}  ·  …/$tail"
}

/** An emulator's name from its id ("linux.eden" is Eden). */
private fun emulatorName(id: String): String = when (val base = id.substringAfterLast('.')) {
    "eden", "eden-nightly" -> "Eden"
    "kenji-nx" -> "Kenji-NX"
    "yuzu", "suyu", "torzu" -> base
    else -> base.replaceFirstChar { it.uppercase() }
}

/** "1 Oct 2026, 12:00", in this device's time zone. */
private fun whenText(seconds: Long): String {
    val t = Instant.fromEpochSeconds(seconds).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[t.monthNumber - 1]
    return "${t.dayOfMonth} $month ${t.year}, ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
}
