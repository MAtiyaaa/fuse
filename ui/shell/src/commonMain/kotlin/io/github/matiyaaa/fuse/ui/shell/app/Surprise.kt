package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.GameArtStyle
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.ui.designsystem.components.ButtonKind
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.FuseButton
import io.github.matiyaaa.fuse.ui.designsystem.components.Overlay
import io.github.matiyaaa.fuse.ui.designsystem.components.OverlayEdge
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.LayerPriority
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.sound.SoundCue
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.components.LocalGameArt
import io.github.matiyaaa.fuse.ui.shell.components.PosterGameArt
import io.github.matiyaaa.fuse.ui.shell.components.SquareGameArt
import io.github.matiyaaa.fuse.ui.shell.components.agoText
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameQuery
import io.github.matiyaaa.fuse.ui.shell.store.impl.TimeWords
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.flow.first

/** Surprise Me, open: drawing from every game, or one system's ([platform]). */
data class SurpriseSpec(val platform: PlatformId? = null)

/**
 * How Surprise Me chooses: from the games that can be played now, those never played first, then
 * those barely played and left a while, then those not played for long, then any. It never lands
 * on the same game twice in one go ([pick]'s exclude).
 */
internal object SurprisePicker {
    /** Played less than this counts as barely played. */
    const val BARELY_SECONDS = 30 * 60L

    /** Left this long, a barely played game is worth another go; this long, any game is. */
    const val RESTED_MS = 14 * TimeWords.DAY_MS
    const val FORGOTTEN_MS = 60 * TimeWords.DAY_MS

    /** How much likelier each kind of game is to be chosen. */
    fun weight(g: GameCard, now: Long): Int {
        val never = g.playSeconds == 0L && g.lastPlayedAt == null
        val since = g.lastPlayedAt?.let { now - it } ?: Long.MAX_VALUE
        return when {
            never -> 6
            g.playSeconds < BARELY_SECONDS && since >= RESTED_MS -> 4
            since >= FORGOTTEN_MS -> 2
            else -> 1
        }
    }

    fun pick(games: List<GameCard>, now: Long, random: Random, exclude: Set<GameId> = emptySet()): GameCard? {
        val open = games.filter { !it.dimmed }
        val pool = open.filter { it.id !in exclude }.ifEmpty { open }
        if (pool.isEmpty()) return null
        val weights = pool.map { weight(it, now) }
        var r = random.nextInt(weights.sum())
        pool.forEachIndexed { i, g ->
            if (r < weights[i]) return g
            r -= weights[i]
        }
        return pool.last()
    }

    /** Why [g] is a good one to land on, in a few words. */
    fun why(g: GameCard, now: Long): String {
        val since = g.lastPlayedAt?.let { now - it }
        return when {
            g.playSeconds == 0L && g.lastPlayedAt == null -> if (g.addedAt > 0) "Never played, added ${agoText(g.addedAt, now)}" else "Never played"
            since != null && g.playSeconds < BARELY_SECONDS && since >= RESTED_MS ->
                "Played for ${playtimeText(g.playSeconds).replaceFirstChar { it.lowercase() }}, last ${agoText(g.lastPlayedAt!!, now)}"
            since != null && since >= FORGOTTEN_MS -> "Not played for ${agoText(g.lastPlayedAt!!, now).removeSuffix(" ago")}"
            else -> "${playtimeText(g.playSeconds)} played"
        }
    }

    /**
     * The reel that spins to [pick]: [length] covers drawn from [games], [pick] at [LANDING] with a
     * few after it, so it stops in a full row.
     */
    fun reel(games: List<GameCard>, pick: GameCard, random: Random, length: Int = REEL_LENGTH): List<GameCard> {
        val others = games.filter { it.id != pick.id && !it.dimmed }.ifEmpty { listOf(pick) }
        fun any() = others[random.nextInt(others.size)]
        return List(LANDING) { any() } + pick + List(length - LANDING - 1) { any() }
    }

    const val REEL_LENGTH = 30

    /** Where on the reel the pick sits. */
    const val LANDING = 26
}

/**
 * Surprise Me: a reel of covers that spins and slows to a stop on a game worth playing
 * ([SurprisePicker]), then says what it is and why, with Play, Spin Again and its page. A presses
 * the chosen button (Play first); X spins again; Y opens its page; Left and Right walk the buttons;
 * B closes. Touch presses them.
 * Under Reduced motion it lands at once.
 */
@Composable
internal fun SurpriseOverlay(app: AppState) {
    val spec = app.surprise
    var shown by remember { mutableStateOf(spec) }
    if (spec != null) shown = spec
    var games by remember(spec) { mutableStateOf<List<GameCard>?>(null) }
    LaunchedEffect(spec) {
        if (spec == null) return@LaunchedEffect
        games = app.store.library.games(GameQuery(platform = spec.platform)).first()
        app.platform.sounds.play(SoundCue.OPEN)
    }
    val random = remember(spec) { Random(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    var exclude by remember(spec) { mutableStateOf(emptySet<GameId>()) }
    var reel by remember(spec) { mutableStateOf<List<GameCard>>(emptyList()) }
    var spins by remember(spec) { mutableIntStateOf(0) }
    var landed by remember(spec) { mutableStateOf(false) }
    var button by remember(spec) { mutableIntStateOf(0) }
    val pick = reel.getOrNull(SurprisePicker.LANDING)
    fun close() {
        app.surprise = null
        app.platform.sounds.play(SoundCue.CLOSE)
    }
    fun spin() {
        val list = games ?: return
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val next = SurprisePicker.pick(list, now, random, exclude) ?: return
        exclude = exclude + next.id
        reel = SurprisePicker.reel(list, next, random)
        landed = false
        button = 0
        spins++
    }
    LaunchedEffect(games) { if (games != null && reel.isEmpty()) spin() }
    val actions = pick?.let { g ->
        listOf(
            PreviewAction("Play", FuseIcons.Play, primary = true) { app.surprise = null; app.play(g) },
            PreviewAction("Spin Again", FuseIcons.Dice) { spin() },
            PreviewAction("Details", FuseIcons.Info) { app.surprise = null; app.go(Route.GameInfo(g.id)) },
        )
    }.orEmpty()
    if (spec != null) {
        InputLayer(priority = LayerPriority.DIALOG + 1, modal = true) { e ->
            when (e.action) {
                NavAction.BACK -> { close(); NavResult.CONSUMED }
                NavAction.LEFT -> if (landed && button > 0) { button--; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.RIGHT -> if (landed && button < actions.lastIndex) { button++; NavResult.MOVED } else NavResult.BLOCKED
                NavAction.SELECT -> if (landed) { actions.getOrNull(button)?.run?.invoke(); NavResult.ACTIVATED } else NavResult.CONSUMED
                NavAction.CONTEXT -> { spin(); NavResult.ACTIVATED }
                NavAction.SEARCH -> if (landed) { actions.getOrNull(2)?.run?.invoke(); NavResult.ACTIVATED } else NavResult.CONSUMED
                else -> NavResult.CONSUMED
            }
        }
    }
    Overlay(visible = spec != null, onDismiss = ::close, edge = OverlayEdge.CENTER) {
        val s = shown ?: return@Overlay
        val c = Fuse.colors
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val room = maxWidth - Space.l * 2
            val short = maxHeight < 520.dp
            val coverHeight = if (short) minOf(maxHeight * 0.36f, 170.dp) else minOf(maxHeight * 0.34f, 260.dp)
            Panel(Modifier.widthIn(max = minOf(900.dp, room))) {
                Column(Modifier.padding(vertical = if (short) Space.l else Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        FuseIcon(FuseIcons.Dice, size = 16.dp, tint = c.accent)
                        FText(if (s.platform != null) "SURPRISE ME, FROM THIS SYSTEM" else "SURPRISE ME", Fuse.type.overline, color = c.accent, maxLines = 1)
                    }
                    Spacer(Modifier.height(if (short) Space.m else Space.l))
                    val list = games
                    when {
                        list == null -> Spacer(Modifier.height(if (short) 150.dp else 220.dp))
                        pick == null -> {
                            FText("No games to choose from yet", Fuse.type.title, maxLines = 1)
                            Spacer(Modifier.height(Space.s))
                            FText("Add some to your library and come back for a surprise.", Fuse.type.body, color = c.textMuted, maxLines = 2)
                        }
                        else -> {
                            Reel(reel, spins, coverHeight, onTick = { app.platform.sounds.play(SoundCue.MOVE) }) {
                                landed = true
                                app.platform.sounds.play(SoundCue.SELECT)
                            }
                            Spacer(Modifier.height(if (short) Space.m else Space.l))
                            val words = Modifier.padding(horizontal = Space.xl).graphicsLayer { alpha = if (landed) 1f else 0.25f }
                            FText(if (landed) pick.title else " ", Fuse.type.title, maxLines = 2, align = TextAlign.Center, modifier = words)
                            Spacer(Modifier.height(Space.xs))
                            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                            FText(
                                if (landed) listOfNotNull(pick.platformShort.takeIf { it.isNotBlank() }, SurprisePicker.why(pick, now)).joinToString("  ·  ") else "Choosing…",
                                Fuse.type.body, color = c.textMuted, maxLines = 2, align = TextAlign.Center, modifier = words,
                            )
                            Spacer(Modifier.height(if (short) Space.m else Space.l))
                            Row(
                                Modifier.padding(horizontal = Space.l).graphicsLayer { alpha = if (landed) 1f else 0.4f },
                                horizontalArrangement = Arrangement.spacedBy(Space.m),
                            ) {
                                actions.forEachIndexed { i, a ->
                                    FuseButton(
                                        a.label, selected = landed && button == i, icon = a.icon,
                                        kind = if (a.primary) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                                        onClick = { if (landed || i == 1) { button = i; a.run() } },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The reel: [cards] side by side, sliding past a lit middle and slowing to a stop on the pick
 * ([SurprisePicker.LANDING]).
 * Each spin ([spin]) starts it again. [onTick] as each cover passes the middle; [onLanded] once it stops.
 */
@Composable
private fun Reel(cards: List<GameCard>, spin: Int, height: Dp, onTick: () -> Unit, onLanded: () -> Unit) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val style = LocalGameArt.current
    val width = if (style == GameArtStyle.POSTER) height * (2f / 3f) else height
    val step = width + Space.m
    val target = SurprisePicker.LANDING.toFloat()
    val pos = remember { FuselineValue(0f) }
    LaunchedEffect(spin) {
        if (motion.reduced) {
            pos.snapTo(target)
        } else {
            pos.snapTo(0f)
            pos.animateTo(target, tween(SPIN_MS, easing = Curves.Enter))
        }
        onLanded()
    }
    // A tick as each cover crosses the middle, fast at first and slower to the end.
    LaunchedEffect(spin) {
        var last = 0
        snapshotFlow { floor(pos.value + 0.5f).toInt() }.collect { i ->
            if (i != last) {
                last = i
                if (i <= SurprisePicker.LANDING) onTick()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height * 1.12f), contentAlignment = Alignment.Center) {
        // The middle, lit.
        Box(
            Modifier.size(width * 1.12f + 12.dp, height * 1.12f + 12.dp)
                .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.32f), Color.Transparent))),
        )
        val p = pos.value
        val first = (floor(p).toInt() - VISIBLE).coerceAtLeast(0)
        val last = (floor(p).toInt() + VISIBLE).coerceAtMost(cards.lastIndex)
        for (i in first..last) {
            val d = i - p
            val near = (1f - abs(d)).coerceIn(0f, 1f)
            val scale = 0.82f + 0.3f * near
            val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.6f)
            Box(
                Modifier
                    .offset(x = step * d)
                    .size(width, height)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - abs(d) * 0.28f).coerceIn(0.2f, 1f)
                    }
                    .clip(shape)
                    .then(if (near > 0.5f) Modifier.border(2.dp, c.accent.copy(alpha = near), shape) else Modifier),
            ) {
                val card = cards[i]
                val generated: @Composable () -> Unit = { GeneratedArt(card.title, Color(card.accent), Modifier.matchParentSize()) }
                if (style == GameArtStyle.POSTER) PosterGameArt(card.art, Modifier.matchParentSize(), fallback = generated)
                else SquareGameArt(card.art, Modifier.matchParentSize(), fallback = generated)
            }
        }
    }
}

/** How long a spin takes, and how many covers show each side of the middle. */
private const val SPIN_MS = 2_600
private const val VISIBLE = 3
