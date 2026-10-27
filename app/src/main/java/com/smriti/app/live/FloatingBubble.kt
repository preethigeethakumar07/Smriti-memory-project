package com.smriti.app.live

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Smriti's floating eye. It sits over every other app, twinkles with AI sparkles to catch the eye, and docks
 * half-hidden at the left or right edge when it has not been touched for a moment. Touch it and it slides out;
 * drag it anywhere and it snaps back to the nearest edge; tap it and it squashes, springs back with a ripple, and
 * opens Smriti. While Live it also shows what Smriti is doing (listening rings, thinking orbit, speaking pulse).
 */
class FloatingBubble(private val context: Context, private val onTap: () -> Unit) {

    private val density = context.resources.displayMetrics.density
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sizePx = (76 * density).toInt()
    private val margin = (4 * density).toInt()
    private val view = BubbleView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var shown = false
    private var docked = false
    private var dragging = false
    private var xAnim: ValueAnimator? = null

    private val screenW get() = context.resources.displayMetrics.widthPixels
    private val screenH get() = context.resources.displayMetrics.heightPixels

    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = context.resources.displayMetrics.widthPixels - sizePx - margin
        y = (context.resources.displayMetrics.heightPixels * 0.30f).toInt()
    }

    private val dockRunnable = Runnable { dock() }

    init { attachTouch() }

    fun show() {
        if (shown) return
        shown = true
        view.animate().cancel()
        if (!view.isAttachedToWindow) {
            runCatching { wm.addView(view, params) }.onFailure { shown = false; return }
        }
        view.alpha = 1f
        view.scaleX = 0f; view.scaleY = 0f
        view.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(2.2f)).start()
        view.startClock()
        docked = false
        scheduleDock(2500)
    }

    fun hide() {
        if (!shown) return
        shown = false
        handler.removeCallbacks(dockRunnable)
        view.animate().cancel()
        view.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).withEndAction {
            if (!shown) { runCatching { wm.removeView(view) }; view.stopClock() }
        }.start()
    }

    fun update(phase: LivePhase, level: Float, muted: Boolean, live: Boolean) {
        view.phase = phase
        view.level = level
        view.muted = muted
        view.live = live
        // a Live session keeps the eye out where you can see it
        if (live && docked) undock()
    }

    // ---- docking ---------------------------------------------------------------------------------------------------

    private fun scheduleDock(afterMs: Long) {
        handler.removeCallbacks(dockRunnable)
        if (!view.live) handler.postDelayed(dockRunnable, afterMs)
    }

    private fun onLeft() = params.x + sizePx / 2 < screenW / 2

    private fun dock() {
        if (!shown || dragging || view.pressed || view.live) return
        docked = true
        val target = if (onLeft()) -(sizePx * 0.55f).toInt() else screenW - (sizePx * 0.45f).toInt()
        animateX(target, 380, OvershootInterpolator(1.2f))
        view.animate().alpha(0.9f).setDuration(380).start()
    }

    private fun undock() {
        if (!docked) return
        docked = false
        animateX(if (onLeft()) margin else screenW - sizePx - margin, 320, OvershootInterpolator(1.6f))
        view.animate().alpha(1f).setDuration(200).start()
    }

    private fun snapToEdge() {
        val target = if (onLeft()) margin else screenW - sizePx - margin
        animateX(target, 380, OvershootInterpolator(1.4f))
        params.y = params.y.coerceIn((24 * density).toInt(), screenH - sizePx - (48 * density).toInt())
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun animateX(to: Int, ms: Long, interp: android.view.animation.Interpolator) {
        xAnim?.cancel()
        xAnim = ValueAnimator.ofInt(params.x, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener { params.x = it.animatedValue as Int; if (shown) runCatching { wm.updateViewLayout(view, params) } }
            start()
        }
    }

    // ---- touch: press, tap, drag ----------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(dockRunnable)
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; dragging = false
                    view.setPressedAnim(true)
                    if (docked) undock()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 14 * density || abs(dy) > 14 * density)) { moved = true; dragging = true; xAnim?.cancel(); docked = false; view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(120).start() }
                    if (moved) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.setPressedAnim(false)
                    if (moved) {
                        dragging = false
                        view.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                        snapToEdge()
                        scheduleDock(2500)
                    } else {
                        view.burst()
                        // let the squash-and-spring and the ripple play before the app takes over the screen
                        handler.postDelayed({ onTap() }, 190)
                        scheduleDock(5000)
                    }
                }
                MotionEvent.ACTION_CANCEL -> { view.setPressedAnim(false); dragging = false; scheduleDock(2500) }
            }
            true
        }
    }

    // ---- the eye ---------------------------------------------------------------------------------------------------------

    private class BubbleView(context: Context) : View(context) {
        var phase = LivePhase.IDLE
        var level = 0f
        var muted = false
        var live = false
        var pressed = false
            private set

        private val density = context.resources.displayMetrics.density
        private var pressScale = 1f
        private var ripple = 0f
        private var rippleAnim: ValueAnimator? = null
        private var pressAnim: ValueAnimator? = null
        private var clock: ValueAnimator? = null
        private var frame = 0

        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val almond = Path()
        private val star = Path().apply {
            // a four-point AI sparkle, unit size
            moveTo(0f, -1f); quadTo(0.14f, -0.14f, 1f, 0f); quadTo(0.14f, 0.14f, 0f, 1f); quadTo(-0.14f, 0.14f, -1f, 0f); quadTo(-0.14f, -0.14f, 0f, -1f); close()
        }
        private val clip = Path()

        fun startClock() {
            if (clock?.isRunning == true) return
            clock = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 6000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                // every other frame is plenty for a twinkle and keeps the battery cost low
                addUpdateListener { if (frame++ % 2 == 0) invalidate() }
                start()
            }
        }

        fun stopClock() { clock?.cancel(); clock = null; rippleAnim?.cancel(); pressAnim?.cancel() }

        /** Finger down: squash to 0.82. Finger up: spring back past 1 and settle. */
        fun setPressedAnim(down: Boolean) {
            pressed = down
            pressAnim?.cancel()
            pressAnim = ValueAnimator.ofFloat(pressScale, if (down) 0.82f else 1f).apply {
                duration = if (down) 110 else 380
                interpolator = if (down) DecelerateInterpolator() else OvershootInterpolator(3.4f)
                addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        /** A ring that expands from the eye and fades. */
        fun burst() {
            rippleAnim?.cancel()
            rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 560; interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() { stopClock(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val cx = w / 2f; val cy = height / 2f
            val ms = System.currentTimeMillis()
            val t = ms / 1000f
            val accent = if (muted) RED else BLUE
            val breath = 1f + 0.035f * sin(t * 2.2f)
            val activity = when (phase) {
                LivePhase.LISTENING -> 0.25f + level * 0.75f
                LivePhase.SPEAKING -> 0.55f + 0.25f * sin(t * 7f)
                LivePhase.THINKING -> 0.45f
                else -> 0f
            }

            // soft halo that breathes (stronger when something is happening)
            glow.shader = RadialGradient(cx, cy, w * 0.5f, intArrayOf(withAlpha(accent, (70 + 110 * activity).toInt().coerceAtMost(200)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, w * 0.5f * (breath + 0.06f * activity), glow)

            // expanding tap ripple
            if (ripple > 0f && ripple < 1f) {
                stroke.shader = null
                stroke.strokeWidth = 3f * density * (1f - ripple) + 1f
                stroke.color = withAlpha(Color.WHITE, ((1f - ripple) * 200).toInt())
                c.drawCircle(cx, cy, w * (0.30f + 0.22f * ripple), stroke)
            }

            c.save()
            c.scale(pressScale * breath, pressScale * breath, cx, cy)

            // the disc the eye sits on, dark so it reads on any app
            val r = w * 0.34f
            fill.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.4f, r * 1.5f, intArrayOf(0xFF1A2A55.toInt(), 0xFF0A1226.toInt(), 0xFF05070C.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, fill)
            stroke.strokeWidth = 2.2f * density
            stroke.shader = SweepGradient(cx, cy, intArrayOf(accent, DEEP, RED, DEEP, accent), null)
            c.drawCircle(cx, cy, r, stroke)

            // activity ticks around the disc: listening climbs with your voice, thinking chases, speaking pulses
            drawTicks(c, cx, cy, r * 1.18f, r * 1.34f, t, accent)

            // the almond-shaped eye with a blink every few seconds
            val open = blink(ms)
            val rx = r * 0.82f
            val ry = r * 0.46f * open
            almond.reset()
            almond.moveTo(cx - rx, cy)
            almond.quadTo(cx, cy - ry * 2f, cx + rx, cy)
            almond.quadTo(cx, cy + ry * 2f, cx - rx, cy)
            almond.close()
            fill.shader = LinearGradient(cx, cy - ry, cx, cy + ry, intArrayOf(0xFFF2F8FF.toInt(), 0xFFB8D6F5.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(almond, fill)

            c.save()
            clip.reset(); clip.addPath(almond); c.clipPath(clip)
            // iris glides slightly, as if looking around; it also follows the voice
            val lookX = sin(t * 0.9f) * r * 0.10f
            val irisR = r * (0.42f + 0.05f * activity)
            fill.shader = RadialGradient(cx + lookX, cy, irisR, intArrayOf(0xFF8CC8FF.toInt(), accent, DEEP), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx + lookX, cy, irisR, fill)
            val pupil = irisR * (0.46f + 0.14f * activity)
            fill.shader = null; fill.color = 0xFF03060C.toInt()
            c.drawCircle(cx + lookX, cy, pupil, fill)
            fill.color = Color.WHITE
            c.drawCircle(cx + lookX - pupil * 0.45f, cy - pupil * 0.5f, pupil * 0.30f, fill)
            c.drawCircle(cx + lookX + pupil * 0.55f, cy + pupil * 0.45f, pupil * 0.14f, fill)
            c.restore()

            stroke.strokeWidth = 1.8f * density
            stroke.shader = LinearGradient(cx - rx, cy, cx + rx, cy, intArrayOf(accent, Color.WHITE, accent), null, Shader.TileMode.CLAMP)
            c.drawPath(almond, stroke)
            c.restore()

            // AI sparkles: each one swells, turns a little and fades on its own beat, so something is always twinkling
            sparkle(c, cx + w * 0.30f, cy - w * 0.30f, w * 0.17f, t, 0.00f)
            sparkle(c, cx - w * 0.33f, cy + w * 0.26f, w * 0.12f, t, 0.33f)
            sparkle(c, cx + w * 0.34f, cy + w * 0.30f, w * 0.08f, t, 0.62f)
            sparkle(c, cx - w * 0.27f, cy - w * 0.34f, w * 0.07f, t, 0.85f)
        }

        private fun blink(ms: Long): Float {
            val x = (ms % 4600L) - 4350L           // blink during the last 250 ms of each 4.6 s
            if (x !in 0..250) return 1f
            return max(0.08f, abs((x - 125f) / 125f))
        }

        private fun sparkle(c: Canvas, x: Float, y: Float, size: Float, t: Float, phase: Float) {
            val tw = ((t / 2.4f) + phase) % 1f
            val k = sin(PI.toFloat() * tw).let { it * it }       // 0 -> 1 -> 0 over each cycle
            if (k < 0.04f) return
            spark.color = withAlpha(Color.WHITE, (255 * k).toInt())
            spark.setShadowLayer(size * 0.7f * k, 0f, 0f, withAlpha(BLUE, (200 * k).toInt()))
            c.save()
            c.translate(x, y)
            c.rotate(18f * sin(t + phase * 6f))
            c.scale(size * (0.35f + 0.65f * k), size * (0.35f + 0.65f * k))
            c.drawPath(star, spark)
            c.restore()
        }

        private fun drawTicks(c: Canvas, cx: Float, cy: Float, r1: Float, r2: Float, t: Float, accent: Int) {
            val n = 28
            val lit = when (phase) {
                LivePhase.LISTENING -> (3 + level * (n - 3)).toInt()
                LivePhase.SPEAKING -> (n * (0.35f + 0.3f * (0.5f + 0.5f * sin(t * 4f)))).toInt()
                else -> 0
            }
            for (i in 0 until n) {
                val a = (-PI / 2 + 2 * PI * i / n).toFloat()
                val on = when (phase) {
                    LivePhase.THINKING -> ((i - (t * 12f).toInt()) % n + n) % n < 6
                    LivePhase.LISTENING, LivePhase.SPEAKING -> i < lit
                    else -> false
                }
                stroke.shader = null
                stroke.strokeWidth = 1.6f * density
                stroke.color = if (on) accent else 0x22FFFFFF
                if (!on && phase != LivePhase.IDLE && phase != LivePhase.PAUSED) stroke.color = 0x33FFFFFF
                if (!on && (phase == LivePhase.IDLE || phase == LivePhase.PAUSED)) continue    // nothing to show when resting
                c.drawLine(cx + r1 * cos(a), cy + r1 * sin(a), cx + r2 * cos(a), cy + r2 * sin(a), stroke)
            }
        }

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        private companion object {
            val BLUE = 0xFF4AA8FF.toInt()
            val DEEP = 0xFF1B3FA0.toInt()
            val RED = 0xFFE4262C.toInt()
        }
    }
}
