package io.github.matiyaaa.fuse.ui.designsystem.input

import io.github.matiyaaa.fuse.model.InputProfile
import io.github.matiyaaa.fuse.model.NavAction
import io.github.matiyaaa.fuse.model.PadButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** What a layer did with an action. Drives sounds and haptics, and whether lower layers see it. */
enum class NavResult {
    /** Selection moved to another item. */
    MOVED,

    /** Something opened, launched or toggled. */
    ACTIVATED,

    /** Handled without visible movement (for example Back closing a panel). */
    CONSUMED,

    /** Understood, but there is nowhere to go (end of a row). Plays a soft bump, never a move sound. */
    BLOCKED,

    /** Not handled here; lower layers get a chance unless this layer is modal. */
    IGNORED,
}

enum class InputSource { GAMEPAD, KEYBOARD, TOUCH, POINTER }

data class NavEvent(val action: NavAction, val repeat: Int, val source: InputSource) {
    val isRepeat: Boolean get() = repeat > 0
}

/** Layer priorities: higher wins. Within a priority, the most recently registered layer wins. */
object LayerPriority {
    const val SHELL = 0
    const val SCREEN = 10
    const val OVERLAY = 20
    const val DIALOG = 30
    const val SYSTEM = 40
}

/** Receives feedback events so the app can play sounds and haptics. */
fun interface InputFeedback {
    fun onResult(event: NavEvent, result: NavResult)
}

/**
 * The single entry point for controller, keyboard and remote input. Platforms feed raw button
 * presses; the router maps them to [NavAction]s (honouring the Nintendo layout and remaps), runs its
 * own key repeat (platform auto-repeat is ignored) and hands each action to the top input layer.
 *
 * Direction repeat accelerates while held so a user can fly through hundreds of games; it never
 * queues events, so letting go stops movement instantly.
 */
class InputRouter(
    private val scope: CoroutineScope,
    profile: InputProfile = InputProfile(),
    /** Called after every action with its result (sounds, haptics). Set by the app shell. */
    var feedback: InputFeedback = InputFeedback { _, _ -> },
) {
    var profile: InputProfile = profile
        set(value) {
            field = value
            releaseAll()
        }

    private val _lastSource = MutableStateFlow(InputSource.KEYBOARD)

    /** Last input kind used; the UI can adapt (for example hint glyphs) without hiding focus. */
    val lastSource: StateFlow<InputSource> = _lastSource.asStateFlow()

    internal class Layer(
        val priority: Int,
        val seq: Long,
        var enabled: Boolean,
        var modal: Boolean,
        var longPress: Boolean,
        var handler: (NavEvent) -> NavResult,
    )

    private val layers = mutableListOf<Layer>()
    private var seq = 0L

    /** Handle returned by [register]; keep it to update or remove the layer. */
    inner class Registration internal constructor(private val layer: Layer) {
        fun update(enabled: Boolean, modal: Boolean, longPress: Boolean, handler: (NavEvent) -> NavResult) {
            layer.enabled = enabled
            layer.modal = modal
            layer.longPress = longPress
            layer.handler = handler
        }

        fun remove() {
            layers.remove(layer)
        }
    }

    fun register(
        priority: Int,
        modal: Boolean = false,
        longPress: Boolean = false,
        handler: (NavEvent) -> NavResult,
    ): Registration {
        val layer = Layer(priority, seq++, enabled = true, modal = modal, longPress = longPress, handler = handler)
        layers += layer
        return Registration(layer)
    }

    private fun orderedLayers(): List<Layer> =
        layers.filter { it.enabled }.sortedWith(compareByDescending<Layer> { it.priority }.thenByDescending { it.seq })

    /** Sends an action through the layer stack. Returns what the handling layer did. */
    fun dispatch(action: NavAction, source: InputSource, repeat: Int = 0): NavResult {
        _lastSource.value = source
        val event = NavEvent(action, repeat, source)
        var result = route(event)
        // Hold-to-reorder falls back to the options menu where a screen has no reorder mode.
        if (result == NavResult.IGNORED && action == NavAction.REORDER) {
            result = route(NavEvent(NavAction.CONTEXT, 0, source))
        }
        feedback.onResult(event, result)
        return result
    }

    private fun route(event: NavEvent): NavResult {
        for (layer in orderedLayers()) {
            val r = layer.handler(event)
            if (r != NavResult.IGNORED) return r
            if (layer.modal) return NavResult.IGNORED
        }
        return NavResult.IGNORED
    }

    // ---------------------------------------------------------------- raw input

    private val held = mutableMapOf<PadButton, Job?>()
    private val longPressConsumed = mutableSetOf<PadButton>()

    /** Sees every physical press and release (the controller test screen uses it). */
    var rawListener: ((PadButton, Boolean) -> Unit)? = null

    /**
     * While set, the next physical presses go only here and never become actions (the button
     * mapping screen listens for "press the button for Confirm"). Releases still reach [rawListener].
     */
    var capture: ((PadButton) -> Unit)? = null

    /** A physical button went down. Platform key repeats must not be forwarded. */
    fun press(button: PadButton, source: InputSource) {
        rawListener?.invoke(button, true)
        capture?.let {
            _lastSource.value = source
            it(button)
            return
        }
        if (held.containsKey(button)) return
        val action = actionFor(button) ?: return
        _lastSource.value = source
        when {
            action.repeats -> {
                dispatch(action, source)
                held[button] = scope.launch { repeatLoop(action, source) }
            }
            action == NavAction.SELECT && topWantsLongPress() -> {
                // Decide on release, or turn into REORDER once held long enough.
                held[button] = scope.launch {
                    delay(profile.longPressMs.toLong())
                    longPressConsumed += button
                    dispatch(NavAction.REORDER, source)
                }
            }
            else -> {
                held[button] = null
                dispatch(action, source)
            }
        }
    }

    fun release(button: PadButton, source: InputSource) {
        rawListener?.invoke(button, false)
        if (!held.containsKey(button)) return
        val job = held.remove(button)
        job?.cancel()
        // A pending long press that was let go early is an ordinary select.
        if (job != null && actionFor(button) == NavAction.SELECT && !longPressConsumed.remove(button)) {
            dispatch(NavAction.SELECT, source)
        }
    }

    /** Releases every held button (focus loss, profile change, window hidden). */
    fun releaseAll() {
        held.values.forEach { it?.cancel() }
        held.clear()
        longPressConsumed.clear()
        stickDirection = null
    }

    private suspend fun repeatLoop(action: NavAction, source: InputSource) {
        delay(profile.repeatDelayMs.toLong())
        var interval = profile.repeatIntervalMs.toFloat()
        val floor = profile.repeatIntervalMs * 0.5f
        var n = 1
        while (true) {
            dispatch(action, source, repeat = n++)
            delay(interval.toLong())
            if (profile.repeatAccelerate && n > 4) interval = max(floor, interval * 0.9f)
        }
    }

    private fun topWantsLongPress(): Boolean = orderedLayers().firstOrNull()?.longPress == true

    private val NavAction.repeats: Boolean
        get() = this == NavAction.UP || this == NavAction.DOWN || this == NavAction.LEFT ||
            this == NavAction.RIGHT || this == NavAction.PAGE_UP || this == NavAction.PAGE_DOWN

    /** Maps a physical button to an action, applying Nintendo layout and user remaps. */
    fun actionFor(button: PadButton): NavAction? {
        profile.remap[button]?.let { return it }
        val n = profile.nintendoLayout
        return when (button) {
            PadButton.A -> if (n) NavAction.BACK else NavAction.SELECT
            PadButton.B -> if (n) NavAction.SELECT else NavAction.BACK
            PadButton.X -> if (n) NavAction.SEARCH else NavAction.CONTEXT
            PadButton.Y -> if (n) NavAction.CONTEXT else NavAction.SEARCH
            PadButton.L1 -> NavAction.PREVIOUS_SECTION
            PadButton.R1 -> NavAction.NEXT_SECTION
            PadButton.L2 -> NavAction.PAGE_UP
            PadButton.R2 -> NavAction.PAGE_DOWN
            PadButton.START -> NavAction.QUICK_MENU
            PadButton.SELECT -> NavAction.CONTEXT
            PadButton.MODE -> NavAction.HOME
            PadButton.L3, PadButton.R3 -> null
            PadButton.DPAD_UP, PadButton.KEY_UP -> NavAction.UP
            PadButton.DPAD_DOWN, PadButton.KEY_DOWN -> NavAction.DOWN
            PadButton.DPAD_LEFT, PadButton.KEY_LEFT -> NavAction.LEFT
            PadButton.DPAD_RIGHT, PadButton.KEY_RIGHT -> NavAction.RIGHT
            PadButton.KEY_ENTER, PadButton.KEY_SPACE -> NavAction.SELECT
            PadButton.KEY_ESCAPE, PadButton.KEY_BACKSPACE -> NavAction.BACK
            PadButton.KEY_TAB -> NavAction.CONTEXT
            PadButton.KEY_SLASH, PadButton.KEY_F -> NavAction.SEARCH
            PadButton.KEY_M -> NavAction.QUICK_MENU
            PadButton.KEY_Q -> NavAction.PREVIOUS_SECTION
            PadButton.KEY_E -> NavAction.NEXT_SECTION
            PadButton.KEY_PAGE_UP -> NavAction.PAGE_UP
            PadButton.KEY_PAGE_DOWN -> NavAction.PAGE_DOWN
            PadButton.KEY_HOME -> NavAction.HOME
        }
    }

    // ---------------------------------------------------------------- analog sticks and triggers

    private var stickDirection: PadButton? = null

    /**
     * Left stick position, each axis -1..1 (y down). Converted to D-pad presses with a deadzone and
     * the user's navigation threshold; the dominant axis wins so diagonals never double-move.
     */
    fun stick(x: Float, y: Float, source: InputSource = InputSource.GAMEPAD) {
        val threshold = max(profile.navigationThreshold, profile.stickDeadzone)
        // Hysteresis: once pressed, release only when clearly back toward the centre.
        val releaseAt = threshold * 0.7f
        val dir: PadButton? = when {
            abs(x) < profile.stickDeadzone && abs(y) < profile.stickDeadzone -> null
            abs(x) >= abs(y) && abs(x) >= threshold -> if (x < 0) PadButton.DPAD_LEFT else PadButton.DPAD_RIGHT
            abs(y) > abs(x) && abs(y) >= threshold -> if (y < 0) PadButton.DPAD_UP else PadButton.DPAD_DOWN
            stickDirection != null && (abs(x) >= releaseAt || abs(y) >= releaseAt) -> stickDirection
            else -> null
        }
        if (dir == stickDirection) return
        stickDirection?.let { release(it, source) }
        stickDirection = dir
        dir?.let { press(it, source) }
    }

    private val triggers = mutableMapOf<PadButton, Boolean>()

    /** Analog trigger value 0..1; crossing half travel counts as a press. */
    fun trigger(button: PadButton, value: Float, source: InputSource = InputSource.GAMEPAD) {
        val down = value > 0.5f
        if (triggers[button] == down) return
        triggers[button] = down
        if (down) press(button, source) else release(button, source)
    }
}
