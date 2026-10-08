package io.github.matiyaaa.fuse.ui.fuseline

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Fuseline 4's frame driver: one per frame clock, and the one place time reaches motion.
 *
 * Fuseline 3.1 stepped every move on every frame. Fuseline 4 asks, for each move, when it next needs
 * a frame at all, and leaves it alone until then. Every motion is a closed-form function of its time,
 * so a move that isn't stepped hasn't stopped: whatever reads its value gets exactly the state the
 * frames in between would have left (see [FuselineValue]). A move comes up again only when:
 *
 * - it could next change by enough to be seen (its event horizon: the value's own bound on how far
 *   its motion can travel by then, [Track.calmUntil]);
 * - it arrives (its end is known in advance, so it lands on exactly the frame it would have);
 * - it is read again after nobody read it (a value nobody looks at needs no frames until someone does);
 * - it is given a new target, sought, or does work every frame (a caller's per-frame block).
 *
 * Moves keep their join order ([Move.seq]), and every frame goes through the moves due in that
 * order, so moves that touch each other within a frame (one ending and starting another, a value's
 * block retargeting a second) see each other exactly as they did when every move was stepped.
 * Moves due every frame are kept in a list; the others wait in a heap ordered by when they are due.
 * A frame that has nothing due costs a look at the heap's top.
 *
 * Moves join from the main thread, as all of Fuseline's do. A move that joins while the driver is
 * stepping begins on the next frame. A move taken over or cancelled leaves at once.
 */
internal class FrameDriver private constructor(private val clock: MonotonicFrameClock) {

    /** A move's value: what is stepped when the move comes up, and what it says about when to come back. */
    internal interface Driven {
        /** A time no later than the move's end, cheap to know. */
        fun boundNanos(): Long

        /** The move's exact length (a spring's Newton solve: asked for only once [boundNanos] has passed). */
        fun durationNanos(): Long

        /**
         * Steps the value at [playNanos] (already held to the move's end) of the frame at [frameNanos].
         * Returns the play time before which it needn't come up again ([PLAY_NEXT] for the next frame,
         * [Long.MAX_VALUE] for not until it arrives). [arriving] says this is its last frame.
         */
        fun frame(playNanos: Long, frameNanos: Long, arriving: Boolean): Long

        /** The move is leaving (taken over or cancelled): keep the value where it is now. */
        fun freeze()

        /** True while nobody has read the value since it last changed what readers would show. */
        val unread: Boolean
    }

    open inner class Move(
        /** The value it moves, or null for a plain per-frame step ([step]). */
        val driven: Driven?,
        /** Per-frame work instead of a value (an [animate] block), run on every frame. */
        val step: FrameStep?,
        var exactDuration: Long,
        val scale: Float,
        /** A suspended caller to resume on arrival, or null for a move made without a coroutine. */
        val waiting: CancellableContinuation<Unit>?,
        /** True when every frame matters (per-frame callbacks): never skipped. */
        val eager: Boolean,
    ) : Retimer, MoveHandle {
        val seq = nextSeq++
        var start = Long.MIN_VALUE
        var gone = false

        /** A seek asked for before the first frame: where the play starts. */
        var pending = -1L

        /** A time no later than the end ([exactDuration] once known). */
        var bound = if (exactDuration >= 0) exactDuration else driven!!.boundNanos()

        /** When it is next due, as a frame time (0: the next frame), in the heap or on the every-frame list. */
        var wake = 0L

        /** Its place in the heap, or -1 when it isn't waiting there. */
        var heapIndex = -1

        /** Where it waits: in the list of moves due every frame, or in the heap. */
        var listed = false

        /** Waiting only to arrive: nobody reads its value. A read brings it back ([rearm]). */
        var parked = false

        /** Joined and not yet started: it comes up on its first frame whatever happens before. */
        var joining = true

        /** Read from another thread while parked: picked up on the driver's next frame. */
        @Volatile
        var remoteRead = false

        val driver: FrameDriver get() = this@FrameDriver

        /** The move's exact length: worked out only once it might be ending. */
        fun duration(): Long {
            if (exactDuration < 0) {
                exactDuration = driven!!.durationNanos()
                bound = exactDuration
            }
            return exactDuration
        }

        /** The time played at [frameNanos], held to the end: exactly as each frame works it out. */
        fun playAt(frameNanos: Long): Long {
            val elapsed = frameNanos - start
            val play = if (scale == 1f) elapsed else (elapsed / scale).toLong()
            return if (play >= bound) minOf(play, duration()) else play
        }

        /**
         * Plays again from the frame just shown (where its value is now), keeping its place among the
         * moves: the next frame is one frame into the new motion, never a pause.
         */
        override fun retime(durationNanos: Long) {
            if (durationNanos >= 0) {
                exactDuration = durationNanos
                bound = durationNanos
            } else {
                exactDuration = -1L
                bound = driven!!.boundNanos()
            }
            if (start != Long.MIN_VALUE) start = lastFrame
            due()
        }

        /** Puts the play at [playNanos] as of the frame just shown, so the next frame carries on from there. */
        override fun seek(playNanos: Long) {
            if (start == Long.MIN_VALUE) pending = playNanos else start = lastFrame - (playNanos * scale).toLong()
            due()
        }

        /** Leaves at once (taken over, or its value no longer shown): never stepped again. */
        override fun cancel() {
            if (gone) return
            driven?.freeze()
            leave()
            stopIfIdle()
        }

        fun leave() {
            gone = true
            unschedule(this)
            live--
        }

        open fun arrive() {
            leave()
            waiting?.resume(Unit)
        }

        /** Due on the next frame, or later in this one if this frame hasn't reached it yet. */
        fun due() {
            if (gone || joining) return
            parked = false
            if (passing && seq > cursor) {
                insertIntoPass(this)
                return
            }
            if (listed) wake = 0L else schedule(this, 0L)
        }
    }

    /** A move made without a coroutine ([start]): [arrived] is called when it lands. */
    private inner class NativeMove(driven: Driven, scale: Float, private val arrived: () -> Unit) :
        Move(driven, null, -1L, scale, null, false) {
        override fun arrive() {
            leave()
            arrived()
        }
    }

    private var nextSeq = 0L
    private var live = 0
    private var running = false
    private var loop: Job? = null

    /** The time of the frame last stepped, and the one before it. */
    var lastFrame = Long.MIN_VALUE
        private set
    private var previousFrame = Long.MIN_VALUE

    /** While a frame is being stepped, and the move it has reached. */
    private var passing = false
    private var cursor = Long.MAX_VALUE

    /** The thread frames arrive on: reads from it may wake a parked move directly. */
    private var frameThread = -1L

    @Volatile
    private var remoteReads = false

    /**
     * The frame a read of [m]'s value is as of: the frame just stepped, except during a frame for a move
     * the frame hasn't reached yet, whose value is still the one the frame before left.
     */
    fun frameFor(m: Move): Long = if (passing && m.seq > cursor) previousFrame else lastFrame

    /**
     * [m]'s value was just read while it waited unread: it comes up again on the next frame (or this
     * one, if this frame hasn't reached it). From another thread, the driver finds it on its next frame.
     */
    fun rearm(m: Move) {
        if (!m.parked || m.gone) return
        if (currentThreadId() != frameThread) {
            m.remoteRead = true
            remoteReads = true
            return
        }
        if (MotionInspector.enabled) Inspection.rearmed++
        m.due()
    }

    // ---------------------------------------------------------------------------------------- waiting

    /** Moves due every frame, in join order. */
    private var listed = ArrayList<Move>()
    private var listedNext = ArrayList<Move>()

    // The heap: moves due later, ordered by when they are due, then by join order. Each move knows
    // its place in it, so a move given a new time moves within the heap rather than leaving an old
    // entry behind: the heap never holds more than the moves waiting, and nothing is made to keep it.
    private var heap = arrayOfNulls<Move>(64)
    private var heapSize = 0

    private fun before(a: Move, b: Move) = a.wake < b.wake || (a.wake == b.wake && a.seq < b.seq)

    private fun schedule(m: Move, wake: Long) {
        m.wake = wake
        val at = m.heapIndex
        if (at >= 0) {
            siftUp(at)
            siftDown(m.heapIndex)
            return
        }
        if (heapSize == heap.size) heap = heap.copyOf(heapSize * 2)
        heap[heapSize] = m
        m.heapIndex = heapSize
        heapSize++
        siftUp(heapSize - 1)
    }

    private fun place(i: Int, m: Move) {
        heap[i] = m
        m.heapIndex = i
    }

    private fun siftUp(from: Int) {
        var i = from
        val m = heap[i]!!
        while (i > 0) {
            val parent = (i - 1) ushr 1
            val p = heap[parent]!!
            if (!before(m, p)) break
            place(i, p)
            i = parent
        }
        place(i, m)
    }

    private fun siftDown(from: Int) {
        var i = from
        val m = heap[i]!!
        while (true) {
            val l = 2 * i + 1
            if (l >= heapSize) break
            val r = l + 1
            val c = if (r < heapSize && before(heap[r]!!, heap[l]!!)) r else l
            val child = heap[c]!!
            if (!before(child, m)) break
            place(i, child)
            i = c
        }
        place(i, m)
    }

    /** Takes [m] out of the heap, wherever it is. */
    private fun unschedule(m: Move) {
        val i = m.heapIndex
        if (i < 0) return
        m.heapIndex = -1
        val last = --heapSize
        val tail = heap[last]!!
        heap[last] = null
        if (i == last) return
        place(i, tail)
        siftUp(i)
        siftDown(tail.heapIndex)
    }

    /** Takes the heap's first move off. */
    private fun pop(): Move {
        val m = heap[0]!!
        unschedule(m)
        return m
    }

    /** This frame's moves, by join order: the every-frame list itself, or [merged] when others came due. */
    private var visiting = ArrayList<Move>()
    private val merged = ArrayList<Move>()
    private var visitIndex = 0

    /** Moves joined since the last frame (or during this one): started on their first frame. */
    private val joined = ArrayList<Move>()

    /** A move brought into this frame after it began (retimed by an earlier move's work): in its place by join order. */
    private fun insertIntoPass(m: Move) {
        // Already coming up later in this frame: nothing to do.
        var lo = visitIndex
        var hi = visiting.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (visiting[mid].seq < m.seq) lo = mid + 1 else hi = mid
        }
        if (lo < visiting.size && visiting[lo] === m) {
            m.wake = 0L
            return
        }
        unschedule(m)
        m.wake = 0L
        visiting.add(lo, m)
    }

    // ---------------------------------------------------------------------------------------- joining

    /** Steps [onFrame] on every frame until [durationNanos] has played (scaled by [scale]), then returns. */
    suspend fun run(context: CoroutineContext, durationNanos: Long, scale: Float, onStart: ((Retimer) -> Unit)?, onFrame: FrameStep) {
        suspendCancellableCoroutine { waiting ->
            val move = Move(null, onFrame, durationNanos, scale, waiting, eager = true)
            onStart?.invoke(move)
            join(move, context, waiting)
        }
    }

    /** Moves [driven]'s value until its motion has played (scaled by [scale]), then returns. */
    suspend fun run(context: CoroutineContext, driven: Driven, eager: Boolean, scale: Float, onStart: ((Retimer) -> Unit)?) {
        suspendCancellableCoroutine { waiting ->
            val move = Move(driven, null, -1L, scale, waiting, eager)
            onStart?.invoke(move)
            join(move, context, waiting)
        }
    }

    private fun join(move: Move, context: CoroutineContext, waiting: CancellableContinuation<Unit>) {
        live++
        joined += move
        waiting.invokeOnCancellation {
            if (move.gone) return@invokeOnCancellation
            // Its value keeps the state it had when its caller was cancelled.
            move.driven?.freeze()
            move.leave()
            stopIfIdle()
        }
        // A loop that was just told to stop (its last move cancelled) ends a moment later: a move
        // joining in that moment gets a loop of its own rather than that ending.
        if (!running || loop?.isCancelled == true) start(context)
    }

    /**
     * [run] without a coroutine: the move steps [driven] when due and calls [arrived] when it has
     * played. Nothing is suspended or launched for it, so a value that follows a target
     * ([rememberFollowing]) costs no coroutine of its own. Ends early with [MoveHandle.cancel].
     */
    fun start(context: CoroutineContext, driven: Driven, scale: Float, onStart: ((Retimer) -> Unit)?, arrived: () -> Unit): MoveHandle {
        val move = NativeMove(driven, scale, arrived)
        live++
        joined += move
        onStart?.invoke(move)
        if (!running || loop?.isCancelled == true) start(context)
        return move
    }

    /**
     * The last move cancelled: stop waiting for frames now (a clock that never ticks again, its window
     * closed or a test over, would otherwise hold the driver for ever). A move that arrives needs
     * nothing of the kind: the loop sees nothing left after the frame and ends by itself, without
     * the cost of cancelling it.
     */
    private fun stopIfIdle() {
        if (live == 0) loop?.cancel()
    }

    private fun start(context: CoroutineContext) {
        running = true
        // Its own job: a move that is cancelled never takes the others' frames with it.
        val job = Job()
        loop = job
        CoroutineScope(context.minusKey(Job) + job).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    clock.withFrameNanos(stepper)
                    if (live == 0) break
                }
            } finally {
                // A newer loop has taken over (this one was stopped as its last move went): the
                // moves are that loop's now, and this ending leaves them alone.
                if (loop === job) {
                    FramePacing.moving(clock, false)
                    running = false
                    loop = null
                    if (drivers[clock] === this@FrameDriver) drivers.remove(clock)
                    // Normally every move has ended; if the clock itself stopped (its window closed),
                    // the moves still waiting end with it rather than wait for a frame that never comes.
                    val left = ArrayList<Move>()
                    left += joined
                    left += listed
                    for (i in 0 until heapSize) heap[i]?.let { left += it; it.heapIndex = -1 }
                    joined.clear()
                    listed.clear()
                    heap.fill(null)
                    heapSize = 0
                    for (m in left) if (!m.gone) {
                        m.driven?.freeze()
                        m.gone = true
                        m.waiting?.cancel()
                    }
                    live = 0
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------- a frame

    /** The frame callback, made once rather than on every frame. */
    private val stepper: (Long) -> Unit = ::step

    private fun step(frameNanos: Long) {
        val inspected = MotionInspector.enabled
        val began = if (inspected) monotonicNanos() else 0L
        stepMoves(frameNanos)
        if (inspected) {
            MotionInspector.frameCostNanos = monotonicNanos() - began
            var resting = 0
            for (i in 0 until heapSize) if (heap[i]!!.parked) resting++
            MotionInspector.pass(live, listed.size, heapSize, resting, if (heapSize > 0) (heap[0]!!.wake - frameNanos).coerceAtLeast(0L) else -1L)
            MotionInspector.sample()
        }
    }

    private fun stepMoves(frameNanos: Long) {
        FramePacing.frameAt(frameNanos, clock)
        FramePacing.moving(clock, live > 0)
        frameThread = currentThreadId()
        previousFrame = lastFrame
        lastFrame = frameNanos
        if (remoteReads) takeRemoteReads()

        // This frame's moves, by join order: those due every frame, those whose time has come, and
        // those that joined since the last frame. With nothing come due from the heap (the usual
        // case), the every-frame list is gone through as it is, not copied.
        val due = dueNow(frameNanos)
        val current = listed
        val visit = if (due.isEmpty()) current else merged.also { it.clear(); merge(current, due, it) }
        visiting = visit
        visitIndex = 0
        // Joiners all came after every move already here.
        for (m in joined) if (!m.gone) {
            m.joining = false
            visit += m
        }
        joined.clear()

        passing = true
        val counting = MotionInspector.enabled
        while (visitIndex < visit.size) {
            val m = visit[visitIndex]
            cursor = m.seq
            visitIndex++
            if (m.gone) continue
            if (m.wake > frameNanos) {
                // On the every-frame list but proved calm until a later frame: left alone, as the heap would.
                listedNext += m
                continue
            }
            m.listed = false
            if (m.start == Long.MIN_VALUE) {
                m.start = frameNanos - if (m.pending >= 0) (m.pending * m.scale).toLong() else 0L
            }
            val elapsed = frameNanos - m.start
            val play = if (m.scale == 1f) elapsed else (elapsed / m.scale).toLong()
            val ending = play >= m.bound && play >= m.duration()
            val shown = if (ending) m.exactDuration else play
            if (counting) Inspection.visited++
            val s = m.step
            var next: Long
            if (s != null) {
                s.step(shown)
                next = PLAY_NEXT
            } else {
                next = m.driven!!.frame(shown, frameNanos, ending)
            }
            // Whether it has arrived is asked after its frame, as it always was: a move whose own
            // per-frame work gave it a new, shorter motion arrives now if it has already played that long.
            val arriving = !m.gone && play >= m.bound && play >= m.duration()
            if (arriving) {
                m.arrive()
                continue
            }
            if (m.gone) continue
            place(m, play, next, frameNanos)

        }
        passing = false
        cursor = Long.MAX_VALUE
        visit.clear()
        current.clear()
        // Moves due every frame, for the next one; the lists swapped, so nothing is made per frame.
        listed = listedNext
        listedNext = current
    }

    /** Where [m] waits after this frame, given it can be left alone until play time [calmUntil]. */
    private fun place(m: Move, play: Long, calmUntil: Long, frameNanos: Long) {
        if (m.eager || calmUntil <= play + 1) {
            listOnward(m, 0L)
            return
        }
        if (calmUntil == Long.MAX_VALUE) {
            // Not before it arrives: unread, or still for good.
            if (m.driven?.unread == true) {
                m.parked = true
                if (MotionInspector.enabled) Inspection.parked++
            }
        }
        // Whichever comes first: the horizon or the end (both as frame times, rounded early).
        val end = frameAt(m, m.bound)
        val wake = if (calmUntil == Long.MAX_VALUE) end else minOf(frameAt(m, calmUntil), end)
        // Due within a few frames: kept on the every-frame list, passed over until then (a look at it
        // costs less than the heap).
        if (wake <= frameNanos + FramePacing.intervalFor(clock) * 4) listOnward(m, wake) else schedule(m, wake)
    }

    private fun listOnward(m: Move, wake: Long) {
        unschedule(m)
        m.wake = wake
        m.listed = true
        listedNext += m
    }

    /** The earliest frame time at which [m] could have played [play] (early by a hair where the scale divides). */
    private fun frameAt(m: Move, play: Long): Long {
        if (play == Long.MAX_VALUE) return Long.MAX_VALUE
        // A start can be far in the past (a move sought far into its play), even below zero: the
        // sum is checked for running past the end of time, never the difference.
        if (m.scale == 1f) {
            val at = m.start + play
            return if (play > 0 && at < m.start) Long.MAX_VALUE else at
        }
        val at = m.start.toDouble() + play.toDouble() * m.scale * (1.0 - 1e-6) - 2.0
        return if (at >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else at.toLong()
    }

    private val dueBuffer = ArrayList<Move>()

    /** The heap's moves whose time has come, by join order. */
    private fun dueNow(frameNanos: Long): ArrayList<Move> {
        val due = dueBuffer
        due.clear()
        while (heapSize > 0 && heap[0]!!.wake <= frameNanos) due += pop()
        if (due.size > 1) sortBySeq(due)
        return due
    }

    /** [a] and [b] (each by join order) into [out], by join order. */
    private fun merge(a: ArrayList<Move>, b: ArrayList<Move>, out: ArrayList<Move>) {
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i].seq < b[j].seq) out += a[i++] else out += b[j++]
        }
        while (i < a.size) out += a[i++]
        while (j < b.size) out += b[j++]
    }

    private fun takeRemoteReads() {
        remoteReads = false
        var i = 0
        while (i < heapSize) {
            val m = heap[i]!!
            if (m.remoteRead) {
                m.remoteRead = false
                // Brought forward: the heap reorders around it, so look at this place again.
                m.due()
                continue
            }
            i++
        }
    }

    companion object {
        private val drivers = HashMap<MonotonicFrameClock, FrameDriver>()

        /** The driver for [clock], made when the first move on it starts. */
        fun of(clock: MonotonicFrameClock): FrameDriver = drivers.getOrPut(clock) { FrameDriver(clock) }

        /** A move's answer for "come up on the next frame". */
        const val PLAY_NEXT = Long.MIN_VALUE

        /** Sorts by join order in place: an insertion sort, as due moves come mostly in order already. */
        private fun sortBySeq(list: ArrayList<Move>) {
            for (i in 1 until list.size) {
                val m = list[i]
                var j = i - 1
                while (j >= 0 && list[j].seq > m.seq) {
                    list[j + 1] = list[j]
                    j--
                }
                list[j + 1] = m
            }
        }
    }
}

/** What the driver did, counted only while the Motion Inspector is on. */
internal object Inspection {
    var visited = 0L
    var parked = 0L
    var rearmed = 0L
    var horizonSkips = 0L

    fun reset() {
        visited = 0L
        parked = 0L
        rearmed = 0L
        horizonSkips = 0L
    }
}

/**
 * One frame of a move, given the time played. An interface of its own rather than a function type,
 * so the time passes as a plain number: a `(Long) -> Unit` would box it on every frame of every value.
 */
internal fun interface FrameStep {
    fun step(playNanos: Long)
}

/** A move made without a coroutine ([FrameDriver.start]), ended early with [cancel]. */
internal interface MoveHandle {
    fun cancel()
}

/**
 * A move under way that can be given a new length and started again from its next frame, in place:
 * how a spring takes a new target without a new move (see [FuselineValue.retarget]). A length below
 * zero means the move's value knows it ([FrameDriver.Driven]).
 */
internal interface Retimer {
    fun retime(durationNanos: Long)

    /** Moves the play to [playNanos], as of the frame just shown. */
    fun seek(playNanos: Long)
}

/**
 * What the frames themselves say about the display and the load: the interval between frames as it
 * really is (30 Hz is about 33.3 ms, 120 Hz 8.3 ms, 240 Hz 4.2 ms; never assumed to be 60 Hz), and
 * whether frames are running late. Late frames are answered by thinning out decoration only (ambient
 * loops update every other frame), never by changing any motion's time: every value is always worked
 * out from the real frame time, so physics stays exactly the same however busy the device is, and
 * interaction, focus, navigation, gestures and transitions keep every frame.
 */
object FramePacing {
    private const val HISTORY = 32

    /** Frames at least this much longer than the display's interval count as late. */
    private const val LATE_RATIO = 1.5f

    /** Late frames in a row (of the history) before decoration is thinned, and on-time ones before it comes back. */
    private const val LATE_TO_THIN = 6
    private const val ON_TIME_TO_RESTORE = 30

    private class History {
        val intervals = LongArray(HISTORY)
        var shortest = Long.MAX_VALUE
        var count = 0
        var head = 0
        var late = 0
        var onTime = 0
        var intervalNanos = 16_666_667L
        var underLoad = false
        var decorationTick = 0L
        var decorationAt = Long.MIN_VALUE
        var lastFrame = Long.MIN_VALUE
        var moving = false

        fun frame(nanos: Long) {
            if (nanos <= 0L || nanos > 1_000_000_000L) return
            val evicted = if (count == HISTORY) intervals[head] else Long.MAX_VALUE
            intervals[head] = nanos
            head = (head + 1) % HISTORY
            if (count < HISTORY) count++
            if (nanos <= shortest || count == 1) shortest = nanos
            else if (evicted == shortest) {
                var best = Long.MAX_VALUE
                for (i in 0 until count) if (intervals[i] < best) best = intervals[i]
                shortest = best
            }
            intervalNanos = shortest
            if (nanos > intervalNanos * LATE_RATIO) {
                late++
                onTime = 0
                if (late >= LATE_TO_THIN) underLoad = true
            } else {
                onTime++
                if (onTime >= ON_TIME_TO_RESTORE) { underLoad = false; late = 0 }
            }
        }
    }

    private val defaultHistory = History()
    private var lastObserved = defaultHistory
    private val sources = arrayOfNulls<Any>(8)
    private val histories = arrayOfNulls<History>(8)

    private fun history(source: Any?): History {
        if (source == null) return defaultHistory
        var vacant = -1
        for (i in sources.indices) {
            if (sources[i] === source) return histories[i]!!
            if (sources[i] == null && vacant < 0) vacant = i
        }
        // A process normally has one or two clocks. A ninth replaces a retired slot, never mixes
        // its intervals with the old display. Storage remains bounded for window recreation.
        val slot = if (vacant >= 0) vacant else 0
        return History().also { sources[slot] = source; histories[slot] = it }
    }

    /** Latest clock's diagnostic estimate. Scheduling uses [intervalFor], never this shared view. */
    val intervalNanos: Long get() = lastObserved.intervalNanos
    val refreshRate: Float get() = (1e9 / intervalNanos).toFloat()
    val underLoad: Boolean get() = lastObserved.underLoad

    /** This clock's independent estimate, including its own pressure and recovery history. */
    fun intervalFor(source: Any?): Long = history(source).intervalNanos
    fun underLoadFor(source: Any?): Boolean = history(source).underLoad
    internal fun moving(source: Any?, moving: Boolean) { history(source).moving = moving }

    /** Counts each time once on its own clock; another display never changes its estimator. */
    fun frameAt(frameNanos: Long, source: Any? = null) {
        val h = history(source)
        lastObserved = h
        val before = h.lastFrame
        if (frameNanos == before) return
        h.lastFrame = frameNanos
        if (before != Long.MIN_VALUE && frameNanos > before) h.frame(frameNanos - before)
    }

    /** Records a direct/default-clock interval (tests and callers without a frame clock). */
    fun frame(nanos: Long) { lastObserved = defaultHistory; defaultHistory.frame(nanos) }

    /**
     * What the device says about itself, where it can tell (Android's thermal status, battery saver):
     * set by the platform. Under pressure decoration updates less often, always at its true time;
     * interaction, navigation, focus and transitions keep every frame and their exact physics.
     */
    var devicePressure: DevicePressure = DevicePressure.NONE

    /** Battery saver is on: decoration updates half as often. */
    var powerSaving: Boolean = false

    /**
     * Of decoration's updates, one in this many is kept for the device's sake: 1 normally, 2 while
     * it runs hot or battery saver is on, 3 while it is throttling.
     */
    val pressureEvery: Int
        get() = when {
            devicePressure >= DevicePressure.THROTTLED -> 3
            powerSaving || devicePressure >= DevicePressure.HOT -> 2
            else -> 1
        }

    /**
     * Whether a decorative loop should show this frame: always, unless frames are running late
     * (every other frame) or the device is under pressure ([pressureEvery]). Its value is still worked
     * out from the real time when it shows, so it never catches up: it is simply where it should be.
     */
    fun shouldDrawDecoration(source: Any? = null): Boolean {
        val h = if (source == null) lastObserved else history(source)
        decorationTick(h, if (source == null) null else h.lastFrame)
        val every = maxOf(if (h.underLoad) 2 else 1, pressureEvery)
        return every == 1 || h.decorationTick % every == 0L
    }

    /** [shouldDrawDecoration] for frames running late only (a paced loop counts the device's pressure in its own rate). */
    internal fun shouldDrawDecorationUnderLoad(source: Any? = null, atNanos: Long? = null): Boolean {
        val h = if (source == null) lastObserved else history(source)
        decorationTick(h, atNanos)
        return !h.underLoad || h.decorationTick % 2L == 0L
    }

    // Every loop on the same clock receives the same decision for the same frame. Counting calls
    // instead of frames can permanently starve one of two loops that always draw in the same order.
    private fun decorationTick(h: History, at: Long?) {
        if (at == null || h.decorationAt != at) {
            h.decorationTick++
            if (at != null) h.decorationAt = at
        }
    }

    /** How many frames decorative loops ([decorationFrames]) have woken for, for measuring. */
    var decorationWakeups: Long = 0L
        internal set

    /** Whether the frame driver had moves to step on its last frame. */
    internal var movesUnderWay = false

    private var lastInput = Long.MIN_VALUE

    /**
     * Input just arrived. Under measured or thermal pressure, decoration briefly holds while
     * interaction uses the available budget. Input on a healthy display does not freeze ambience.
     */
    fun input(nowNanos: Long = monotonicNanos()) {
        lastInput = nowNanos
    }

    /**
     * Whether decoration holds briefly after input while actual frame or thermal pressure exists.
     * Navigation and focus always continue; a healthy display preserves ambient motion.
     */
    fun decorationHeld(nowNanos: Long = monotonicNanos(), source: Any? = null): Boolean {
        val h = if (source == null) lastObserved else history(source)
        // Input alone is not evidence of frame pressure. Event horizons already let unread work
        // sleep; optional ambient motion only holds when measured/thermal pressure warrants it.
        if ((!h.underLoad && devicePressure < DevicePressure.HOT) || lastInput == Long.MIN_VALUE) return false
        val since = nowNanos - lastInput
        return since in 0 until INPUT_HOLD_NANOS || (since in 0 until INPUT_HOLD_MAX_NANOS && (h.moving || (source == null && movesUnderWay)))
    }

    /** How long decoration holds after the last input, and the longest it holds while things still move. */
    private const val INPUT_HOLD_NANOS = 300_000_000L
    private const val INPUT_HOLD_MAX_NANOS = 1_500_000_000L

    /** Forgets what it has measured (tests, and a display that changed). */
    fun reset() {
        lastInput = Long.MIN_VALUE
        movesUnderWay = false
        defaultHistory.intervals.fill(0)
        defaultHistory.count = 0; defaultHistory.head = 0; defaultHistory.late = 0; defaultHistory.onTime = 0
        defaultHistory.underLoad = false; defaultHistory.intervalNanos = 16_666_667L
        defaultHistory.shortest = Long.MAX_VALUE; defaultHistory.decorationTick = 0
        defaultHistory.lastFrame = Long.MIN_VALUE; defaultHistory.decorationAt = Long.MIN_VALUE; defaultHistory.moving = false
        lastObserved = defaultHistory
        devicePressure = DevicePressure.NONE
        powerSaving = false
        sources.fill(null)
        histories.fill(null)
    }
}

/** How much the device itself says it is struggling (its thermal state), from none to throttling. */
enum class DevicePressure { NONE, WARM, HOT, THROTTLED }
