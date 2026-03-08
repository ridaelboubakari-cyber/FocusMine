package com.reda.focusmine

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * ExplodedOverlayView — الشاشة الحمراء المرعبة ملي يتفرقع اللغم
 *
 * تضاف كـ overlay فوق الـ rootLayout وتظهر فقط في حالة EXPLODED.
 *
 * تأثيرات:
 *  - خلفية حمراء شفافة تنبض
 *  - خطوط رأسية تتشوش (Glitch)
 *  - نص WARNING يرتجف في المنتصف
 *  - vignette سوداء على الحواف
 *
 * الاستخدام في activity_main.xml:
 *   <com.reda.focusmine.ExplodedOverlayView
 *       android:id="@+id/explodedOverlay"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent"
 *       android:visibility="gone" />
 */
class ExplodedOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var frameCount  = 0L
    private var isActive    = false
    private var animator: ValueAnimator? = null

    // ─── Paints ───────────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glitchPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC3300")
    }

    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#CC3300")
        textSize  = 13f
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.MONOSPACE
        letterSpacing = 0.4f
    }

    // ─── Glitch lines ──────────────────────────────────────────────
    private data class GlitchLine(
        var y: Float,
        var height: Float,
        var alpha: Float,
        var speed: Float
    )

    private val glitchLines = List(6) {
        GlitchLine(
            y      = (Math.random() * 1000).toFloat(),
            height = (2f + Math.random() * 8f).toFloat(),
            alpha  = (0.1f + Math.random() * 0.2f).toFloat(),
            speed  = (0.5f + Math.random() * 2f).toFloat()
        )
    }

    // ══════════════════════════════════════════════════════════════
    fun activate() {
        isActive   = true
        visibility = VISIBLE
        startLoop()
    }

    fun deactivate() {
        isActive   = false
        animator?.cancel()
        visibility = GONE
    }

    private fun startLoop() {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 16L
            repeatCount  = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                frameCount++
                updateGlitchLines()
                invalidate()
            }
            start()
        }
    }

    private fun updateGlitchLines() {
        glitchLines.forEach { line ->
            line.y += line.speed
            if (line.y > height) {
                line.y      = -line.height
                line.alpha  = (0.05f + Math.random() * 0.2f).toFloat()
                line.height = (2f + Math.random() * 10f).toFloat()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    override fun onDraw(canvas: Canvas) {
        if (!isActive) return

        val cx = width / 2f
        val cy = height / 2f

        // ─── 1. خلفية حمراء نابضة ─────────────────────────────────
        val pulse = sin(frameCount * 0.08f) * 0.5f + 0.5f
        val bgAlpha = (0.12f + pulse * 0.08f)
        bgPaint.color = Color.argb(
            (bgAlpha * 255).toInt(),
            204, 51, 0
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // ─── 2. خطوط Glitch ───────────────────────────────────────
        glitchLines.forEach { line ->
            // كل خط يظهر عشوائياً
            if (frameCount % 3L == 0L && Math.random() > 0.4) {
                glitchPaint.alpha = (line.alpha * 255).toInt()
                canvas.drawRect(0f, line.y, width.toFloat(), line.y + line.height, glitchPaint)
            }
        }

        // ─── 3. Vignette سوداء على الحواف ────────────────────────
        vignettePaint.shader = RadialGradient(
            cx, cy,
            maxOf(width, height) * 0.7f,
            intArrayOf(Color.TRANSPARENT, Color.argb(160, 0, 0, 0)),
            floatArrayOf(0.3f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        // ─── 4. نص WARNING يرتجف ──────────────────────────────────
        val shakeX = (sin(frameCount * 0.7f) * 3f)
        val shakeY = (sin(frameCount * 0.5f) * 2f)

        warningPaint.alpha = (180 + sin(frameCount * 0.15f) * 75).toInt().coerceIn(0, 255)
        canvas.drawText(
            "! SECURITY BREACH !",
            cx + shakeX,
            cy * 0.35f + shakeY,
            warningPaint
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
