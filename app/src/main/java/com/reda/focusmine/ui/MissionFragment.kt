package com.reda.focusmine.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.*
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.FocusService
import com.reda.focusmine.R
import com.reda.focusmine.RadarView
import com.reda.focusmine.data.ModeConfigProvider
import com.reda.focusmine.data.OperativeMode

// ══════════════════════════════════════════════════════════════
// MissionFragment — PHASE 2
//
// CHANGES:
//   ✓ Reads microGoal + emotionalSnapshot from args
//   ✓ Iron Door overlay on abandon attempt
//   ✓ Mode-based friction: RECRUIT=no excuse,
//                          OPERATIVE=10s wait,
//                          GHOST=30s wait
//   ✓ Excuse validation (entropy + word count + fingerprint)
//   ✓ Three-zone haptic timer (no visual dependency)
//   ✓ Tracks appLeftCount + wasCompromised from FocusService
//   ✗ Sensor / accelerometer logic REMOVED
//   ✗ ExplodedOverlayView REMOVED
// ══════════════════════════════════════════════════════════════

class MissionFragment : Fragment() {

    private val args: MissionFragmentArgs by navArgs()

    // ── Mode config ───────────────────────────────────────────
    private lateinit var operativeMode: OperativeMode

    // ── Views — main session ──────────────────────────────────
    private lateinit var radarView:            RadarView
    private lateinit var timerText:            MaterialTextView
    private lateinit var statusText:           MaterialTextView
    private lateinit var statusDot:            View
    private lateinit var surrenderBtn:         MaterialButton
    private lateinit var missionLabel:         MaterialTextView
    private lateinit var tvMicroGoal:          MaterialTextView
    private lateinit var tvEmotionalSnapshot:  MaterialTextView

    // ── Views — Iron Door overlay ─────────────────────────────
    private lateinit var ironDoorOverlay:      View
    private lateinit var tvIronDoorTitle:      MaterialTextView
    private lateinit var tvIronDoorGoal:       MaterialTextView
    private lateinit var tvIronDoorCountdown:  MaterialTextView
    private lateinit var tvExcusePrompt:       MaterialTextView
    private lateinit var etExcuse:             EditText
    private lateinit var tvExcuseValidation:   MaterialTextView
    private lateinit var btnConfirmAbandon:    MaterialButton
    private lateinit var btnCancelAbandon:     MaterialButton

    // ── Timer ─────────────────────────────────────────────────
    private val handler        = Handler(Looper.getMainLooper())
    private var sessionStartMs = 0L
    private var remainingMs    = 0L
    private var totalMs        = 0L
    private var isRunning      = false

    // ── Haptic zone tracking ──────────────────────────────────
    private val hapticDelivered = mutableMapOf(2 to false, 3 to false)
    private lateinit var vibrator: Vibrator

    // ── Compromise tracking (from FocusService) ───────────────
    private var appLeftCount   = 0
    private var wasCompromised = false

    // ── Iron Door state ───────────────────────────────────────
    private var ironDoorActive     = false
    private var frictionCountdown  = 0
    private var excuseUnlocked     = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !isAdded) return
            remainingMs = (totalMs - (System.currentTimeMillis() - sessionStartMs))
                .coerceAtLeast(0)
            updateTimerDisplay(remainingMs)
            radarView.setProgress(remainingMs.toFloat() / totalMs.toFloat())
            checkHapticZone(remainingMs, totalMs)
            if (remainingMs <= 0) onSessionSuccess()
            else handler.postDelayed(this, 1_000)
        }
    }

    // ── BroadcastReceiver ─────────────────────────────────────
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

                // App left background — service detected it
                FocusService.ACTION_APP_LEFT -> {
                    appLeftCount   = intent.getIntExtra(
                        FocusService.EXTRA_APP_LEFT_COUNT, 0)
                    wasCompromised = intent.getBooleanExtra(
                        FocusService.EXTRA_WAS_COMPROMISED, false)
                    // Just flag it — don't interrupt the session UI
                    updateMissionLabel("YOU LEFT — $appLeftCount TIME(S)")
                }

                FocusService.ACTION_APP_RETURNED -> {
                    appLeftCount   = intent.getIntExtra(
                        FocusService.EXTRA_APP_LEFT_COUNT, 0)
                    wasCompromised = intent.getBooleanExtra(
                        FocusService.EXTRA_WAS_COMPROMISED, false)
                    if (isRunning) updateMissionLabel("MISSION IN PROGRESS")
                }

                FocusService.ACTION_SURRENDERED -> onSurrenderConfirmed()
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_mission, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        totalMs     = args.durationMs
        remainingMs = totalMs

        vibrator = requireContext()
            .getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Read mode from SharedPrefs
        val prefs = requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
        operativeMode = ModeConfigProvider.fromPrefs(prefs)

        bindViews(view)
        displayPreContractData()
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

    // ══════════════════════════════════════════════════════════
    // BIND VIEWS
    // ══════════════════════════════════════════════════════════

    private fun bindViews(v: View) {
        // Main session
        radarView           = v.findViewById(R.id.radarView)
        timerText           = v.findViewById(R.id.timerText)
        statusText          = v.findViewById(R.id.statusText)
        statusDot           = v.findViewById(R.id.statusDot)
        surrenderBtn        = v.findViewById(R.id.surrenderButton)
        missionLabel        = v.findViewById(R.id.missionLabel)
        tvMicroGoal         = v.findViewById(R.id.tvMicroGoal)
        tvEmotionalSnapshot = v.findViewById(R.id.tvEmotionalSnapshot)

        // Iron Door overlay
        ironDoorOverlay     = v.findViewById(R.id.ironDoorOverlay)
        tvIronDoorTitle     = v.findViewById(R.id.tvIronDoorTitle)
        tvIronDoorGoal      = v.findViewById(R.id.tvIronDoorGoalReminder)
        tvIronDoorCountdown = v.findViewById(R.id.tvIronDoorCountdown)
        tvExcusePrompt      = v.findViewById(R.id.tvExcusePrompt)
        etExcuse            = v.findViewById(R.id.etExcuse)
        tvExcuseValidation  = v.findViewById(R.id.tvExcuseValidation)
        btnConfirmAbandon   = v.findViewById(R.id.btnConfirmAbandon)
        btnCancelAbandon    = v.findViewById(R.id.btnCancelAbandon)

        // Abandon button → triggers Iron Door (NOT instant exit)
        surrenderBtn.setOnClickListener { openIronDoor() }

        // Cancel → return to session
        btnCancelAbandon.setOnClickListener { closeIronDoor() }

        // Confirm abandon → validate excuse then exit
        btnConfirmAbandon.setOnClickListener { attemptConfirmAbandon() }

        // Excuse text watcher
        etExcuse.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (excuseUnlocked) onExcuseTextChanged(s?.toString() ?: "")
            }
        })
    }

    // ══════════════════════════════════════════════════════════
    // PRE-CONTRACT DATA DISPLAY
    // ══════════════════════════════════════════════════════════

    private fun displayPreContractData() {
        val goal     = args.microGoal ?: ""
        val snapshot = args.emotionalSnapshot ?: ""

        tvMicroGoal.text = if (goal.isNotBlank()) "\"$goal\"" else ""
        tvEmotionalSnapshot.text = snapshot
    }

    // ══════════════════════════════════════════════════════════
    // ARMING SEQUENCE
    // ══════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════
    // IRON DOOR — Abandon flow
    // ══════════════════════════════════════════════════════════

    private fun openIronDoor() {
        if (ironDoorActive) return
        ironDoorActive  = true
        excuseUnlocked  = false
        val config      = ModeConfigProvider.get(operativeMode)
        val goal        = args.microGoal ?: ""

        // Populate overlay
        tvIronDoorGoal.text = if (goal.isNotBlank())
            "Your goal was:\n\"$goal\"" else ""

        // Show overlay
        ironDoorOverlay.visibility = View.VISIBLE
        ironDoorOverlay.alpha      = 0f
        ironDoorOverlay.animate()
            .alpha(1f)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Reset excuse input
        etExcuse.setText("")
        etExcuse.visibility          = View.GONE
        tvExcusePrompt.visibility    = View.GONE
        tvExcuseValidation.visibility = View.GONE
        btnConfirmAbandon.visibility  = View.GONE
        btnConfirmAbandon.isEnabled  = false
        btnConfirmAbandon.alpha      = 0.3f

        when {
            // RECRUIT — no friction, no excuse required
            !config.excuseRequired -> {
                tvIronDoorCountdown.text = ""
                unlockExcuseInput(skipExcuse = true)
            }

            // OPERATIVE / GHOST — mandatory wait first
            else -> {
                frictionCountdown = config.excuseFrictionSec
                runFrictionCountdown()
            }
        }
    }

    private fun runFrictionCountdown() {
        tvIronDoorCountdown.text =
            "Take $frictionCountdown seconds.\nThink about why you really left."

        val r = object : Runnable {
            override fun run() {
                if (!isAdded || !ironDoorActive) return
                frictionCountdown--
                if (frictionCountdown > 0) {
                    tvIronDoorCountdown.text =
                        "Take $frictionCountdown seconds.\nThink about why you really left."
                    handler.postDelayed(this, 1_000)
                } else {
                    tvIronDoorCountdown.text = "Now write it honestly."
                    unlockExcuseInput(skipExcuse = false)
                }
            }
        }
        handler.postDelayed(r, 1_000)
    }

    private fun unlockExcuseInput(skipExcuse: Boolean) {
        if (skipExcuse) {
            // RECRUIT — skip excuse, show confirm directly
            btnConfirmAbandon.visibility = View.VISIBLE
            btnConfirmAbandon.isEnabled  = true
            btnConfirmAbandon.alpha      = 1f
            excuseUnlocked = true
            return
        }

        excuseUnlocked = true

        tvExcusePrompt.visibility = View.VISIBLE
        etExcuse.visibility       = View.VISIBLE
        btnConfirmAbandon.visibility = View.VISIBLE
        tvExcuseValidation.visibility = View.VISIBLE

        etExcuse.requestFocus()
    }

    private fun onExcuseTextChanged(text: String) {
        val result = ExcuseValidator.validate(text)

        when (result) {
            ExcuseValidator.Result.VALID -> {
                tvExcuseValidation.text = ""
                tvExcuseValidation.visibility = View.GONE
                btnConfirmAbandon.isEnabled   = true
                btnConfirmAbandon.alpha       = 1f
            }
            ExcuseValidator.Result.TOO_SHORT -> {
                tvExcuseValidation.text = "Write at least 3 words."
                tvExcuseValidation.visibility = View.VISIBLE
                btnConfirmAbandon.isEnabled   = false
                btnConfirmAbandon.alpha       = 0.3f
            }
            ExcuseValidator.Result.JUNK -> {
                tvExcuseValidation.text = "That's not an excuse. Try again."
                tvExcuseValidation.visibility = View.VISIBLE
                btnConfirmAbandon.isEnabled   = false
                btnConfirmAbandon.alpha       = 0.3f
            }
        }
    }

    private fun attemptConfirmAbandon() {
        val excuse = etExcuse.text.toString().trim()
        val config = ModeConfigProvider.get(operativeMode)

        if (config.excuseRequired) {
            val result = ExcuseValidator.validate(excuse)
            if (result != ExcuseValidator.Result.VALID) {
                // Re-trigger friction — reset and wait again
                etExcuse.setText("")
                excuseUnlocked = false
                frictionCountdown = config.excuseFrictionSec
                tvExcuseValidation.text = ""
                tvExcuseValidation.visibility = View.GONE
                btnConfirmAbandon.isEnabled   = false
                btnConfirmAbandon.alpha       = 0.3f
                tvIronDoorCountdown.text = "Take $frictionCountdown seconds.\nThink about why you really left."
                runFrictionCountdown()
                return
            }
        }

        // Valid — trigger surrender with excuse data passed via Intent
        isRunning = false
        handler.removeCallbacks(timerRunnable)
        ironDoorActive = false

        val elapsed = totalMs - remainingMs

        // Navigate directly — pass excuse + compromise data to ReportFragment
        val action = MissionFragmentDirections.actionMissionToReport(
            isSuccess         = false,
            durationMs        = elapsed,
            microGoal         = args.microGoal ?: "",
            exitExcuse        = excuse,
            wasCompromised    = wasCompromised,
            appLeftCount      = appLeftCount,
            emotionalSnapshot = args.emotionalSnapshot ?: ""
        )

        FocusService.stop(requireContext())
        findNavController().navigate(action)
    }

    private fun closeIronDoor() {
        if (!ironDoorActive) return
        ironDoorActive = false
        handler.removeCallbacksAndMessages(null)

        ironDoorOverlay.animate()
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                ironDoorOverlay.visibility = View.GONE
                // Resume timer
                if (isRunning) handler.post(timerRunnable)
            }
            .start()
    }

    // ══════════════════════════════════════════════════════════
    // SESSION EVENTS
    // ══════════════════════════════════════════════════════════

    private fun onSessionSuccess() {
        isRunning = false
        FocusService.stop(requireContext())
        setUIState(UIState.SUCCESS)

        handler.postDelayed({
            if (!isAdded) return@postDelayed
            val action = MissionFragmentDirections.actionMissionToReport(
                isSuccess         = true,
                durationMs        = totalMs,
                microGoal         = args.microGoal ?: "",
                exitExcuse        = "",
                wasCompromised    = wasCompromised,
                appLeftCount      = appLeftCount,
                emotionalSnapshot = args.emotionalSnapshot ?: ""
            )
            findNavController().navigate(action)
        }, 2_000)
    }

    private fun onSurrenderConfirmed() {
        // Called if service sends ACTION_SURRENDERED directly
        // (e.g. system kill) — not the normal flow in Phase 2
        if (!isAdded) return
        isRunning = false
        handler.removeCallbacks(timerRunnable)
    }

    // ══════════════════════════════════════════════════════════
    // UI STATES
    // ══════════════════════════════════════════════════════════

    private enum class UIState { ARMING, ARMED, SUCCESS }

    private fun setUIState(state: UIState) {
        val RED   = Color.parseColor("#CC3300")
        val GREEN = Color.parseColor("#00CC44")
        val WHITE = Color.parseColor("#E8E8E8")
        val DIM   = Color.parseColor("#1A1A1A")

        when (state) {
            UIState.ARMING -> {
                statusText.text = "ARMING SEQUENCE"
                statusText.setTextColor(RED)
                timerText.setTextColor(DIM)
                missionLabel.text = "PREPARING MINE"
                radarView.setState(RadarView.RadarState.ARMING)
            }
            UIState.ARMED -> {
                statusText.text = "MINE ARMED"
                statusText.setTextColor(RED)
                timerText.setTextColor(WHITE)
                missionLabel.text = "MISSION IN PROGRESS"
                radarView.setState(RadarView.RadarState.ARMED)
            }
            UIState.SUCCESS -> {
                statusText.text = "MISSION COMPLETE"
                statusText.setTextColor(GREEN)
                timerText.setTextColor(GREEN)
                missionLabel.text = "OBJECTIVE ACHIEVED"
                surrenderBtn.visibility = View.GONE
                radarView.setState(RadarView.RadarState.SUCCESS)
            }
        }
    }

    private fun updateMissionLabel(text: String) {
        missionLabel.text = text
    }

    // ══════════════════════════════════════════════════════════
    // HAPTIC ZONES — 3 zones, each fires ONCE
    // ══════════════════════════════════════════════════════════

    private fun checkHapticZone(remainingMs: Long, totalMs: Long) {
        val ratio = remainingMs.toFloat() / totalMs.toFloat()
        when {
            ratio <= 0.05f -> deliverZoneHaptic(3)
            ratio <= 0.25f -> deliverZoneHaptic(2)
        }
    }

    private fun deliverZoneHaptic(zone: Int) {
        if (hapticDelivered[zone] == true) return
        hapticDelivered[zone] = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (zone) {
                    2 -> vibrator.vibrate(
                        VibrationEffect.createOneShot(
                            800L, VibrationEffect.DEFAULT_AMPLITUDE
                        )
                    )
                    3 -> vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 200, 150, 200, 150, 200), -1
                        )
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                when (zone) {
                    2 -> vibrator.vibrate(800L)
                    3 -> vibrator.vibrate(longArrayOf(0, 200, 150, 200, 150, 200), -1)
                }
            }
        } catch (e: Exception) { /* silent */ }
    }

    // ══════════════════════════════════════════════════════════
    // TIMER DISPLAY
    // ══════════════════════════════════════════════════════════

    private fun updateTimerDisplay(ms: Long) {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        timerText.text = String.format("%02d:%02d:%02d", h, m, s)

        // Zone 2: last 25% — color shifts toward amber
        val ratio = if (totalMs > 0) ms.toFloat() / totalMs.toFloat() else 1f
        when {
            ratio <= 0.05f -> timerText.setTextColor(Color.parseColor("#CC3300"))
            ratio <= 0.25f -> {
                val amber = Color.parseColor("#CC8800")
                val white = Color.parseColor("#E8E8E8")
                timerText.setTextColor(blendColors(amber, white, ratio * 4f))
            }
        }
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val r = (Color.red(c1)   * (1 - ratio) + Color.red(c2)   * ratio).toInt()
        val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
        val b = (Color.blue(c1)  * (1 - ratio) + Color.blue(c2)  * ratio).toInt()
        return Color.rgb(r.coerceIn(0,255), g.coerceIn(0,255), b.coerceIn(0,255))
    }

    // ══════════════════════════════════════════════════════════
    // ENTRANCE ANIMATION
    // ══════════════════════════════════════════════════════════

    private fun playEntrance() {
        listOf(R.id.topBar, R.id.tvMicroGoal, R.id.radarView,
               R.id.timerText, R.id.statusContainer, R.id.bottomBar)
            .forEachIndexed { i, id ->
                view?.findViewById<View>(id)?.apply {
                    alpha = 0f
                    translationY = 14f
                    animate().alpha(1f).translationY(0f)
                        .setDuration(360)
                        .setStartDelay((i * 80).toLong())
                        .setInterpolator(DecelerateInterpolator(2f))
                        .start()
                }
            }
    }

    // ══════════════════════════════════════════════════════════
    // BROADCAST RECEIVER
    // ══════════════════════════════════════════════════════════

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ARMED)
            addAction(FocusService.ACTION_SURRENDERED)
            addAction(FocusService.ACTION_APP_LEFT)
            addAction(FocusService.ACTION_APP_RETURNED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            requireContext().registerReceiver(
                receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

// ══════════════════════════════════════════════════════════════
// ExcuseValidator — no AI, no paid API
// Three checks: empty → word count → entropy
// ══════════════════════════════════════════════════════════════

object ExcuseValidator {

    enum class Result { VALID, TOO_SHORT, JUNK }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.TOO_SHORT

        val words = trimmed.split("\\s+".toRegex())
            .filter { it.length >= 2 }
        if (words.size < 3) return Result.TOO_SHORT

        if (isKeyboardMash(trimmed)) return Result.JUNK

        return Result.VALID
    }

    private fun isKeyboardMash(input: String): Boolean {
        val letters = input.lowercase().filter { it.isLetter() }
        if (letters.length < 4) return false

        // Keyboard row patterns
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val hasRowPattern = rows.any { row ->
            (0..row.length - 4).any { i ->
                letters.contains(row.substring(i, i + 4))
            }
        }
        if (hasRowPattern) return true

        // Low character diversity
        val uniqueRatio = letters.toSet().size.toFloat() / letters.length
        if (uniqueRatio < 0.35f) return true

        // High repetition
        val maxRepeat = letters.groupBy { it }.maxOf { it.value.size }
        if (maxRepeat.toFloat() / letters.length > 0.45f) return true

        return false
    }
}

