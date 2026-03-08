package com.reda.focusmine.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.view.*
import android.view.animation.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.ExplodedOverlayView
import com.reda.focusmine.FocusService
import com.reda.focusmine.R
import com.reda.focusmine.RadarView

class MissionFragment : Fragment() {

    private val args: MissionFragmentArgs by navArgs()

    // ─── Views ────────────────────────────────────────────────────
    private lateinit var radarView:       RadarView
    private lateinit var explodedOverlay: ExplodedOverlayView
    private lateinit var timerText:       MaterialTextView
    private lateinit var statusText:      MaterialTextView
    private lateinit var statusDot:       View
    private lateinit var accelValue:      MaterialTextView
    private lateinit var surrenderBtn:    MaterialButton
    private lateinit var missionLabel:    MaterialTextView

    // ─── Timer ────────────────────────────────────────────────────
    private val handler        = Handler(Looper.getMainLooper())
    private var sessionStartMs = 0L
    private var remainingMs    = 0L
    private var totalMs        = 0L
    private var isRunning      = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isAdded) return
            remainingMs = (totalMs - (System.currentTimeMillis() - sessionStartMs)).coerceAtLeast(0)
            updateTimerDisplay(remainingMs)
            radarView.setProgress(remainingMs.toFloat() / totalMs.toFloat())
            if (remainingMs <= 0) onSessionSuccess()
            else handler.postDelayed(this, 1_000)
        }
    }

    // ─── BroadcastReceiver ────────────────────────────────────────
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                FocusService.ACTION_ARMED -> {
                    sessionStartMs = System.currentTimeMillis()
                    isRunning      = true
                    setUIState(UIState.ARMED)
                    handler.post(timerRunnable)
                }
                FocusService.ACTION_ACCEL_UPDATE -> {
                    val v = intent.getDoubleExtra(FocusService.EXTRA_ACCEL, 0.0)
                    updateAccel(v)
                }
                FocusService.ACTION_GRACE_START     -> setUIState(UIState.GRACE)
                FocusService.ACTION_GRACE_CANCELLED -> setUIState(UIState.ARMED)
                FocusService.ACTION_ALARM_TRIGGERED -> {
                    isRunning = false
                    handler.removeCallbacks(timerRunnable)
                    setUIState(UIState.EXPLODED)
                }
                FocusService.ACTION_SURRENDERED -> onSurrenderConfirmed()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_mission, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        totalMs     = args.durationMs
        remainingMs = totalMs

        bindViews(view)
        updateTimerDisplay(totalMs)
        registerReceiver()
        startArming()
        setUIState(UIState.ARMING)
        playEntrance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver()
    }

    // ─── Bind ─────────────────────────────────────────────────────
    private fun bindViews(v: View) {
        radarView       = v.findViewById(R.id.radarView)
        explodedOverlay = v.findViewById(R.id.explodedOverlay)
        timerText       = v.findViewById(R.id.timerText)
        statusText      = v.findViewById(R.id.statusText)
        statusDot       = v.findViewById(R.id.statusDot)
        accelValue      = v.findViewById(R.id.accelValue)
        surrenderBtn    = v.findViewById(R.id.surrenderButton)
        missionLabel    = v.findViewById(R.id.missionLabel)

        surrenderBtn.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), FocusService::class.java)
                    .apply { action = FocusService.ACTION_SURRENDER }
            )
        }
    }

    // ─── Arming sequence ─────────────────────────────────────────
    private fun startArming() {
        var s = 5
        val r = object : Runnable {
            override fun run() {
                if (!isAdded) return
                if (s > 0) {
                    statusText.text = "ARMING IN $s..."
                    timerText.text  = "00:00:0$s"
                    s--
                    handler.postDelayed(this, 1_000)
                } else {
                    FocusService.arm(requireContext())
                }
            }
        }
        handler.post(r)
    }

    // ─── UI States ────────────────────────────────────────────────
    private enum class UIState { ARMING, ARMED, GRACE, EXPLODED, SUCCESS }

    private fun setUIState(state: UIState) {
        val RED   = 0xFFCC3300.toInt()
        val GREEN = 0xFF00CC44.toInt()
        val WHITE = 0xFFE8E8E8.toInt()
        val DIM   = 0xFF1A1A1A.toInt()

        when (state) {
            UIState.ARMING -> {
                statusText.text = "ARMING SEQUENCE"; statusText.setTextColor(RED)
                timerText.setTextColor(DIM)
                missionLabel.text = "PLACE DEVICE FACE DOWN"
                surrenderBtn.visibility = View.GONE
                explodedOverlay.deactivate()
                radarView.setState(RadarView.RadarState.ARMING)
            }
            UIState.ARMED -> {
                statusText.text = "MINE ARMED — DO NOT MOVE"; statusText.setTextColor(RED)
                timerText.setTextColor(WHITE)
                missionLabel.text = "MISSION IN PROGRESS"
                surrenderBtn.visibility = View.GONE
                explodedOverlay.deactivate()
                radarView.setState(RadarView.RadarState.ARMED)
            }
            UIState.GRACE -> {
                statusText.text = "WARNING — REPLACE NOW"; statusText.setTextColor(RED)
                timerText.setTextColor(RED)
                missionLabel.text = "REPLACE IMMEDIATELY"
                radarView.setState(RadarView.RadarState.GRACE)
            }
            UIState.EXPLODED -> {
                statusText.text = "MISSION FAILED"; statusText.setTextColor(RED)
                timerText.setTextColor(RED)
                missionLabel.text = "PRESS ABORT TO SILENCE"
                surrenderBtn.alpha = 0f
                surrenderBtn.visibility = View.VISIBLE
                surrenderBtn.animate().alpha(1f).setDuration(400).start()
                explodedOverlay.activate()
                radarView.setState(RadarView.RadarState.EXPLODED)
            }
            UIState.SUCCESS -> {
                statusText.text = "MISSION COMPLETE"; statusText.setTextColor(GREEN)
                timerText.setTextColor(GREEN)
                missionLabel.text = "OBJECTIVE ACHIEVED"
                surrenderBtn.visibility = View.GONE
                explodedOverlay.deactivate()
                radarView.setState(RadarView.RadarState.SUCCESS)
            }
        }
    }

    // ─── Session events ───────────────────────────────────────────
    private fun onSessionSuccess() {
        isRunning = false
        FocusService.stop(requireContext())
        setUIState(UIState.SUCCESS)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            val action = MissionFragmentDirections.actionMissionToReport(
                isSuccess  = true,
                durationMs = totalMs
            )
            findNavController().navigate(action)
        }, 2_000)
    }

    private fun onSurrenderConfirmed() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        FocusService.stop(requireContext())
        val elapsed = totalMs - remainingMs
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            val action = MissionFragmentDirections.actionMissionToReport(
                isSuccess  = false,
                durationMs = elapsed
            )
            findNavController().navigate(action)
        }, 1_500)
    }

    // ─── Helpers ──────────────────────────────────────────────────
    private fun updateTimerDisplay(ms: Long) {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        timerText.text = String.format("%02d:%02d:%02d", h, m, s)
        if (ms in 1..299_999) timerText.setTextColor(0xFFCC3300.toInt())
    }

    private fun updateAccel(value: Double) {
        accelValue.text = String.format("%.2f", value)
        val ratio   = (value / 2.5).coerceIn(0.0, 1.0)
        val r       = (ratio * 204).toInt()
        val g       = ((1 - ratio) * 32).toInt()
        accelValue.setTextColor(android.graphics.Color.rgb(maxOf(r, g), g, g))
    }

    private fun playEntrance() {
        listOf(R.id.topBar, R.id.radarView, R.id.timerText,
               R.id.statusContainer, R.id.bottomBar)
            .forEachIndexed { i, id ->
                view?.findViewById<View>(id)?.apply {
                    alpha = 0f; translationY = 14f
                    animate().alpha(1f).translationY(0f)
                        .setDuration(360).setStartDelay((i * 80).toLong())
                        .setInterpolator(DecelerateInterpolator(2f)).start()
                }
            }
    }

    // ─── Receiver ─────────────────────────────────────────────────
    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ARMED)
            addAction(FocusService.ACTION_ACCEL_UPDATE)
            addAction(FocusService.ACTION_GRACE_START)
            addAction(FocusService.ACTION_GRACE_CANCELLED)
            addAction(FocusService.ACTION_ALARM_TRIGGERED)
            addAction(FocusService.ACTION_SURRENDERED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            requireContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            requireContext().registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { requireContext().unregisterReceiver(receiver) } catch (_: Exception) {}
        receiverRegistered = false
    }
}
