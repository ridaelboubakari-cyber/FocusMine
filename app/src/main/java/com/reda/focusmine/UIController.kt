package com.reda.focusmine

import android.animation.*
import android.view.View
import android.view.animation.*
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

/**
 * UIController 3.0 — النسخة النهائية
 *
 * الجديد:
 *  - ربط مع ExplodedOverlayView
 *  - setState أكثر دقة مع transitions سلسة
 *  - updateAccel يحرك Data Panel بالوقت الحقيقي
 */
class UIController(private val activity: MainActivity) {

    // ─── العناصر ──────────────────────────────────────────────────
    private val rootLayout:      View                = activity.findViewById(R.id.rootLayout)
    private val radarView:       RadarView           = activity.findViewById(R.id.radarView)
    private val explodedOverlay: ExplodedOverlayView = activity.findViewById(R.id.explodedOverlay)
    private val timerText:       MaterialTextView    = activity.findViewById(R.id.timerText)
    private val statusText:      MaterialTextView    = activity.findViewById(R.id.statusText)
    private val statusDot:       View                = activity.findViewById(R.id.statusDot)
    val surrenderButton:         MaterialButton      = activity.findViewById(R.id.surrenderButton)
    private val accelValue:      MaterialTextView    = activity.findViewById(R.id.accelValue)
    private val missionLabel:    MaterialTextView    = activity.findViewById(R.id.missionLabel)
    private val topDivider:      View                = activity.findViewById(R.id.topDivider)
    private val appLabel:        MaterialTextView    = activity.findViewById(R.id.appLabel)
    private val statusContainer: LinearLayout        = activity.findViewById(R.id.statusContainer)

    // ─── الألوان ──────────────────────────────────────────────────
    private val RED   = 0xFFCC3300.toInt()
    private val GREEN = 0xFF00CC44.toInt()
    private val WHITE = 0xFFE8E8E8.toInt()
    private val DIM   = 0xFF1E1E1E.toInt()
    private val DIMMER= 0xFF141414.toInt()

    // ─── الأنيماشيونات ────────────────────────────────────────────
    private var heartbeat: AnimatorSet?    = null
    private var dotBlink:  ObjectAnimator? = null

    // ══════════════════════════════════════════════════════════════
    fun animateUI() {
        playEntranceStagger()
        startHeartbeat()
        startDotBlink()
    }

    // ─── دخول متتابع ──────────────────────────────────────────────
    private fun playEntranceStagger() {
        listOf(
            appLabel,
            activity.findViewById<View>(R.id.appDot),
            topDivider,
            radarView,
            timerText,
            statusContainer,
            activity.findViewById<View>(R.id.leftPanel),
            activity.findViewById<View>(R.id.rightPanel),
            activity.findViewById<View>(R.id.bottomDivider),
            activity.findViewById<View>(R.id.missionLabel)
        ).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 18f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay((i * 85).toLong())
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    // ─── نبضة القلب ───────────────────────────────────────────────
    private fun startHeartbeat(speed: Float = 1f) {
        heartbeat?.cancel()
        val duration = (950f / speed).toLong()
        val scale    = if (speed > 1.5f) 1.05f else 1.022f
        heartbeat = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(timerText, "scaleX", 1f, scale, 1f),
                ObjectAnimator.ofFloat(timerText, "scaleY", 1f, scale, 1f)
            )
            this.duration    = duration
            repeatCount      = ObjectAnimator.INFINITE
            interpolator     = OvershootInterpolator(1.1f)
            start()
        }
    }

    // ─── وميض النقطة ──────────────────────────────────────────────
    private fun startDotBlink(fast: Boolean = false) {
        dotBlink?.cancel()
        dotBlink = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.05f).apply {
            duration     = if (fast) 280L else 850L
            repeatCount  = ObjectAnimator.INFINITE
            repeatMode   = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // setState
    // ══════════════════════════════════════════════════════════════

    enum class UIState { ARMING, ARMED, GRACE, EXPLODED, SUCCESS }

    fun setState(state: UIState) {
        when (state) {

            UIState.ARMING -> {
                setStatus("ARMING SEQUENCE", RED)
                timerText.setTextColor(DIMMER)
                setMissionLabel("PLACE DEVICE FACE DOWN", DIM)
                surrenderButton.visibility = View.GONE
                explodedOverlay.deactivate()
                radarView.setState(RadarView.RadarState.ARMING)
            }

            UIState.ARMED -> {
                setStatus("MINE ARMED — DO NOT MOVE", RED)
                timerText.setTextColor(WHITE)
                setMissionLabel("MISSION IN PROGRESS", DIMMER)
                surrenderButton.visibility = View.GONE
                explodedOverlay.deactivate()
                radarView.setState(RadarView.RadarState.ARMED)
            }

            UIState.GRACE -> {
                setStatus("WARNING — REPLACE DEVICE NOW", RED)
                timerText.setTextColor(RED)
                setMissionLabel("REPLACE IMMEDIATELY", RED)
                surrenderButton.visibility = View.GONE
                startHeartbeat(speed = 2.5f)
                startDotBlink(fast = true)
                radarView.setState(RadarView.RadarState.GRACE)
            }

            UIState.EXPLODED -> {
                setStatus("MISSION FAILED", RED)
                timerText.setTextColor(RED)
                setMissionLabel("PRESS ABORT TO SILENCE", RED)

                // ظهور الزر بـ fade-in
                surrenderButton.alpha = 0f
                surrenderButton.visibility = View.VISIBLE
                surrenderButton.animate().alpha(1f).setDuration(400).start()

                // تفعيل الـ overlay المرعب
                explodedOverlay.activate()

                startHeartbeat(speed = 3f)
                startDotBlink(fast = true)
                radarView.setState(RadarView.RadarState.EXPLODED)
            }

            UIState.SUCCESS -> {
                setStatus("MISSION COMPLETE", GREEN)
                timerText.setTextColor(GREEN)
                setMissionLabel("OBJECTIVE ACHIEVED", GREEN)
                surrenderButton.visibility = View.GONE
                explodedOverlay.deactivate()

                heartbeat?.cancel()
                dotBlink?.cancel()
                statusDot.alpha = 1f
                statusDot.setBackgroundColor(GREEN)

                radarView.setState(RadarView.RadarState.SUCCESS)
                startSuccessAnim()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // updateTimer — كل ثانية
    // ══════════════════════════════════════════════════════════════

    fun updateTimer(remainingMs: Long, totalMs: Long) {
        val h = remainingMs / 3_600_000
        val m = (remainingMs % 3_600_000) / 60_000
        val s = (remainingMs % 60_000) / 1_000

        timerText.text = String.format("%02d:%02d:%02d", h, m, s)
        radarView.setProgress((remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f))

        // تحذير آخر 5 دقائق
        if (remainingMs in 1..299_999) {
            timerText.setTextColor(RED)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // updateAccel — من onSensorChanged في FocusService
    // ══════════════════════════════════════════════════════════════

    fun updateAccel(value: Double) {
        accelValue.text = String.format("%.2f", value)
        // يحمر ملي يقترب من العبار 2.5
        val ratio    = (value / 2.5).coerceIn(0.0, 1.0)
        val redComp  = (ratio * 204).toInt()
        val greyComp = ((1 - ratio) * 32).toInt()
        accelValue.setTextColor(
            android.graphics.Color.rgb(
                maxOf(redComp, greyComp),
                greyComp,
                greyComp
            )
        )
    }

    // ══════════════════════════════════════════════════════════════
    // مساعدات
    // ══════════════════════════════════════════════════════════════

    private fun setStatus(text: String, color: Int) {
        statusText.text = text
        statusText.setTextColor(color)
    }

    private fun setMissionLabel(text: String, color: Int) {
        missionLabel.text = text
        missionLabel.setTextColor(color)
    }

    private fun startSuccessAnim() {
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(timerText, "scaleX", 1f, 1.1f, 1f),
                ObjectAnimator.ofFloat(timerText, "scaleY", 1f, 1.1f, 1f)
            )
            duration     = 700
            interpolator = OvershootInterpolator(2f)
            start()
        }
    }
}
