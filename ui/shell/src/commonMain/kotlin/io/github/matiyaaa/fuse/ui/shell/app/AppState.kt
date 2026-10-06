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
    /** A line under the title saying what the text is for. */
    val message: String? = null,
    /** Closed without Done (Back, a tap outside). */
    val onCancel: () -> Unit = {},
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
 * Where Fuse's menus are: the pages open (and where each was left), the Addons view, the Settings
 * groups unfolded. Kept apart from the window drawing them, so the menus moved to the other screen
 * (flipping a two-screen handheld) open on the very page they were on.
 */
@androidx.compose.runtime.Stable
class KeptPlace(start: Route) {
    val navigator = Navigator(start)
    val openGroups = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var addonsPart by mutableStateOf<AddonsPart?>(null)
}

/** The place kept for the app's own windows while Fuse runs ([FuseApp]'s keepPlace). */
object KeptPlaces {
    var current: KeptPlace? = null
}

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
    /** Where the menus are, kept when their window is made again (see [KeptPlace]). */
    private val kept: KeptPlace = KeptPlace(start),
) {
    val navigator = kept.navigator
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

    /** Syncthing setup sent the person to the Store for Syncthing-Fork: once installed, setup carries on. */
    var awaitingSyncthing by mutableStateOf(false)

    /** What the last "Restore Fuse Default Art" replaced, while it can still be undone (this session). */
    var artUndo by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.store.ArtUndo?>(null)
    var textInput by mutableStateOf<TextInputSpec?>(null)
    var choice by mutableStateOf<ChoiceSpec?>(null)

    /** Fuse Player is open over everything (Jellyfin). */
    var playerOpen by mutableStateOf(false)

    /**
     * These menus are on the second screen (flipped, a 3DS's way round): the main screen shows the
     * showcase. Set by the window drawing them.
     */
    var menusOnSecondScreen = false

    /** The code for typing on a phone is showing (the keyboard's phone key). */
    var phoneTyping by mutableStateOf(false)
    var reorder by mutableStateOf<ReorderSpec?>(null)

    /** Fuse's startup animation is playing ([StartupIntroOverlay]). */
    var intro by mutableStateOf(false)

    /** Setup's own opening is playing (the first start of Fuse, in place of [intro]). */
    var setupOpening by mutableStateOf(false)

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

    /** Who is playing here (Fuse Sync's profile in use); null while Fuse Sync is off or no one is chosen. */
    var syncProfile by mutableStateOf<io.github.matiyaaa.fuse.sync.ProfileInfo?>(null)

    /** How many profiles the Fuse Sync host has (0 while it is off): the top line shows who is playing only with two or more. */
    var syncProfileCount by mutableStateOf(0)

    /** Someone just became the one playing: their arrival plays over everything, then clears. */
    var profileArrival by mutableStateOf<io.github.matiyaaa.fuse.sync.ProfileInfo?>(null)

    /** The arrival is someone's first time playing here: it gets the grand one (a fuse burns in and lights them). */
    var arrivalGrand by mutableStateOf(false)

    /** The grand arrival is for a profile just made here (not one signed in to from another device). */
    var arrivalMade by mutableStateOf(true)

    /** Fuse is using Syncthing here (its state isn't Off): Addons shows its tab. */
    var syncthingActive by mutableStateOf(false)

    /** "Your profiles and the host's" is on screen while joining a host (see [io.github.matiyaaa.fuse.sync.SyncService.merge]). */
    var profileMerge by mutableStateOf(false)

    /** Fuse Sync's startup choice of profile was made (once per run, not on every recomposition). */
    var syncStartupDone = false

    /** What the Fuse Sync host keeps for the profile in use, as the Sync tab last heard it. */
    var syncReport by mutableStateOf<io.github.matiyaaa.fuse.sync.ProfileReport?>(null)

    /** The host's "Add a device" sheet, with its pairing code. */
    var pairing by mutableStateOf(false)

    /** "Who's playing?", Fuse Sync's profile picker, and why it is open. */
    var whoAreYou by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.sync.WhoMode?>(null)

    /** A profile being edited (name, picture, PIN), with its PIN as typed to open it when it has one. */
    var profileEdit by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.sync.ProfileEditSpec?>(null)

    /** A save conflict Fuse Sync asks about before a game starts. */
    var saveConflict by mutableStateOf<io.github.matiyaaa.fuse.ui.shell.sync.SaveConflictSpec?>(null)

    /** "Play on which screen?" on a device with two screens. */
    var screenPrompt by mutableStateOf<ScreenPromptSpec?>(null)

    /** "Detect my buttons" is running; it takes every press until it finishes. */
    var buttonDetect by mutableStateOf(false)

    /** Text being typed in the text input overlay (on-screen keyboard or a hardware keyboard). */
    val textDraft = io.github.matiyaaa.fuse.ui.designsystem.components.EditableText()

    /** Where hardware keyboard typing goes (search field, rename dialog), or null for navigation keys. */
    var keyboardTarget by mutableStateOf<KeyboardTarget?>(null)

    /** Which part of Addons shows (Cartridge or the Store); null until one is chosen or opened. */
    var addonsPart: AddonsPart?
        get() = kept.addonsPart
        set(value) { kept.addonsPart = value }

    /** Settings groups that are open, by id. They stay open while Fuse runs. */
    val openGroups = kept.openGroups

    /** What the room is lit by. Screens set it from their selection. */
    var hero by mutableStateOf<HeroSource?>(null)

    /** Developer options: off until the version in About is tapped five times, and only until Fuse closes. */
    val dev = DevOptions()

    /** Hints for the current selection; screens set them. */
    var hints by mutableStateOf<List<Hint>>(emptyList())

    /** A game is being launched: the launch veil is drawn over everything until Fuse is paused. */
    var launching by mutableStateOf<LaunchVeil?>(null)

    val overlayOpen: Boolean
        get() = quickMenuOpen || contextMenu != null || confirm != null || textInput != null || choice != null || reorder != null || screenPrompt != null || buttonDetect || problem != null || saveConflict != null || whoAreYou != null || profileEdit != null || profileMerge || pairing || textPreview != null || phoneTyping

    fun openContextMenu(spec: ContextMenuSpec) {
        contextMenu = spec
    }

    fun closeOverlays() {
        quickMenuOpen = false
        contextMenu = null
        confirm = null
        textInput?.let { t -> textInput = null; t.onCancel() }
        phoneTyping = false
        choice = null
        screenPrompt = null
        problem = null
        textPreview = null
        buttonDetect = false
    }

    /**
     * Where setup is: kept here rather than in its page, so a detour from it (setting up Fuse Sync
     * or Syncthing, which are pages of their own) comes back to the very step it left, with that
     * step's news ("Fuse Sync is on"). Setup opened afresh starts from the beginning.
     */
    var onboarding by mutableStateOf(io.github.matiyaaa.fuse.ui.shell.onboarding.OnboardingState())
        private set

    fun go(route: Route) {
        focusZone = FocusZone.CONTENT
        if (route == Route.Onboarding) onboarding = io.github.matiyaaa.fuse.ui.shell.onboarding.OnboardingState()
        navigator.push(route)
    }

    fun back(): Boolean = navigator.pop()

    fun selectTab(destination: Destination) {
        navigator.selectRoot(destination)
    }
}

/**
 * A text field open for typing: hardware keyboards type into it, and through Phone Link a phone can
 * too, which shows it as [title] with its [doneLabel] key. [cancel] closes it without finishing
 * (null where it can't be closed, like Search's own field).
 */
class KeyboardTarget(
    val field: io.github.matiyaaa.fuse.ui.designsystem.components.EditableText,
    val title: String = "Search",
    val secret: Boolean = false,
    val placeholder: String = "",
    val doneLabel: String = "Done",
    val cancel: (() -> Unit)? = null,
    val submit: () -> Unit,
)

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
    /** What is happening now, under the title ("Installing 1 of 3"); null is "Starting". */
    val status: String? = null,
    /** Calls the launch off, while nothing has started yet (the save is still being checked); null once it can't be. */
    val cancel: (() -> Unit)? = null,
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
