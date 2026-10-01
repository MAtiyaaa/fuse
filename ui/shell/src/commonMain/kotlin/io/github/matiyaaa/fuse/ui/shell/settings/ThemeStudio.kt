package io.github.matiyaaa.fuse.ui.shell.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import io.github.matiyaaa.fuse.model.AmbientSpec
import io.github.matiyaaa.fuse.model.BackgroundStyle
import io.github.matiyaaa.fuse.model.Contrast
import io.github.matiyaaa.fuse.model.CornerFamily
import io.github.matiyaaa.fuse.model.FocusStyle
import io.github.matiyaaa.fuse.model.GlassSettings
import io.github.matiyaaa.fuse.model.MotionProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.SoundProfile
import io.github.matiyaaa.fuse.model.ThemeCodec
import io.github.matiyaaa.fuse.model.ThemeSpec
import io.github.matiyaaa.fuse.ui.designsystem.background.AmbientBackground
import io.github.matiyaaa.fuse.ui.designsystem.components.FText
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.Panel
import io.github.matiyaaa.fuse.ui.designsystem.components.Toggle
import io.github.matiyaaa.fuse.ui.designsystem.components.fadingEdges
import io.github.matiyaaa.fuse.ui.designsystem.effects.fuseClickable
import io.github.matiyaaa.fuse.ui.designsystem.effects.rememberGlide
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcon
import io.github.matiyaaa.fuse.ui.designsystem.icons.FuseIcons
import io.github.matiyaaa.fuse.ui.designsystem.icons.HintButton
import io.github.matiyaaa.fuse.ui.designsystem.input.InputLayer
import io.github.matiyaaa.fuse.ui.designsystem.input.NavResult
import io.github.matiyaaa.fuse.ui.designsystem.shape.SquircleShape
import io.github.matiyaaa.fuse.ui.designsystem.shape.squirclePath
import io.github.matiyaaa.fuse.ui.designsystem.theme.Durations
import io.github.matiyaaa.fuse.ui.designsystem.theme.Easings
import io.github.matiyaaa.fuse.ui.designsystem.theme.Fuse
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseGeometry
import io.github.matiyaaa.fuse.ui.designsystem.theme.FuseTheme
import io.github.matiyaaa.fuse.ui.designsystem.theme.Radius
import io.github.matiyaaa.fuse.ui.designsystem.theme.Size
import io.github.matiyaaa.fuse.ui.designsystem.theme.Space
import io.github.matiyaaa.fuse.ui.designsystem.theme.ThemePresets
import io.github.matiyaaa.fuse.ui.shell.app.AppState
import io.github.matiyaaa.fuse.ui.shell.app.FocusZone

/** A colour the studio offers for the accent, and the name it goes by (colour is never the only cue). */
@Immutable
internal data class Swatch(val name: String, val argb: Long)

/** Accents that glow in a dark room: each reads at 5:1 or more on every dark theme's room and panels. */
private val DarkAccents = listOf(
    Swatch("Ember", 0xFFFF6A3D), Swatch("Coral", 0xFFFF8F7A), Swatch("Rose", 0xFFFF9CC2), Swatch("Magenta", 0xFFFF4FA3),
    Swatch("Violet", 0xFFB79CFF), Swatch("Iris", 0xFF7C8CFF), Swatch("Sky", 0xFF8CCBFF), Swatch("Teal", 0xFF43E3D3),
    Swatch("Lime", 0xFF86DC5C), Swatch("Amber", 0xFFFFB02E),
)

/** Accents deep enough for a bright room: each reads at 4.8:1 or more on white. */
private val BrightAccents = listOf(
    Swatch("Ember", 0xFFC9431F), Swatch("Raspberry", 0xFFB8336A), Swatch("Plum", 0xFF7E3FB0), Swatch("Iris", 0xFF5B44D4),
    Swatch("Ocean", 0xFF1471AA), Swatch("Teal", 0xFF0D7672), Swatch("Forest", 0xFF2E7D32), Swatch("Olive", 0xFF56661C),
    Swatch("Amber", 0xFF935A00), Swatch("Graphite", 0xFF2A2D31),
)

/** Every background, the plainest first: the game's own room, a plain one, then the drawn scenes. */
private val Backgrounds: List<BackgroundStyle> =
    listOf(BackgroundStyle.HERO, BackgroundStyle.SOLID) + BackgroundStyle.entries.filter { it != BackgroundStyle.HERO && it != BackgroundStyle.SOLID }

/** The id the studio's draft goes by while it is being made, so the stage changes it in place. */
private const val STUDIO_ID = "studio"

/** One line of the studio, top to bottom. */
internal enum class StudioRow(val label: String, val icon: ImageVector) {
    ACCENT("Accent colour", FuseIcons.Paintbrush),
    BACKGROUND("Background", FuseIcons.Image),
    CORNERS("Corners", FuseIcons.Corners),
    FOCUS("Focus", FuseIcons.Target),
    MOTION("Motion", FuseIcons.Activity),
    SOUND("Sounds", FuseIcons.Volume),
    GLASS("Glass panels", FuseIcons.Layers),
    CRT("CRT effect", FuseIcons.Tv),
    SAVE("Save as your theme", FuseIcons.Save),
}

/**
 * A theme being made in the studio: it starts as [base] and changes one trait at a time. [editing]
 * is the added theme being changed (saving under its name replaces it); [extendsId] is the built-in
 * theme the file will name in `extends`, which brings along what the file doesn't spell out (how
 * sections are laid out, the finer glass settings).
 */
@Stable
internal class StudioState(val base: ThemeSpec, val editing: ThemeSpec?, val extendsId: String?) {
    /** The theme's own accent first, then the curated colours that suit its room. */
    val swatches: List<Swatch> =
        listOf(Swatch("Its own", base.palette.accent)) + (if (base.palette.dark) DarkAccents else BrightAccents).filter { it.argb != base.palette.accent }

    var accent by mutableIntStateOf(0)
    var background by mutableStateOf(base.background)
    var corners by mutableStateOf(base.geometry)
    var focus by mutableStateOf(base.focus)
    var motion by mutableStateOf(base.motion)
    var sound by mutableStateOf(base.sound)
    var glass by mutableStateOf(base.glass.enabled)
    var crt by mutableStateOf(base.crt.enabled)
    var row by mutableStateOf(StudioRow.ACCENT)

    /** Which way the last change went (-1 or 1), so a value slides in from that side. */
    var direction by mutableIntStateOf(1)

    val changed: Boolean
        get() = accent != 0 || background != base.background || corners != base.geometry || focus != base.focus ||
            motion != base.motion || sound != base.sound || glass != base.glass.enabled || crt != base.crt.enabled

    fun reset() {
        direction = -1
        accent = 0
        background = base.background
        corners = base.geometry
        focus = base.focus
        motion = base.motion
        sound = base.sound
        glass = base.glass.enabled
        crt = base.crt.enabled
    }

    /**
     * Moves [row]'s value by [delta]: choices step through their options and wrap; a switch turns on
     * to the right and off to the left. False when nothing changed (a switch already that way, Save).
     */
    fun step(row: StudioRow, delta: Int): Boolean {
        direction = if (delta < 0) -1 else 1
        when (row) {
            StudioRow.ACCENT -> accent = (accent + delta).mod(swatches.size)
            StudioRow.BACKGROUND -> background = Backgrounds.cycle(background, delta)
            StudioRow.CORNERS -> corners = CornerFamily.entries.cycle(corners, delta)
            StudioRow.FOCUS -> focus = FocusStyle.entries.cycle(focus, delta)
            StudioRow.MOTION -> motion = MotionProfile.entries.cycle(motion, delta)
            StudioRow.SOUND -> sound = SoundProfile.entries.cycle(sound, delta)
            StudioRow.GLASS -> if (glass == delta > 0) return false else glass = delta > 0
            StudioRow.CRT -> if (crt == delta > 0) return false else crt = delta > 0
            StudioRow.SAVE -> return false
        }
        return true
    }

    /** Flips a switch row. */
    fun toggle(row: StudioRow) {
        when (row) {
            StudioRow.GLASS -> glass = !glass
            StudioRow.CRT -> crt = !crt
            else -> Unit
        }
    }

    /** Picks the swatch at [index] (a tap on it). */
    fun pick(index: Int) {
        direction = if (index < accent) -1 else 1
        accent = index.coerceIn(0, swatches.lastIndex)
    }

    /**
     * The theme as it stands, before Fuse checks it. A new accent brings its own soft tint and the
     * text colour that reads on it; a room that was held still (no light, no movement) wakes up when
     * it gets a drawn background, or the new background would not show.
     */
    fun draft(): ThemeSpec {
        val p = base.palette
        val a = swatches[accent].argb
        val palette = if (a == p.accent) p else p.copy(
            accent = a,
            accentSoft = ((if (p.dark) 0x33L else 0x26L) shl 24) or (a and 0xFFFFFF),
            onAccent = Contrast.bestOn(a),
        )
        val still = base.ambient.intensity <= 0f || base.ambient.speed <= 0f
        val ambient = if (background != base.background && background.moves && still) AmbientSpec(secondary = base.ambient.secondary) else base.ambient
        return base.copy(
            id = STUDIO_ID,
            name = editing?.name ?: "Your theme",
            tagline = "From ${base.name}",
            author = null,
            palette = palette,
            background = background,
            ambient = ambient,
            geometry = corners,
            focus = focus,
            motion = motion,
            sound = sound,
            glass = when {
                glass == base.glass.enabled -> base.glass
                glass -> GlassSettings(enabled = true)
                else -> base.glass.copy(enabled = false)
            },
            crt = base.crt.copy(enabled = crt),
        )
    }

    companion object {
        /**
         * A studio for [spec]: a built-in theme is the start of something new; an added one is
         * changed in place. [file] is an added theme's file, read for the theme it extends.
         */
        fun of(spec: ThemeSpec, custom: Boolean, file: String?): StudioState {
            val extends = if (!custom) spec.id else file?.let { Regex(""""extends"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.trim()?.lowercase() }
            return StudioState(spec, if (custom) spec else null, extends?.takeIf { ThemePresets.find(it) != null })
        }
    }
}

private fun <T> List<T>.cycle(current: T, delta: Int): T = this[(indexOf(current).coerceAtLeast(0) + delta).mod(size)]

/** The studio's theme as Fuse will keep it (colours repaired where they would be hard to read) and what was repaired. */
@Immutable
internal data class StudioLook(val spec: ThemeSpec, val notes: List<String>)

/**
 * The draft written as a theme file and read back, exactly as saving it will: the stage shows
 * what will be kept, and [StudioLook.notes] says what Fuse had to change for it to read well.
 */
@Composable
internal fun rememberStudioLook(studio: StudioState): StudioLook {
    val draft = studio.draft()
    return remember(draft, studio.extendsId) {
        val json = ThemeCodec.encode(draft.copy(id = ThemeCodec.CUSTOM_PREFIX + STUDIO_ID), extends = studio.extendsId)
        when (val r = ThemeCodec.parse(json, ThemePresets::find, ThemePresets.Fuse)) {
            is ThemeCodec.Imported -> StudioLook(r.spec.copy(id = STUDIO_ID, name = draft.name, tagline = draft.tagline), r.notes)
            is ThemeCodec.Failed -> StudioLook(draft, emptyList())
        }
    }
}

/**
 * The studio's controls, beside the stage: one row per trait, changed with left and right (or a
 * tap on its arrows), switches flipped with confirm, and Save at the end. The highlight glides from
 * row to row as in every menu. While the studio is open the interface's own sounds follow the
 * draft's, so a new sound profile is heard as you move. [narrow] trades the swatch strip and the
 * icon wells for compact steppers.
 */
@Composable
internal fun StudioPanel(
    app: AppState,
    studio: StudioState,
    look: StudioLook,
    modifier: Modifier = Modifier,
    /** False while the panel leaves (the studio closed): it no longer takes input. */
    active: Boolean = true,
    compact: Boolean = false,
    narrow: Boolean = false,
    onSave: () -> Unit,
    onLeave: () -> Unit,
) {
    val c = Fuse.colors
    val rows = StudioRow.entries
    val prefs by app.store.prefs.collectAsState()
    val userMotion = prefs.motion
    DisposableEffect(studio) {
        app.platform.sounds.setProfile(studio.sound)
        onDispose { app.platform.sounds.setProfile(app.store.prefs.value.sound) }
    }
    LaunchedEffect(studio.row, studio.changed, active) {
        if (!active) return@LaunchedEffect
        app.hints = buildList {
            when (studio.row) {
                StudioRow.SAVE -> add(Hint(HintButton.CONFIRM, "Save"))
                StudioRow.GLASS, StudioRow.CRT -> add(Hint(HintButton.CONFIRM, "Switch"))
                else -> add(Hint(HintButton.DPAD, "Change"))
            }
            if (studio.changed) add(Hint(HintButton.OPTIONS, "Start over"))
            add(Hint(HintButton.BACK, "Leave"))
        }
    }
    fun change(row: StudioRow, delta: Int): Boolean {
        val moved = studio.step(row, delta)
        // A new sound profile is heard straight away, in the move that chose it.
        if (moved && row == StudioRow.SOUND) app.platform.sounds.setProfile(studio.sound)
        return moved
    }
    InputLayer(enabled = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val row = studio.row
        when (e.action) {
            NavAction.UP, NavAction.DOWN, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                val step = when (e.action) {
                    NavAction.UP -> -1
                    NavAction.DOWN -> 1
                    NavAction.PAGE_UP -> -rows.size
                    else -> rows.size
                }
                val next = (row.ordinal + step).coerceIn(0, rows.lastIndex)
                if (next == row.ordinal) NavResult.BLOCKED else { studio.row = rows[next]; NavResult.MOVED }
            }
            NavAction.LEFT, NavAction.RIGHT -> if (change(row, if (e.action == NavAction.LEFT) -1 else 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.SELECT -> {
                when (row) {
                    StudioRow.SAVE -> onSave()
                    StudioRow.GLASS, StudioRow.CRT -> studio.toggle(row)
                    else -> change(row, 1)
                }
                NavResult.ACTIVATED
            }
            NavAction.CONTEXT -> if (studio.changed) { studio.reset(); app.platform.sounds.setProfile(studio.sound); NavResult.ACTIVATED } else NavResult.BLOCKED
            NavAction.BACK -> { onLeave(); NavResult.CONSUMED }
            else -> NavResult.IGNORED
        }
    }

    // Where each row sits in the scrolling column (top and height, px), for the highlight and scrolling.
    val bounds = remember { mutableStateMapOf<StudioRow, Pair<Float, Float>>() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val at = bounds[studio.row]
    LaunchedEffect(studio.row, at) {
        val (top, height) = at ?: return@LaunchedEffect
        val margin = with(density) { Space.l.toPx() }
        val view = scroll.viewportSize.toFloat()
        val target = when {
            top - margin < scroll.value -> top - margin
            top + height + margin > scroll.value + view -> top + height + margin - view
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.toInt().coerceIn(0, scroll.maxValue))
    }
    val glide = key(at != null) {
        rememberGlide(
            with(density) { (at?.first ?: 0f).toDp() },
            with(density) { ((at?.first ?: 0f) + (at?.second ?: 0f)).toDp() },
        )
    }
    val fill = c.text.copy(alpha = if (c.isDark) 0.1f else 0.07f)
    val accent = c.accent
    val corner = Fuse.geometry.control
    val outline = if (Fuse.look.highContrastFocus) c.focus else null
    val shown by animateFloatAsState(if (at != null) 1f else 0f, Fuse.motion.fade(Durations.FAST), label = "studioHighlight")

    Column(modifier) {
        Panel(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            Column(
                Modifier
                    .fadingEdges(scroll, top = Space.m, bottom = Space.xl)
                    .verticalScroll(scroll)
                    .padding(Space.s)
                    .drawBehind {
                        if (shown <= 0.01f) return@drawBehind
                        val top = glide.start.toPx()
                        val h = glide.size.toPx()
                        if (h <= 0f) return@drawBehind
                        val r = corner.toPx().coerceAtMost(h / 2)
                        drawRoundRect(fill, Offset(0f, top), size.copy(height = h), CornerRadius(r), alpha = shown)
                        if (outline != null) {
                            val sw = Size.focusStroke.toPx()
                            drawRoundRect(outline, Offset(sw / 2, top + sw / 2), size.copy(width = size.width - sw, height = h - sw), CornerRadius((r - sw / 2).coerceAtLeast(0f)), alpha = shown, style = Stroke(sw))
                        }
                        // The accent bar, stepped in on strongly rounded rows so it stays inside the curve.
                        val bh = Size.glyph.toPx().coerceAtMost(h - Space.s.toPx())
                        val curve = if (r > bh / 2) r - kotlin.math.sqrt(r * r - (bh / 2) * (bh / 2)) else 0f
                        drawRoundRect(accent, Offset(curve, top + (h - bh) / 2), androidx.compose.ui.geometry.Size(Size.sparkHeight.toPx(), bh), CornerRadius(Size.sparkHeight.toPx() / 2), alpha = shown)
                    },
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                for (row in rows) {
                    StudioRowView(
                        row = row,
                        studio = studio,
                        look = look,
                        selected = studio.row == row && app.focusZone == FocusZone.CONTENT,
                        compact = compact,
                        narrow = narrow,
                        detail = when (row) {
                            StudioRow.MOTION -> userMotion?.let { "Your Motion setting (${it.label()}) is used instead" }
                            StudioRow.SAVE -> studio.editing?.let { "Saving under its name replaces ${it.name}" } ?: "Name it, and it joins your themes"
                            else -> null
                        },
                        onTap = {
                            app.focusZone = FocusZone.CONTENT
                            if (studio.row == row) {
                                when (row) {
                                    StudioRow.SAVE -> onSave()
                                    StudioRow.GLASS, StudioRow.CRT -> studio.toggle(row)
                                    else -> Unit
                                }
                            } else {
                                studio.row = row
                                if (row == StudioRow.SAVE) onSave()
                                if (row == StudioRow.GLASS || row == StudioRow.CRT) studio.toggle(row)
                            }
                        },
                        onStep = { delta ->
                            app.focusZone = FocusZone.CONTENT
                            studio.row = row
                            change(row, delta)
                        },
                        onPick = { i ->
                            app.focusZone = FocusZone.CONTENT
                            studio.row = row
                            studio.pick(i)
                        },
                        modifier = Modifier.onPlaced { bounds[row] = it.positionInParent().y to it.size.height.toFloat() },
                    )
                }
            }
        }
        if (look.notes.isNotEmpty()) {
            Row(Modifier.padding(top = Space.m, start = Space.s), verticalAlignment = Alignment.Top) {
                FuseIcon(FuseIcons.Info, size = Size.iconS, tint = c.warning)
                Spacer(Modifier.width(Space.s))
                FText(look.notes.joinToString(" "), Fuse.type.caption, color = c.textMuted, maxLines = 3)
            }
        }
    }
}

/** One row of the studio: the trait's icon and name, and its control. Anatomy matches menu rows. */
@Composable
private fun StudioRowView(
    row: StudioRow,
    studio: StudioState,
    look: StudioLook,
    selected: Boolean,
    compact: Boolean,
    narrow: Boolean,
    detail: String?,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Fuse.colors
    val shape = RoundedCornerShape(Fuse.geometry.control)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) Size.rowCompact else Size.row)
            .fuseClickable(shape = shape, scale = false, role = if (row == StudioRow.GLASS || row == StudioRow.CRT) Role.Switch else Role.Button, onClick = onTap)
            // Screen readers (and the UI audit) can tell which row the controller is on.
            .semantics { this.selected = selected }
            .padding(start = Size.sparkHeight + Space.m, end = if (narrow) Space.xs else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!narrow) {
            Well(row.icon, selected)
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(row.label, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.92f), maxLines = 1)
            if (detail != null) FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
        Spacer(Modifier.width(if (narrow) Space.s else Space.m))
        val dir = studio.direction
        when (row) {
            StudioRow.ACCENT -> if (narrow) {
                Stepper(studio.accent, dir, selected, onStep) { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SwatchDisc(studio.swatches[i].argb, chosen = false, Modifier.size(Size.iconS))
                        Spacer(Modifier.width(Space.s))
                        FText(studio.swatches[i].name, Fuse.type.label, maxLines = 1)
                    }
                }
            } else {
                SwatchStrip(studio.swatches, studio.accent, onPick)
            }
            StudioRow.BACKGROUND -> Stepper(studio.background, dir, selected, onStep, narrow) { bg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!narrow) {
                        BackgroundThumb(look.spec, bg)
                        Spacer(Modifier.width(Space.s))
                    }
                    FText(bg.label(), Fuse.type.label, maxLines = 1)
                }
            }
            StudioRow.CORNERS -> Stepper(studio.corners, dir, selected, onStep, narrow) { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CornerGlyph(f)
                    Spacer(Modifier.width(Space.s))
                    FText(f.label(), Fuse.type.label, maxLines = 1)
                }
            }
            StudioRow.FOCUS -> Stepper(studio.focus, dir, selected, onStep, narrow) { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(f.icon(), size = Size.iconS, tint = c.textMuted)
                    Spacer(Modifier.width(Space.s))
                    FText(f.label(), Fuse.type.label, maxLines = 1)
                }
            }
            StudioRow.MOTION -> Stepper(studio.motion, dir, selected, onStep, narrow) { m -> FText(m.label(), Fuse.type.label, maxLines = 1) }
            StudioRow.SOUND -> Stepper(studio.sound, dir, selected, onStep, narrow) { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FuseIcon(s.icon(), size = Size.iconS, tint = c.textMuted)
                    Spacer(Modifier.width(Space.s))
                    FText(s.label(), Fuse.type.label, maxLines = 1)
                }
            }
            StudioRow.GLASS -> Toggle(studio.glass, Modifier.padding(end = Space.xs))
            StudioRow.CRT -> Toggle(studio.crt, Modifier.padding(end = Space.xs))
            StudioRow.SAVE -> FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
        }
    }
}

/** The row's icon in a small well shaped like the theme's tiles, as menu rows have. */
@Composable
private fun Well(icon: ImageVector, selected: Boolean) {
    val c = Fuse.colors
    Box(
        Modifier
            .size(Size.iconXL)
            .clip(wellShape())
            .background(c.text.copy(alpha = if (selected) (if (c.isDark) 0.13f else 0.1f) else (if (c.isDark) 0.07f else 0.055f))),
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = Size.glyphS, tint = if (selected) c.text else c.text.copy(alpha = 0.82f))
    }
}

@Composable
@ReadOnlyComposable
private fun wellShape(): Shape = when (Fuse.geometry.family) {
    CornerFamily.PILL -> CircleShape
    else -> SquircleShape.fraction(Fuse.geometry.tileCornerFraction.coerceAtLeast(0.12f) + 0.06f)
}

/**
 * A value with an arrow either side: the value slides in from the side it was moved toward, and the
 * arrows (touch targets of their own) brighten while the row is selected.
 */
@Composable
private fun <T> Stepper(
    value: T,
    direction: Int,
    selected: Boolean,
    onStep: (Int) -> Unit,
    narrow: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val motion = Fuse.motion
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepArrow(FuseIcons.ChevronLeft, selected, narrow, "Previous") { onStep(-1) }
        Box(Modifier.widthIn(min = if (narrow) Space.x5 - Space.l else Space.x5 + Space.xl), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val shift = if (motion.reduced) 0 else 1
                    (fadeIn(motion.fade(Durations.FAST)) + slideInHorizontally(motion.tween(Durations.BASE, Easings.Enter)) { it / 4 * direction * shift }) togetherWith
                        (fadeOut(motion.fade(Durations.INSTANT)) + slideOutHorizontally(motion.exit(Durations.FAST)) { -it / 4 * direction * shift })
                },
                contentAlignment = Alignment.Center,
                label = "studioValue",
            ) { v -> content(v) }
        }
        StepArrow(FuseIcons.ChevronRight, selected, narrow, "Next") { onStep(1) }
    }
}

@Composable
private fun StepArrow(icon: ImageVector, selected: Boolean, narrow: Boolean, label: String, onClick: () -> Unit) {
    val c = Fuse.colors
    val a by animateFloatAsState(if (selected) 1f else 0.45f, Fuse.motion.fade(Durations.FAST), label = "stepArrow")
    Box(
        Modifier
            .size(if (narrow) Size.thumb else Size.touch)
            .clip(CircleShape)
            .fuseClickable(shape = CircleShape, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        FuseIcon(icon, size = Size.iconS, tint = c.text, modifier = Modifier.graphicsLayer { alpha = a })
    }
}

/** The accent choices as a strip of discs; the chosen one is ringed and checked. */
@Composable
private fun SwatchStrip(swatches: List<Swatch>, chosen: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        swatches.forEachIndexed { i, s ->
            SwatchDisc(
                s.argb,
                chosen = i == chosen,
                Modifier
                    .size(Size.iconM)
                    .fuseClickable(shape = CircleShape, onClickLabel = s.name) { onPick(i) }
                    .semantics { contentDescription = s.name; selected = i == chosen },
            )
        }
    }
}

/**
 * One accent swatch: a disc of the colour with a hairline so pale colours show. Chosen, it grows a
 * little, gets a ring of the text colour just outside it and a check in the colour that reads on it.
 */
@Composable
private fun SwatchDisc(argb: Long, chosen: Boolean, modifier: Modifier = Modifier) {
    val c = Fuse.colors
    val color = Color(argb)
    val mark = Color(Contrast.bestOn(argb))
    val k by animateFloatAsState(if (chosen) 1f else 0f, Fuse.motion.value(), label = "swatch")
    val ring = c.text
    val hair = c.hairlineStrong
    Box(
        modifier.drawWithCache {
            val r = size.minDimension / 2
            val stroke = Stroke(Size.stroke.toPx())
            val ringStroke = Stroke(Size.focusStroke.toPx())
            val gap = Size.focusGap.toPx() * 0.7f
            onDrawBehind {
                val grow = 1f + 0.12f * k
                drawCircle(color, r * grow)
                drawCircle(hair, r * grow, style = stroke)
                if (k > 0.01f) drawCircle(ring, r * grow + gap + ringStroke.width / 2, style = ringStroke, alpha = k.coerceIn(0f, 1f))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (chosen) FuseIcon(FuseIcons.Check, size = Size.iconXS * 0.85f, tint = mark)
    }
}

/** A still picture of [style] in [spec]'s colours, so each background is recognised by sight as well as name. */
@Composable
private fun BackgroundThumb(spec: ThemeSpec, style: BackgroundStyle) {
    val shape = RoundedCornerShape(Radius.xs)
    Box(Modifier.size(width = Size.thumb, height = Size.thumb * 0.5625f).clip(shape)) {
        FuseTheme(spec = spec) {
            AmbientBackground(
                if (style == BackgroundStyle.HERO) BackgroundStyle.SOLID else style,
                Fuse.colors.accent,
                Modifier.fillMaxSize(),
                ambient = if (spec.ambient.intensity <= 0f) AmbientSpec(secondary = spec.ambient.secondary) else spec.ambient,
                animate = false,
            )
            if (style == BackgroundStyle.HERO) FuseIcon(FuseIcons.Image, Modifier.align(Alignment.Center), size = Size.iconXS, tint = Fuse.colors.textMuted)
        }
    }
}

/** A tile's outline in [family]'s corners. */
@Composable
private fun CornerGlyph(family: CornerFamily, size: Dp = Size.glyphS) {
    val tint = Fuse.colors.textMuted
    val fraction = FuseGeometry.of(family).tileCornerFraction
    Spacer(
        Modifier.size(size).drawWithCache {
            val inset = Size.stroke.toPx()
            val w = this.size.width - inset * 2
            val path = squirclePath(w, w, w * fraction, 0.6f).apply { translate(Offset(inset, inset)) }
            val stroke = Stroke(Size.stroke.toPx() * 1.5f)
            onDrawBehind { drawPath(path, tint, style = stroke) }
        },
    )
}
