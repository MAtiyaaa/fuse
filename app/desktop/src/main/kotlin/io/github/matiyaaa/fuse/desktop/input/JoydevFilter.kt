package io.github.matiyaaa.fuse.desktop.input

import java.io.File

/**
 * Whether a joydev node (`/dev/input/jsN`) is really a game controller. The kernel's joydev driver
 * gives a `js` node to anything with absolute axes and buttons, which includes some mice (VM and
 * tablet-mode pointers), drawing tablets, touchpads and controllers' separate motion-sensor devices.
 * Read as controllers, those move Fuse's selection whenever the mouse moves and switch the hints to
 * a controller that isn't there. A node counts only when its input device has gamepad or joystick
 * buttons; one that looks like a pointer, a touch surface or a sensor is left alone.
 *
 * The capability files are bitmaps as the kernel prints them: hexadecimal words, most significant
 * first, each [wordBits] wide. When they can't be read the node is accepted, as before.
 */
internal object JoydevFilter {
    private const val BTN_MOUSE = 0x110
    private const val BTN_TASK = 0x117
    private const val BTN_JOYSTICK = 0x120
    private const val BTN_DEAD = 0x12f
    private const val BTN_GAMEPAD = 0x130
    private const val BTN_THUMBR = 0x13e
    private const val BTN_TOOL_PEN = 0x140
    private const val BTN_TOOL_FINGER = 0x145
    private const val BTN_TOUCH = 0x14a
    private const val BTN_TRIGGER_HAPPY = 0x2c0
    private const val BTN_TRIGGER_HAPPY40 = 0x2e7
    private const val BTN_DPAD_UP = 0x220
    private const val BTN_DPAD_RIGHT = 0x223
    private const val REL_X = 0x00
    private const val REL_Y = 0x01
    private const val INPUT_PROP_POINTER = 0x00
    private const val INPUT_PROP_DIRECT = 0x01
    private const val INPUT_PROP_ACCELEROMETER = 0x06

    /** Why a node is not a controller, or null when it is one. */
    fun rejection(key: String?, rel: String?, props: String?, wordBits: Int = 64): String? {
        if (key == null) return null
        val keys = bits(key, wordBits)
        val pad = (BTN_GAMEPAD..BTN_THUMBR).any { it in keys } || (BTN_JOYSTICK..BTN_DEAD).any { it in keys } ||
            (BTN_TRIGGER_HAPPY..BTN_TRIGGER_HAPPY40).any { it in keys } || (BTN_DPAD_UP..BTN_DPAD_RIGHT).any { it in keys }
        val p = props?.let { bits(it, wordBits) }.orEmpty()
        if (INPUT_PROP_ACCELEROMETER in p) return "a motion sensor"
        if (pad) return null
        val r = rel?.let { bits(it, wordBits) }.orEmpty()
        return when {
            (BTN_MOUSE..BTN_TASK).any { it in keys } || (REL_X in r && REL_Y in r) -> "a pointer"
            BTN_TOUCH in keys || BTN_TOOL_PEN in keys || BTN_TOOL_FINGER in keys || INPUT_PROP_POINTER in p || INPUT_PROP_DIRECT in p -> "a touch surface or tablet"
            else -> "no controller buttons"
        }
    }

    /** Why `/dev/input/[js]` is not a controller, read from sysfs; null when it is (or can't be told). */
    fun rejection(js: String, sysfs: File = File("/sys/class/input")): String? {
        val caps = File(sysfs, "$js/device/capabilities")
        fun read(f: File) = runCatching { f.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return rejection(read(File(caps, "key")), read(File(caps, "rel")), read(File(sysfs, "$js/device/properties")))
    }

    /** The set bits of a kernel bitmap: words most significant first, each [wordBits] wide. */
    fun bits(bitmap: String, wordBits: Int = 64): Set<Int> {
        val words = bitmap.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.reversed()
        val out = HashSet<Int>()
        for ((i, w) in words.withIndex()) {
            val value = w.toULongOrNull(16) ?: continue
            for (b in 0 until wordBits.coerceAtMost(64)) if ((value shr b) and 1uL == 1uL) out += i * wordBits + b
        }
        return out
    }
}
