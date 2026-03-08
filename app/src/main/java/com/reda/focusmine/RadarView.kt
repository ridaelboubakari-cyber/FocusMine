package com.reda.focusmine

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.*

/**
 * RadarView 2.0 — محسّن للأداء + حركة مستمرة
 *
 * تحسينات الأداء:
 *  - LAYER_TYPE_HARDWARE بدل SOFTWARE (GPU بدل CPU)
 *  - BlurMaskFilter محدود فقط لعناصر ثابتة
 *  - كل الـ Paint objects محضّرة مسبقاً في init
 *  - RectF و Path محضّرة مسبقاً بدل ما تتخلق كل frame
 *
 * الحركة المستمرة (Living UI):
 *  - شعاع رادار دوار في ARMING
 *  - نبض خارجي متواصل في ARMED
 *  - خطوط شبكة تومض بخفاء في ARMED
 *  - رجفة كاملة في GRACE
 *  - particles تتطاير في EXPLODED
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ─── الحالات ──────────────────────────────────────────────────
    enum class RadarState { ARMING, ARMED, GRACE, EXPLODED, SUCCESS }
    private var state = RadarState.ARMING

    // ─── متغيرات الأنيماشيون ──────────────────────────────────────
    private var sweepAngle    = 0f   // زاوية شعاع الرادار
    private var pulseScale    = 0f   // نبض خارجي 0..1
    private var pulseAlpha    = 0f
    private var gridFlicker   = 1f   // وميض الشبكة 0..1
    private var shakeOffset   = 0f   // رجفة GRACE
    private var progressValue = 1f   // تقدم الجلسة 0..1

    // particles للانفجار
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var alpha: Float, var radius: Float
    )
    private val particles = mutableListOf<Particle>()
    private var particlesActive = false

    // ─── الألوان ──────────────────────────────────────────────────
    private val C_RED         = Color.parseColor("#CC3300")
    private val C_RED_40      = Color.parseColor("#66CC3300")
    private val C_RED_15      = Color.parseColor("#26CC3300")
    private val C_RED_08      = Color.parseColor("#14CC3300")
    private val C_GREEN       = Color.parseColor("#00CC44")
    private val C_GREEN_40    = Color.parseColor("#6600CC44")
    private val C_ORANGE      = Color.parseColor("#FF6600")
    private val C_BG          = Color.parseColor("#080808")
    private val C_GRID        = Color.parseColor("#111111")
    private val C_GRID_MID    = Color.parseColor("#181818")

    // ─── Paints — كلهم محضّرين مسبقاً ────────────────────────────

    private val bgPaint        = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint      = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweepPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlowPaint   = Paint(Paint.ANTI_ALIAS_FLAG)

    // ─── Reusable objects ─────────────────────────────────────────
    private val arcRect        = RectF()
    private val progressRect   = RectF()

    // ─── الأنيماشيونات ────────────────────────────────────────────
    private var masterAnimator: ValueAnimator? = null
    private var frameCount = 0L

    // ══════════════════════════════════════════════════════════════
    init {
        // ✅ HARDWARE layer — GPU rendering، ما كياكلش CPU
        setLayerType(LAYER_TYPE_HARDWARE, null)
        initPaints()
        startMasterLoop()
    }

    private fun initPaints() {
        ringPaint.apply {
            style       = Paint.Style.STROKE
            color       = C_GRID_MID
            strokeWidth = 0.8f
            pathEffect  = DashPathEffect(floatArrayOf(3f, 9f), 0f)
        }

        outerRingPaint.apply {
            style       = Paint.Style.STROKE
            color       = C_RED
            strokeWidth = 1.2f
            alpha       = 50
        }

        gridPaint.apply {
            style       = Paint.Style.STROKE
            color       = C_GRID
            strokeWidth = 0.6f
        }

        tickPaint.apply {
            style       = Paint.Style.STROKE
            color       = C_RED
            strokeWidth = 0.8f
            alpha       = 45
        }

        tickMajorPaint.apply {
            style       = Paint.Style.STROKE
            color       = C_RED
            strokeWidth = 1.4f
            alpha       = 90
        }

        progressPaint.apply {
            style       = Paint.Style.STROKE
            strokeWidth = 3.5f
            strokeCap   = Paint.Cap.BUTT
            color       = C_RED
        }

        sweepPaint.apply {
            style = Paint.Style.FILL
        }

        pulsePaint.apply {
            style       = Paint.Style.STROKE
            strokeWidth = 1f
            color       = C_RED
        }

        centerPaint.apply {
            style = Paint.Style.FILL
            color = C_RED
        }

        labelPaint.apply {
            color     = C_RED
            textSize  = 17f
            textAlign = Paint.Align.CENTER
            alpha     = 55
            typeface  = Typeface.MONOSPACE
        }

        particlePaint.apply {
            style = Paint.Style.FILL
            color = C_RED
        }

        dotGlowPaint.apply {
            style  = Paint.Style.FILL
            color  = C_RED
            alpha  = 30
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Master Animation Loop — loop وحيد يحرك كل شي
    // ══════════════════════════════════════════════════════════════

    private fun startMasterLoop() {
        masterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration    = 16L   // ~60fps
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                frameCount++
                updateAnimationValues()
                invalidate()
            }
            start()
        }
    }

    private fun updateAnimationValues() {
        val t = (frameCount % 1000).toFloat() / 1000f  // 0..1 كل ~16 ثانية

        when (state) {
            RadarState.ARMING -> {
                sweepAngle  = (frameCount * 1.8f) % 360f
                pulseScale  = sin(frameCount * 0.05f) * 0.5f + 0.5f
                pulseAlpha  = pulseScale * 0.3f
                gridFlicker = sin(frameCount * 0.02f) * 0.15f + 0.85f
            }

            RadarState.ARMED -> {
                sweepAngle  = 0f
                pulseScale  = sin(frameCount * 0.03f) * 0.5f + 0.5f
                pulseAlpha  = pulseScale * 0.2f
                gridFlicker = sin(frameCount * 0.01f) * 0.1f + 0.9f
            }

            RadarState.GRACE -> {
                sweepAngle  = (frameCount * 4f) % 360f   // سريع
                shakeOffset = sin(frameCount * 0.4f) * 4f
                pulseScale  = sin(frameCount * 0.15f) * 0.5f + 0.5f
                pulseAlpha  = pulseScale * 0.5f
                gridFlicker = sin(frameCount * 0.1f) * 0.3f + 0.7f
            }

            RadarState.EXPLODED -> {
                sweepAngle  = 0f
                pulseScale  = sin(frameCount * 0.08f) * 0.5f + 0.5f
                pulseAlpha  = pulseScale * 0.6f
                updateParticles()
            }

            RadarState.SUCCESS -> {
                pulseScale  = sin(frameCount * 0.03f) * 0.5f + 0.5f
                pulseAlpha  = pulseScale * 0.25f
                gridFlicker = 1f
                shakeOffset = 0f
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // onDraw
    // ══════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = (minOf(width, height) / 2f) - 10f

        // رجفة GRACE — نحرك الـ canvas كامل
        if (state == RadarState.GRACE) {
            canvas.translate(shakeOffset, 0f)
        }

        drawBackground(canvas, cx, cy, r)
        drawGridLines(canvas, cx, cy, r)
        drawRings(canvas, cx, cy, r)
        drawTickMarks(canvas, cx, cy, r)
        drawCardinalLabels(canvas, cx, cy, r)

        if (state == RadarState.ARMING || state == RadarState.GRACE) {
            drawSweepBeam(canvas, cx, cy, r)
        }

        drawProgressArc(canvas, cx, cy, r)
        drawPulseRing(canvas, cx, cy, r)

        if (particlesActive) drawParticles(canvas)

        drawCenterDot(canvas, cx, cy)
    }

    // ─── 1. الخلفية ───────────────────────────────────────────────
    private fun drawBackground(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        bgPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.parseColor("#0F0F0F"), C_BG),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, bgPaint)
    }

    // ─── 2. خطوط الشبكة ───────────────────────────────────────────
    private fun drawGridLines(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        gridPaint.alpha = (gridFlicker * 255 * 0.08f).toInt()
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30).toDouble())
            canvas.drawLine(
                cx, cy,
                cx + r * cos(angle).toFloat(),
                cy + r * sin(angle).toFloat(),
                gridPaint
            )
        }
    }

    // ─── 3. الحلقات المتحدة المركز ────────────────────────────────
    private fun drawRings(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        ringPaint.alpha = (gridFlicker * 255 * 0.35f).toInt()
        floatArrayOf(0.28f, 0.50f, 0.72f).forEach { ratio ->
            canvas.drawCircle(cx, cy, r * ratio, ringPaint)
        }
        outerRingPaint.alpha = (gridFlicker * 255 * 0.25f).toInt()
        canvas.drawCircle(cx, cy, r, outerRingPaint)
    }

    // ─── 4. علامات الدرجات ────────────────────────────────────────
    private fun drawTickMarks(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        for (i in 0 until 72) {
            val angle  = Math.toRadians((i * 5).toDouble())
            val isMaj  = i % 6 == 0
            val paint  = if (isMaj) tickMajorPaint else tickPaint
            val innerR = if (isMaj) r - 13f else r - 7f

            canvas.drawLine(
                cx + innerR * cos(angle).toFloat(),
                cy + innerR * sin(angle).toFloat(),
                cx + (r - 2f) * cos(angle).toFloat(),
                cy + (r - 2f) * sin(angle).toFloat(),
                paint
            )
        }
    }

    // ─── 5. تسميات الاتجاهات ─────────────────────────────────────
    private fun drawCardinalLabels(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W").forEach { (deg, lbl) ->
            val a  = Math.toRadians((deg - 90).toDouble())
            val lr = r - 26f
            canvas.drawText(
                lbl,
                cx + lr * cos(a).toFloat(),
                cy + lr * sin(a).toFloat() + labelPaint.textSize / 3,
                labelPaint
            )
        }
    }

    // ─── 6. شعاع الرادار ──────────────────────────────────────────
    private fun drawSweepBeam(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val beamColor = if (state == RadarState.GRACE) C_ORANGE else C_RED
        sweepPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, Color.argb(80, Color.red(beamColor), Color.green(beamColor), Color.blue(beamColor))),
            floatArrayOf(0f, 1f)
        )
        canvas.save()
        canvas.rotate(sweepAngle, cx, cy)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -90f, 80f, true, sweepPaint)
        canvas.restore()
    }

    // ─── 7. قوس التقدم ────────────────────────────────────────────
    private fun drawProgressArc(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val arcR    = r - 5f
        val degrees = progressValue * 360f

        val color = when (state) {
            RadarState.SUCCESS  -> C_GREEN
            RadarState.GRACE    -> C_ORANGE
            RadarState.EXPLODED -> C_RED
            else                -> C_RED
        }

        progressPaint.color = color
        progressRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)

        // القوس الرئيسي
        canvas.drawArc(progressRect, -90f, degrees, false, progressPaint)

        // نقطة في رأس القوس
        if (degrees > 3f) {
            val endAngle = Math.toRadians((-90f + degrees).toDouble())
            centerPaint.color = color
            canvas.drawCircle(
                cx + arcR * cos(endAngle).toFloat(),
                cy + arcR * sin(endAngle).toFloat(),
                4f, centerPaint
            )
        }
    }

    // ─── 8. نبض خارجي ─────────────────────────────────────────────
    private fun drawPulseRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        if (pulseAlpha <= 0.01f) return
        val pulseR = r + 8f + pulseScale * 18f
        pulsePaint.color = when (state) {
            RadarState.SUCCESS -> C_GREEN
            else -> C_RED
        }
        pulsePaint.alpha = (pulseAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, pulseR, pulsePaint)
    }

    // ─── 9. نقطة المركز ───────────────────────────────────────────
    private fun drawCenterDot(canvas: Canvas, cx: Float, cy: Float) {
        val color = when (state) {
            RadarState.SUCCESS -> C_GREEN
            else -> C_RED
        }
        // Glow خفيف
        dotGlowPaint.color = color
        dotGlowPaint.alpha = 25
        canvas.drawCircle(cx, cy, 14f, dotGlowPaint)
        // النقطة
        centerPaint.color = color
        canvas.drawCircle(cx, cy, 4f, centerPaint)
        // حلقة صغيرة حولها
        pulsePaint.color   = color
        pulsePaint.alpha   = 60
        pulsePaint.strokeWidth = 1f
        canvas.drawCircle(cx, cy, 10f, pulsePaint)
    }

    // ─── 10. Particles للانفجار ───────────────────────────────────
    private fun spawnParticles(cx: Float, cy: Float) {
        particles.clear()
        repeat(24) {
            val angle = Math.toRadians((it * 15).toDouble())
            val speed = 2f + (Math.random() * 3f).toFloat()
            particles.add(Particle(
                x      = cx,
                y      = cy,
                vx     = cos(angle).toFloat() * speed,
                vy     = sin(angle).toFloat() * speed,
                alpha  = 1f,
                radius = 2f + (Math.random() * 3f).toFloat()
            ))
        }
        particlesActive = true
    }

    private fun updateParticles() {
        var allDead = true
        particles.forEach { p ->
            p.x     += p.vx
            p.y     += p.vy
            p.alpha -= 0.012f
            p.vx    *= 0.96f
            p.vy    *= 0.96f
            if (p.alpha > 0f) allDead = false
        }
        if (allDead) particlesActive = false
    }

    private fun drawParticles(canvas: Canvas) {
        particles.forEach { p ->
            if (p.alpha > 0f) {
                particlePaint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)
                canvas.drawCircle(p.x, p.y, p.radius, particlePaint)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════

    fun setProgress(progress: Float) {
        progressValue = progress.coerceIn(0f, 1f)
    }

    fun setState(newState: RadarState) {
        if (newState == RadarState.EXPLODED && state != RadarState.EXPLODED) {
            // spawn particles من المركز
            post { spawnParticles(width / 2f, height / 2f) }
        }
        state = newState
        shakeOffset = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        masterAnimator?.cancel()
    }
}
