package io.github.matiyaaa.fuse.desktop.input

import com.studiohartman.jamepad.Configuration
import com.studiohartman.jamepad.ControllerManager
import com.studiohartman.jamepad.ControllerState
import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.model.PadButton
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * What every connected controller holds at one moment, merged: any pad can drive Fuse. Pure, so the
 * change from one poll to the next ([diff]) is tested without a controller.
 */
internal data class PadSnapshot(
    val buttons: Set<PadButton> = emptySet(),
    val stickX: Float = 0f,
    val stickY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f,
) {
    /** One change for the [GamepadSink]. */
    sealed interface Event {
        data class Press(val button: PadButton) : Event
        data class Release(val button: PadButton) : Event
        data class Stick(val x: Float, val y: Float) : Event
        data class Trigger(val button: PadButton, val value: Float) : Event
    }

    /** Both pads at once: buttons held on either, the stick pushed furthest, the trigger pulled most. */
    fun merge(other: PadSnapshot): PadSnapshot = PadSnapshot(
        buttons = buttons + other.buttons,
        stickX = if (abs(other.stickX) > abs(stickX)) other.stickX else stickX,
        stickY = if (abs(other.stickY) > abs(stickY)) other.stickY else stickY,
        leftTrigger = maxOf(leftTrigger, other.leftTrigger),
        rightTrigger = maxOf(rightTrigger, other.rightTrigger),
    )

    companion object {
        val EMPTY = PadSnapshot()

        /** Below this a stick counts as centred, like the Linux reader. */
        private const val DEAD = 0.02f

        /** Smaller moves than this aren't sent again (they would only repeat the same direction). */
        private const val STEP = 0.01f

        /** Presses and releases first, then the stick and triggers when they moved. */
        fun diff(old: PadSnapshot, new: PadSnapshot): List<Event> = buildList {
            (old.buttons - new.buttons).forEach { add(Event.Release(it)) }
            (new.buttons - old.buttons).forEach { add(Event.Press(it)) }
            if (abs(new.stickX - old.stickX) >= STEP || abs(new.stickY - old.stickY) >= STEP ||
                ((new.stickX == 0f && new.stickY == 0f) && (old.stickX != 0f || old.stickY != 0f))
            ) {
                add(Event.Stick(new.stickX, new.stickY))
            }
            if (abs(new.leftTrigger - old.leftTrigger) >= STEP || (new.leftTrigger == 0f && old.leftTrigger > 0f)) add(Event.Trigger(PadButton.L2, new.leftTrigger))
            if (abs(new.rightTrigger - old.rightTrigger) >= STEP || (new.rightTrigger == 0f && old.rightTrigger > 0f)) add(Event.Trigger(PadButton.R2, new.rightTrigger))
        }

        /** Rounds a stick axis: inside the dead zone it is 0. */
        fun axis(value: Float): Float {
            val v = value.coerceIn(-1f, 1f)
            return if (abs(v) < DEAD) 0f else v
        }

        /** SDL's game controller layout (Xbox positions), which SDL maps every known pad to. */
        fun of(state: ControllerState): PadSnapshot {
            if (!state.isConnected) return EMPTY
            val held = HashSet<PadButton>()
            fun on(pressed: Boolean, button: PadButton) {
                if (pressed) held += button
            }
            on(state.a, PadButton.A)
            on(state.b, PadButton.B)
            on(state.x, PadButton.X)
            on(state.y, PadButton.Y)
            on(state.lb, PadButton.L1)
            on(state.rb, PadButton.R1)
            on(state.leftStickClick, PadButton.L3)
            on(state.rightStickClick, PadButton.R3)
            on(state.start, PadButton.START)
            on(state.back, PadButton.SELECT)
            on(state.guide, PadButton.MODE)
            on(state.dpadUp, PadButton.DPAD_UP)
            on(state.dpadDown, PadButton.DPAD_DOWN)
            on(state.dpadLeft, PadButton.DPAD_LEFT)
            on(state.dpadRight, PadButton.DPAD_RIGHT)
            return PadSnapshot(
                buttons = held,
                stickX = axis(state.leftStickX),
                // SDL's y axis points down, as the sink wants.
                stickY = axis(state.leftStickY),
                leftTrigger = state.leftTrigger.coerceIn(0f, 1f),
                rightTrigger = state.rightTrigger.coerceIn(0f, 1f),
            )
        }
    }
}

/**
 * Game controllers on Windows and macOS through SDL2 (libGDX Jamepad, which bundles SDL for both),
 * the same library and controller database most emulators use, so a pad that works in them works
 * here. One thread starts SDL and polls it about 120 times a second; SDL is only ever touched from
 * that thread. When SDL can't start, controllers don't work in Fuse; the keyboard still does.
 */
class SdlGamepads(private val sink: GamepadSink) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** True once SDL started (for `--self-test`). */
    @Volatile var started: Boolean = false
        private set

    /** Why SDL couldn't start, when it couldn't. */
    @Volatile var failure: String? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::run, "fuse-sdl-gamepads").apply {
            isDaemon = true
            start()
        }
    }

    private fun run() {
        val manager = try {
            ControllerManager(Configuration().apply { maxNumControllers = MAX_PADS }).also { it.initSDLGamepad() }
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
            Log.warn("controllers are unavailable (SDL did not start)", e)
            return
        }
        started = true
        var previous = PadSnapshot.EMPTY
        try {
            while (running.get()) {
                manager.update()
                var now = PadSnapshot.EMPTY
                for (i in 0 until MAX_PADS) now = now.merge(PadSnapshot.of(manager.getState(i)))
                PadSnapshot.diff(previous, now).forEach(::send)
                previous = now
                try {
                    Thread.sleep(POLL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        } catch (e: Throwable) {
            Log.warn("controller polling stopped", e)
        } finally {
            // Nothing stays held once Fuse stops listening.
            PadSnapshot.diff(previous, PadSnapshot.EMPTY).forEach(::send)
            try {
                manager.quitSDLGamepad()
            } catch (e: Throwable) {
                // Closing on exit.
            }
        }
    }

    private fun send(event: PadSnapshot.Event) = when (event) {
        is PadSnapshot.Event.Press -> sink.press(event.button)
        is PadSnapshot.Event.Release -> sink.release(event.button)
        is PadSnapshot.Event.Stick -> sink.stick(event.x, event.y)
        is PadSnapshot.Event.Trigger -> sink.trigger(event.button, event.value)
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
    }

    private companion object {
        const val MAX_PADS = 4
        const val POLL_MS = 8L
    }
}
