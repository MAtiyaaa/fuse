package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ReorderEntry
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastState
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import io.github.matiyaaa.fuse.ui.shell.store.PhoneLinkControl
import io.github.matiyaaa.fuse.ui.shell.store.UiPrefs
import kotlinx.coroutines.CoroutineScope

/** Where controller focus is: the section tabs at the top, or the page content. */
enum class FocusZone { TABS, CONTENT }

/**
 * An options menu opened on something (a game, a system, an app, a widget). Its header shows [art],
 * else an [icon]. With an [accent], something without art gets generated art in its colour, as its
 * tile does, so the menu still shows what it belongs to.
 */
data class ContextMenuSpec(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val art: Any? = null,
    val actions: List<MenuAction>,
    /** ARGB colour for generated art when there is no [art]. */
    val accent: Long? = null,
)

data class ConfirmSpec(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val onConfirm: () -> Unit,
)

data class TextInputSpec(
    val title: String,
    val initial: String,
    val placeholder: String = "",
    /** Shows dots instead of the text (passwords). */
    val secret: Boolean = false,
    /** A capital to start with and after full stops; off for keys, addresses and user names. */
    val capitalize: Boolean = true,
    val doneLabel: String = "Done",
    val onDone: (String) -> Unit,
)

/**
 * A list to choose from, in a dialog: what it is about ([title], an optional [message]) and its
 * [options]. An [icon] heads it in a well, so a picker reads as part of the thing it changes.
 */
data class ChoiceSpec(
    val title: String,
    val message: String? = null,
    val options: List<MenuAction>,
    val icon: ImageVector? = null,
)

/**
 * A list to put in order, in a dialog: rows with grips that drag, or pick up with A and move with
 * the D-pad. [onMoved] gets each new order (keys) the moment a row is put down, so the setting
 * underneath changes as you go.
 */
data class ReorderSpec(
    val title: String,
    val entries: List<ReorderEntry>,
    val message: String? = null,
    val icon: ImageVector? = null,
    val onMoved: (List<String>) -> Unit,
)

/**
 * The interface's live state: navigation, focus zone, overlays and what the backdrop shows. One
 * instance per window/screen; the second screen has its own.
 */
@Stable
class AppState(
    val store: FuseStore,
    val platform: PlatformUi,
    val scope: CoroutineScope,
    start: Route,
    /** Phone Link's server, where this build has one. */
    val phoneLink: PhoneLinkControl? = null,
) {
    val navigator = Navigator(start)
    val toasts = ToastState()

    /** Screenshots and recordings of Fuse's screen; null where the platform can't capture it. */
    val capture: io.github.matiyaaa.fuse.ui.shell.capture.CaptureController? = platform.capture?.let { c ->
        io.github.matiyaaa.fuse.ui.shell.capture.CaptureController(
            capture = c,
            scope = scope,
            haptics = platform.haptics,
            notify = { message, kind -> toasts.show(message, kind) },
            withSound = { store.prefs.value.captureSound },
        )
    }

    var focusZone by mutableStateOf(FocusZone.CONTENT)

    /** Search or Settings in the top line has controller focus (only while [focusZone] is TABS). */
    var hudButton by mutableStateOf<HudButton?>(null)
    var quickMenuOpen by mutableStateOf(false)
    var contextMenu by mutableStateOf<ContextMenuSpec?>(null)
    var confirm by mutableStateOf<ConfirmSpec?>(null)
    var textInput by mutableStateOf<TextInputSpec?>(null)
    var choice by mutableStateOf<ChoiceSpec?>(null)
    var reorder by mutableStateOf<ReorderSpec?>(null)

    /** Fuse's startup animation is playing ([StartupIntroOverlay]). */
    var intro by mutableStateOf(false)

    /**
     * Licence files picked and zRIFs pasted for installs, by "gameId|contentId". Kept in memory for
     * this run of Fuse only, never written down, so a key goes no further than the emulator.
     */
    val contentPicks = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val contentKeys = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** Text to read before it is saved or shared ([TextPreviewOverlay]). */
    var textPreview by mutableStateOf<TextPreviewSpec?>(null)

    /** Pictures shown one at a time over everything, top bar included (a game's screenshots). */
    var gallery by mutableStateOf<GallerySpec?>(null)

    /** Fuse's standby screen is up ([StandbyScreen]): left alone for the user's Standby time. */
    var standby by mutableStateOf(false)

    /** A Library view asked for from elsewhere (the Favourites widget), opened once and cleared. */
    var librarySegment by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.library.LibrarySegment?>(null)

    /** Set while Fuse runs in safe mode ([SafeMode]); cleared when the user leaves it. */
    var safeMode by mutableStateOf<SafeMode?>(null)

    /** Something went wrong or needs attention, told with what can be done ([ProblemOverlay]). */
    var problem by mutableStateOf<ProblemSpec?>(null)

    /** "Play on which screen?" on a device with two screens. */
    var screenPrompt by mutableStateOf<ScreenPromptSpec?>(null)

    /** "Detect my buttons" is running; it takes every press until it finishes. */
    var buttonDetect by mutableStateOf(false)

    /** Text being typed in the text input overlay (on-screen keyboard or a hardware keyboard). */
    val textDraft = io.github.matiyaaa.fuse.ui.designsystem.components.EditableText()

    /** Where hardware keyboard typing goes (search field, rename dialog), or null for navigation keys. */
    var keyboardTarget by mutableStateOf<KeyboardTarget?>(null)

    /** Which part of Addons shows (Cartridge or the Store); null until one is chosen or opened. */
    var addonsPart by mutableStateOf<AddonsPart?>(null)

    /** Settings groups that are open, by id. They stay open while Fuse runs. */
    val openGroups = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    /** What the room is lit by. Screens set it from their selection. */
    var hero by mutableStateOf<HeroSource?>(null)

    /** Developer options: off until the version in About is tapped five times, and only until Fuse closes. */
    val dev = DevOptions()

    /** Hints for the current selection; screens set them. */
    var hints by mutableStateOf<List<Hint>>(emptyList())

    /** A game is being launched: the launch veil is drawn over everything until Fuse is paused. */
    var launching by mutableStateOf<LaunchVeil?>(null)

    val overlayOpen: Boolean
        get() = quickMenuOpen || contextMenu != null || confirm != null || textInput != null || choice != null || reorder != null || screenPrompt != null || buttonDetect || problem != null || textPreview != null

    fun openContextMenu(spec: ContextMenuSpec) {
        contextMenu = spec
    }

    fun closeOverlays() {
        quickMenuOpen = false
        contextMenu = null
        confirm = null
        textInput = null
        choice = null
        screenPrompt = null
        problem = null
        textPreview = null
        buttonDetect = false
    }

    fun go(route: Route) {
        focusZone = FocusZone.CONTENT
        navigator.push(route)
    }

    fun back(): Boolean = navigator.pop()

    fun selectTab(destination: Destination) {
        navigator.selectRoot(destination)
    }
}

/** A text field that accepts hardware keyboard input. */
class KeyboardTarget(val field: io.github.matiyaaa.fuse.ui.designsystem.components.EditableText, val submit: () -> Unit)

/**
 * Shown for the moment between pressing Play and the emulator taking over: the game's [title] over
 * its [art] (its room: background, screenshot or system art), lit in [accent]. [cover] is its own
 * square or box art, set beside the title as a tile (generated from the title when it has none);
 * [logo] replaces the written title where the user shows logos; [system] names where it runs.
 */
data class LaunchVeil(
    val title: String,
    val art: Any?,
    val accent: Long,
    val cover: Any? = null,
    val logo: Any? = null,
    val system: String? = null,
    val artFocusX: Float = 0.5f,
    val artFocusY: Float = 0.35f,
    /** [art] is a cover, not a background: drawn blurred into a colour field behind everything. */
    val artBlurred: Boolean = false,
)

/**
 * Options for testing Fuse itself. They are never saved: tapping the version in About five times
 * turns them on for this launch only, and closing Fuse turns them off again.
 */
@Stable
class DevOptions {
    /** Taps on the version so far; the fifth turns the options on. */
    var taps by mutableIntStateOf(0)
    var enabled by mutableStateOf(false)

    /** Every onboarding step can be skipped and its buttons pressed, required ones included. */
    var skipRequired by mutableStateOf(false)

    /** A live graph of frame times in the corner, to see lag on the device. */
    var frameGraph by mutableStateOf(false)

    /**
     * While setup is replayed as a rehearsal: the preferences as they were before it started.
     * Nothing a step does outside preferences is carried out, and these are put back at the end.
     */
    var rehearsalPrefs by mutableStateOf<UiPrefs?>(null)

    val rehearsing: Boolean get() = rehearsalPrefs != null

    /** Counts a tap on the version; returns how many more it takes (0 once on). */
    fun tap(): Int {
        if (enabled) return 0
        taps++
        if (taps >= TAPS) enabled = true
        return (TAPS - taps).coerceAtLeast(0)
    }

    companion object {
        const val TAPS = 5
    }
}

/** [AppState.gallery]: the pictures, the one to open on, and what to tell the page as they change. */
class GallerySpec(val pictures: List<Any?>, val start: Int, val onIndex: (Int) -> Unit, val onClose: () -> Unit)
