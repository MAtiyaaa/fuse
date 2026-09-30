package io.github.matiyaaa.fuse.desktop.input

import io.github.matiyaaa.fuse.model.PadButton
import java.io.File
import java.math.BigInteger
import java.util.Locale

/** What one joydev axis means for Fuse. */
internal enum class AxisRole { LEFT_X, LEFT_Y, LEFT_TRIGGER, RIGHT_TRIGGER, HAT_X, HAT_Y, OTHER }

/**
 * How a joydev device's button and axis numbers map to Fuse's pad buttons.
 *
 * joydev numbers buttons in order of their evdev key codes (codes from BTN_JOYSTICK upward first,
 * then BTN_MISC up to BTN_JOYSTICK) and axes in order of their ABS codes, so reading the device's
 * capability bitmaps from sysfs recovers the evdev meaning of every number without an ioctl. When
 * sysfs can't be read, the common Xbox (xpad) layout is assumed.
 */
internal class JoystickMapping(
    private val buttons: Map<Int, PadButton>,
    private val axes: Map<Int, AxisRole>,
) {
    fun button(number: Int): PadButton? = buttons[number]
    fun axis(number: Int): AxisRole = axes[number] ?: AxisRole.OTHER

    companion object {
        // evdev key codes (linux/input-event-codes.h).
        private const val BTN_MISC = 0x100
        private const val BTN_JOYSTICK = 0x120
        private const val KEY_MAX = 0x2ff
        private const val BTN_SOUTH = 0x130
        private const val BTN_EAST = 0x131
        private const val BTN_NORTH = 0x133 // Also BTN_X: xpad reports Xbox X here.
        private const val BTN_WEST = 0x134 // Also BTN_Y: xpad reports Xbox Y here.
        private const val BTN_TL = 0x136
        private const val BTN_TR = 0x137
        private const val BTN_TL2 = 0x138
        private const val BTN_TR2 = 0x139
        private const val BTN_SELECT = 0x13a
        private const val BTN_START = 0x13b
        private const val BTN_MODE = 0x13c
        private const val BTN_THUMBL = 0x13d
        private const val BTN_THUMBR = 0x13e
        private const val BTN_DPAD_UP = 0x220
        private const val BTN_DPAD_DOWN = 0x221
        private const val BTN_DPAD_LEFT = 0x222
        private const val BTN_DPAD_RIGHT = 0x223

        // ABS codes.
        private const val ABS_X = 0x00
        private const val ABS_Y = 0x01
        private const val ABS_Z = 0x02
        private const val ABS_RX = 0x03
        private const val ABS_RZ = 0x05
        private const val ABS_GAS = 0x09
        private const val ABS_BRAKE = 0x0a
        private const val ABS_HAT0X = 0x10
        private const val ABS_HAT0Y = 0x11
        private const val ABS_MAX = 0x3f

        /** The xpad layout: buttons A B X Y LB RB Back Start Guide LS RS; axes LX LY LT RX RY RT HatX HatY. */
        val XPAD = JoystickMapping(
            buttons = mapOf(
                0 to PadButton.A, 1 to PadButton.B, 2 to PadButton.X, 3 to PadButton.Y, 4 to PadButton.L1, 5 to PadButton.R1,
                6 to PadButton.SELECT, 7 to PadButton.START, 8 to PadButton.MODE, 9 to PadButton.L3, 10 to PadButton.R3,
            ),
            axes = mapOf(
                0 to AxisRole.LEFT_X, 1 to AxisRole.LEFT_Y, 2 to AxisRole.LEFT_TRIGGER, 5 to AxisRole.RIGHT_TRIGGER,
                6 to AxisRole.HAT_X, 7 to AxisRole.HAT_Y,
            ),
        )

        /** Reads `/sys/class/input/<js>/device/capabilities/{key,abs}`; falls back to [XPAD]. */
        fun forDevice(jsName: String, deviceName: String?): JoystickMapping {
            val caps = "/sys/class/input/$jsName/device/capabilities"
            val keys = readBitmap("$caps/key") ?: return XPAD
            val abs = readBitmap("$caps/abs") ?: return XPAD
            return fromCapabilities(keys, abs, isPlayStation(deviceName))
        }

        /**
         * PlayStation drivers (hid-sony, hid-playstation) put Triangle on BTN_NORTH and Square on
         * BTN_WEST by position, while xpad and hid-steam put X and Y on the same two codes the other
         * way round. Positions are what the interface cares about.
         */
        fun isPlayStation(name: String?): Boolean {
            val n = name?.lowercase(Locale.ROOT) ?: return false
            return "sony" in n || "playstation" in n || "dualsense" in n || "dualshock" in n || n == "wireless controller"
        }

        fun fromCapabilities(keyBits: BigInteger, absBits: BigInteger, playStation: Boolean): JoystickMapping {
            val codes = ArrayList<Int>()
            for (code in BTN_JOYSTICK..KEY_MAX) if (keyBits.testBit(code)) codes += code
            for (code in BTN_MISC until BTN_JOYSTICK) if (keyBits.testBit(code)) codes += code
            val buttons = HashMap<Int, PadButton>()
            codes.forEachIndexed { number, code ->
                val b = when (code) {
                    BTN_SOUTH -> PadButton.A
                    BTN_EAST -> PadButton.B
                    BTN_NORTH -> if (playStation) PadButton.Y else PadButton.X
                    BTN_WEST -> if (playStation) PadButton.X else PadButton.Y
                    BTN_TL -> PadButton.L1
                    BTN_TR -> PadButton.R1
                    BTN_TL2 -> PadButton.L2
                    BTN_TR2 -> PadButton.R2
                    BTN_SELECT -> PadButton.SELECT
                    BTN_START -> PadButton.START
                    BTN_MODE -> PadButton.MODE
                    BTN_THUMBL -> PadButton.L3
                    BTN_THUMBR -> PadButton.R3
                    BTN_DPAD_UP -> PadButton.DPAD_UP
                    BTN_DPAD_DOWN -> PadButton.DPAD_DOWN
                    BTN_DPAD_LEFT -> PadButton.DPAD_LEFT
                    BTN_DPAD_RIGHT -> PadButton.DPAD_RIGHT
                    else -> null
                }
                if (b != null) buttons[number] = b
            }
            val absCodes = (0..ABS_MAX).filter { absBits.testBit(it) }
            // With a right stick on RX/RY, Z and RZ are the analog triggers (xpad, PlayStation). Without
            // one, Z/RZ is usually the right stick, and triggers, if any, are GAS/BRAKE.
            val zIsTrigger = absBits.testBit(ABS_RX)
            val axes = HashMap<Int, AxisRole>()
            absCodes.forEachIndexed { number, code ->
                axes[number] = when (code) {
                    ABS_X -> AxisRole.LEFT_X
                    ABS_Y -> AxisRole.LEFT_Y
                    ABS_Z -> if (zIsTrigger) AxisRole.LEFT_TRIGGER else AxisRole.OTHER
                    ABS_RZ -> if (zIsTrigger) AxisRole.RIGHT_TRIGGER else AxisRole.OTHER
                    ABS_BRAKE -> AxisRole.LEFT_TRIGGER
                    ABS_GAS -> AxisRole.RIGHT_TRIGGER
                    ABS_HAT0X -> AxisRole.HAT_X
                    ABS_HAT0Y -> AxisRole.HAT_Y
                    else -> AxisRole.OTHER
                }
            }
            return JoystickMapping(buttons, axes)
        }

        /**
         * A sysfs capability bitmap: space-separated hex words, most significant first, each one
         * machine word (64 bits on x86_64) wide.
         */
        fun parseBitmap(text: String, wordBits: Int = 64): BigInteger? {
            val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            var result = BigInteger.ZERO
            for (w in words) {
                val v = w.toBigIntegerOrNull(16) ?: return null
                result = result.shiftLeft(wordBits).or(v)
            }
            return result
        }

        private fun readBitmap(path: String): BigInteger? = try {
            File(path).takeIf { it.isFile }?.readText()?.let { parseBitmap(it) }
        } catch (e: Exception) {
            null
        }
    }
}
