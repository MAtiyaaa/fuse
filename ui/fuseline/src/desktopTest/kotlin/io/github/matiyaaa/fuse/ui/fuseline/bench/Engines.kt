@file:Suppress("UNCHECKED_CAST")

package io.github.matiyaaa.fuse.ui.fuseline.bench

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.spring as cSpring
import androidx.compose.animation.core.tween as cTween

/**
 * A motion described once, for every engine: each engine turns it into its own motion object
 * ([Engine.motion]) before a case starts, so no engine builds one per call.
 */
sealed interface Spec {
    data class Spring(val dampingRatio: Float, val stiffness: Float) : Spec
    data class Tween(val durationMs: Int, val curve: CurveId) : Spec
}

enum class CurveId { STANDARD, LINEAR }

/** A position in time handed to a velocity reading, without boxing it. */
fun interface VelocityAt {
    fun at(playNanos: Long): Float
}

/** A tab bar's five pages moving between states: [go] to a tab, [read] what a frame draws. */
interface Tabs {
    fun go(to: Int)
    fun read(): Float
}

/** A timeline of twenty keyframed tracks, played, sought and reversed through each engine's own player. */
interface Player {
    fun tick(frameNanos: Long)
    fun seek(ms: Int)
    fun reverse()
    fun read(): Float
}

/**
 * One animation engine, as the benchmark and the equivalence tests drive it. Every case is written
 * once against this, so each engine gets exactly the same values, motions, targets and frames, and
 * every call goes through the same interface dispatch. Each method uses the engine's own API, the
 * way Fuse (or an app on Compose) would: where an engine has a cheaper way of doing something (a
 * retarget in place, reading one component without building the value), it uses it; where it has
 * no way at all, the capability flag says so and the cell is "n/a".
 *
 * Values are handed around as [Any]: the cast back costs every engine the same.
 */
interface Engine {
    val name: String

    fun float(initial: Float): Any
    fun offset(x: Float, y: Float): Any
    fun color(c: Color): Any
    fun motion(spec: Spec): Any

    fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any)
    fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any)
    fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any)

    /** Gives a moving float value a new target the engine's own cheapest way. */
    fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any)
    fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any)

    fun snap(scope: CoroutineScope, v: Any, x: Float)

    /** What a frame that draws the value reads (a subscribing read, as drawing does). */
    fun read(v: Any): Float
    fun readOffset(v: Any): Float
    fun readColor(v: Any): Float

    val decays: Boolean get() = false
    fun decay(scope: CoroutineScope, v: Any, velocity: Float, friction: Float, threshold: Float): Unit = error("no decay")

    val drags: Boolean get() = false
    fun dragBy(scope: CoroutineScope, v: Any, delta: Float, timeNanos: Long): Unit = error("no drag")
    fun releaseVelocity(v: Any, timeNanos: Long): Float = error("no drag")

    val tweenVelocity: Boolean get() = true
    fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt

    fun tabs(scope: CoroutineScope): Tabs
    fun player(names: List<String>): Player? = null
    val seeks: Boolean get() = true
    val reverses: Boolean get() = true
}

/** The engines, newest first: the order of the table's columns. */
val ENGINES: List<Engine> = listOf(Fuseline4, Fuseline31, Fuseline3, Fuseline2, Fuseline1, ComposeEngine)

/** The five-tab emulation for engines without a transition of their own: ten values, each engine's own way. */
private class ValueTabs(private val e: Engine, private val scope: CoroutineScope, private val m: Any) : Tabs {
    private val presence = Array(5) { e.float(if (it == 0) 1f else 0f) }
    private val place = Array(5) { e.float(0f) }
    private var at = 0

    override fun go(to: Int) {
        val dir = if (to > at) -1f else 1f
        for (i in 0 until 5) {
            e.retarget(scope, presence[i], if (i == to) 1f else 0f, m)
            e.retarget(scope, place[i], if (i == to) 0f else dir, m)
        }
        at = to
    }

    override fun read(): Float {
        var s = 0f
        for (i in 0 until 5) s += e.read(presence[i]) + e.read(place[i])
        return s
    }
}

// ------------------------------------------------------------------------------------- Fuseline 4

object Fuseline4 : Engine {
    override val name = "Fuseline 4"
    private val standard = io.github.matiyaaa.fuse.ui.fuseline.CubicCurve(0.2f, 0f, 0f, 1f)
    private val enter = io.github.matiyaaa.fuse.ui.fuseline.CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    override fun float(initial: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.FuselineValue(initial)
    override fun offset(x: Float, y: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.FuselineValue(Offset(x, y))
    override fun color(c: Color): Any = io.github.matiyaaa.fuse.ui.fuseline.FuselineValue(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> io.github.matiyaaa.fuse.ui.fuseline.Spring(spec.dampingRatio, spec.stiffness)
        is Spec.Tween -> io.github.matiyaaa.fuse.ui.fuseline.Tween(spec.durationMs, curve = if (spec.curve == CurveId.STANDARD) standard else io.github.matiyaaa.fuse.ui.fuseline.Curves.Linear)
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.Motion) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Offset>).animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.Motion) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Color>).animateTo(c, m as io.github.matiyaaa.fuse.ui.fuseline.Motion) }
    }
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>
        if (!fv.retargetFloat(target, m as io.github.matiyaaa.fuse.ui.fuseline.Motion)) scope.launch { fv.animateTo(target, m) }
    }
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Offset>
        if (!fv.retargetXY(x, y, m as io.github.matiyaaa.fuse.ui.fuseline.Motion)) scope.launch { fv.animateTo(Offset(x, y), m) }
    }
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).floatValue
    override fun readOffset(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Offset>).let { it.component(0) + it.component(1) }
    override fun readColor(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Color>).value.red

    override val decays = true
    override fun decay(scope: CoroutineScope, v: Any, velocity: Float, friction: Float, threshold: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).animateDecay(velocity, io.github.matiyaaa.fuse.ui.fuseline.Decay(friction, threshold)) }
    }
    override val drags = true
    override fun dragBy(scope: CoroutineScope, v: Any, delta: Float, timeNanos: Long) = (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).dragBy(delta, timeNanos)
    override fun releaseVelocity(v: Any, timeNanos: Long): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.FuselineValue<Float>).releaseVelocity(timeNanos)

    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt {
        val t = io.github.matiyaaa.fuse.ui.fuseline.TweenTrack(io.github.matiyaaa.fuse.ui.fuseline.Tween(durationMs, curve = standard), 0f, distance, 0f)
        return VelocityAt { t.velocityAt(it) }
    }

    override fun tabs(scope: CoroutineScope): Tabs = object : Tabs {
        val t = io.github.matiyaaa.fuse.ui.fuseline.MotionTransition(0, order = { it })
        val m = io.github.matiyaaa.fuse.ui.fuseline.Spring(1f, 900f)
        override fun go(to: Int) = t.go(to, scope, m)
        override fun read(): Float {
            var s = 0f
            for (p in t.parts) s += p.presenceValue + p.position
            return s
        }
    }

    override fun player(names: List<String>): Player {
        val tl = io.github.matiyaaa.fuse.ui.fuseline.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, enter); at(4000, 0.5f, standard) } }
        val p = io.github.matiyaaa.fuse.ui.fuseline.TimelinePlayer(tl)
        return object : Player {
            override fun tick(frameNanos: Long) { p.tick(frameNanos, 1f) }
            override fun seek(ms: Int) = p.seek(ms)
            override fun reverse() = p.reverse()
            override fun read(): Float {
                var s = 0f
                for (n in names) s += p[n]
                return s
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Fuseline 3.1

object Fuseline31 : Engine {
    override val name = "Fuseline 3.1"
    private val standard = io.github.matiyaaa.fuse.ui.fuseline.v31.CubicCurve(0.2f, 0f, 0f, 1f)
    private val enter = io.github.matiyaaa.fuse.ui.fuseline.v31.CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    override fun float(initial: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(initial)
    override fun offset(x: Float, y: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(Offset(x, y))
    override fun color(c: Color): Any = io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> io.github.matiyaaa.fuse.ui.fuseline.v31.Spring(spec.dampingRatio, spec.stiffness)
        is Spec.Tween -> io.github.matiyaaa.fuse.ui.fuseline.v31.Tween(spec.durationMs, curve = if (spec.curve == CurveId.STANDARD) standard else io.github.matiyaaa.fuse.ui.fuseline.v31.Curves.Linear)
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.v31.Motion) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Offset>).animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.v31.Motion) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Color>).animateTo(c, m as io.github.matiyaaa.fuse.ui.fuseline.v31.Motion) }
    }
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>
        if (!fv.retargetFloat(target, m as io.github.matiyaaa.fuse.ui.fuseline.v31.Motion)) scope.launch { fv.animateTo(target, m) }
    }
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Offset>
        if (!fv.retargetXY(x, y, m as io.github.matiyaaa.fuse.ui.fuseline.v31.Motion)) scope.launch { fv.animateTo(Offset(x, y), m) }
    }
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).floatValue
    override fun readOffset(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Offset>).let { it.component(0) + it.component(1) }
    override fun readColor(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Color>).value.red

    override val decays = true
    override fun decay(scope: CoroutineScope, v: Any, velocity: Float, friction: Float, threshold: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).animateDecay(velocity, io.github.matiyaaa.fuse.ui.fuseline.v31.Decay(friction, threshold)) }
    }
    override val drags = true
    override fun dragBy(scope: CoroutineScope, v: Any, delta: Float, timeNanos: Long) = (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).dragBy(delta, timeNanos)
    override fun releaseVelocity(v: Any, timeNanos: Long): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v31.FuselineValue<Float>).releaseVelocity(timeNanos)

    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt {
        val t = io.github.matiyaaa.fuse.ui.fuseline.v31.TweenTrack(io.github.matiyaaa.fuse.ui.fuseline.v31.Tween(durationMs, curve = standard), 0f, distance, 0f)
        return VelocityAt { t.velocityAt(it) }
    }

    override fun tabs(scope: CoroutineScope): Tabs = object : Tabs {
        val t = io.github.matiyaaa.fuse.ui.fuseline.v31.MotionTransition(0, order = { it })
        val m = io.github.matiyaaa.fuse.ui.fuseline.v31.Spring(1f, 900f)
        override fun go(to: Int) = t.go(to, scope, m)
        override fun read(): Float {
            var s = 0f
            for (p in t.parts) s += p.presenceValue + p.position
            return s
        }
    }

    override fun player(names: List<String>): Player {
        val tl = io.github.matiyaaa.fuse.ui.fuseline.v31.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, enter); at(4000, 0.5f, standard) } }
        val p = io.github.matiyaaa.fuse.ui.fuseline.v31.TimelinePlayer(tl)
        return object : Player {
            override fun tick(frameNanos: Long) { p.tick(frameNanos, 1f) }
            override fun seek(ms: Int) = p.seek(ms)
            override fun reverse() = p.reverse()
            override fun read(): Float {
                var s = 0f
                for (n in names) s += p[n]
                return s
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Fuseline 3

object Fuseline3 : Engine {
    override val name = "Fuseline 3"
    private val standard = io.github.matiyaaa.fuse.ui.fuseline.v3.CubicCurve(0.2f, 0f, 0f, 1f)
    private val enter = io.github.matiyaaa.fuse.ui.fuseline.v3.CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    override fun float(initial: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue(initial)
    override fun offset(x: Float, y: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue(Offset(x, y))
    override fun color(c: Color): Any = io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(spec.dampingRatio, spec.stiffness)
        is Spec.Tween -> io.github.matiyaaa.fuse.ui.fuseline.v3.Tween(spec.durationMs, curve = if (spec.curve == CurveId.STANDARD) standard else io.github.matiyaaa.fuse.ui.fuseline.v3.Curves.Linear)
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.v3.Motion) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Offset>).animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.v3.Motion) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Color>).animateTo(c, m as io.github.matiyaaa.fuse.ui.fuseline.v3.Motion) }
    }
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>
        if (!fv.retargetFloat(target, m as io.github.matiyaaa.fuse.ui.fuseline.v3.Motion)) scope.launch { fv.animateTo(target, m) }
    }
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Offset>
        if (!fv.retargetXY(x, y, m as io.github.matiyaaa.fuse.ui.fuseline.v3.Motion)) scope.launch { fv.animateTo(Offset(x, y), m) }
    }
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).floatValue
    override fun readOffset(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Offset>).let { it.component(0) + it.component(1) }
    override fun readColor(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Color>).value.red

    override val decays = true
    override fun decay(scope: CoroutineScope, v: Any, velocity: Float, friction: Float, threshold: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).animateDecay(velocity, io.github.matiyaaa.fuse.ui.fuseline.v3.Decay(friction, threshold)) }
    }
    override val drags = true
    override fun dragBy(scope: CoroutineScope, v: Any, delta: Float, timeNanos: Long) = (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).dragBy(delta, timeNanos)
    override fun releaseVelocity(v: Any, timeNanos: Long): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v3.FuselineValue<Float>).releaseVelocity(timeNanos)

    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt {
        val t = io.github.matiyaaa.fuse.ui.fuseline.v3.TweenTrack(io.github.matiyaaa.fuse.ui.fuseline.v3.Tween(durationMs, curve = standard), 0f, distance, 0f)
        return VelocityAt { t.velocityAt(it) }
    }

    override fun tabs(scope: CoroutineScope): Tabs = object : Tabs {
        val t = io.github.matiyaaa.fuse.ui.fuseline.v3.MotionTransition(0, order = { it })
        val m = io.github.matiyaaa.fuse.ui.fuseline.v3.Spring(1f, 900f)
        override fun go(to: Int) = t.go(to, scope, m)
        override fun read(): Float {
            var s = 0f
            for (p in t.parts) s += p.presenceValue + p.position
            return s
        }
    }

    override fun player(names: List<String>): Player {
        val tl = io.github.matiyaaa.fuse.ui.fuseline.v3.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, enter); at(4000, 0.5f, standard) } }
        val p = io.github.matiyaaa.fuse.ui.fuseline.v3.TimelinePlayer(tl)
        return object : Player {
            override fun tick(frameNanos: Long) { p.tick(frameNanos, 1f) }
            override fun seek(ms: Int) = p.seek(ms)
            override fun reverse() = p.reverse()
            override fun read(): Float {
                var s = 0f
                for (n in names) s += p[n]
                return s
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Fuseline 2

object Fuseline2 : Engine {
    override val name = "Fuseline 2"
    private val standard = io.github.matiyaaa.fuse.ui.fuseline.v2.CubicCurve(0.2f, 0f, 0f, 1f)
    private val enter = io.github.matiyaaa.fuse.ui.fuseline.v2.CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    override fun float(initial: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue(initial)
    override fun offset(x: Float, y: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue(Offset(x, y))
    override fun color(c: Color): Any = io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> io.github.matiyaaa.fuse.ui.fuseline.v2.Spring(spec.dampingRatio, spec.stiffness)
        is Spec.Tween -> io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(spec.durationMs, curve = if (spec.curve == CurveId.STANDARD) standard else io.github.matiyaaa.fuse.ui.fuseline.v2.Curves.Linear)
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Float>).animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.v2.Motion) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Offset>).animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.v2.Motion) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Color>).animateTo(c, m as io.github.matiyaaa.fuse.ui.fuseline.v2.Motion) }
    }
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Float>
        // Fuseline 2 retargets a spring in place; anything else is a new move.
        if (m !is io.github.matiyaaa.fuse.ui.fuseline.v2.Spring || !fv.retarget(target, m)) scope.launch { fv.animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.v2.Motion) }
    }
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        val fv = v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Offset>
        if (m !is io.github.matiyaaa.fuse.ui.fuseline.v2.Spring || !fv.retarget(Offset(x, y), m)) scope.launch { fv.animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.v2.Motion) }
    }
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Float>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Float>).value
    override fun readOffset(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Offset>).value.let { it.x + it.y }
    override fun readColor(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v2.FuselineValue<Color>).value.red

    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt {
        val t = io.github.matiyaaa.fuse.ui.fuseline.v2.TweenTrack(io.github.matiyaaa.fuse.ui.fuseline.v2.Tween(durationMs, curve = standard), 0f, distance)
        return VelocityAt { t.velocityAt(it) }
    }

    override fun tabs(scope: CoroutineScope): Tabs = ValueTabs(this, scope, motion(Spec.Spring(1f, 900f)))

    override fun player(names: List<String>): Player {
        val tl = io.github.matiyaaa.fuse.ui.fuseline.v2.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, enter); at(4000, 0.5f, standard) } }
        val p = io.github.matiyaaa.fuse.ui.fuseline.v2.TimelinePlayer(tl)
        return object : Player {
            override fun tick(frameNanos: Long) { p.tick(frameNanos, 1f) }
            override fun seek(ms: Int) = p.seek(ms)
            override fun reverse() = error("no reverse")
            override fun read(): Float {
                var s = 0f
                for (n in names) s += p[n]
                return s
            }
        }
    }

    override val reverses = false
}

// ------------------------------------------------------------------------------------- Fuseline 1

object Fuseline1 : Engine {
    override val name = "Fuseline 1"
    private val standard = io.github.matiyaaa.fuse.ui.fuseline.v1.CubicCurve(0.2f, 0f, 0f, 1f)
    private val enter = io.github.matiyaaa.fuse.ui.fuseline.v1.CubicCurve(0.05f, 0.7f, 0.1f, 1f)

    override fun float(initial: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue(initial)
    override fun offset(x: Float, y: Float): Any = io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue(Offset(x, y))
    override fun color(c: Color): Any = io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> io.github.matiyaaa.fuse.ui.fuseline.v1.Spring(spec.dampingRatio, spec.stiffness)
        is Spec.Tween -> io.github.matiyaaa.fuse.ui.fuseline.v1.Tween(spec.durationMs, curve = if (spec.curve == CurveId.STANDARD) standard else io.github.matiyaaa.fuse.ui.fuseline.v1.Curves.Linear)
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Float>).animateTo(target, m as io.github.matiyaaa.fuse.ui.fuseline.v1.Motion) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Offset>).animateTo(Offset(x, y), m as io.github.matiyaaa.fuse.ui.fuseline.v1.Motion) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Color>).animateTo(c, m as io.github.matiyaaa.fuse.ui.fuseline.v1.Motion) }
    }
    // Fuseline 1 has no retarget: a new target is a new move, its way.
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) = animate(scope, v, target, m)
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) = animateOffset(scope, v, x, y, m)
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Float>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Float>).value
    override fun readOffset(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Offset>).value.let { it.x + it.y }
    override fun readColor(v: Any): Float = (v as io.github.matiyaaa.fuse.ui.fuseline.v1.FuselineValue<Color>).value.red

    override val tweenVelocity = false
    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt = error("Fuseline 1 has no tween velocity")

    override fun tabs(scope: CoroutineScope): Tabs = ValueTabs(this, scope, motion(Spec.Spring(1f, 900f)))

    override fun player(names: List<String>): Player {
        val tl = io.github.matiyaaa.fuse.ui.fuseline.v1.Timeline(4000) { for (n in names) track(n) { at(0, 0f); at(1500, 1f, enter); at(4000, 0.5f, standard) } }
        val p = io.github.matiyaaa.fuse.ui.fuseline.v1.TimelinePlayer(tl)
        return object : Player {
            override fun tick(frameNanos: Long) { p.tick(frameNanos, 1f) }
            override fun seek(ms: Int) = p.seek(ms)
            override fun reverse() = error("no reverse")
            override fun read(): Float {
                var s = 0f
                for (n in names) s += p[n]
                return s
            }
        }
    }

    override val reverses = false
}

// ------------------------------------------------------------------------------------- Compose

/** Compose's own animation engine (Animatable), driven the way an app on Compose would drive it. */
object ComposeEngine : Engine {
    override val name = "Compose"
    private val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    private class Specs(val f: AnimationSpec<Float>, val o: AnimationSpec<Offset>, val c: AnimationSpec<Color>)

    override fun float(initial: Float): Any = Animatable(initial)
    override fun offset(x: Float, y: Float): Any = Animatable(Offset(x, y), Offset.VectorConverter)
    override fun color(c: Color): Any = Animatable(c)
    override fun motion(spec: Spec): Any = when (spec) {
        is Spec.Spring -> Specs(cSpring(spec.dampingRatio, spec.stiffness), cSpring(spec.dampingRatio, spec.stiffness), cSpring(spec.dampingRatio, spec.stiffness))
        is Spec.Tween -> {
            val easing = if (spec.curve == CurveId.STANDARD) standard else LinearEasing
            Specs(cTween(spec.durationMs, easing = easing), cTween(spec.durationMs, easing = easing), cTween(spec.durationMs, easing = easing))
        }
    }

    override fun animate(scope: CoroutineScope, v: Any, target: Float, m: Any) {
        scope.launch { (v as Animatable<Float, AnimationVector1D>).animateTo(target, (m as Specs).f) }
    }
    override fun animateOffset(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) {
        scope.launch { (v as Animatable<Offset, AnimationVector2D>).animateTo(Offset(x, y), (m as Specs).o) }
    }
    override fun animateColor(scope: CoroutineScope, v: Any, c: Color, m: Any) {
        scope.launch { (v as Animatable<Color, *>).animateTo(c, (m as Specs).c) }
    }
    // Compose has no retarget: a new target is a new animateTo, which takes over from the one under way.
    override fun retarget(scope: CoroutineScope, v: Any, target: Float, m: Any) = animate(scope, v, target, m)
    override fun retargetXY(scope: CoroutineScope, v: Any, x: Float, y: Float, m: Any) = animateOffset(scope, v, x, y, m)
    override fun snap(scope: CoroutineScope, v: Any, x: Float) {
        scope.launch { (v as Animatable<Float, AnimationVector1D>).snapTo(x) }
    }
    override fun read(v: Any): Float = (v as Animatable<Float, AnimationVector1D>).value
    override fun readOffset(v: Any): Float = (v as Animatable<Offset, AnimationVector2D>).value.let { it.x + it.y }
    override fun readColor(v: Any): Float = (v as Animatable<Color, *>).value.red

    override val decays = true
    override fun decay(scope: CoroutineScope, v: Any, velocity: Float, friction: Float, threshold: Float) {
        // Compose's exponential decay takes the friction as a multiplier of its own base (4.2 per second).
        scope.launch { (v as Animatable<Float, AnimationVector1D>).animateDecay(velocity, exponentialDecay(friction / 4.2f, 0.1f)) }
    }

    // Compose follows a finger by snapping the value and tracking the velocity beside it.
    private val trackers = HashMap<Any, VelocityTracker1D>()
    override val drags = true
    override fun dragBy(scope: CoroutineScope, v: Any, delta: Float, timeNanos: Long) {
        val a = v as Animatable<Float, AnimationVector1D>
        val x = a.value + delta
        scope.launch { a.snapTo(x) }
        trackers.getOrPut(v) { VelocityTracker1D(false) }.addDataPoint(timeNanos / 1_000_000L, x)
    }
    override fun releaseVelocity(v: Any, timeNanos: Long): Float = trackers[v]?.calculateVelocity() ?: 0f

    override fun tweenVelocity(durationMs: Int, distance: Float): VelocityAt {
        val spec = cTween<Float>(durationMs, easing = standard).vectorize(Float.VectorConverter)
        val a = AnimationVector1D(0f)
        val b = AnimationVector1D(distance)
        val v = AnimationVector1D(0f)
        return VelocityAt { spec.getVelocityFromNanos(it, a, b, v).value }
    }

    override fun tabs(scope: CoroutineScope): Tabs = ValueTabs(this, scope, motion(Spec.Spring(1f, 900f)))

    override fun player(names: List<String>): Player {
        // Compose has no timeline: twenty keyframe animations, one per track, started together.
        val vs = List(names.size) { Animatable(0f) }
        var started = false
        return object : Player {
            override fun tick(frameNanos: Long) {
                if (started) return
                started = true
                // Launched on the first frame by the benchmark's own scope (see [ComposeTimeline]).
            }
            override fun seek(ms: Int) = error("no seek")
            override fun reverse() = error("no reverse")
            override fun read(): Float {
                var s = 0f
                for (v in vs) s += v.value
                return s
            }
        }.also { pendingTimeline = vs }
    }

    /** The keyframe animations the last [player] made, for the benchmark to launch in its scope. */
    var pendingTimeline: List<Animatable<Float, AnimationVector1D>>? = null

    fun startTimeline(scope: CoroutineScope) {
        val vs = pendingTimeline ?: return
        pendingTimeline = null
        vs.forEach { v -> scope.launch { v.animateTo(0.5f, keyframes { durationMillis = 4000; 0f at 0; 1f at 1500 using enter }) } }
    }

    override val seeks = false
    override val reverses = false

    fun forgetTrackers() = trackers.clear()
}
