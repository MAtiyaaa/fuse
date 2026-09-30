package io.github.matiyaaa.fuse.platform

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.matiyaaa.fuse.ui.shell.platform.Haptics

/**
 * Short haptic ticks on the device's vibrator, scaled by the controller vibration setting. Uses
 * haptic primitives where the vibrator supports them, amplitude-controlled one-shots otherwise.
 * Marked as touch feedback, so the system's "touch feedback" switch still silences them.
 */
class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }?.takeIf { it.hasVibrator() }

    /** 0..1 from the controller settings; 0 turns haptics off. */
    @Volatile var intensity: Float = 0.5f

    val available: Boolean get() = vibrator != null

    override fun tick() = vibrate(Kind.TICK)
    override fun confirm() = vibrate(Kind.CONFIRM)
    override fun reject() = vibrate(Kind.REJECT)

    private enum class Kind { TICK, CONFIRM, REJECT }

    private fun vibrate(kind: Kind) {
        val v = vibrator ?: return
        val scale = intensity.coerceIn(0f, 1f)
        if (scale <= 0f) return
        val effect = effect(v, kind, scale) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        } catch (e: RuntimeException) {
            // Vibration is best effort (some OEM services throw).
        }
    }

    private fun effect(v: Vibrator, kind: Kind, scale: Float): VibrationEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = when (kind) {
                Kind.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
                Kind.CONFIRM -> VibrationEffect.Composition.PRIMITIVE_CLICK
                Kind.REJECT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                } else {
                    VibrationEffect.Composition.PRIMITIVE_TICK
                }
            }
            if (v.areAllPrimitivesSupported(primitive)) {
                val composition = VibrationEffect.startComposition().addPrimitive(primitive, scale)
                if (kind == Kind.REJECT) composition.addPrimitive(primitive, scale, 60)
                return composition.compose()
            }
        }
        val (millis, base) = when (kind) {
            Kind.TICK -> 8L to 90
            Kind.CONFIRM -> 16L to 170
            Kind.REJECT -> 28L to 120
        }
        return when {
            v.hasAmplitudeControl() -> VibrationEffect.createOneShot(millis, (base * scale).toInt().coerceIn(1, 255))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> VibrationEffect.createPredefined(
                when (kind) {
                    Kind.TICK -> VibrationEffect.EFFECT_TICK
                    Kind.CONFIRM -> VibrationEffect.EFFECT_CLICK
                    Kind.REJECT -> VibrationEffect.EFFECT_DOUBLE_CLICK
                },
            )
            else -> VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }
}
