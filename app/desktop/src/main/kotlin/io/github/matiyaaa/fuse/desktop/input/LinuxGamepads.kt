package io.github.matiyaaa.fuse.desktop.input

import io.github.matiyaaa.fuse.desktop.Log
import io.github.matiyaaa.fuse.model.PadButton
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Receives controller input. Called on reader threads; implementations hand it to the UI thread. */
interface GamepadSink {
    fun press(button: PadButton)
    fun release(button: PadButton)

    /** Left stick, each axis -1..1, y down. */
    fun stick(x: Float, y: Float)

    /** Analog trigger ([PadButton.L2] or [PadButton.R2]), 0..1. */
    fun trigger(button: PadButton, value: Float)

    /** The controller being pressed calls itself [name] (its device name, or SDL's type for it). */
    fun identified(name: String?) {}
}

/**
 * Game controllers through the kernel's joystick interface (`/dev/input/js*`, joydev), without
 * native libraries. Each device gets a reader thread; a scanner thread looks for new devices every
 * two seconds. A device that disappears (unplugged, powered off) releases everything it held.
 *
 * Reading needs permission on the device node, which desktop sessions normally grant to the logged-in
 * user. When no device can be read, controllers simply don't work in Fuse; the keyboard still does.
 */
class LinuxGamepads(private val sink: GamepadSink) : AutoCloseable {
    private val open = ConcurrentHashMap<String, Device>()
    private val warnedUnreadable = ConcurrentHashMap.newKeySet<String>()
    private val running = AtomicBoolean(false)
    private var scanner: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scanner = Thread({
            while (running.get()) {
                try {
                    scan()
                } catch (e: Exception) {
                    Log.warn("controller scan failed", e)
                }
                try {
                    Thread.sleep(SCAN_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "fuse-gamepad-scan").apply {
            isDaemon = true
            start()
        }
    }

    /** Names of the controllers currently being read, for diagnostics. */
    fun connected(): List<String> = open.values.map { it.label }

    private fun scan() {
        val nodes = File("/dev/input").listFiles { f -> f.name.matches(JS_NAME) }?.toList() ?: emptyList()
        for (node in nodes) {
            if (open.containsKey(node.name)) continue
            val stream = try {
                FileInputStream(node)
            } catch (e: IOException) {
                if (warnedUnreadable.add(node.name)) Log.info("can't read ${node.path} (no permission); that controller is ignored")
                continue
            } catch (e: SecurityException) {
                continue
            }
            warnedUnreadable.remove(node.name)
            val name = readName(node.name)
            val device = Device(node.name, name, stream, JoystickMapping.forDevice(node.name, name))
            open[node.name] = device
            Log.info("controller connected: ${device.label}")
            device.start()
        }
    }

    private fun readName(js: String): String? = try {
        File("/sys/class/input/$js/device/name").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    override fun close() {
        running.set(false)
        scanner?.interrupt()
        open.values.forEach { it.stop() }
        open.clear()
    }

    private inner class Device(
        val node: String,
        name: String?,
        private val input: FileInputStream,
        private val mapping: JoystickMapping,
    ) {
        val label: String = name ?: node
        private val held = HashSet<PadButton>()
        private var hatX: PadButton? = null
        private var hatY: PadButton? = null
        private var stickX = 0f
        private var stickY = 0f
        private var rightX = 0f
        private var rightY = 0f
        private var rightHeld: PadButton? = null
        private val triggerRest = HashMap<Int, Int>()
        private val triggerDown = HashMap<PadButton, Float>()
        private var thread: Thread? = null

        fun start() {
            thread = Thread(::loop, "fuse-gamepad-$node").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            try {
                input.close()
            } catch (e: IOException) {
                // Already closed.
            }
        }

        private fun loop() {
            val event = ByteArray(8)
            try {
                while (running.get()) {
                    var read = 0
                    while (read < 8) {
                        val n = input.read(event, read, 8 - read)
                        if (n < 0) throw IOException("end of stream")
                        read += n
                    }
                    // struct js_event { __u32 time; __s16 value; __u8 type; __u8 number; } (little endian)
                    val value = ((event[4].toInt() and 0xFF) or (event[5].toInt() shl 8)).toShort().toInt()
                    val type = event[6].toInt() and 0xFF
                    val number = event[7].toInt() and 0xFF
                    val initial = type and JS_EVENT_INIT != 0
                    when (type and JS_EVENT_INIT.inv()) {
                        JS_EVENT_BUTTON -> if (!initial) onButton(number, value != 0)
                        JS_EVENT_AXIS -> onAxis(number, value, initial)
                    }
                }
            } catch (e: IOException) {
                // Unplugged or closed.
            } finally {
                releaseAll()
                stop()
                open.remove(node, this)
                if (running.get()) Log.info("controller disconnected: $label")
            }
        }

        private fun onButton(number: Int, down: Boolean) {
            val button = mapping.button(number) ?: return
            if (down) {
                if (held.add(button)) {
                    sink.identified(label)
                    sink.press(button)
                }
            } else if (held.remove(button)) {
                sink.release(button)
            }
        }

        private fun onAxis(number: Int, value: Int, initial: Boolean) {
            when (mapping.axis(number)) {
                AxisRole.LEFT_X -> if (!initial) {
                    stickX = normalize(value)
                    sink.stick(stickX, stickY)
                }
                AxisRole.LEFT_Y -> if (!initial) {
                    stickY = normalize(value)
                    sink.stick(stickX, stickY)
                }
                AxisRole.RIGHT_X -> if (!initial) {
                    rightX = normalize(value)
                    right()
                }
                AxisRole.RIGHT_Y -> if (!initial) {
                    rightY = normalize(value)
                    right()
                }
                AxisRole.LEFT_TRIGGER -> trigger(number, PadButton.L2, value, initial)
                AxisRole.RIGHT_TRIGGER -> trigger(number, PadButton.R2, value, initial)
                AxisRole.HAT_X -> if (!initial) {
                    hatX = hat(hatX, if (value < -HAT_THRESHOLD) PadButton.DPAD_LEFT else if (value > HAT_THRESHOLD) PadButton.DPAD_RIGHT else null)
                }
                AxisRole.HAT_Y -> if (!initial) {
                    hatY = hat(hatY, if (value < -HAT_THRESHOLD) PadButton.DPAD_UP else if (value > HAT_THRESHOLD) PadButton.DPAD_DOWN else null)
                }
                AxisRole.OTHER -> Unit
            }
        }

        /**
         * Triggers rest at -32767 on xpad and at 0 on some other drivers; the value seen at connect time
         * (never above 0) is taken as the rest position.
         */
        private fun trigger(number: Int, button: PadButton, value: Int, initial: Boolean) {
            if (initial) {
                triggerRest[number] = minOf(value, 0)
                return
            }
            val rest = triggerRest[number] ?: -AXIS_MAX
            val fraction = ((value - rest).toFloat() / (AXIS_MAX - rest)).coerceIn(0f, 1f)
            triggerDown[button] = fraction
            sink.trigger(button, fraction)
        }

        /** The right stick, pushed well over, as a press of that side; let go back near the centre. */
        private fun right() {
            val next = when {
                abs(rightX) >= abs(rightY) && abs(rightX) >= RIGHT_PRESS -> if (rightX < 0) PadButton.RSTICK_LEFT else PadButton.RSTICK_RIGHT
                abs(rightY) > abs(rightX) && abs(rightY) >= RIGHT_PRESS -> if (rightY < 0) PadButton.RSTICK_UP else PadButton.RSTICK_DOWN
                rightHeld != null && (abs(rightX) >= RIGHT_PRESS * 0.6f || abs(rightY) >= RIGHT_PRESS * 0.6f) -> rightHeld
                else -> null
            }
            rightHeld = hat(rightHeld, next)
        }

        private fun hat(previous: PadButton?, next: PadButton?): PadButton? {
            if (previous == next) return previous
            previous?.let { sink.release(it) }
            next?.let { sink.press(it) }
            return next
        }

        private fun normalize(value: Int): Float {
            val v = (value.toFloat() / AXIS_MAX).coerceIn(-1f, 1f)
            return if (abs(v) < 0.02f) 0f else v
        }

        /** Lets go of everything this device held, so nothing stays pressed after an unplug. */
        private fun releaseAll() {
            held.forEach { sink.release(it) }
            held.clear()
            hatX?.let { sink.release(it) }
            hatY?.let { sink.release(it) }
            rightHeld?.let { sink.release(it) }
            hatX = null
            hatY = null
            rightHeld = null
            if (stickX != 0f || stickY != 0f) sink.stick(0f, 0f)
            stickX = 0f
            stickY = 0f
            triggerDown.forEach { (b, v) -> if (v > 0f) sink.trigger(b, 0f) }
            triggerDown.clear()
        }
    }

    private companion object {
        val JS_NAME = Regex("js\\d+")
        const val SCAN_INTERVAL_MS = 2_000L
        const val JS_EVENT_BUTTON = 0x01
        const val JS_EVENT_AXIS = 0x02
        const val JS_EVENT_INIT = 0x80
        const val AXIS_MAX = 32767
        const val HAT_THRESHOLD = 16384

        /** How far the right stick goes over before it counts as pushed (a flick, not a drift). */
        const val RIGHT_PRESS = 0.6f
    }
}
