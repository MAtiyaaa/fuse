package io.github.matiyaaa.fuse.ui.shell.settings

import io.github.matiyaaa.fuse.ui.designsystem.theme.tabular
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.abs
import io.github.matiyaaa.fuse.ui.shell.app.TextInputSpec
import io.github.matiyaaa.fuse.model.ThemePalette
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
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
import io.github.matiyaaa.fuse.model.Wallpaper
import io.github.matiyaaa.fuse.model.WallpaperAlign
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

/** How many changes Y can take back. */
private const val UNDO_DEPTH = 80

/** How many recent colours the studio offers. */
private const val RECENT_MAX = 10

/** The id the studio's draft goes by while it is being made, so the stage changes it in place. */
private const val STUDIO_ID = "studio"

/**
 * The studio's steps, in the order it guides you through them. Each has a line saying what it is
 * for; you can move between them freely (the shoulder buttons, or the dots at the top).
 */
internal enum class StudioStep(val title: String, val guide: String) {
    ROOM("The room", "Light or dark, then the colours of the room and the panels on it."),
    TEXT("Text", "Words in the main colour, details in a quieter one. Both should read well."),
    ACCENT("Accent", "The spark: what's selected, buttons and progress, and the text on them."),
    SIGNALS("Focus and signals", "The outline on what you're on, and the colours for done, careful and wrong."),
    SHAPE("Shape", "How round tiles and panels are, and how the one you're on stands out."),
    SCENE("Background", "A picture of your own, or a scene drawn by Fuse: which one, how bright and how lively."),
    EFFECTS("Effects", "Frosted glass panels and an old screen's glow, each with its own amount."),
    FEEL("Motion and sound", "How the interface moves and sounds as you use it."),
    SAVE("Save", "Check what reads well, then keep it as your theme."),
}

/** A colour the studio lets you change, what it sits on, and how strongly it must stand out there. */
internal enum class ColorRole(val label: String, val note: String) {
    BACKGROUND("Room", "Behind everything"),
    SURFACE("Panels", "Menus, sheets and cards"),
    RAISED("Raised panels", "Panels on panels, selected rows"),
    TEXT("Text", "Titles and everything you read"),
    MUTED("Details text", "Captions, counts and hints"),
    ACCENT("Accent colour", "Buttons, progress and what's chosen"),
    ON_ACCENT("Text on the accent", "Words and icons on accent buttons"),
    FOCUS("Focus colour", "The outline on what you're on"),
    SUCCESS("Done", "Installed, saved, ready"),
    WARNING("Careful", "Offline, needs a look"),
    DANGER("Wrong", "Errors and deleting"),
    SECOND_LIGHT("Second colour", "The scene's other light"),
}

internal enum class RowKind { COLOR, CHOICE, SWITCH, LEVEL, ACTION }

/** One line of the studio. [step] is where it lives; [role] is the colour a COLOR row changes. */
internal enum class StudioRow(val label: String, val icon: ImageVector, val step: StudioStep?, val kind: RowKind, val role: ColorRole? = null) {
    MODE("Light or dark", FuseIcons.SunMoon, StudioStep.ROOM, RowKind.CHOICE),
    ROOM_COLOR("Room", FuseIcons.Square, StudioStep.ROOM, RowKind.COLOR, ColorRole.BACKGROUND),
    SURFACE("Panels", FuseIcons.PanelsTop, StudioStep.ROOM, RowKind.COLOR, ColorRole.SURFACE),
    RAISED("Raised panels", FuseIcons.Layers, StudioStep.ROOM, RowKind.COLOR, ColorRole.RAISED),
    TEXT("Text", FuseIcons.Type, StudioStep.TEXT, RowKind.COLOR, ColorRole.TEXT),
    MUTED("Details text", FuseIcons.TextSize, StudioStep.TEXT, RowKind.COLOR, ColorRole.MUTED),
    ACCENT("Accent colour", FuseIcons.Paintbrush, StudioStep.ACCENT, RowKind.COLOR, ColorRole.ACCENT),
    ON_ACCENT("Text on the accent", FuseIcons.CircleDot, StudioStep.ACCENT, RowKind.COLOR, ColorRole.ON_ACCENT),
    FOCUS_COLOR("Focus colour", FuseIcons.Crosshair, StudioStep.SIGNALS, RowKind.COLOR, ColorRole.FOCUS),
    SUCCESS("Done", FuseIcons.CircleCheck, StudioStep.SIGNALS, RowKind.COLOR, ColorRole.SUCCESS),
    WARNING("Careful", FuseIcons.Warning, StudioStep.SIGNALS, RowKind.COLOR, ColorRole.WARNING),
    DANGER("Wrong", FuseIcons.CircleX, StudioStep.SIGNALS, RowKind.COLOR, ColorRole.DANGER),
    CORNERS("Corners", FuseIcons.Corners, StudioStep.SHAPE, RowKind.CHOICE),
    FOCUS("Focus", FuseIcons.Target, StudioStep.SHAPE, RowKind.CHOICE),
    PICTURE("Your picture", FuseIcons.ImagePlay, StudioStep.SCENE, RowKind.ACTION),
    PICTURE_DIM("Picture dimming", FuseIcons.SunDim, StudioStep.SCENE, RowKind.LEVEL),
    PICTURE_ALIGN("Picture position", FuseIcons.Move, StudioStep.SCENE, RowKind.CHOICE),
    PICTURE_REMOVE("Remove the picture", FuseIcons.Trash, StudioStep.SCENE, RowKind.ACTION),
    BACKGROUND("Background", FuseIcons.Image, StudioStep.SCENE, RowKind.CHOICE),
    LIGHT("Brightness", FuseIcons.SunDim, StudioStep.SCENE, RowKind.LEVEL),
    SPEED("Movement", FuseIcons.Waves, StudioStep.SCENE, RowKind.LEVEL),
    SECOND("Second colour", FuseIcons.Blend, StudioStep.SCENE, RowKind.COLOR, ColorRole.SECOND_LIGHT),
    GLASS("Glass panels", FuseIcons.Layers, StudioStep.EFFECTS, RowKind.SWITCH),
    BLUR("Glass blur", FuseIcons.Droplet, StudioStep.EFFECTS, RowKind.LEVEL),
    OPACITY("Glass cover", FuseIcons.Contrast, StudioStep.EFFECTS, RowKind.LEVEL),
    CRT("CRT effect", FuseIcons.Tv, StudioStep.EFFECTS, RowKind.SWITCH),
    SCANLINES("Scanlines", FuseIcons.Rows, StudioStep.EFFECTS, RowKind.LEVEL),
    BLOOM("Glow", FuseIcons.Sparkle, StudioStep.EFFECTS, RowKind.LEVEL),
    MOTION("Motion", FuseIcons.Activity, StudioStep.FEEL, RowKind.CHOICE),
    SOUND("Sounds", FuseIcons.Volume, StudioStep.FEEL, RowKind.CHOICE),
    SAVE("Save as your theme", FuseIcons.Save, StudioStep.SAVE, RowKind.ACTION),
    NEXT("Next", FuseIcons.ArrowRight, null, RowKind.ACTION),
}

/** The lines shown while a colour is being changed: its hue, saturation and lightness, and the rest. */
internal enum class EditRow(val label: String, val icon: ImageVector, val kind: RowKind) {
    RECENT("Recent colours", FuseIcons.History, RowKind.CHOICE),
    HUE("Hue", FuseIcons.Palette, RowKind.LEVEL),
    SATURATION("Saturation", FuseIcons.Droplet, RowKind.LEVEL),
    LIGHTNESS("Lightness", FuseIcons.SunDim, RowKind.LEVEL),
    CODE("Type a colour code", FuseIcons.Hash, RowKind.ACTION),
    FIX("Make it easy to read", FuseIcons.Wand, RowKind.ACTION),
    RESET("Back to the theme's colour", FuseIcons.RotateCcw, RowKind.ACTION),
    DONE("Done", FuseIcons.Check, RowKind.ACTION),
}

/** A line of the panel: a row of the step, or a line of the colour being changed. */
internal sealed interface StudioLine {
    data class Main(val row: StudioRow) : StudioLine
    data class Edit(val row: EditRow) : StudioLine
}

/** How well a colour stands out on what it sits on: the lowest ratio, and what it needs. */
@Immutable
internal data class Legibility(val ratio: Double, val needs: Double) {
    val ok: Boolean get() = ratio >= needs
}

/** Everything the studio can change, as one value (to tell whether anything changed). */
@Immutable
internal data class StudioValues(
    val dark: Boolean,
    val colors: Map<ColorRole, Long>,
    val background: BackgroundStyle,
    val light: Float,
    val speed: Float,
    val corners: CornerFamily,
    val focus: FocusStyle,
    val motion: MotionProfile,
    val sound: SoundProfile,
    val glass: Boolean,
    val blur: Float,
    val opacity: Float,
    val crt: Boolean,
    val scanlines: Float,
    val bloom: Float,
    val picture: String?,
    val pictureDim: Float,
    val pictureAlign: WallpaperAlign,
)

/**
 * A theme being made in the studio: it starts as [base] and is changed step by step. [editing] is
 * the added theme being changed (saving under its name replaces it); [extendsId] is the built-in
 * theme the file will name in `extends`, which brings along what the file doesn't spell out (how
 * sections are laid out, the finer glass settings).
 */
@Stable
internal class StudioState(val base: ThemeSpec, val editing: ThemeSpec?, val extendsId: String?) {
    private val initial: StudioValues = valuesOf(base)

    var dark by mutableStateOf(initial.dark)
    val colors = mutableStateMapOf<ColorRole, Long>().apply { putAll(initial.colors) }
    var background by mutableStateOf(initial.background)
    var light by mutableStateOf(initial.light)
    var speed by mutableStateOf(initial.speed)
    var corners by mutableStateOf(initial.corners)
    var focus by mutableStateOf(initial.focus)
    var motion by mutableStateOf(initial.motion)
    var sound by mutableStateOf(initial.sound)
    var glass by mutableStateOf(initial.glass)
    var blur by mutableStateOf(initial.blur)
    var opacity by mutableStateOf(initial.opacity)
    var crt by mutableStateOf(initial.crt)
    var scanlines by mutableStateOf(initial.scanlines)
    var bloom by mutableStateOf(initial.bloom)
    var picture by mutableStateOf(initial.picture)
    var pictureDim by mutableStateOf(initial.pictureDim)
    var pictureAlign by mutableStateOf(initial.pictureAlign)

    /** Colours picked lately (newest first), offered in every colour's editor. */
    val recents = mutableStateListOf<Long>()

    /** What the studio looked like before each change, newest last: Y takes the last one back. */
    private val history = mutableStateListOf<StudioValues>()
    val canUndo: Boolean get() = history.isNotEmpty()

    /** The colour the open editor started from, so closing it can tell whether it changed. */
    private var openedWith: Long? = null

    /** Runs [change], remembering how things were before it when it changed anything. */
    fun <T> edit(change: () -> T): T {
        val before = values()
        val result = change()
        if (values() != before) {
            history.add(before)
            if (history.size > UNDO_DEPTH) history.removeAt(0)
        }
        return result
    }

    /** Takes the last change back; false when there is nothing to undo. */
    fun undo(): Boolean {
        val v = history.removeLastOrNull() ?: return false
        direction = -1
        apply(v)
        index = index.coerceAtMost(lines().lastIndex.coerceAtLeast(0))
        return true
    }

    private fun apply(v: StudioValues) {
        dark = v.dark
        colors.clear()
        colors.putAll(v.colors)
        background = v.background
        light = v.light
        speed = v.speed
        corners = v.corners
        focus = v.focus
        motion = v.motion
        sound = v.sound
        glass = v.glass
        blur = v.blur
        opacity = v.opacity
        crt = v.crt
        scanlines = v.scanlines
        bloom = v.bloom
        picture = v.picture
        pictureDim = v.pictureDim
        pictureAlign = v.pictureAlign
    }

    var step by mutableStateOf(StudioStep.ROOM)
    /** The colour being changed, when one is open; its lines replace the step's. */
    var openColor: ColorRole? by mutableStateOf(null)
    /** Which line of the panel the controller is on. */
    var index by mutableIntStateOf(0)

    /** Which way the last change went (-1 or 1), so a value slides in from that side. */
    var direction by mutableIntStateOf(1)

    /** The theme's own accent first, then the curated colours that suit its room. */
    val swatches: List<Swatch>
        get() = listOf(Swatch("Its own", base.palette.accent)) +
            (if (dark) DarkAccents else BrightAccents).filter { it.argb != base.palette.accent }

    fun values(): StudioValues = StudioValues(
        dark, colors.toMap(), background, light, speed, corners, focus, motion, sound, glass, blur, opacity, crt, scanlines, bloom,
        picture, pictureDim, pictureAlign,
    )

    val changed: Boolean get() = values() != initial

    fun color(role: ColorRole): Long = colors[role] ?: initial.colors.getValue(role)

    /** The lines of the panel now: the open colour's, or the step's (with effect amounts only while the effect is on). */
    fun lines(): List<StudioLine> {
        val open = openColor
        if (open != null) {
            return EditRow.entries
                .filter { it != EditRow.FIX || !legibility(open).ok }
                .filter { it != EditRow.RECENT || recents.isNotEmpty() }
                .map { StudioLine.Edit(it) }
        }
        val rows = StudioRow.entries.filter { it.step == step }.filter { r ->
            when (r) {
                StudioRow.BLUR, StudioRow.OPACITY -> glass
                StudioRow.SCANLINES, StudioRow.BLOOM -> crt
                StudioRow.LIGHT, StudioRow.SPEED, StudioRow.SECOND -> picture == null && background != BackgroundStyle.HERO && background != BackgroundStyle.SOLID
                StudioRow.BACKGROUND -> picture == null
                StudioRow.PICTURE_DIM, StudioRow.PICTURE_ALIGN, StudioRow.PICTURE_REMOVE -> picture != null
                else -> true
            }
        }
        return (rows + if (step != StudioStep.SAVE) listOf(StudioRow.NEXT) else emptyList()).map { StudioLine.Main(it) }
    }

    fun line(): StudioLine? = lines().getOrNull(index)

    fun reset() {
        direction = -1
        dark = initial.dark
        colors.clear()
        colors.putAll(initial.colors)
        background = initial.background
        light = initial.light
        speed = initial.speed
        corners = initial.corners
        focus = initial.focus
        motion = initial.motion
        sound = initial.sound
        glass = initial.glass
        blur = initial.blur
        opacity = initial.opacity
        crt = initial.crt
        scanlines = initial.scanlines
        bloom = initial.bloom
        picture = initial.picture
        pictureDim = initial.pictureDim
        pictureAlign = initial.pictureAlign
        openColor = null
        index = index.coerceAtMost(lines().lastIndex)
    }

    /** Goes to [to], at its first line. */
    fun go(to: StudioStep) {
        direction = if (to.ordinal < step.ordinal) -1 else 1
        openColor = null
        step = to
        index = 0
    }

    fun open(role: ColorRole) {
        openColor = role
        openedWith = color(role)
        // The first line is Hue; recent colours sit above it for a quick pick.
        index = if (recents.isNotEmpty()) 1 else 0
    }

    /**
     * Closes the colour being changed, back on its row. A colour that changed joins the recent ones
     * ([onRecent] keeps them); one picked from them moves to the front.
     */
    fun close(onRecent: (Long) -> Unit = {}) {
        val role = openColor ?: return
        val now = color(role)
        if (now != openedWith) {
            recents.remove(now)
            recents.add(0, now)
            while (recents.size > RECENT_MAX) recents.removeAt(recents.lastIndex)
            onRecent(now)
        }
        openedWith = null
        openColor = null
        index = lines().indexOfFirst { (it as? StudioLine.Main)?.row?.role == role }.coerceAtLeast(0)
    }

    /**
     * Moves [row]'s value by [delta]: colours get lighter or darker (the accent steps through its
     * swatches), choices step through their options and wrap, amounts change by a tenth, and a
     * switch turns on to the right and off to the left. False when nothing changed.
     */
    fun step(row: StudioRow, delta: Int): Boolean {
        direction = if (delta < 0) -1 else 1
        when (row) {
            // Dark is on the left, light on the right.
            StudioRow.MODE -> if (dark == delta < 0) return false else switchRoom(dark = delta < 0)
            StudioRow.ACCENT -> {
                val list = swatches
                val now = list.indexOfFirst { it.argb == color(ColorRole.ACCENT) }
                pickAccent(list[(now.coerceAtLeast(0) + delta).mod(list.size)].argb)
            }
            StudioRow.BACKGROUND -> background = Backgrounds.cycle(background, delta)
            StudioRow.CORNERS -> corners = CornerFamily.entries.cycle(corners, delta)
            StudioRow.FOCUS -> focus = FocusStyle.entries.cycle(focus, delta)
            StudioRow.MOTION -> motion = MotionProfile.entries.cycle(motion, delta)
            StudioRow.SOUND -> sound = SoundProfile.entries.cycle(sound, delta)
            StudioRow.GLASS -> if (glass == delta > 0) return false else glass = delta > 0
            StudioRow.CRT -> if (crt == delta > 0) return false else crt = delta > 0
            StudioRow.LIGHT -> light = level(light, delta, max = 1.5f) ?: return false
            StudioRow.SPEED -> speed = level(speed, delta, max = 2f) ?: return false
            StudioRow.BLUR -> blur = (blur + delta * 4f).coerceIn(0f, 48f).takeIf { it != blur } ?: return false
            StudioRow.OPACITY -> opacity = level(opacity, delta, min = 0.3f, max = 0.95f) ?: return false
            StudioRow.SCANLINES -> scanlines = level(scanlines, delta) ?: return false
            StudioRow.BLOOM -> bloom = level(bloom, delta) ?: return false
            StudioRow.PICTURE_DIM -> pictureDim = level(pictureDim, delta, max = 0.9f) ?: return false
            StudioRow.PICTURE_ALIGN -> pictureAlign = WallpaperAlign.entries.cycle(pictureAlign, delta)
            StudioRow.SAVE, StudioRow.NEXT, StudioRow.PICTURE, StudioRow.PICTURE_REMOVE -> return false
            else -> {
                val role = row.role ?: return false
                return nudge(role, EditRow.LIGHTNESS, delta)
            }
        }
        return true
    }

    /** Changes the open colour's hue (by 10 degrees), saturation or lightness (by 4%). */
    fun nudge(role: ColorRole, part: EditRow, delta: Int): Boolean {
        if (part == EditRow.RECENT) {
            if (recents.isEmpty()) return false
            val now = recents.indexOf(color(role))
            val next = if (now < 0) (if (delta > 0) 0 else recents.lastIndex) else (now + delta).mod(recents.size)
            direction = if (delta < 0) -1 else 1
            set(role, recents[next])
            return true
        }
        val hsl = Hsl.of(color(role))
        val next = when (part) {
            EditRow.HUE -> hsl.copy(h = (hsl.h + delta * 10f).mod(360f))
            EditRow.SATURATION -> hsl.copy(s = (hsl.s + delta * 0.04f).coerceIn(0f, 1f))
            EditRow.LIGHTNESS -> hsl.copy(l = (hsl.l + delta * 0.04f).coerceIn(0f, 1f))
            else -> return false
        }
        // Very dark or grey colours can look the same after a small turn; only a real change counts.
        if (next.argb() == color(role)) return false
        direction = if (delta < 0) -1 else 1
        set(role, next.argb())
        return true
    }

    /** Sets [role]'s colour; a new accent brings the text colour that reads on it. */
    fun set(role: ColorRole, argb: Long) {
        val opaque = argb or 0xFF000000L
        if (role == ColorRole.ACCENT) pickAccent(opaque) else colors[role] = opaque
    }

    private fun pickAccent(argb: Long) {
        colors[ColorRole.ACCENT] = argb
        colors[ColorRole.ON_ACCENT] = Contrast.bestOn(argb)
    }

    /** Picks the swatch at [index] (a tap on it). */
    fun pick(index: Int) {
        val list = swatches
        val now = list.indexOfFirst { it.argb == color(ColorRole.ACCENT) }
        direction = if (index < now) -1 else 1
        pickAccent(list[index.coerceIn(0, list.lastIndex)].argb)
    }

    /** Puts [role] back to the colour the theme started with. */
    fun resetColor(role: ColorRole) {
        set(role, initial.colors.getValue(role))
    }

    /** Lightens or darkens [role] until it reads well where it sits (or moves the room away from the text). */
    fun fix(role: ColorRole) {
        val need = needs(role)
        val fixed = when (role) {
            ColorRole.BACKGROUND, ColorRole.SURFACE, ColorRole.RAISED ->
                Contrast.repair(color(role), listOf(color(ColorRole.TEXT)), need, lighten = !dark)
            ColorRole.ON_ACCENT -> Contrast.repair(color(role), listOf(color(ColorRole.ACCENT)), need, Contrast.luminance(color(ColorRole.ACCENT)) < 0.4)
            ColorRole.SECOND_LIGHT -> null
            else -> Contrast.repair(color(role), against(role), need, lighten = dark)
        }
        if (fixed != null) colors[role] = fixed
    }

    /** What [role] sits on (or, for the room and panels, what sits on them: the text). */
    private fun against(role: ColorRole): List<Long> = when (role) {
        ColorRole.BACKGROUND, ColorRole.SURFACE, ColorRole.RAISED -> listOf(color(ColorRole.TEXT))
        ColorRole.TEXT -> listOf(color(ColorRole.BACKGROUND), color(ColorRole.SURFACE), color(ColorRole.RAISED))
        ColorRole.MUTED -> listOf(color(ColorRole.SURFACE), color(ColorRole.RAISED))
        ColorRole.ON_ACCENT -> listOf(color(ColorRole.ACCENT))
        ColorRole.ACCENT, ColorRole.FOCUS -> listOf(color(ColorRole.BACKGROUND), color(ColorRole.SURFACE))
        ColorRole.SUCCESS, ColorRole.WARNING, ColorRole.DANGER -> listOf(color(ColorRole.SURFACE))
        ColorRole.SECOND_LIGHT -> emptyList()
    }

    /** Words need 4.5:1; outlines, buttons and signals 3:1 (WCAG 2.2, 1.4.3 and 1.4.11). */
    private fun needs(role: ColorRole): Double = when (role) {
        ColorRole.ACCENT, ColorRole.FOCUS, ColorRole.SUCCESS, ColorRole.WARNING, ColorRole.DANGER -> 3.0
        ColorRole.SECOND_LIGHT -> 0.0
        else -> 4.5
    }

    fun legibility(role: ColorRole): Legibility {
        val on = against(role)
        val ratio = if (on.isEmpty()) 21.0 else on.minOf { Contrast.ratio(color(role), it) }
        return Legibility(ratio, needs(role))
    }

    /**
     * Turns the room light or dark: the room, panels and text are made again around the accent's
     * hue, and the accent and signals are moved until they read on the new room.
     */
    fun switchRoom(dark: Boolean) {
        val on = dark
        if (on == this.dark) return
        this.dark = on
        val hue = Hsl.of(color(ColorRole.ACCENT)).h
        fun c(s: Float, l: Float) = Hsl(hue, s, l).argb()
        if (on) {
            colors[ColorRole.BACKGROUND] = c(0.28f, 0.045f)
            colors[ColorRole.SURFACE] = c(0.22f, 0.085f)
            colors[ColorRole.RAISED] = c(0.2f, 0.125f)
            colors[ColorRole.TEXT] = c(0.25f, 0.96f)
            colors[ColorRole.MUTED] = c(0.12f, 0.7f)
            colors[ColorRole.FOCUS] = 0xFFFFFFFF
            colors[ColorRole.SUCCESS] = 0xFF5FE0A8
            colors[ColorRole.WARNING] = 0xFFFFC46B
            colors[ColorRole.DANGER] = 0xFFFF7A86
        } else {
            colors[ColorRole.BACKGROUND] = c(0.2f, 0.95f)
            colors[ColorRole.SURFACE] = 0xFFFFFFFF
            colors[ColorRole.RAISED] = c(0.25f, 0.975f)
            colors[ColorRole.TEXT] = c(0.22f, 0.09f)
            colors[ColorRole.MUTED] = c(0.1f, 0.34f)
            colors[ColorRole.FOCUS] = c(0.22f, 0.09f)
            colors[ColorRole.SUCCESS] = 0xFF12804F
            colors[ColorRole.WARNING] = 0xFF8F5A00
            colors[ColorRole.DANGER] = 0xFFC0303C
        }
        Contrast.repair(color(ColorRole.ACCENT), against(ColorRole.ACCENT), 3.0, lighten = on)?.let { colors[ColorRole.ACCENT] = it }
        colors[ColorRole.ON_ACCENT] = Contrast.bestOn(color(ColorRole.ACCENT))
    }

    /**
     * The theme as it stands, before Fuse checks it. A room that was held still (no light, no
     * movement) wakes up when it gets a drawn background, or the new background would not show.
     */
    fun draft(): ThemeSpec {
        val accent = color(ColorRole.ACCENT)
        val palette = ThemePalette(
            dark = dark,
            background = color(ColorRole.BACKGROUND), surface = color(ColorRole.SURFACE), surfaceRaised = color(ColorRole.RAISED),
            accent = accent,
            accentSoft = if (accent == base.palette.accent && dark == base.palette.dark) base.palette.accentSoft
            else ((if (dark) 0x33L else 0x26L) shl 24) or (accent and 0xFFFFFF),
            onAccent = color(ColorRole.ON_ACCENT),
            textPrimary = color(ColorRole.TEXT), textSecondary = color(ColorRole.MUTED), focusRing = color(ColorRole.FOCUS),
            success = color(ColorRole.SUCCESS), warning = color(ColorRole.WARNING), danger = color(ColorRole.DANGER),
        )
        val still = light <= 0f || speed <= 0f
        val moves = background != base.background && background.moves && still
        val ambient = AmbientSpec(
            intensity = if (moves) 1f else light,
            speed = if (moves) 1f else speed,
            // Left alone, the scene keeps the theme's own second light (or none).
            secondary = color(ColorRole.SECOND_LIGHT).takeIf { it != initial.colors[ColorRole.SECOND_LIGHT] } ?: base.ambient.secondary,
        )
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
            glass = if (glass) base.glass.copy(enabled = true, blur = blur, surfaceOpacity = opacity) else base.glass.copy(enabled = false),
            crt = base.crt.copy(enabled = crt, scanlines = scanlines, bloom = bloom),
            wallpaper = picture?.let { Wallpaper(it, pictureDim, pictureAlign) },
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

        private fun valuesOf(t: ThemeSpec): StudioValues {
            val p = t.palette
            return StudioValues(
                dark = p.dark,
                colors = mapOf(
                    ColorRole.BACKGROUND to p.background, ColorRole.SURFACE to p.surface, ColorRole.RAISED to p.surfaceRaised,
                    ColorRole.TEXT to p.textPrimary, ColorRole.MUTED to p.textSecondary,
                    ColorRole.ACCENT to p.accent, ColorRole.ON_ACCENT to p.onAccent, ColorRole.FOCUS to p.focusRing,
                    ColorRole.SUCCESS to (p.success ?: 0xFF3DD68C), ColorRole.WARNING to (p.warning ?: 0xFFFFB547),
                    ColorRole.DANGER to (p.danger ?: 0xFFFF5D6C),
                    ColorRole.SECOND_LIGHT to (t.ambient.secondary ?: p.accent),
                ),
                background = t.background,
                light = t.ambient.intensity,
                speed = t.ambient.speed,
                corners = t.geometry,
                focus = t.focus,
                motion = t.motion,
                sound = t.sound,
                glass = t.glass.enabled,
                blur = t.glass.blur,
                opacity = t.glass.surfaceOpacity,
                crt = t.crt.enabled,
                scanlines = t.crt.scanlines,
                bloom = t.crt.bloom,
                picture = t.wallpaper?.path,
                pictureDim = t.wallpaper?.dim ?: 0.35f,
                pictureAlign = t.wallpaper?.align ?: WallpaperAlign.CENTER,
            )
        }
    }
}

/** A tenth up or down, kept in [min]..[max]; null when it is already at the end. */
private fun level(value: Float, delta: Int, min: Float = 0f, max: Float = 1f): Float? {
    val next = ((value * 10).roundToInt() + delta).div(10f).coerceIn(min, max)
    return next.takeIf { abs(it - value) > 0.001f }
}

private fun <T> List<T>.cycle(current: T, delta: Int): T = this[(indexOf(current).coerceAtLeast(0) + delta).mod(size)]

/** A colour as hue (degrees), saturation and lightness (0..1), for changing one at a time. */
@Immutable
internal data class Hsl(val h: Float, val s: Float, val l: Float) {
    fun argb(): Long {
        val c = (1f - abs(2 * l - 1f)) * s
        val x = c * (1f - abs((h / 60f).mod(2f) - 1f))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60f).toInt().mod(6)) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    companion object {
        fun of(argb: Long): Hsl {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val l = (max + min) / 2
            if (max == min) return Hsl(0f, 0f, l)
            val d = max - min
            val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            val h = when (max) {
                r -> ((g - b) / d).mod(6f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            return Hsl(h, s, l)
        }
    }
}

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
 * The studio's controls, beside the stage, one step at a time: a header saying which step this is
 * and what it is for (with a dot per step to jump with), the step's rows, and a row on to the next
 * step. Rows change with left and right (or a tap on their arrows); a colour opens with confirm into
 * its hue, saturation and lightness, a code, and a fix when it is hard to read. The shoulder buttons
 * move between steps; back closes a colour, then steps back, then leaves. The highlight glides from
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
    val lines = studio.lines()
    val index = studio.index.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    val current = lines.getOrNull(index)
    val prefs by app.store.prefs.collectAsState()
    val userMotion = prefs.motion
    DisposableEffect(studio) {
        app.platform.sounds.setProfile(studio.sound)
        onDispose { app.platform.sounds.setProfile(app.store.prefs.value.sound) }
    }
    // The colours picked lately, kept across visits to the studio.
    LaunchedEffect(studio) { if (studio.recents.isEmpty()) studio.recents.addAll(prefs.recentColors) }
    fun keepRecent(argb: Long) = app.store.updatePrefs { p -> p.copy(recentColors = (listOf(argb) + p.recentColors.filter { it != argb }).take(10)) }
    fun closeColor() = studio.close(::keepRecent)
    LaunchedEffect(current, studio.changed, active, studio.step, studio.canUndo) {
        if (!active) return@LaunchedEffect
        app.hints = buildList {
            when (current) {
                is StudioLine.Main -> when (current.row.kind) {
                    RowKind.ACTION -> add(Hint(HintButton.CONFIRM, when (current.row) {
                        StudioRow.SAVE -> "Save"
                        StudioRow.PICTURE -> "Choose a picture"
                        StudioRow.PICTURE_REMOVE -> "Remove"
                        else -> "Next step"
                    }))
                    RowKind.SWITCH -> add(Hint(HintButton.CONFIRM, "Switch"))
                    RowKind.COLOR -> { add(Hint(HintButton.DPAD, "Lighter or darker")); add(Hint(HintButton.CONFIRM, "Edit")) }
                    else -> add(Hint(HintButton.DPAD, "Change"))
                }
                is StudioLine.Edit -> if (current.row.kind == RowKind.LEVEL) add(Hint(HintButton.DPAD, "Change")) else add(Hint(HintButton.CONFIRM, "Choose"))
                null -> Unit
            }
            if (studio.openColor == null) add(Hint(HintButton.NEXT, "Steps"))
            if (studio.canUndo) add(Hint(HintButton.SEARCH, "Undo"))
            if (studio.changed) add(Hint(HintButton.OPTIONS, "Start over"))
            add(Hint(HintButton.BACK, if (studio.openColor != null) "Done" else if (studio.step.ordinal > 0) "Back" else "Leave"))
        }
    }

    fun nextStep() = StudioStep.entries.getOrNull(studio.step.ordinal + 1)?.let { studio.go(it) }

    fun change(line: StudioLine, delta: Int): Boolean = studio.edit {
        when (line) {
            is StudioLine.Main -> studio.step(line.row, delta).also { moved ->
                // A new sound profile is heard straight away, in the move that chose it.
                if (moved && line.row == StudioRow.SOUND) app.platform.sounds.setProfile(studio.sound)
            }
            is StudioLine.Edit -> studio.openColor?.let { studio.nudge(it, line.row, delta) } ?: false
        }
    }

    fun pickPicture() {
        app.scope.launch {
            val path = app.platform.storage.pickImage("Choose a picture for your theme") ?: return@launch
            studio.edit {
                studio.picture = path
                studio.index = studio.lines().indexOfFirst { (it as? StudioLine.Main)?.row == StudioRow.PICTURE_DIM }.coerceAtLeast(0)
            }
        }
    }

    fun typeCode(role: ColorRole) {
        app.textInput = TextInputSpec(
            role.label, ThemeCodec.hex(studio.color(role)).removePrefix("#").take(6), placeholder = "A colour code, like 2BB673",
            capitalize = false, doneLabel = "Use it",
        ) { typed ->
            val parsed = ThemeCodec.parseColor(if (typed.trim().startsWith("#")) typed.trim() else "#" + typed.trim())
            if (parsed == null) app.toasts.show("That isn't a colour code. Try six letters and digits, like 2BB673")
            else studio.edit { studio.set(role, parsed) }
        }
    }

    fun activate(line: StudioLine) {
        when (line) {
            is StudioLine.Main -> when (line.row.kind) {
                RowKind.ACTION -> when (line.row) {
                    StudioRow.SAVE -> onSave()
                    StudioRow.PICTURE -> pickPicture()
                    StudioRow.PICTURE_REMOVE -> studio.edit {
                        studio.picture = null
                        studio.index = studio.lines().indexOfFirst { (it as? StudioLine.Main)?.row == StudioRow.PICTURE }.coerceAtLeast(0)
                    }
                    else -> nextStep()
                }
                RowKind.SWITCH -> studio.edit { studio.step(line.row, if (line.row == StudioRow.GLASS && studio.glass || line.row == StudioRow.CRT && studio.crt) -1 else 1) }
                RowKind.COLOR -> line.row.role?.let(studio::open)
                RowKind.CHOICE, RowKind.LEVEL -> change(line, 1)
            }
            is StudioLine.Edit -> {
                val role = studio.openColor ?: return
                when (line.row) {
                    EditRow.CODE -> typeCode(role)
                    EditRow.FIX -> studio.edit { studio.fix(role) }
                    EditRow.RESET -> studio.edit { studio.resetColor(role) }
                    EditRow.DONE -> closeColor()
                    else -> change(line, 1)
                }
            }
        }
    }

    InputLayer(enabled = active && app.focusZone == FocusZone.CONTENT && !app.overlayOpen) { e ->
        val line = lines.getOrNull(index) ?: return@InputLayer NavResult.IGNORED
        when (e.action) {
            NavAction.UP, NavAction.DOWN -> {
                val next = (index + if (e.action == NavAction.UP) -1 else 1).coerceIn(0, lines.lastIndex)
                if (next == index) NavResult.BLOCKED else { studio.index = next; NavResult.MOVED }
            }
            // The shoulder buttons (and the triggers) move between steps.
            NavAction.PREVIOUS_SECTION, NavAction.NEXT_SECTION, NavAction.PAGE_UP, NavAction.PAGE_DOWN -> {
                if (studio.openColor != null) return@InputLayer NavResult.BLOCKED
                val back = e.action == NavAction.PREVIOUS_SECTION || e.action == NavAction.PAGE_UP
                val to = StudioStep.entries.getOrNull(studio.step.ordinal + if (back) -1 else 1)
                if (to == null) NavResult.BLOCKED else { studio.go(to); NavResult.MOVED }
            }
            NavAction.LEFT, NavAction.RIGHT -> if (change(line, if (e.action == NavAction.LEFT) -1 else 1)) NavResult.MOVED else NavResult.BLOCKED
            NavAction.SELECT -> { activate(line); NavResult.ACTIVATED }
            NavAction.CONTEXT -> if (studio.changed) { studio.edit { studio.reset() }; app.platform.sounds.setProfile(studio.sound); NavResult.ACTIVATED } else NavResult.BLOCKED
            // Y takes the last change back, one at a time, as far as the studio's start.
            NavAction.SEARCH -> if (studio.undo()) { app.platform.sounds.setProfile(studio.sound); NavResult.ACTIVATED } else NavResult.BLOCKED
            NavAction.BACK -> {
                when {
                    studio.openColor != null -> closeColor()
                    studio.step.ordinal > 0 -> studio.go(StudioStep.entries[studio.step.ordinal - 1])
                    else -> onLeave()
                }
                NavResult.CONSUMED
            }
            else -> NavResult.IGNORED
        }
    }

    // Where each line sits in the scrolling column (top and height, px), for the highlight and scrolling.
    val bounds = remember { mutableStateMapOf<StudioLine, Pair<Float, Float>>() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val at = current?.let { bounds[it] }
    LaunchedEffect(current, at) {
        val (top, height) = at ?: return@LaunchedEffect
        val margin = with(density) { Space.l.toPx() }
        val view = scroll.viewportSize.toFloat()
        val target = when {
            index == 0 -> 0f
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
            Column {
                StepHeader(studio, compact) { app.focusZone = FocusZone.CONTENT; studio.go(it) }
                // A step's rows slide in from the side it was reached from.
                val motion = Fuse.motion
                AnimatedContent(
                    targetState = studio.step to studio.openColor,
                    transitionSpec = {
                        val shift = if (motion.reduced) 0 else 1
                        val dir = studio.direction
                        (fadeIn(motion.fade(Durations.FAST)) + slideInHorizontally(motion.tween(Durations.BASE, Easings.Enter)) { it / 8 * dir * shift }) togetherWith
                            fadeOut(motion.fade(Durations.INSTANT))
                    },
                    label = "studioStep",
                ) { _ ->
                    Column(
                        Modifier
                            .fadingEdges(scroll, top = Space.xl, bottom = Space.xl)
                            .verticalScroll(scroll)
                            .padding(Space.xs)
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
                    ) {
                        studio.openColor?.let { role -> ColorHeader(studio, role) }
                        for ((i, line) in lines.withIndex()) {
                            StudioLineView(
                                line = line,
                                studio = studio,
                                look = look,
                                selected = i == index && app.focusZone == FocusZone.CONTENT,
                                compact = compact,
                                narrow = narrow,
                                detail = detailOf(line, studio, userMotion?.label()),
                                onTap = {
                                    app.focusZone = FocusZone.CONTENT
                                    val was = studio.index == i
                                    studio.index = i
                                    val kind = when (line) {
                                        is StudioLine.Main -> line.row.kind
                                        is StudioLine.Edit -> line.row.kind
                                    }
                                    if (was || kind == RowKind.ACTION || kind == RowKind.SWITCH || kind == RowKind.COLOR) activate(line)
                                },
                                onStep = { delta ->
                                    app.focusZone = FocusZone.CONTENT
                                    studio.index = i
                                    change(line, delta)
                                },
                                onPick = { p ->
                                    app.focusZone = FocusZone.CONTENT
                                    studio.index = i
                                    val role = studio.openColor
                                    if (line is StudioLine.Edit && line.row == EditRow.RECENT && role != null) {
                                        studio.recents.getOrNull(p)?.let { argb -> studio.edit { studio.set(role, argb) } }
                                    } else {
                                        studio.edit { studio.pick(p) }
                                    }
                                },
                                modifier = Modifier.onPlaced { bounds[line] = it.positionInParent().y to it.size.height.toFloat() },
                            )
                        }
                    }
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

/** The line under a row's name: what it is for, or why it reads the way it does. */
private fun detailOf(line: StudioLine, studio: StudioState, userMotion: String?): String? = when (line) {
    is StudioLine.Main -> when (line.row) {
        StudioRow.MOTION -> userMotion?.let { "Your Motion setting ($it) is used instead" }
        StudioRow.SAVE -> studio.editing?.let { "Saving under its name replaces ${it.name}" } ?: "Name it, and it joins your themes"
        StudioRow.NEXT -> StudioStep.entries.getOrNull(studio.step.ordinal + 1)?.title
        StudioRow.MODE -> if (studio.dark) "A dark room. Changing it makes the room and text again" else "A bright room. Changing it makes the room and text again"
        StudioRow.LIGHT -> "How bright the scene's light is"
        StudioRow.SPEED -> "How fast it drifts; none holds it still"
        StudioRow.BLUR -> "How much the art behind the panels softens"
        StudioRow.OPACITY -> "How much of the panels' colour covers the art"
        StudioRow.PICTURE -> if (studio.picture != null) "Your own picture is behind everything. Choose another" else "A picture from this device, behind everything"
        StudioRow.PICTURE_DIM -> "Darker keeps text easy to read over it"
        StudioRow.PICTURE_ALIGN -> "Which part stays in view when the screen crops it"
        StudioRow.PICTURE_REMOVE -> "Back to a background Fuse draws"
        else -> line.row.role?.note
    }
    is StudioLine.Edit -> when (line.row) {
        EditRow.FIX -> "Lightens or darkens it just enough"
        EditRow.CODE -> "Six letters and digits, like 2BB673"
        EditRow.RECENT -> "The colours you picked lately"
        else -> null
    }
}

/** The step's place in the studio, its name and what it is for, and a dot per step to jump with. */
@Composable
private fun StepHeader(studio: StudioState, compact: Boolean, onGo: (StudioStep) -> Unit) {
    val c = Fuse.colors
    val step = studio.step
    Column(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, top = if (compact) Space.m else Space.l, bottom = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FText("STEP ${step.ordinal + 1} OF ${StudioStep.entries.size}", Fuse.type.overline, color = c.accent, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                for (s in StudioStep.entries) {
                    val on = s == step
                    val w by animateFloatAsState(if (on) 1f else 0f, Fuse.motion.value(), label = "stepDot")
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(6.dp + 12.dp * w)
                            .clip(CircleShape)
                            .background(if (on) c.accent else if (s.ordinal < step.ordinal) c.text.copy(alpha = 0.45f) else c.text.copy(alpha = 0.16f))
                            .fuseClickable(shape = CircleShape, scale = false, onClickLabel = s.title) { onGo(s) }
                            .semantics { contentDescription = s.title },
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.xs))
        FText(studio.openColor?.label ?: step.title, if (compact) Fuse.type.titleSmall else Fuse.type.title, maxLines = 1)
        // On a short screen the rows need the room more: each row says what it does itself.
        if (!compact) {
            Spacer(Modifier.height(Space.xxs))
            FText(studio.openColor?.note ?: step.guide, Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
    }
}

/** The colour being changed: a large swatch, its code, and how well it reads where it sits. */
@Composable
private fun ColorHeader(studio: StudioState, role: ColorRole) {
    val c = Fuse.colors
    val argb = studio.color(role)
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(Size.iconXL)
                .clip(SquircleShape.fraction(0.3f))
                .background(Color(argb))
                .border(1.dp, c.hairlineStrong, SquircleShape.fraction(0.3f)),
        )
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            FText(ThemeCodec.hex(argb).take(7), Fuse.type.bodyStrong.tabular(), maxLines = 1)
            val sits = when (role) {
                ColorRole.BACKGROUND, ColorRole.SURFACE, ColorRole.RAISED -> "Text on it"
                ColorRole.SECOND_LIGHT -> "A light in the scene"
                else -> "On the room and panels"
            }
            FText(sits, Fuse.type.caption, color = c.textMuted, maxLines = 1)
        }
        if (role != ColorRole.SECOND_LIGHT) LegibilityBadge(studio.legibility(role))
    }
}

/** "Reads well" or "Hard to read", with the ratio: colour is never the only cue. */
@Composable
private fun LegibilityBadge(l: Legibility, small: Boolean = false) {
    val c = Fuse.colors
    val tint = if (l.ok) c.success else c.warning
    Row(
        Modifier
            .clip(io.github.matiyaaa.fuse.ui.designsystem.shape.PillShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = Space.s, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FuseIcon(if (l.ok) FuseIcons.Check else FuseIcons.Warning, size = 12.dp, tint = tint)
        Spacer(Modifier.width(Space.xxs + 2.dp))
        val ratio = ((l.ratio * 10).roundToInt() / 10.0).let { if (it == it.toInt().toDouble()) it.toInt().toString() else it.toString() }
        FText(if (small) "$ratio:1" else "${if (l.ok) "Reads well" else "Hard to read"}  $ratio:1", Fuse.type.caption.tabular(), color = tint, maxLines = 1)
    }
}

/** One line of the studio: its icon and name, and its control. Anatomy matches menu rows. */
@Composable
private fun StudioLineView(
    line: StudioLine,
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
    val (label, icon, kind) = when (line) {
        is StudioLine.Main -> Triple(
            if (line.row == StudioRow.NEXT) "Next step" else line.row.label, line.row.icon, line.row.kind,
        )
        is StudioLine.Edit -> Triple(line.row.label, line.row.icon, line.row.kind)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) Size.rowCompact else Size.row)
            .fuseClickable(shape = shape, scale = false, role = if (kind == RowKind.SWITCH) Role.Switch else Role.Button, onClick = onTap)
            // Screen readers (and the UI audit) can tell which row the controller is on.
            .semantics { this.selected = selected }
            .padding(start = Size.sparkHeight + Space.m, end = if (narrow) Space.xs else Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!narrow) {
            Well(icon, selected)
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            FText(label, Fuse.type.bodyStrong, color = if (selected) c.text else c.text.copy(alpha = 0.92f), maxLines = 1)
            if (detail != null) FText(detail, Fuse.type.caption, color = c.textMuted, maxLines = 2)
        }
        Spacer(Modifier.width(if (narrow) Space.s else Space.m))
        val dir = studio.direction
        when (line) {
            is StudioLine.Edit -> when (line.row) {
                EditRow.HUE, EditRow.SATURATION, EditRow.LIGHTNESS -> {
                    val role = studio.openColor
                    val hsl = role?.let { Hsl.of(studio.color(it)) }
                    if (hsl != null) {
                        val value = when (line.row) {
                            EditRow.HUE -> hsl.h / 360f
                            EditRow.SATURATION -> hsl.s
                            else -> hsl.l
                        }
                        val ends = when (line.row) {
                            EditRow.HUE -> (0..6).map { Color(Hsl(it * 60f, hsl.s.coerceAtLeast(0.5f), hsl.l.coerceIn(0.3f, 0.7f)).argb()) }
                            EditRow.SATURATION -> listOf(Color(hsl.copy(s = 0f).argb()), Color(hsl.copy(s = 1f).argb()))
                            else -> listOf(Color.Black, Color(hsl.copy(l = 0.5f).argb()), Color.White)
                        }
                        StepperShell(selected, narrow, onStep) { Gauge(value, ends, narrow) }
                    }
                }
                EditRow.RECENT -> {
                    val role = studio.openColor
                    val now = role?.let { studio.recents.indexOf(studio.color(it)) } ?: -1
                    SwatchStrip(studio.recents.take(if (narrow) 5 else 8).map { Swatch("Recent colour", it) }, now, onPick)
                }
                EditRow.DONE -> FuseIcon(FuseIcons.Check, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
                else -> FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
            }
            is StudioLine.Main -> when (val row = line.row) {
                StudioRow.ACCENT -> if (narrow) {
                    ColorValue(studio, ColorRole.ACCENT, narrow = true)
                } else {
                    val list = studio.swatches
                    val chosen = list.indexOfFirst { it.argb == studio.color(ColorRole.ACCENT) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (chosen < 0) {
                            SwatchDisc(studio.color(ColorRole.ACCENT), chosen = true, Modifier.size(Size.iconM))
                            Spacer(Modifier.width(Space.m))
                        }
                        SwatchStrip(list, chosen, onPick)
                    }
                }
                StudioRow.MODE -> Stepper(studio.dark, dir, selected, onStep, narrow) { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FuseIcon(if (d) FuseIcons.Moon else FuseIcons.Sun, size = Size.iconS, tint = c.textMuted)
                        Spacer(Modifier.width(Space.s))
                        FText(if (d) "Dark" else "Light", Fuse.type.label, maxLines = 1)
                    }
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
                StudioRow.LIGHT -> StepperShell(selected, narrow, onStep) { Gauge(studio.light / 1.5f, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.SPEED -> StepperShell(selected, narrow, onStep) { Gauge(studio.speed / 2f, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.BLUR -> StepperShell(selected, narrow, onStep) { Gauge(studio.blur / 48f, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.OPACITY -> StepperShell(selected, narrow, onStep) { Gauge(studio.opacity, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.SCANLINES -> StepperShell(selected, narrow, onStep) { Gauge(studio.scanlines, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.BLOOM -> StepperShell(selected, narrow, onStep) { Gauge(studio.bloom, listOf(c.text.copy(alpha = 0.1f), c.accent), narrow) }
                StudioRow.PICTURE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    studio.picture?.let { path ->
                        io.github.matiyaaa.fuse.ui.designsystem.media.Artwork(
                            path,
                            Modifier.size(width = Size.thumb, height = Size.thumb * 0.5625f).clip(RoundedCornerShape(Radius.xs)),
                        )
                        Spacer(Modifier.width(Space.s))
                    }
                    FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
                }
                StudioRow.PICTURE_DIM -> StepperShell(selected, narrow, onStep) { Gauge(studio.pictureDim / 0.9f, listOf(c.text.copy(alpha = 0.1f), c.ink), narrow) }
                StudioRow.PICTURE_ALIGN -> Stepper(studio.pictureAlign, dir, selected, onStep, narrow) { a ->
                    FText(a.name.lowercase().replaceFirstChar { it.uppercase() }, Fuse.type.label, maxLines = 1)
                }
                StudioRow.PICTURE_REMOVE -> FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
                StudioRow.GLASS -> Toggle(studio.glass, Modifier.padding(end = Space.xs))
                StudioRow.CRT -> Toggle(studio.crt, Modifier.padding(end = Space.xs))
                StudioRow.SAVE, StudioRow.NEXT -> FuseIcon(FuseIcons.ChevronRight, size = Size.iconS, tint = if (selected) c.text else c.textMuted, modifier = Modifier.padding(end = Space.xs))
                else -> row.role?.let { ColorValue(studio, it, narrow) }
            }
        }
    }
}

/** A colour row's value: how well it reads, its code and a swatch of it. */
@Composable
private fun ColorValue(studio: StudioState, role: ColorRole, narrow: Boolean) {
    val c = Fuse.colors
    val argb = studio.color(role)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (role != ColorRole.SECOND_LIGHT) {
            LegibilityBadge(studio.legibility(role), small = narrow)
            Spacer(Modifier.width(Space.s))
        }
        if (!narrow) {
            FText(ThemeCodec.hex(argb).take(7), Fuse.type.caption.tabular(), color = c.textMuted, maxLines = 1)
            Spacer(Modifier.width(Space.s))
        }
        SwatchDisc(argb, chosen = false, Modifier.size(Size.iconM))
    }
}

/** Arrows either side of an amount, like [Stepper] but for a value that slides rather than swaps. */
@Composable
private fun StepperShell(selected: Boolean, narrow: Boolean, onStep: (Int) -> Unit, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepArrow(FuseIcons.ChevronLeft, selected, narrow, "Less") { onStep(-1) }
        Box(Modifier.widthIn(min = if (narrow) Space.x5 - Space.l else Space.x5 + Space.xl), contentAlignment = Alignment.Center) { content() }
        StepArrow(FuseIcons.ChevronRight, selected, narrow, "More") { onStep(1) }
    }
}

/** An amount as a track in [colors] (a gradient for hue and the like) with a knob at [value] (0..1). */
@Composable
private fun Gauge(value: Float, colors: List<Color>, narrow: Boolean) {
    val c = Fuse.colors
    val v by animateFloatAsState(value.coerceIn(0f, 1f), Fuse.motion.value(), label = "gauge")
    Spacer(
        Modifier.size(width = if (narrow) Space.x5 - Space.l else Space.x5 + Space.l, height = Size.iconS).drawBehind {
            val h = 6.dp.toPx()
            val y = (size.height - h) / 2
            drawRoundRect(Brush.horizontalGradient(colors), Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(c.hairlineStrong, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, h), CornerRadius(h / 2), style = Stroke(1.dp.toPx()))
            val x = (size.width * v).coerceIn(size.height / 2, size.width - size.height / 2)
            drawCircle(c.surfaceOverlay, size.height / 2, Offset(x, size.height / 2))
            drawCircle(c.text, size.height / 2, Offset(x, size.height / 2), style = Stroke(2.dp.toPx()))
        },
    )
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

/**
 * What the stage lights for the studio's selected line: the parts of the miniature the setting
 * changes, its name and what it does. A colour being edited keeps its parts lit throughout.
 */
internal fun spotlightOf(studio: StudioState): Spotlight? {
    val line = studio.line()
    val role = studio.openColor ?: (line as? StudioLine.Main)?.row?.role
    if (role != null) {
        return when (role) {
            ColorRole.BACKGROUND -> Spotlight(emptySet(), "Room", "The colour behind everything")
            ColorRole.SURFACE -> Spotlight(setOf(SpotPart.SHEET), "Panels", "Menus, sheets and cards", sheet = true)
            ColorRole.RAISED -> Spotlight(setOf(SpotPart.SHEET_RAISED), "Raised panels", "Panels on panels, and the row you're on", sheet = true)
            ColorRole.TEXT -> Spotlight(setOf(SpotPart.TITLE, SpotPart.LABELS), "Text", "Titles and everything you read")
            ColorRole.MUTED -> Spotlight(setOf(SpotPart.SHEET_MUTED, SpotPart.HINTS), "Details text", "Captions, counts and hints", sheet = true)
            ColorRole.ACCENT -> Spotlight(setOf(SpotPart.FOCUSED_TILE, SpotPart.SHEET_BUTTON), "Accent colour", "The spark under what's chosen, buttons and progress", sheet = true)
            ColorRole.ON_ACCENT -> Spotlight(setOf(SpotPart.SHEET_BUTTON), "Text on the accent", "Words and icons on accent buttons", sheet = true)
            ColorRole.FOCUS -> Spotlight(setOf(SpotPart.FOCUSED_TILE), "Focus colour", "The outline on what you're on")
            ColorRole.SUCCESS, ColorRole.WARNING, ColorRole.DANGER -> Spotlight(setOf(SpotPart.SHEET_SIGNALS), role.label, role.note, sheet = true)
            ColorRole.SECOND_LIGHT -> Spotlight(emptySet(), "Second colour", "The scene's other light, blended with the accent")
        }
    }
    val row = (line as? StudioLine.Main)?.row ?: return null
    return when (row) {
        StudioRow.MODE -> Spotlight(emptySet(), "Light or dark", "A bright room or a dark one; the colours are made again around your accent")
        StudioRow.CORNERS -> Spotlight(setOf(SpotPart.TILES, SpotPart.SHEET), "Corners", "How round tiles, panels and buttons are", sheet = true)
        StudioRow.FOCUS -> Spotlight(setOf(SpotPart.FOCUSED_TILE), "Focus", "How the tile you're on stands out from the rest")
        StudioRow.BACKGROUND, StudioRow.LIGHT, StudioRow.SPEED -> Spotlight(emptySet(), row.label, "The scene behind everything: which one, how bright, how lively")
        StudioRow.PICTURE, StudioRow.PICTURE_DIM, StudioRow.PICTURE_ALIGN, StudioRow.PICTURE_REMOVE -> Spotlight(emptySet(), "Your picture", "Behind everything, darkened so text reads over it")
        StudioRow.GLASS, StudioRow.BLUR, StudioRow.OPACITY -> Spotlight(setOf(SpotPart.SHEET), row.label, "Panels like frosted glass, with the room showing through", sheet = true)
        StudioRow.CRT, StudioRow.SCANLINES, StudioRow.BLOOM -> Spotlight(emptySet(), row.label, "An old screen's lines and glow over everything")
        StudioRow.MOTION -> Spotlight(setOf(SpotPart.FOCUSED_TILE, SpotPart.TILES), "Motion", "How tiles lift and things glide as you move")
        StudioRow.SOUND -> Spotlight(setOf(SpotPart.HINTS), "Sounds", "What you hear as you move and choose")
        else -> null
    }
}
