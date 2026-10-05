package io.github.matiyaaa.fuse.ui.designsystem.background

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Which screen a Fusi room is drawn on. [OWN] is a room of its own, with its own Fusi (a preview,
 * a theme card, any single window). [TOP] and [BOTTOM] are a two-screen device's screens, which
 * share one Fusi: she lives on one of them at a time, and now and then hops from one to the other.
 */
enum class FusiScreen { OWN, TOP, BOTTOM }

/** The screen the rooms drawn below this point are on (see [FusiScreen]). */
val LocalFusiScreen = staticCompositionLocalOf { FusiScreen.OWN }

/** Where the ground is, shared by the room that draws it and the Fusi who walks on it. */
internal object FusiGround {
    /** How far down the screen the ground lies, as a share of its height. */
    const val BASE = 0.84f

    /** The room's scale on a canvas [w] by [h] px: 1 on a screen, down to 0.45 on a small card. */
    fun fit(w: Float, h: Float, density: Float): Float = (minOf(w, h) / (480f * density)).coerceIn(0.45f, 1f)

    /** The size of one of Fusi's pixels, in whole device pixels so the art stays crisp. */
    fun cell(w: Float, h: Float, density: Float): Float = (4f * density * fit(w, h, density)).roundToInt().coerceAtLeast(2).toFloat()

    /** The ground's height at [x], in px: a gentle roll across the room. */
    fun y(x: Float, w: Float, h: Float, density: Float): Float =
        h * BASE + sin(x / w * 7.5f + 0.6f) * 7f * density * fit(w, h, density)

    /** Where the doghouse and the bowl stand, as shares of the width. */
    const val HOUSE_X = 0.1f
    const val BOWL_X = 0.88f
}

/**
 * Fusi, the dog in the Fusi theme, and everything she does: she wanders, runs about, sits and
 * wags, sniffs the flowers, naps by her house, chases her ball and hops for joy. On a two-screen
 * device (both screens drawing a Fusi room) she sometimes jumps down to the screen below, or leaps
 * up to the one above.
 *
 * Time comes from a monotonic clock, so however many rooms draw her she lives at one pace; a room
 * drawn still (reduced motion) shows her as she is without moving her. Nothing here allocates per frame.
 */
internal class FusiWorld(
    /** Seconds since some fixed moment; a monotonic clock unless a test drives her. */
    private val clockNow: (() -> Float)? = null,
) {
    private enum class Act { IDLE, WALK, RUN, SIT, SNIFF, SLEEP, PLAY, HOP, CROSS }

    // The screens that draw her: 0 is the main (or only) one, 1 the screen below.
    private val seen = FloatArray(2) { -100f }
    private val sw = FloatArray(2)
    private val sh = FloatArray(2)
    private val sd = FloatArray(2) { 1f }

    private val clock = TimeSource.Monotonic.markNow()
    private var last = -1f
    private val rnd = Random(20_240_611)

    private var screen = 0
    private var x = 0.5f
    private var facing = 1
    private var act = Act.SIT
    private var actT = 0f
    private var actDur = 4f
    private var target = 0.5f
    private var phase = 0
    private var moving = false
    private var anim = 0f

    // Height above the ground in dp (negative is up), and speed; [ground] off while she falls between screens.
    private var yOff = 0f
    private var vy = 0f
    private var airborne = false
    private var ground = true

    private var blinkIn = 2.5f
    private var blinking = 0f
    private var sinceSleep = 0f
    private var kicks = 0
    private var maxKicks = 3

    private var ballScreen = 0
    private var ballX = 0.68f
    private var ballVx = 0f
    private var ballY = 0f
    private var ballVy = 0f

    private val heartX = FloatArray(HEARTS)
    private val heartY = FloatArray(HEARTS)
    private val heartLife = FloatArray(HEARTS)
    private val heartScreen = IntArray(HEARTS)
    private var nextHeart = 0

    private val dustX = FloatArray(DUST)
    private val dustLife = FloatArray(DUST)
    private val dustScreen = IntArray(DUST)
    private var nextDust = 0

    private fun now(): Float = clockNow?.invoke() ?: (clock.elapsedNow().inWholeMicroseconds / 1_000_000f)

    /** Which screen she is on (0 the main one, 1 the one below) and where across it, for tests. */
    internal val onScreen: Int get() = screen
    internal val across: Float get() = x

    private fun present(i: Int, t: Float) = sw[i] > 0f && t - seen[i] < 1.5f

    /**
     * A room on screen [index] ([w] by [h] px at [density]) is about to draw her: notes it is there,
     * and unless [still], moves her on to now.
     */
    fun frame(index: Int, w: Float, h: Float, density: Float, still: Boolean) {
        val t = now()
        seen[index] = t
        sw[index] = w
        sh[index] = h
        sd[index] = density
        if (still) return
        if (last < 0f) last = t
        val dt = (t - last).coerceIn(0f, 0.1f)
        last = t
        if (dt <= 0f) return
        step(dt, t)
    }

    private fun step(dt: Float, t: Float) {
        // Her screen went away (the screen below was turned off): she walks in on the other one.
        if (!present(screen, t)) {
            val other = 1 - screen
            if (!present(other, t)) return
            screen = other
            x = -0.08f
            yOff = 0f
            vy = 0f
            airborne = false
            ground = true
            start(Act.WALK, 0.3f)
        }
        val w = sw[screen]
        val perDp = 1f / (w / sd[screen])
        anim += dt
        actT += dt
        sinceSleep += dt
        moving = false

        // Blinks, every few seconds.
        if (blinking > 0f) blinking -= dt else {
            blinkIn -= dt
            if (blinkIn <= 0f) {
                blinking = 0.14f
                blinkIn = 2f + rnd.nextFloat() * 4f
            }
        }

        when (act) {
            Act.IDLE, Act.SIT, Act.SNIFF -> {
                if (act == Act.IDLE && rnd.nextFloat() < dt * 0.06f) heart(x, 46f)
                if (actT >= actDur) next(t)
            }
            Act.WALK -> if (moveTo(target, 52f * perDp, dt)) next(t)
            Act.RUN -> if (moveTo(target, 175f * perDp, dt)) {
                if (rnd.nextFloat() < 0.4f) start(Act.HOP) else next(t)
            }
            Act.SLEEP -> when (phase) {
                0 -> if (moveTo(FusiGround.HOUSE_X + 0.085f, 52f * perDp, dt)) {
                    phase = 1
                    actT = 0f
                    actDur = 8f + rnd.nextFloat() * 6f
                    facing = 1
                }
                1 -> if (actT >= actDur) {
                    sinceSleep = 0f
                    start(Act.IDLE)
                    actDur = 1.6f
                }
            }
            Act.PLAY -> {
                val near = abs(ballX - x) < 46f * perDp
                if (near && abs(ballVx) < 50f && ballY >= -1f) {
                    if (kicks >= maxKicks) {
                        start(Act.HOP)
                    } else {
                        // A nudge with her nose: the ball rolls away and she is after it.
                        val dir = if (ballX >= x) 1 else -1
                        ballVx = dir * (240f + rnd.nextFloat() * 140f)
                        ballVy = -(140f + rnd.nextFloat() * 120f)
                        kicks++
                    }
                } else {
                    moveTo(ballX, 175f * perDp, dt)
                }
                if (actT > 16f) start(Act.SIT)
            }
            Act.HOP -> if (!airborne && actT > 0.6f) next(t)
            Act.CROSS -> cross(dt, t, perDp)
        }

        // Gravity, and the ground underfoot (unless she is between screens).
        if (airborne) {
            vy += GRAVITY * dt
            yOff += vy * dt
            if (ground && vy > 0f && yOff >= 0f) {
                yOff = 0f
                vy = 0f
                airborne = false
                landed()
            }
        }
        stepBall(dt, perDp)
        for (i in 0 until HEARTS) if (heartLife[i] > 0f) {
            heartLife[i] -= dt / 1.8f
            heartY[i] += 34f * dt
        }
        for (i in 0 until DUST) if (dustLife[i] > 0f) dustLife[i] -= dt / 0.5f
    }

    /** Walks or runs toward [to]; true once there. */
    private fun moveTo(to: Float, speed: Float, dt: Float): Boolean {
        val d = to - x
        if (abs(d) < 0.004f) return true
        facing = if (d > 0f) 1 else -1
        val stepX = speed * dt
        x = if (abs(d) <= stepX) to else x + facing * stepX
        moving = true
        return false
    }

    private fun stepBall(dt: Float, perDp: Float) {
        if (ballScreen != screen && act == Act.PLAY) return
        val bw = sw[ballScreen]
        val bPerDp = if (bw > 0f) 1f / (bw / sd[ballScreen]) else perDp
        if (abs(ballVx) > 1f) {
            ballX += ballVx * bPerDp * dt
            ballVx *= exp(-1.7f * dt)
            if (ballX < 0.04f && ballVx < 0f) { ballX = 0.04f; ballVx = -ballVx * 0.6f }
            if (ballX > 0.96f && ballVx > 0f) { ballX = 0.96f; ballVx = -ballVx * 0.6f }
            if (abs(ballVx) < 6f) ballVx = 0f
        }
        if (ballY < 0f || ballVy < 0f) {
            ballVy += GRAVITY * dt
            ballY += ballVy * dt
            if (ballY >= 0f) {
                ballY = 0f
                ballVy = if (ballVy > 120f) -ballVy * 0.42f else 0f
            }
        }
    }

    private fun cross(dt: Float, t: Float, perDp: Float) {
        val both = present(0, t) && present(1, t)
        when (phase) {
            0 -> if (moveTo(target, 52f * perDp, dt)) { phase = 1; actT = 0f }
            // A crouch, then the jump: a small hop off the bottom edge going down, a big leap going up.
            1 -> if (actT > 0.4f) {
                if (!both) { start(Act.IDLE); return }
                phase = 2
                airborne = true
                ground = false
                vy = if (screen == 0) -230f else -sqrt(2f * GRAVITY * (groundDp(screen) + SPRITE_DP + 40f))
            }
            2 -> {
                val leaving = if (screen == 0) yOff > (sh[0] / sd[0] - groundDp(0)) + SPRITE_DP else yOff < -(groundDp(1) + SPRITE_DP + 10f)
                if (leaving) {
                    if (!both) {
                        // The other screen went away mid-leap: she comes back down where she was.
                        ground = true
                        yOff = -(groundDp(screen) + SPRITE_DP)
                        vy = 0f
                        phase = 3
                        return
                    }
                    if (screen == 0) {
                        screen = 1
                        yOff = -(groundDp(1) + SPRITE_DP + 10f)
                        vy = 60f
                    } else {
                        screen = 0
                        yOff = (sh[0] / sd[0] - groundDp(0)) + SPRITE_DP
                        vy = -sqrt(2f * GRAVITY * (yOff + 60f))
                    }
                    ground = true
                    phase = 3
                }
            }
            3 -> if (!airborne) start(Act.IDLE)
        }
    }

    private fun groundDp(i: Int): Float = if (sw[i] > 0f) FusiGround.y(x * sw[i], sw[i], sh[i], sd[i]) / sd[i] else 0f

    private fun landed() {
        for (k in 0 until 4) dust(x)
        if (act == Act.HOP || act == Act.CROSS) {
            heart(x - 0.01f, 54f)
            heart(x + 0.012f, 64f)
            if (act == Act.HOP) heart(x, 76f)
        }
    }

    private fun heart(at: Float, height: Float) {
        val i = nextHeart
        nextHeart = (nextHeart + 1) % HEARTS
        heartX[i] = at + (rnd.nextFloat() - 0.5f) * 0.02f
        heartY[i] = height
        heartLife[i] = 1f
        heartScreen[i] = screen
    }

    private fun dust(at: Float) {
        val i = nextDust
        nextDust = (nextDust + 1) % DUST
        dustX[i] = at + (rnd.nextFloat() - 0.5f) * 0.05f
        dustLife[i] = 1f
        dustScreen[i] = screen
    }

    private fun start(a: Act, to: Float = target) {
        act = a
        actT = 0f
        phase = 0
        target = to
        when (a) {
            Act.IDLE -> actDur = 1.2f + rnd.nextFloat() * 2f
            Act.SIT -> actDur = 3f + rnd.nextFloat() * 4f
            Act.SNIFF -> actDur = 1.5f + rnd.nextFloat() * 1.6f
            Act.HOP -> {
                airborne = true
                ground = true
                vy = -330f
            }
            Act.PLAY -> {
                kicks = 0
                maxKicks = 3 + rnd.nextInt(3)
                if (ballScreen != screen) {
                    // Her ball stays where she left it; on this screen she finds it by the bowl.
                    ballScreen = screen
                    ballX = 0.72f
                    ballY = 0f
                    ballVx = 0f
                }
            }
            else -> Unit
        }
    }

    /** What she does next: mostly wandering, sitting and sniffing, now and then a game or a nap. */
    private fun next(t: Float) {
        val both = present(0, t) && present(1, t)
        val crossW = if (both) 12f else 0f
        val sleepW = if (sinceSleep > 60f) 6f else 0f
        val total = 20f + 22f + 11f + 14f + 10f + 10f + 6f + sleepW + crossW
        var r = rnd.nextFloat() * total
        fun take(weight: Float): Boolean {
            r -= weight
            return r < 0f
        }
        when {
            take(20f) -> start(Act.IDLE)
            take(22f) -> start(Act.WALK, pick(0.14f, 0.86f, 0.12f))
            take(11f) -> start(Act.RUN, if (x < 0.5f) 0.7f + rnd.nextFloat() * 0.22f else 0.08f + rnd.nextFloat() * 0.22f)
            take(14f) -> start(Act.SIT)
            take(10f) -> start(Act.SNIFF)
            take(10f) -> start(Act.PLAY)
            take(6f) -> start(Act.HOP)
            take(sleepW) -> start(Act.SLEEP)
            else -> start(Act.CROSS, 0.25f + rnd.nextFloat() * 0.5f)
        }
    }

    /** A spot between [from] and [to] at least [away] from where she is. */
    private fun pick(from: Float, to: Float, away: Float): Float {
        repeat(4) {
            val p = from + rnd.nextFloat() * (to - from)
            if (abs(p - x) >= away) return p
        }
        return if (x < 0.5f) to else from
    }

    /** Draws her (and her ball, hearts and dust) on screen [index], if she is there. */
    fun draw(scope: DrawScope, index: Int, dark: Boolean): Unit = with(scope) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return
        val cell = FusiGround.cell(w, h, density)
        val palette = if (dark) FusiPalette.night else FusiPalette.day
        val shadow = if (dark) Color(0x55000000) else Color(0x296A3F5C)

        // Her ball, resting or rolling.
        if (ballScreen == index) {
            val bx = ballX * w
            val gy = FusiGround.y(bx, w, h, density)
            val ball = FusiFrames.ball
            val lift = (-ballY).coerceAtLeast(0f) * density
            drawOval(shadow, Offset(bx - 3f * cell, gy - cell * 0.6f), Size(6f * cell, cell * 1.4f))
            with(ball) { draw(snap(bx - 3f * cell), snap(gy + cell - lift), cell, false, palette) }
        }

        // Dust from a landing.
        for (i in 0 until DUST) if (dustLife[i] > 0f && dustScreen[i] == index) {
            val life = dustLife[i]
            val dx = dustX[i] * w
            val gy = FusiGround.y(dx, w, h, density)
            drawCircle(Color.White, (1.4f - life) * 3.2f * cell, Offset(dx, gy - cell), alpha = life * 0.75f)
        }

        if (screen == index) {
            val px = x * w
            val gy = FusiGround.y(px, w, h, density)
            val lift = (-yOff).coerceAtLeast(0f)
            val shrink = (1f - lift / 140f).coerceIn(0.3f, 1f)
            if (yOff > -200f && yOff < 40f) {
                drawOval(shadow, Offset(px - 10f * cell * shrink, gy - cell * 0.7f), Size(20f * cell * shrink, cell * 1.6f), alpha = shrink)
            }
            val sprite = sprite()
            // Every frame stands on its row 21, two rows above the canvas's foot.
            val bottom = snap(gy + 2f * cell + yOff * density)
            with(sprite) { draw(snap(px - 15f * cell), bottom, cell, facing < 0, palette) }

            // Asleep: little z's drifting up from her head.
            if (act == Act.SLEEP && phase == 1) {
                val z = FusiFrames.z
                for (k in 0 until 3) {
                    val p = ((anim * 0.45f + k / 3f) % 1f)
                    val zx = px + (8f + p * 10f) * cell * (if (facing < 0) -1 else 1) + sin(p * PI.toFloat() * 2f) * cell
                    val zy = gy - (9f + p * 14f) * cell
                    with(z) { draw(snap(zx), snap(zy), (cell * 0.75f).coerceAtLeast(1f), false, palette, alpha = sin(p * PI.toFloat()).coerceAtLeast(0f)) }
                }
            }
        }

        // Hearts floating up and fading.
        val heart = FusiFrames.heart
        val hc = (cell * 0.75f).roundToInt().coerceAtLeast(1).toFloat()
        for (i in 0 until HEARTS) if (heartLife[i] > 0f && heartScreen[i] == index) {
            val hx = heartX[i] * w + sin(heartY[i] * 0.08f) * 2f * cell
            val hy = FusiGround.y(hx, w, h, density) - heartY[i] * density
            with(heart) { draw(snap(hx - 3.5f * hc), snap(hy), hc, false, palette, alpha = heartLife[i].coerceIn(0f, 1f)) }
        }
    }

    private fun sprite(): PixelSprite {
        val f = FusiFrames
        val blink = blinking > 0f
        return when (act) {
            Act.IDLE -> if (blink) f.blink else if ((anim * 3.4f).toInt() % 2 == 0) f.stand0 else f.stand1
            Act.SIT -> if (blink) f.sitBlink else if ((anim * 1.8f).toInt() % 2 == 0) f.sit0 else f.sit1
            Act.SNIFF -> if ((anim * 2.8f).toInt() % 2 == 0) f.sniff0 else f.sniff1
            Act.WALK -> walk()
            Act.RUN, Act.PLAY -> if (moving) run() else if (blink) f.blink else f.stand0
            Act.SLEEP -> if (phase == 0) walk() else f.sleep
            Act.HOP -> if (airborne) f.jump else f.stand0
            Act.CROSS -> when {
                airborne -> f.jump
                phase == 1 -> f.sniff0
                moving -> walk()
                else -> f.stand0
            }
        }
    }

    private fun walk() = if ((anim * 5.5f).toInt() % 2 == 0) FusiFrames.walk0 else FusiFrames.walk1

    private fun run() = if ((anim * 10f).toInt() % 2 == 0) FusiFrames.run0 else FusiFrames.run1

    private fun snap(v: Float): Float = v.roundToInt().toFloat()

    companion object {
        private const val HEARTS = 10
        private const val DUST = 8
        private const val GRAVITY = 1500f

        /** How tall she is, in dp, near enough, for leaving and arriving at a screen's edge. */
        private const val SPRITE_DP = 100f

        /** The one Fusi that a two-screen device's screens share. */
        val shared = FusiWorld()
    }
}
