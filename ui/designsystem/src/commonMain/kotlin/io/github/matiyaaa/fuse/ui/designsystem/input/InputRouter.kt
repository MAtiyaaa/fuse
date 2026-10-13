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

/** Where an action came from; [REMOTE] is a phone used as a controller through Phone Link. */
enum class InputSource { GAMEPAD, KEYBOARD, TOUCH, POINTER, REMOTE }

/**
 * One action for the layers. [modifier] is set when it came while a hold modifier was held down
 * (a direction pressed with Options held, for resizing): see [InputRouter.register].
 */
data class NavEvent(val action: NavAction, val repeat: Int, val source: InputSource, val modifier: NavAction? = null) {
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

/** What pressing both sticks in together (L3 + R3) did: a quick press, or a hold. */
enum class ComboGesture { TAP, HOLD }

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
    /** Temporarily sends host input to an independent nested interface, such as setup rehearsal. */
    var redirectTo: InputRouter? = null

    var profile: InputProfile = profile
        set(value) {
            field = value
            releaseAll()
        }

    /** When a button, key or touch last reached Fuse (epoch millis), for standby. */
    @kotlin.concurrent.Volatile var lastActivityAt: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
        private set

    /** Notes input that reached Fuse some other way (a touch, a mouse move). */
    fun touched() {
        lastActivityAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
    }

    /** The last pointer that reached Fuse was a mouse (or trackpad), not a finger or pen. */
    @kotlin.concurrent.Volatile var mouse: Boolean = false
        private set

    /** When the mouse last really moved over Fuse (not content moving under a resting pointer). */
    @kotlin.concurrent.Volatile var mouseMovedAt: Long = 0L
        private set

    private var lastMouseX = Float.NaN
    private var lastMouseY = Float.NaN

    /**
     * A pointer event at the root, in the window's own coordinates: [mouse] says what kind, and a
     * mouse whose place in the window changed has moved, so hovering may move the highlight. One
     * moved with a button [held] is a drag (a finger on a computer's touch screen arrives as one):
     * it is still the input in use, but what scrolls under it afterwards isn't being pointed at.
     */
    fun pointer(mouse: Boolean, x: Float, y: Float, pressed: Boolean, held: Boolean = false) {
        redirectTo?.let { it.pointer(mouse, x, y, pressed, held); return }
        this.mouse = mouse
        if (!mouse) return
        if (lastMouseX.isNaN()) {
            lastMouseX = x
            lastMouseY = y
        } else if (kotlin.math.abs(x - lastMouseX) + kotlin.math.abs(y - lastMouseY) >= MOUSE_MOVE_PX) {
            // A real move (not a desk's tremble): the mouse is the input in use now, so hints stop
            // naming controller buttons that may not even be connected.
            mouseMovedAt = if (held && !pressed) 0L else kotlin.time.Clock.System.now().toEpochMilliseconds()
            lastMouseX = x
            lastMouseY = y
            _lastSource.value = InputSource.POINTER
        }
        if (pressed) {
            mouseMovedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            _lastSource.value = InputSource.POINTER
        }
    }

    /** The mouse moved just now: hovering something may make it the highlighted one. */
    fun mouseJustMoved(): Boolean = mouse && kotlin.time.Clock.System.now().toEpochMilliseconds() - mouseMovedAt < HOVER_FRESH_MS

    private val _lastSource = MutableStateFlow(InputSource.KEYBOARD)

    /** Last input kind used; the UI can adapt (for example hint glyphs) without hiding focus. */
    val lastSource: StateFlow<InputSource> = _lastSource.asStateFlow()

    private val _padFamily = MutableStateFlow<io.github.matiyaaa.fuse.model.GlyphStyle?>(null)

    /**
     * The family of the controller last pressed ([io.github.matiyaaa.fuse.model.PadFamily]), or null
     * when it doesn't say which it is. The platform's controller reader sets it.
     */
    val padFamily: StateFlow<io.github.matiyaaa.fuse.model.GlyphStyle?> = _padFamily.asStateFlow()

    /** A hardware keyboard typed into the open field: the keyboard is the input in use. */
    internal fun typedOnKeyboard() {
        touched()
        _lastSource.value = InputSource.KEYBOARD
    }

    private val _controllers = MutableStateFlow<List<String>>(emptyList())

    /** The controllers connected now, by the names they give themselves, in the order they arrived. */
    val controllers: StateFlow<List<String>> = _controllers.asStateFlow()

    private val _lastController = MutableStateFlow<String?>(null)

    /** The controller a button was last pressed on (null: none yet, or the platform doesn't say). */
    val lastController: StateFlow<String?> = _lastController.asStateFlow()

    /** The platform's controllers changed: one was connected, or one went away. */
    fun controllersChanged(names: List<String>) {
        redirectTo?.let { it.controllersChanged(names); return }
        if (_controllers.value != names) _controllers.value = names
    }

    /**
     * Whether a press on [device] may move around Fuse: always when no controller was chosen (or the
     * chosen one is away), otherwise only on the chosen one. Notes [device] as the last one pressed.
     */
    fun fromController(device: String?): Boolean {
        redirectTo?.let { return it.fromController(device) }
        if (device != null && _lastController.value != device) _lastController.value = device
        // Guided setup listens to every controller, so the one to use can be picked by pressing it.
        if (exclusive != null) return true
        return allows(device)
    }

    /** [fromController] without noting the press: for sticks and triggers, which drift. */
    fun allows(device: String?): Boolean {
        redirectTo?.let { return it.allows(device) }
        if (device == null) return true
        val chosen = profile.controller
        return chosen.isEmpty() || chosen !in _controllers.value || chosen == device
    }

    /** The controller now in use calls itself [family] (null: it doesn't say). */
    fun padIdentified(family: io.github.matiyaaa.fuse.model.GlyphStyle?) {
        redirectTo?.let { it.padIdentified(family); return }
        if (_padFamily.value != family) _padFamily.value = family
    }

    internal class Layer(
        val priority: Int,
        val seq: Long,
        var enabled: Boolean,
        var modal: Boolean,
        var longPress: Boolean,
        var handler: (NavEvent) -> NavResult,
        /** Actions beyond the directions that repeat while held here (the keyboard's delete and caret keys). */
        var repeats: Set<NavAction> = emptySet(),
        /** An action whose button modifies directions while held, instead of acting at once. */
        var holdModifier: NavAction? = null,
        /** Holding Options becomes [NavAction.CONTEXT_HOLD] here; a quick press is still Options. */
        var contextHold: Boolean = false,
    )

    private val layers = mutableListOf<Layer>()
    private var seq = 0L

    /** Handle returned by [register]; keep it to update or remove the layer. */
    inner class Registration internal constructor(private val layer: Layer) {
        fun update(
            enabled: Boolean,
            modal: Boolean,
            longPress: Boolean,
            handler: (NavEvent) -> NavResult,
            repeats: Set<NavAction> = emptySet(),
            holdModifier: NavAction? = null,
            contextHold: Boolean = false,
        ) {
            layer.contextHold = contextHold
            layer.enabled = enabled
            layer.modal = modal
            layer.longPress = longPress
            layer.handler = handler
            layer.repeats = repeats
            layer.holdModifier = holdModifier
        }

        fun remove() {
            layers.remove(layer)
        }
    }

    fun register(
        priority: Int,
        modal: Boolean = false,
        longPress: Boolean = false,
        holdModifier: NavAction? = null,
        handler: (NavEvent) -> NavResult,
    ): Registration {
        val layer = Layer(priority, seq++, enabled = true, modal = modal, longPress = longPress, handler = handler, holdModifier = holdModifier)
        layers += layer
        return Registration(layer)
    }

    private fun orderedLayers(): List<Layer> =
        layers.filter { it.enabled }.sortedWith(compareByDescending<Layer> { it.priority }.thenByDescending { it.seq })

    /**
     * The interface is laid out right to left: left and right (and the shoulders' previous and next)
     * move the way the screen reads, so right still goes to what is on the right.
     */
    @Volatile var mirrored: Boolean = false

    /** Sends an action through the layer stack. Returns what the handling layer did. */
    fun dispatch(requested: NavAction, source: InputSource, repeat: Int = 0): NavResult {
        redirectTo?.let { return it.dispatch(requested, source, repeat) }
        val action = if (mirrored) requested.mirroredAcross() else requested
        if (repeat > 0 && action in consumedUntilRelease) return NavResult.CONSUMED
        val trace = io.github.matiyaaa.fuse.ui.designsystem.effects.UiRenderTrace
        val started = if (trace.enabled) trace.now() else 0L
        touched()
        _lastSource.value = source
        val modifier = _heldModifier.value?.takeIf { action.isDirection }
        if (modifier != null) modifierUsed = true
        val event = NavEvent(action, repeat, source, modifier)
        var result = route(event)
        // Hold-to-reorder falls back to the options menu where a screen has no reorder mode.
        if (result == NavResult.IGNORED && action == NavAction.REORDER) {
            result = route(NavEvent(NavAction.CONTEXT, 0, source))
        }
        if (result != NavResult.IGNORED) trace.input(started)
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

    // ---------------------------------------------------------------- hold modifiers

    private val _heldModifier = MutableStateFlow<NavAction?>(null)

    /**
     * The hold modifier being held down right now, or null. A layer asks for one (`holdModifier`):
     * its button then acts when let go, as a tap, unless a direction was pressed meanwhile, and the
     * directions pressed while it is down carry it in [NavEvent.modifier]. Screens read this to show
     * that holding has changed what the directions do.
     */
    val heldModifier: StateFlow<NavAction?> = _heldModifier.asStateFlow()

    private var modifierButton: PadButton? = null
    private var modifierUsed = false

    private val NavAction.isDirection: Boolean
        get() = this == NavAction.UP || this == NavAction.DOWN || this == NavAction.LEFT || this == NavAction.RIGHT

    // ---------------------------------------------------------------- raw input

    private val held = mutableMapOf<PadButton, Job?>()
    private val consumedUntilRelease = mutableSetOf<NavAction>()

    /** A wake press must never become navigation repeats after its overlay disappears. */
    fun consumeUntilRelease(action: NavAction) { consumedUntilRelease += action }
    private val longPressConsumed = mutableSetOf<PadButton>()

    /** Sees every physical press and release (the controller test screen uses it). */
    var rawListener: ((PadButton, Boolean) -> Unit)? = null

    /**
     * The text field a hardware keyboard types into while one is open (search, rename). Letters then
     * become text instead of shortcuts; arrows and Escape keep navigating.
     */
    private var ownTextInput: TextInput? = null
    var textInput: TextInput?
        get() { val nested = redirectTo; return if (nested != null) nested.textInput else ownTextInput }
        set(value) { ownTextInput = value }

    /**
     * While set, the next physical presses go only here and never become actions (the button
     * mapping screen listens for "press the button for Confirm"). Releases still reach [rawListener].
     */
    var capture: ((PadButton) -> Unit)? = null

    /**
     * While set, every press and release goes only here and nothing becomes an action, not even
     * Back. The controller test uses it so any button can be tried without leaving the screen.
     */
    var exclusive: ((PadButton, Boolean) -> Unit)? = null

    /**
     * Called when both sticks are pressed in together (L3 + R3): [ComboGesture.TAP] when let go
     * quickly, [ComboGesture.HOLD] once held for [COMBO_HOLD_MS]. Null turns the combo off, and the
     * sticks act on their own. Never called while [capture] or [exclusive] is set.
     */
    var onCaptureCombo: ((ComboGesture) -> Unit)? = null

    private val sticksDown = mutableSetOf<PadButton>()
    private var comboHold: Job? = null

    /**
     * Called when Start and Select are held together for [RESET_HOLD_MS] on a controller: the way
     * back from any mapping, however broken. It is read from the physical buttons, before the remap,
     * a capture or a screen that has the controller to itself, so it works whatever they do.
     */
    var onResetCombo: (() -> Unit)? = null
    private val resetDown = mutableSetOf<PadButton>()
    private var resetHold: Job? = null

    private fun resetPress(button: PadButton, source: InputSource) {
        if (source != InputSource.GAMEPAD || button !in RESET_BUTTONS) return
        resetDown += button
        if (resetDown.containsAll(RESET_BUTTONS) && resetHold == null && onResetCombo != null) {
            resetHold = scope.launch {
                delay(RESET_HOLD_MS)
                exclusive = null
                capture = null
                releaseAll()
                onResetCombo?.invoke()
            }
        }
    }

    private fun resetRelease(button: PadButton) {
        if (!resetDown.remove(button)) return
        resetHold?.cancel()
        resetHold = null
    }

    /** Sticks pressed on their own, waiting for release to act (unless the combo forms first). */
    private val stickPending = mutableSetOf<PadButton>()

    /** Both sticks went down together; stays set until both are up, so neither acts on its own. */
    private var comboActive = false
    private var comboDone = false

    /** A physical button went down. Platform key repeats must not be forwarded. */
    fun press(button: PadButton, source: InputSource) {
        redirectTo?.let { it.press(button, source); return }
        io.github.matiyaaa.fuse.ui.fuseline.FramePacing.input()
        touched()
        resetPress(button, source)
        if (!fromStick) rawListener?.invoke(button, true)
        exclusive?.let {
            _lastSource.value = source
            it(button, true)
            return
        }
        // Learning a button for a mapping listens to the device's own controllers only.
        capture?.takeIf { source != InputSource.REMOTE }?.let {
            _lastSource.value = source
            it(button)
            return
        }
        if (button.isStick && comboPress(button, source)) return
        if (held.containsKey(button)) return
        val action = actionFor(button) ?: return
        _lastSource.value = source
        if (button.isStick && onCaptureCombo != null) {
            // While both sticks together take a screenshot, a stick on its own acts when it is let go,
            // and only if the other stick never joined it: pressing one a moment early must not act.
            stickPending += button
            return
        }
        val top = orderedLayers().firstOrNull()
        if (top?.holdModifier != null && action == top.holdModifier && modifierButton == null) {
            // Held, it changes what the directions do; let go untouched, it is an ordinary press.
            held[button] = null
            modifierButton = button
            modifierUsed = false
            _heldModifier.value = action
            return
        }
        when {
            action.repeats || action in topRepeats() -> {
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
            action == NavAction.CONTEXT && orderedLayers().firstOrNull()?.contextHold == true -> {
                // The same for Options: let go early it is Options, held it is the second action.
                held[button] = scope.launch {
                    delay(profile.longPressMs.toLong())
                    longPressConsumed += button
                    dispatch(NavAction.CONTEXT_HOLD, source)
                }
            }
            else -> {
                held[button] = null
                dispatch(action, source)
            }
        }
    }

    fun release(button: PadButton, source: InputSource) {
        redirectTo?.let { it.release(button, source); return }
        resetRelease(button)
        actionFor(button)?.let { consumedUntilRelease.remove(it) }
        if (!fromStick) rawListener?.invoke(button, false)
        exclusive?.let {
            it(button, false)
            // A button held since before the test started is let go without acting.
            held.remove(button)?.cancel()
            longPressConsumed.remove(button)
            return
        }
        if (button.isStick && comboRelease(button)) {
            stickPending -= button
            return
        }
        if (stickPending.remove(button)) {
            actionFor(button)?.let { dispatch(it, source) }
            return
        }
        if (!held.containsKey(button)) return
        if (button == modifierButton) {
            held.remove(button)
            val action = _heldModifier.value
            val used = modifierUsed
            modifierButton = null
            modifierUsed = false
            _heldModifier.value = null
            if (!used && action != null) dispatch(action, source)
            return
        }
        val job = held.remove(button)
        job?.cancel()
        // A pending long press that was let go early is an ordinary select.
        val pressed = actionFor(button)
        if (job != null && (pressed == NavAction.SELECT || pressed == NavAction.CONTEXT) && !longPressConsumed.remove(button)) {
            dispatch(pressed, source)
        }
    }

    /** Releases every held button (focus loss, profile change, window hidden). */
    fun releaseAll() {
        consumedUntilRelease.clear()
        held.values.forEach { it?.cancel() }
        held.clear()
        longPressConsumed.clear()
        stickDirection = null
        rightDirection = null
        triggers.clear()
        sticksDown.clear()
        stickPending.clear()
        comboHold?.cancel()
        comboHold = null
        comboActive = false
        comboDone = false
        modifierButton = null
        modifierUsed = false
        _heldModifier.value = null
    }

    private val PadButton.isStick: Boolean get() = this == PadButton.L3 || this == PadButton.R3

    /** Returns true when the press belongs to the combo and must not act on its own. */
    private fun comboPress(button: PadButton, source: InputSource): Boolean {
        sticksDown += button
        if (comboActive) return true
        val listener = onCaptureCombo ?: return false
        if (sticksDown.size < 2) return false
        _lastSource.value = source
        comboActive = true
        comboDone = false
        // Neither stick acts on its own now: the press was the start of the combo.
        stickPending.clear()
        comboHold = scope.launch {
            delay(COMBO_HOLD_MS)
            comboDone = true
            listener(ComboGesture.HOLD)
        }
        return true
    }

    /** Returns true when the release belongs to the combo. The first stick let go decides a tap. */
    private fun comboRelease(button: PadButton): Boolean {
        sticksDown -= button
        if (!comboActive) return false
        // A stick that acted on its own before the combo formed is let go quietly.
        held.remove(button)?.cancel()
        if (!comboDone) {
            comboDone = true
            comboHold?.cancel()
            onCaptureCombo?.invoke(ComboGesture.TAP)
        }
        if (sticksDown.isEmpty()) {
            comboActive = false
            comboHold = null
        }
        return true
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

    private fun topRepeats(): Set<NavAction> = orderedLayers().firstOrNull()?.repeats.orEmpty()

    private val NavAction.repeats: Boolean
        get() = this == NavAction.UP || this == NavAction.DOWN || this == NavAction.LEFT ||
            this == NavAction.RIGHT || this == NavAction.PAGE_UP || this == NavAction.PAGE_DOWN

    /** Maps a physical button to an action, applying the confirm/back swap and user remaps. */
    fun actionFor(button: PadButton): NavAction? = profile.actionFor(button)

    // ---------------------------------------------------------------- analog sticks and triggers

    private var stickDirection: PadButton? = null

    private val _sticks = MutableStateFlow(StickPositions())

    /** Where both sticks are now, for pictures of the pad: a stick is shown as a stick, never as the D-pad. */
    val sticks: StateFlow<StickPositions> = _sticks.asStateFlow()

    /** Presses the sticks make are navigation, not buttons: [rawListener] doesn't hear them. */
    private var fromStick = false

    private inline fun asStick(block: () -> Unit) {
        fromStick = true
        try {
            block()
        } finally {
            fromStick = false
        }
    }

    /**
     * Left stick position, each axis -1..1 (y down). Converted to D-pad presses with a deadzone and
     * the user's navigation threshold; the dominant axis wins so diagonals never double-move.
     */
    fun stick(x: Float, y: Float, source: InputSource = InputSource.GAMEPAD) {
        redirectTo?.let { it.stick(x, y, source); return }
        io.github.matiyaaa.fuse.ui.fuseline.FramePacing.input()
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
        _sticks.value.let { if (it.leftX != x || it.leftY != y) _sticks.value = it.copy(leftX = x, leftY = y) }
        if (dir == stickDirection) return
        asStick {
            stickDirection?.let { release(it, source) }
            stickDirection = dir
            dir?.let { press(it, source) }
        }
    }

    private var rightDirection: PadButton? = null

    /**
     * Right stick position, each axis -1..1 (y down): pushed well over to a side it is a press of
     * [PadButton.RSTICK_LEFT] and the rest, let go when it comes back toward the centre, so a flick
     * is one press. Readers that see the stick as buttons already press those directly.
     */
    fun rightStick(x: Float, y: Float, source: InputSource = InputSource.GAMEPAD) {
        redirectTo?.let { it.rightStick(x, y, source); return }
        val threshold = max(RIGHT_STICK_PRESS, profile.stickDeadzone)
        val dir: PadButton? = when {
            abs(x) >= abs(y) && abs(x) >= threshold -> if (x < 0) PadButton.RSTICK_LEFT else PadButton.RSTICK_RIGHT
            abs(y) > abs(x) && abs(y) >= threshold -> if (y < 0) PadButton.RSTICK_UP else PadButton.RSTICK_DOWN
            rightDirection != null && (abs(x) >= threshold * 0.6f || abs(y) >= threshold * 0.6f) -> rightDirection
            else -> null
        }
        _sticks.value.let { if (it.rightX != x || it.rightY != y) _sticks.value = it.copy(rightX = x, rightY = y) }
        if (dir == rightDirection) return
        rightDirection?.let { release(it, source) }
        rightDirection = dir
        dir?.let { press(it, source) }
    }

    private val triggers = mutableMapOf<PadButton, Boolean>()

    /** Analog trigger value 0..1; crossing half travel counts as a press. */
    fun trigger(button: PadButton, value: Float, source: InputSource = InputSource.GAMEPAD) {
        redirectTo?.let { it.trigger(button, value, source); return }
        val down = value > 0.5f
        if (triggers[button] == down) return
        triggers[button] = down
        if (down) press(button, source) else release(button, source)
    }

    companion object {
        /** How long both sticks stay pressed in before the combo counts as a hold. */
        const val COMBO_HOLD_MS = 600L

        /** Start and Select held this long together reset the controls (see [onResetCombo]). */
        const val RESET_HOLD_MS = 3_000L
        private val RESET_BUTTONS = setOf(PadButton.START, PadButton.SELECT)

        /** How far the right stick goes over before it counts as pushed (a deliberate flick, not a drift). */
        const val RIGHT_STICK_PRESS = 0.6f
    }
}

/** What [button] does under this profile: the user's remap, else [defaultActionFor]. */
fun InputProfile.actionFor(button: PadButton): NavAction? = remap[button] ?: defaultActionFor(button)

/**
 * This profile with [button] doing [action]. What [button] did before moves to the first button of
 * [pad] that did [action], when no other button of [pad] would still do it or that button was mapped
 * to it before, so mapping trades two buttons' jobs instead of dropping one. Null when that would leave Confirm or Back with no button
 * on the pad at all. Entries that match the standard layout are dropped, so they don't count as remaps.
 */
fun InputProfile.withMapping(button: PadButton, action: NavAction, pad: List<PadButton>): InputProfile? {
    val before = actionFor(button)
    val remap = LinkedHashMap(remap)
    remap[button] = action
    if (before != null && before != action) {
        val stillDone = pad.any { it != button && copy(remap = remap).actionFor(it) == before }
        val other = pad.firstOrNull { it != button && actionFor(it) == action }
        // A button that got [action] from an earlier mapping trades back, which undoes that mapping.
        if (other != null && (!stillDone || other in this.remap)) remap[other] = before
    }
    val next = copy(remap = remap.filter { (b, a) -> defaultActionFor(b) != a })
    val lost = ESSENTIAL.any { need -> pad.any { actionFor(it) == need } && pad.none { next.actionFor(it) == need } }
    return if (lost) null else next
}

/** Actions a controller can never be left without: choosing and going back. */
private val ESSENTIAL = listOf(NavAction.SELECT, NavAction.BACK)

/**
 * This profile, or without its remaps when they leave Confirm or Back on no button of [pad] (the
 * buttons that really reach Fuse here: on Linux the Guide button never does). A mapping saved that
 * way by an older Fuse, or in a way nobody foresaw, can never leave the controller unable to choose.
 */
fun InputProfile.usable(pad: Collection<PadButton>): InputProfile =
    if (remap.isNotEmpty() && ESSENTIAL.any { need -> pad.none { actionFor(it) == need } }) copy(remap = emptyMap()) else this

/** The buttons of a pad that can carry an action: the face buttons, shoulders, triggers, stick presses, Start, Select and, where it reaches Fuse, Guide. */
fun padButtons(guide: Boolean): List<PadButton> = listOf(
    PadButton.A, PadButton.B, PadButton.X, PadButton.Y, PadButton.L1, PadButton.R1, PadButton.L2, PadButton.R2,
    PadButton.L3, PadButton.R3, PadButton.START, PadButton.SELECT,
) + if (guide) listOf(PadButton.MODE) else emptyList()

/** What [button] does with no remaps: the standard layout, with the confirm/back and shoulder swaps. */
fun InputProfile.defaultActionFor(button: PadButton): NavAction? {
    val n = swapConfirmBack
    return when (button) {
        PadButton.A -> if (n) NavAction.BACK else NavAction.SELECT
        PadButton.B -> if (n) NavAction.SELECT else NavAction.BACK
        PadButton.X -> if (n) NavAction.SEARCH else NavAction.CONTEXT
        PadButton.Y -> if (n) NavAction.CONTEXT else NavAction.SEARCH
        PadButton.L1 -> if (swapShoulders) NavAction.PAGE_UP else NavAction.PREVIOUS_SECTION
        PadButton.R1 -> if (swapShoulders) NavAction.PAGE_DOWN else NavAction.NEXT_SECTION
        PadButton.L2 -> if (swapShoulders) NavAction.PREVIOUS_SECTION else NavAction.PAGE_UP
        PadButton.R2 -> if (swapShoulders) NavAction.NEXT_SECTION else NavAction.PAGE_DOWN
        PadButton.START -> NavAction.QUICK_MENU
        PadButton.SELECT -> NavAction.CONTEXT
        PadButton.MODE -> NavAction.HOME
        // The right stick pressed in opens the quick menu too (Start and M stay); the left stick is free.
        PadButton.R3 -> NavAction.QUICK_MENU
        PadButton.L3 -> null
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
        PadButton.RSTICK_LEFT, PadButton.KEY_BRACKET_LEFT -> NavAction.PAGE_PREVIOUS
        PadButton.RSTICK_RIGHT, PadButton.KEY_BRACKET_RIGHT -> NavAction.PAGE_NEXT
        PadButton.RSTICK_UP, PadButton.RSTICK_DOWN -> null
    }
}

/** Receives hardware keyboard typing for the open text field. */
interface TextInput {
    fun type(text: String)
    fun backspace()
    fun submit()

    /** Deletes the word before the caret (Backspace held a while, as a phone does). */
    fun deleteWordBack() = backspace()

    /** Inserts the clipboard's text (Ctrl+V). */
    fun paste() {}

    /** Selects everything typed (Ctrl+A), so what is typed next replaces it. */
    fun selectAll() {}

    /** The Delete key: removes the character after the caret. */
    fun deleteForward() {}

    /** Home and End: the caret to the start or the end. */
    fun home() {}
    fun end() {}
}

/** How recently the mouse must have moved for a hover to count as the person pointing. */
private const val HOVER_FRESH_MS = 120L

/** How far the mouse goes, in pixels across both axes, before it counts as moved. */
private const val MOUSE_MOVE_PX = 3f

/** Both sticks' positions, each axis -1..1 with y down. */
data class StickPositions(val leftX: Float = 0f, val leftY: Float = 0f, val rightX: Float = 0f, val rightY: Float = 0f)

/** [this] for a layout read right to left: what was left is right, previous is next. */
internal fun NavAction.mirroredAcross(): NavAction = when (this) {
    NavAction.LEFT -> NavAction.RIGHT
    NavAction.RIGHT -> NavAction.LEFT
    NavAction.PREVIOUS_SECTION -> NavAction.NEXT_SECTION
    NavAction.NEXT_SECTION -> NavAction.PREVIOUS_SECTION
    NavAction.PAGE_PREVIOUS -> NavAction.PAGE_NEXT
    NavAction.PAGE_NEXT -> NavAction.PAGE_PREVIOUS
    else -> this
}
