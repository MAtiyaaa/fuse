package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.AchievementState
import io.github.matiyaaa.fuse.model.RecentAchievement
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.flourishOn
import io.github.matiyaaa.fuse.ui.fuseline.Curves
import io.github.matiyaaa.fuse.ui.fuseline.FuselineValue
import io.github.matiyaaa.fuse.ui.fuseline.infiniteRepeatable
import io.github.matiyaaa.fuse.ui.fuseline.rememberLoopClock
import io.github.matiyaaa.fuse.ui.fuseline.spring
import io.github.matiyaaa.fuse.ui.fuseline.tween
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PlayUnlocks
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The achievements unlocked in the game being played, each shown big as it comes, over whatever
 * this screen shows: for a screen in view while the game runs (the second screen, or the touch
 * screen below when the menus are there). While it is here, RetroAchievements is asked about new
 * unlocks every minute ([io.github.matiyaaa.fuse.ui.shell.store.AchievementOps.showLive]). Each
 * stays [MOMENT_MS], or until touched; several in a row follow one another.
 */
@Composable
internal fun AchievementMoments(store: FuseStore, modifier: Modifier = Modifier) {
    val play by store.achievements.playing.collectAsState()
    val running = play.game != null && play.endedAt == null
    DisposableEffect(running) {
        val stop = if (running) store.achievements.showLive() else null
        onDispose { stop?.invoke() }
    }
    // Only unlocks noticed while this is here and the game runs: none from before, nor its recap.
    val seen = remember(play.game) { play.unlocks.mapTo(HashSet()) { it.achievement.id } }
    val queue = remember(play.game) { mutableStateListOf<RecentAchievement>() }
    LaunchedEffect(play.unlocks, running) {
        for (u in play.unlocks) if (seen.add(u.achievement.id) && running) queue += u
    }
    val current = queue.firstOrNull() ?: return
    LaunchedEffect(current) {
        delay(MOMENT_MS)
        queue.remove(current)
    }
    AchievementMoment(current, play.set, waiting = queue.size - 1, modifier = modifier) { queue.remove(current) }
}

/**
 * One unlock, filling the screen: its badge in a ring of light (rays turning slowly behind it and
 * sparks flying out as it lands, where motion allows), what it is called and asked, its points,
 * whether it was hardcore, and how far through the game's set it leaves the person.
 */
@Composable
internal fun AchievementMoment(
    unlock: RecentAchievement,
    set: AchievementState?,
    waiting: Int,
    modifier: Modifier = Modifier,
    onDone: () -> Unit,
) {
    val c = Fuse.colors
    val motion = Fuse.motion
    val flourish = motion.flourishOn(Fuse.quality)
    val a = unlock.achievement
    val complete = set != null && set.total > 0 && set.earned >= set.total
    // Gold for the set's last and for hardcore, the theme's accent otherwise.
    val tint = if (complete || unlock.hardcore) GOLD else c.accent
    val arrive = remember(a.id) { FuselineValue(if (motion.reduced) 1f else 0f) }
    val burst = remember(a.id) { FuselineValue(if (flourish) 0f else 1f) }
    LaunchedEffect(a.id) {
        if (!motion.reduced) launch { arrive.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
        if (flourish) burst.animateTo(1f, tween(BURST_MS, easing = Curves.Exit))
    }
    val clock = if (flourish) rememberLoopClock(label = "achievementMoment") else null
    val turn = clock?.animateFloat(0f, 360f, infiniteRepeatable(tween(RAYS_TURN_MS, easing = Curves.Linear)), label = "rays")
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(c.ink.copy(alpha = 0.9f))
            .background(Brush.radialGradient(listOf(tint.copy(alpha = 0.28f), Color.Transparent)))
            // Touched anywhere, it goes; touches never reach what is beneath.
            .pointerInput(a.id) { detectTapGestures { onDone() } },
    ) {
        val wide = maxWidth > maxHeight * 1.25f && maxHeight < 520.dp
        val badge = when {
            wide -> minOf(maxHeight * 0.42f, 132.dp)
            else -> minOf(maxWidth * 0.36f, maxHeight * 0.27f, 168.dp)
        }
        val halo: @Composable () -> Unit = {
            Halo(a.badgeUrl, badge, tint, arrive.value, burst.value, turn?.value ?: 0f)
        }
        val words: @Composable (Alignment.Horizontal, TextAlign) -> Unit = { align, textAlign ->
            Column(horizontalAlignment = align, modifier = Modifier.graphicsLayer { alpha = ((arrive.value - 0.35f) / 0.65f).coerceIn(0f, 1f) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FuseIcon(FuseIcons.Trophy, size = 16.dp, tint = tint)
                    FText(if (complete) "SET COMPLETE" else "ACHIEVEMENT UNLOCKED", Fuse.type.overline, color = tint, maxLines = 1)
                }
                Spacer(Modifier.height(Space.s))
                FText(a.title, if (wide) Fuse.type.title else Fuse.type.display, maxLines = 2, align = textAlign)
                if (a.description.isNotBlank()) {
                    Spacer(Modifier.height(Space.xs))
                    FText(a.description, Fuse.type.body, color = c.textMuted, maxLines = 3, align = textAlign)
                }
                Spacer(Modifier.height(Space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    if (a.points > 0) Chip("+${a.points} points", FuseIcons.Star, tint)
                    if (unlock.hardcore) Chip("Hardcore", FuseIcons.Flame, GOLD)
                    if (complete) Chip(if (unlock.hardcore) "Mastered" else "Completed", FuseIcons.BadgeCheck, GOLD)
                }
                if (set != null && set.total > 0) {
                    Spacer(Modifier.height(Space.m))
                    Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), horizontalAlignment = align) {
                        FText(
                            "${set.earned} of ${set.total}" + if (set.source.hasPoints && set.points > 0) "  ·  ${set.pointsEarned} of ${set.points} points" else "",
                            Fuse.type.caption, color = c.textMuted, maxLines = 1,
                        )
                        Spacer(Modifier.height(6.dp))
                        ProgressBar(set.progress, Modifier.fillMaxWidth(), color = tint, height = 5.dp)
                    }
                }
            }
        }
        // The game it was in, and how many more are waiting their turn.
        val footer: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                FText(unlock.gameTitle, Fuse.type.caption, color = c.textFaint, maxLines = 1)
                if (waiting > 0) {
                    FText(
                        if (waiting == 1) "1 more" else "$waiting more", Fuse.type.caption, color = c.text, maxLines = 1,
                        modifier = Modifier.clip(PillShape).background(c.text.copy(alpha = 0.1f)).padding(horizontal = Space.s, vertical = 2.dp),
                    )
                }
            }
        }
        // Centred in the room, the footer under it: on a short screen they close up, never overlap.
        Column(Modifier.fillMaxSize().padding(vertical = Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            if (wide) {
                Row(Modifier.padding(horizontal = Space.xl).widthIn(max = 880.dp), verticalAlignment = Alignment.CenterVertically) {
                    halo()
                    Spacer(Modifier.width(Space.xl))
                    Box(Modifier.weight(1f)) { words(Alignment.Start, TextAlign.Start) }
                }
            } else {
                Column(Modifier.padding(horizontal = Space.l).widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    halo()
                    Spacer(Modifier.height(Space.l))
                    words(Alignment.CenterHorizontally, TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(Space.m))
            footer()
        }
    }
}

/**
 * The badge at the heart of the moment: on a glow in [tint], turning rays behind it ([turn],
 * degrees), a ring round it, and sparks flying outward as it lands ([burst], 0 to 1). It grows
 * into place with [arrive].
 */
@Composable
private fun Halo(badge: String, size: Dp, tint: Color, arrive: Float, burst: Float, turn: Float) {
    val c = Fuse.colors
    // Laid out a little wider than the badge; its light spills past, under what is round it.
    Box(Modifier.size(size * 1.5f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val r = size.toPx() * 1.05f
            drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.45f * arrive), Color.Transparent), center, r * 0.9f), r * 0.9f, center)
            // Rays, faint and slow, from behind the badge.
            for (i in 0 until RAYS) {
                val start = turn + i * (360f / RAYS)
                drawArc(
                    Brush.radialGradient(listOf(tint.copy(alpha = 0.2f * arrive), Color.Transparent), center, r),
                    start, 360f / RAYS / 3.2f, useCenter = true,
                )
            }
            // Sparks, outward from the ring as it lands, fading as they go.
            if (burst < 1f) {
                for (i in 0 until SPARKS) {
                    val angle = (i * 360.0 / SPARKS + (i % 3) * 7.0) * PI / 180.0
                    val reach = r * (0.5f + 0.48f * burst * (0.8f + (i % 4) * 0.08f))
                    val p = Offset(center.x + reach * cos(angle).toFloat(), center.y + reach * sin(angle).toFloat())
                    val dot = (if (i % 2 == 0) 3.2f else 2.2f) * density * (1f - burst * 0.5f)
                    drawCircle(lerp(tint, Color.White, 0.5f).copy(alpha = (1f - burst).coerceIn(0f, 1f)), dot, p)
                }
            }
        }
        val scale = 0.6f + 0.4f * arrive
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = arrive.coerceIn(0f, 1f)
                }
                .clip(SquircleShape.fraction(0.24f))
                .background(c.surfaceRaised)
                .border(3.dp, Brush.linearGradient(listOf(lerp(tint, Color.White, 0.35f), tint)), SquircleShape.fraction(0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(
                badge, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fadeIn = false,
                fallback = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { FuseIcon(FuseIcons.Trophy, size = size * 0.4f, tint = tint) } },
            )
        }
    }
}

/** A short fact under the title: its icon and words on a soft pill in [tint]. */
@Composable
private fun Chip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(
        Modifier.clip(PillShape).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.3f), PillShape)
            .padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        FuseIcon(icon, size = 14.dp, tint = tint)
        FText(text, Fuse.type.label, color = Fuse.colors.text, maxLines = 1)
    }
}

/**
 * The unlocks of the game just played, said once on returning to Fuse: how many, in which game.
 * [show] is given the words, for a toast.
 */
@Composable
internal fun AchievementRecap(store: FuseStore, show: (String) -> Unit) {
    val play by store.achievements.playing.collectAsState()
    val said = remember { HashSet<Long>() }
    LaunchedEffect(play.endedAt) {
        val ended = play.endedAt ?: return@LaunchedEffect
        recapWords(play)?.let { words -> if (said.add(ended)) show(words) }
    }
}

/** What the recap says of [play], or null when nothing was unlocked in it. */
internal fun recapWords(play: PlayUnlocks): String? {
    val n = play.unlocks.size
    if (n == 0) return null
    val game = play.unlocks.first().gameTitle.ifBlank { play.set?.title.orEmpty() }
    val points = play.unlocks.sumOf { it.achievement.points }
    val what = if (n == 1) "1 achievement" else "$n achievements"
    return buildString {
        append(what).append(" unlocked")
        if (game.isNotBlank()) append(" in ").append(game)
        if (points > 0) append(", ").append(points).append(" points")
    }
}

/** How long one unlock stays on screen. */
private const val MOMENT_MS = 7_000L

/** How long the sparks fly; how long the rays take to turn once. */
private const val BURST_MS = 1_400
private const val RAYS_TURN_MS = 24_000

/** How many rays turn behind the badge, and how many sparks fly from it. */
private const val RAYS = 16
private const val SPARKS = 20

/** Gold, for a set's last unlock and for hardcore ones, whatever the theme. */
private val GOLD = Color(0xFFFFC24A)
