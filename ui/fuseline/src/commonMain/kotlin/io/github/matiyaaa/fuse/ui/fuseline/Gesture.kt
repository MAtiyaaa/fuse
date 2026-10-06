package io.github.matiyaaa.fuse.ui.fuseline

/**
 * Who moves a [FuselineValue] right now: nobody (it rests), a motion, or a gesture (a finger, the
 * mouse, a stick, the scroll wheel). Handing it from one to the other never moves it: a gesture
 * takes it where a motion left it, and a motion takes it on from where and how fast the gesture
 * left it.
 */
enum class MotionOwner { IDLE, ANIMATION, GESTURE }

/**
 * How fast a dragged value was moving, from the positions it was dragged through: a least-squares
 * line through the samples of the last [WINDOW_NANOS], one component at a time, in units per second.
 * A drag that paused (no sample for [STILL_NANOS] before the question) was standing still. It keeps
 * a fixed ring of samples, so tracking a drag makes no objects.
 */
internal class DragVelocity(private val dims: Int) {
    private val times = LongArray(SAMPLES)
    private val positions = Array(dims) { FloatArray(SAMPLES) }
    private var count = 0
    private var head = 0

    fun reset() {
        count = 0
        head = 0
    }

    fun add(timeNanos: Long, position: FloatArray) {
        // Time only moves forward; a sample out of order replaces the latest.
        if (count > 0) {
            val last = (head - 1 + SAMPLES) % SAMPLES
            if (timeNanos <= times[last]) {
                for (d in 0 until dims) positions[d][last] = position[d]
                return
            }
        }
        times[head] = timeNanos
        for (d in 0 until dims) positions[d][head] = position[d]
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    /** The velocity at [nowNanos] into [out] (zero when there is too little to tell, or the drag had stopped). */
    fun velocity(nowNanos: Long, out: FloatArray) {
        out.fill(0f, 0, dims)
        if (count < 2) return
        val latest = (head - 1 + SAMPLES) % SAMPLES
        if (nowNanos - times[latest] > STILL_NANOS) return
        // The samples within the window, newest first.
        var n = 0
        var sumT = 0.0
        var sumTT = 0.0
        for (i in 0 until count) {
            val at = (latest - i + SAMPLES) % SAMPLES
            val age = times[latest] - times[at]
            if (age > WINDOW_NANOS) break
            val t = -age / NANOS_PER_SECOND
            sumT += t
            sumTT += t * t
            n++
        }
        if (n < 2) return
        val denominator = n * sumTT - sumT * sumT
        if (denominator <= 0.0) return
        for (d in 0 until dims) {
            var sumX = 0.0
            var sumTX = 0.0
            for (i in 0 until n) {
                val at = (latest - i + SAMPLES) % SAMPLES
                val t = -(times[latest] - times[at]) / NANOS_PER_SECOND
                val x = positions[d][at].toDouble()
                sumX += x
                sumTX += t * x
            }
            out[d] = ((n * sumTX - sumT * sumX) / denominator).toFloat()
        }
    }

    companion object {
        const val SAMPLES = 20
        const val WINDOW_NANOS = 100_000_000L
        const val STILL_NANOS = 40_000_000L
    }
}
