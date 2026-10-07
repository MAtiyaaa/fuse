package io.github.matiyaaa.fuse.ui.fuseline

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fuseline 4: what a spring's solution owes to the spring alone, worked out once for every value
 * that uses it. A spring's displacement is e^(−at)(x₀·C(t) + B·S(t)): the decay rate a, the ringing
 * frequency and the shape of C and S depend only on the spring's stiffness and damping, and at a
 * given moment e^(−at)C(t) and e^(−at)S(t) are the same numbers for every value on that spring. Only
 * x₀ and B belong to the value.
 *
 * So a kernel keeps the spring's constants (no square roots when a value is retargeted), the last
 * moment it was solved for (every component of a value, and every value started on the same frame,
 * reads that moment's solution instead of solving it again), and the step that advances a solution
 * by one frame interval (a value solved last frame moves on with a handful of multiplications instead
 * of an exponential, a sine and a cosine).
 *
 * Kernels are interned by stiffness and damping ([of]): every `Spring(0.82f, 900f)` in the interface,
 * however many separate objects describe it, shares one.
 */
internal class SpringKernel private constructor(val dampingRatio: Float, val stiffness: Float) {
    // Exactly the expressions Fuseline 3.1 used for each track, so every solution is the same number.
    private val omega = sqrt(stiffness.toDouble())
    private val zeta = dampingRatio.toDouble()
    val a = zeta * omega
    val q = omega * omega * (zeta - 1.0) * (zeta + 1.0)

    /** Below critical: the ringing frequency; above: γ. Zero at critical. */
    val freq = sqrt(abs(q))
    val under = q < 0.0
    val over = q > 0.0

    // The last moment solved, shared by everyone on this spring.
    private var memoT = Double.NaN
    var memoEc = 0.0
        private set
    var memoEs = 0.0
        private set

    /** Fills [memoEc] and [memoEs] for [t] seconds: from the last solution when it was for [t], else solved. */
    fun solve(t: Double) {
        if (t == memoT) {
            if (Kernels.counting) Kernels.reused++
            return
        }
        if (Kernels.counting) Kernels.solved++
        var ec: Double
        var es: Double
        when {
            under -> {
                val e = exp(-a * t)
                val u = freq * t
                ec = e * cos(u)
                es = e * if (abs(u) < SpringTrack.SERIES) t * (1.0 - u * u / 6.0) else sin(u) / freq
            }
            over -> {
                val u = freq * t
                if (u < SpringTrack.SERIES) {
                    val e = exp(-a * t)
                    ec = e * (1.0 + u * u / 2.0)
                    es = e * t * (1.0 + u * u / 6.0)
                } else {
                    val e1 = exp((freq - a) * t)
                    val e2 = exp(-(freq + a) * t)
                    ec = (e1 + e2) / 2.0
                    es = (e1 - e2) / (2.0 * freq)
                }
            }
            else -> {
                val e = exp(-a * t)
                ec = e
                es = e * t
            }
        }
        memoT = t
        memoEc = ec
        memoEs = es
    }

    /** Records a solution worked out elsewhere (a step from the last frame) as this moment's. */
    fun remember(t: Double, ec: Double, es: Double) {
        memoT = t
        memoEc = ec
        memoEs = es
    }

    fun hasMemo(t: Double) = t == memoT

    // The step across one interval of time: for [stepNanos], the decay over it and the turn of the ringing.
    private var stepNanos = -1L
    private var stepDecay = 0.0
    private var stepCos = 0.0
    private var stepSin = 0.0

    // Above critical, the two exponentials' own decays over the step.
    private var stepE1 = 0.0
    private var stepE2 = 0.0

    private fun stepFor(nanos: Long) {
        if (nanos == stepNanos) return
        stepNanos = nanos
        val d = nanos / NANOS_PER_SECOND
        when {
            under -> {
                stepDecay = exp(-a * d)
                stepCos = cos(freq * d)
                stepSin = sin(freq * d)
            }
            over -> {
                stepE1 = exp((freq - a) * d)
                stepE2 = exp(-(freq + a) * d)
            }
            else -> stepDecay = exp(-a * d)
        }
    }

    /**
     * The solution [nanos] after one at [ec], [es] (time [t0] seconds): e^(−a(t+Δ)) turns the
     * ringing by the angle of Δ and shrinks it by e^(−aΔ), so the step is a rotation and a scale,
     * computed once per spring per interval. Done in double precision, a run of steps stays within
     * about 1e−15 of solving afresh, far finer than the float a value is kept in; [SpringTrack] solves
     * afresh every [SpringTrack.RESOLVE] steps anyway.
     */
    fun step(ec: Double, es: Double, t1: Double, nanos: Long) {
        stepFor(nanos)
        when {
            under -> {
                val c = stepCos
                val s = stepSin
                val e = stepDecay
                stepEc = e * (ec * c - es * freq * s)
                stepEs = e * (es * c + ec * s / freq)
            }
            over -> {
                // e^((γ−a)t) and e^(−(γ+a)t) back out of C and S, each decayed by its own factor.
                val e1 = (ec + freq * es) * stepE1
                val e2 = (ec - freq * es) * stepE2
                stepEc = (e1 + e2) / 2.0
                stepEs = (e1 - e2) / (2.0 * freq)
            }
            else -> {
                val e = ec * stepDecay
                stepEc = e
                stepEs = e * t1
            }
        }
    }

    var stepEc = 0.0
        private set
    var stepEs = 0.0
        private set

    companion object {
        // Interned by the two floats' bits, in an open-addressed table: looking one up makes nothing.
        private var keys = LongArray(64)
        private var values = arrayOfNulls<SpringKernel>(64)
        private var size = 0

        fun of(dampingRatio: Float, stiffness: Float): SpringKernel {
            val key = (dampingRatio.toRawBits().toLong() shl 32) or (stiffness.toRawBits().toLong() and 0xffffffffL)
            var mask = keys.size - 1
            var i = mix(key) and mask
            while (true) {
                val k = values[i] ?: break
                if (keys[i] == key) return k
                i = (i + 1) and mask
            }
            val made = SpringKernel(dampingRatio, stiffness)
            if ((size + 1) * 2 > keys.size) {
                grow()
                mask = keys.size - 1
                i = mix(key) and mask
                while (values[i] != null) i = (i + 1) and mask
            }
            keys[i] = key
            values[i] = made
            size++
            return made
        }

        private fun grow() {
            val oldKeys = keys
            val oldValues = values
            keys = LongArray(oldKeys.size * 2)
            values = arrayOfNulls(oldKeys.size * 2)
            val mask = keys.size - 1
            for (j in oldKeys.indices) {
                val v = oldValues[j] ?: continue
                var i = mix(oldKeys[j]) and mask
                while (values[i] != null) i = (i + 1) and mask
                keys[i] = oldKeys[j]
                values[i] = v
            }
        }

        private fun mix(key: Long): Int {
            val h = key * -0x61c8864680b583ebL
            return (h xor (h ushr 29)).toInt()
        }
    }
}

/**
 * Counts of Fuseline 4's shared work, for the Motion Inspector and the benchmark's notes, kept only
 * while [counting] (the inspector turns it on): otherwise each costs one test of a flag.
 */
internal object Kernels {
    var counting = false

    /** Spring solutions worked out from scratch (an exponential and, ringing, a sine and a cosine). */
    var solved = 0L

    /** Spring solutions read from a kernel's last moment instead (another value, or component, solved it). */
    var reused = 0L

    /** Spring solutions carried on from the frame before with a step. */
    var stepped = 0L

    /** Curve solutions read from a curve's last answer instead of solved. */
    var curveReused = 0L

    fun reset() {
        solved = 0L
        reused = 0L
        stepped = 0L
        curveReused = 0L
    }
}
