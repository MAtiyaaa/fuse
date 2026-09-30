package io.github.matiyaaa.fuse.ui.shell.app

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.matiyaaa.fuse.model.Destination
import io.github.matiyaaa.fuse.ui.designsystem.components.Hint
import io.github.matiyaaa.fuse.ui.designsystem.components.MenuAction
import io.github.matiyaaa.fuse.ui.designsystem.components.ToastState
import io.github.matiyaaa.fuse.ui.designsystem.media.HeroSource
import io.github.matiyaaa.fuse.ui.shell.platform.PlatformUi
import io.github.matiyaaa.fuse.ui.shell.store.FuseStore
import kotlinx.coroutines.CoroutineScope

/** Where controller focus is: the section tabs at the top, or the page content. */
enum class FocusZone { TABS, CONTENT }

/** An options menu opened on something (a game, a system, an app, a widget). */
data class ContextMenuSpec(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val art: Any? = null,
    val actions: List<MenuAction>,
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
    val onDone: (String) -> Unit,
)

data class ChoiceSpec(
    val title: String,
    val message: String? = null,
    val options: List<MenuAction>,
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
) {
    val navigator = Navigator(start)
    val toasts = ToastState()

    var focusZone by mutableStateOf(FocusZone.CONTENT)
    var quickMenuOpen by mutableStateOf(false)
    var contextMenu by mutableStateOf<ContextMenuSpec?>(null)
    var confirm by mutableStateOf<ConfirmSpec?>(null)
    var textInput by mutableStateOf<TextInputSpec?>(null)
    var choice by mutableStateOf<ChoiceSpec?>(null)

    /** "Detect my buttons" is running; it takes every press until it finishes. */
    var buttonDetect by mutableStateOf(false)

    /** Text being typed in the text input overlay (on-screen keyboard or a hardware keyboard). */
    var textDraft by mutableStateOf("")

    /** Where hardware keyboard typing goes (search field, rename dialog), or null for navigation keys. */
    var keyboardTarget by mutableStateOf<KeyboardTarget?>(null)

    /** What the room is lit by. Screens set it from their selection. */
    var hero by mutableStateOf<HeroSource?>(null)

    /** Hints for the current selection; screens set them. */
    var hints by mutableStateOf<List<Hint>>(emptyList())

    /** A game is being launched: the launch veil is drawn over everything until Fuse is paused. */
    var launching by mutableStateOf<LaunchVeil?>(null)

    val overlayOpen: Boolean
        get() = quickMenuOpen || contextMenu != null || confirm != null || textInput != null || choice != null || buttonDetect

    fun openContextMenu(spec: ContextMenuSpec) {
        contextMenu = spec
    }

    fun closeOverlays() {
        quickMenuOpen = false
        contextMenu = null
        confirm = null
        textInput = null
        choice = null
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
class KeyboardTarget(val get: () -> String, val set: (String) -> Unit, val submit: () -> Unit)

/** Shown for the moment between pressing Play and the emulator taking over. */
data class LaunchVeil(val title: String, val art: Any?, val accent: Long)
