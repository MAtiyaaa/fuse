package io.github.matiyaaa.fuse.desktop.input

import io.github.matiyaaa.fuse.ui.shell.platform.Haptics

/**
 * Fuse's haptics on a computer: short rumbles on the controller in use, scaled by Settings, Input,
 * Vibration. Where the controllers are read through SDL (Windows and macOS). On Linux Fuse reads
 * controllers itself from /dev/input and doesn't drive their motors; in Steam's Game Mode, Steam
 * Input gives its own feedback.
 */
class ControllerHaptics : Haptics {
    /** Runs a rumble: low and high motor strength (0 to 1) for a time in milliseconds. */
    @Volatile var rumble: ((low: Float, high: Float, millis: Int) -> Unit)? = null

    @Volatile private var intensity = 0.5f

    override fun setStrength(level: Float) { intensity = level.coerceIn(0f, 1f) }

    override fun tick() = buzz(0.0f, 0.25f, 18)
    override fun confirm() = buzz(0.2f, 0.45f, 35)
    override fun reject() = buzz(0.45f, 0.1f, 60)
    override fun lift() = buzz(0.25f, 0.5f, 40)
    override fun slot() = buzz(0.0f, 0.15f, 12)
    override fun drop() = buzz(0.1f, 0.3f, 22)
    override fun notice() = buzz(0.0f, 0.3f, 25)
    override fun success() = buzz(0.3f, 0.55f, 70)

    private fun buzz(low: Float, high: Float, millis: Int) {
        val scale = intensity
        if (scale <= 0f) return
        runCatching { rumble?.invoke(low * scale, high * scale, millis) }
    }
}
