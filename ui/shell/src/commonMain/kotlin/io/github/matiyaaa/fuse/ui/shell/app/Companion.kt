package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.matiyaaa.fuse.model.BiosState
import io.github.matiyaaa.fuse.model.DualScreenMode
import io.github.matiyaaa.fuse.model.GameId
import io.github.matiyaaa.fuse.model.PlatformId
import io.github.matiyaaa.fuse.model.RenderQuality
import io.github.matiyaaa.fuse.ui.designsystem.components.Chip
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.ProgressBar
import io.github.matiyaaa.fuse.ui.designsystem.components.SectionLabel
import io.github.matiyaaa.fuse.ui.designsystem.components.StatusCluster
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.media.ArtSlot
import io.github.matiyaaa.fuse.ui.designsystem.media.Artwork
import io.github.matiyaaa.fuse.ui.designsystem.media.GeneratedArt
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.designsystem.theme.toColor
import io.github.matiyaaa.fuse.ui.shell.components.playtimeText
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.GameCard
import io.github.matiyaaa.fuse.ui.shell.store.GameDetail
import io.github.matiyaaa.fuse.ui.shell.store.PlatformCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the main screen has in focus, shared with the companion screen. Keys are [GameId] or
 * [PlatformId]; null when nothing specific is focused.
 */
object Spotlight {
    private val state = MutableStateFlow<Any?>(null)
    val focused: StateFlow<Any?> = state

    fun set(key: Any?) {
        state.value = key
    }
}

/**
 * Content for a second screen (dual-screen handhelds, an external display). The Android app shows
 * it in its own activity on the other display; it is touch only and never takes controller input,
 * so the main screen keeps focus.
 */
@Composable
fun CompanionApp(store: FuseStore, platform: PlatformUi, mode: DualScreenMode) {
    val prefs by store.prefs.collectAsState()
    val spec = ThemePresets.byId(prefs.themeId)
    FuseTheme(
        spec = spec,
        motion = prefs.motion,
        quality = RenderQuality.of(prefs.performance, platform.device, prefs.lowPower),
        glass = prefs.glass,
        highContrastFocus = prefs.highContrastFocus,
    ) {
        val home by store.library.home.collectAsState()
        val status by platform.status.collectAsState()
        val focused by Spotlight.focused.collectAsState()
        val time = rememberClockText(prefs.clock24h)
        val playing = home.playtime.currentGame
        Box(Modifier.fillMaxSize().background(Fuse.colors.ink)) {
            val content: Any? = when {
                mode == DualScreenMode.GAME_COMPANION && playing != null -> playing
                mode == DualScreenMode.LIBRARY_COMPANION -> focused
                playing != null -> playing
                else -> null
            }
            val motion = Fuse.motion
            AnimatedContent(
                targetState = content,
                transitionSpec = { fadeIn(motion.fade(Durations.SLOW)) togetherWith fadeOut(motion.fade(Durations.BASE)) },
                contentKey = { (it as? GameCard)?.id ?: it },
                label = "companion",
            ) { target ->
                when (target) {
                    is GameCard -> NowPlaying(target, home.playtime.currentSince)
                    is GameId -> FocusedGame(store, target)
                    is PlatformId -> FocusedPlatform(store.library.platforms.collectAsState().value.firstOrNull { it.platform.id == target })
                    else -> Idle(time)
                }
            }
            if (prefs.display.companionShowsPerformance) {
                val metrics by platform.performance.collectAsState()
                io.github.matiyaaa.fuse.ui.shell.components.PerformanceOverlay(
                    metrics,
                    Modifier.align(Alignment.BottomEnd).padding(Space.l),
                )
            }
            StatusCluster(
                status,
                time,
                Modifier.align(Alignment.TopEnd).padding(horizontal = Space.l, vertical = Space.m),
                showWifi = prefs.showWifi,
                showBluetooth = prefs.showBluetooth,
            )
        }
    }
}

@Composable
private fun Idle(time: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        FuseMark(Modifier.size(56.dp))
        Spacer(Modifier.height(Space.l))
        FText(time, Fuse.type.numericLarge)
        FText(formatDate(), Fuse.type.body, color = Fuse.colors.textMuted)
    }
}

/** Art-lit backdrop shared by the companion layouts: the art, darkened toward the bottom. */
@Composable
private fun Backdrop(model: Any?, accent: Color, focusX: Float = 0.5f, focusY: Float = 0.35f) {
    Box(Modifier.fillMaxSize()) {
        Artwork(model, Modifier.fillMaxSize(), focusX = focusX, focusY = focusY, fallback = {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent))))
        })
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Fuse.colors.ink.copy(alpha = 0.35f), 0.55f to Fuse.colors.ink.copy(alpha = 0.7f), 1f to Fuse.colors.ink),
            ),
        )
    }
}

@Composable
private fun FocusedGame(store: FuseStore, id: GameId) {
    val flow = remember(id) { store.library.game(id) }
    val detail by flow.collectAsState(initial = null)
    val d = detail ?: return
    val accent = d.platform.accent.toColor()
    Box(Modifier.fillMaxSize()) {
        Backdrop(d.art.hero ?: d.art.grid, accent, d.art.heroFocusX, d.art.heroFocusY)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.xl)) {
            val wide = maxWidth > maxHeight
            val maxH = maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.Bottom) {
                    Cover(d, Modifier.fillMaxHeight(0.8f))
                    GameFacts(d, Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                    Cover(d, Modifier.height(maxH * 0.42f))
                    Spacer(Modifier.height(Space.l))
                    GameFacts(d, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Cover(d: GameDetail, modifier: Modifier) {
    val shape = SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.7f)
    Box(modifier.aspectRatio(0.72f).clip(shape)) {
        Artwork(d.art.boxart ?: d.art.grid ?: d.art.icon, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, fallback = {
            GeneratedArt(d.game.displayTitle, d.platform.accent.toColor(), slot = ArtSlot.BOX, label = d.platform.shortName)
        })
    }
}

@Composable
private fun GameFacts(d: GameDetail, modifier: Modifier) {
    val meta = d.game.metadata
    Column(modifier) {
        SectionLabel(d.platform.name)
        Spacer(Modifier.height(Space.xs))
        if (d.art.logo != null) {
            Artwork(d.art.logo, Modifier.widthIn(max = 360.dp).height(88.dp), contentScale = ContentScale.Fit, fadeIn = true, fallback = {
                FText(d.game.displayTitle, Fuse.type.display, maxLines = 2)
            })
        } else {
            FText(d.game.displayTitle, Fuse.type.display, maxLines = 2)
        }
        val facts = listOfNotNull(meta.releaseYear?.toString(), meta.developer, meta.genres.firstOrNull())
        if (facts.isNotEmpty()) FText(facts.joinToString("  ·  "), Fuse.type.label, color = Fuse.colors.textMuted, maxLines = 1)
        Spacer(Modifier.height(Space.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val played = d.game.play.totalSeconds
            Chip(if (played > 0) playtimeText(played) else "Not played yet", icon = FuseIcons.Clock)
            d.emulator.selected?.let { Chip(it.name, icon = FuseIcons.Gamepad) }
        }
        d.achievements?.let { ra ->
            Spacer(Modifier.height(Space.m))
            FText("${ra.earned} of ${ra.total} achievements", Fuse.type.bodyStrong)
            Spacer(Modifier.height(Space.xs))
            ProgressBar(ra.progress, Modifier.fillMaxWidth(0.6f))
        }
        meta.description?.let {
            Spacer(Modifier.height(Space.m))
            FText(it, Fuse.type.body, color = Fuse.colors.textMuted, maxLines = 5)
        }
    }
}

@Composable
private fun FocusedPlatform(card: PlatformCard?) {
    card ?: return
    val p = card.platform
    val accent = p.accent.toColor()
    Box(Modifier.fillMaxSize()) {
        Backdrop(card.art.hero, accent)
        Column(Modifier.align(Alignment.BottomStart).padding(Space.xl)) {
            SectionLabel(listOfNotNull(p.manufacturer, p.releaseYear?.toString()).joinToString("  ·  ").ifEmpty { "System" })
            FText(p.name, Fuse.type.display, maxLines = 2)
            Spacer(Modifier.height(Space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Chip("${card.gameCount} ${if (card.gameCount == 1) "game" else "games"}", icon = FuseIcons.Library)
                Chip(card.emulatorName ?: "No emulator", icon = FuseIcons.Gamepad, color = if (card.emulatorInstalled) Fuse.colors.text else Fuse.colors.warning)
                when (card.bios.state) {
                    BiosState.READY -> Chip("Firmware ready", icon = FuseIcons.Check, color = Fuse.colors.success)
                    BiosState.MISSING -> Chip("Firmware missing", icon = FuseIcons.Warning, color = Fuse.colors.danger)
                    BiosState.PARTIAL -> Chip("Firmware incomplete", icon = FuseIcons.Warning, color = Fuse.colors.warning)
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun NowPlaying(game: GameCard, since: Long?) {
    val accent = game.accent.toColor()
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            delay(15_000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Backdrop(game.art.hero ?: game.art.grid, accent, game.art.heroFocusX, game.art.heroFocusY)
        Row(Modifier.align(Alignment.BottomStart).padding(Space.xl), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.width(120.dp).aspectRatio(0.72f).clip(SquircleShape.fraction(Fuse.geometry.tileCornerFraction * 0.7f))) {
                Artwork(game.art.boxart ?: game.art.icon, Modifier.fillMaxSize(), fallback = {
                    GeneratedArt(game.title, accent, slot = ArtSlot.BOX, label = game.platformShort)
                })
            }
            Spacer(Modifier.width(Space.l))
            Column {
                SectionLabel("Playing now", color = Fuse.colors.accent)
                FText(game.title, Fuse.type.title, maxLines = 2)
                if (since != null) {
                    val seconds = ((now - since) / 1000).coerceAtLeast(0)
                    FText("This session  ${playtimeText(seconds)}", Fuse.type.label, color = Fuse.colors.textMuted)
                }
                FText("${playtimeText(game.playSeconds)} in total", Fuse.type.label, color = Fuse.colors.textMuted)
            }
        }
    }
}
