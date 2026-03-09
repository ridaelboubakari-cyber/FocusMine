package com.reda.focusmine.ui

import android.content.Context
import android.graphics.Color
import android.os.*
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.R
import com.reda.focusmine.data.ModeConfigProvider

// ══════════════════════════════════════════════════════════════
// PreContractFragment — Phase 3
//
// Sits between HomeFragment and MissionFragment.
// Nav: Home → PreContract → Mission
//
// The user MUST:
//   1. Type their microGoal (min 4 words)
//   2. Optionally type an emotionalSnapshot
//   3. Hold the COMMIT button for 3 seconds
//      → heavy haptic → navigate to Mission
//
// The 3-second physical hold is The Iron Door entrance.
// ══════════════════════════════════════════════════════════════

class PreContractFragment : Fragment() {

    private val args: PreContractFragmentArgs by navArgs()

    // ── Views ─────────────────────────────────────────────────
    private lateinit var etGoal:           EditText
    private lateinit var etSnapshot:       EditText
    private lateinit var tvGoalCounter:    MaterialTextView
    private lateinit var tvValidation:     MaterialTextView
    private lateinit var btnCommit:        MaterialButton
    private lateinit var commitProgressBar: View

    // ── State ─────────────────────────────────────────────────
    private val handler       = Handler(Looper.getMainLooper())
    private var isHolding     = false
    private var commitComplete = false
    private var holdStartMs    = 0L

    private lateinit var vibrator: Vibrator

    private val HOLD_DURATION_MS = 3_000L
    private val TICK_INTERVAL_MS = 50L

    // ── Progress bar animation ────────────────────────────────
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!isAdded || !isHolding || commitComplete) return
            val elapsed = System.currentTimeMillis() - holdStartMs
            val ratio   = (elapsed.toFloat() / HOLD_DURATION_MS).coerceIn(0f, 1f)

            commitProgressBar.visibility = View.VISIBLE
            commitProgressBar.scaleX     = ratio

            if (elapsed >= HOLD_DURATION_MS) {
                onCommitComplete()
            } else {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_pre_contract, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vibrator = requireContext()
            .getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        bindViews(view)
        setupTextWatchers()
        setupCommitButton()
        playEntrance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }

    // ══════════════════════════════════════════════════════════
    // BIND
    // ══════════════════════════════════════════════════════════

    private fun bindViews(v: View) {
        etGoal           = v.findViewById(R.id.etMicroGoal)
        etSnapshot       = v.findViewById(R.id.etEmotionalSnapshot)
        tvGoalCounter    = v.findViewById(R.id.tvGoalCounter)
        tvValidation     = v.findViewById(R.id.tvCommitValidation)
        btnCommit        = v.findViewById(R.id.btnCommit)
        commitProgressBar = v.findViewById(R.id.commitProgressBar)
    }

    // ══════════════════════════════════════════════════════════
    // TEXT WATCHERS — unlock commit when goal is valid
    // ══════════════════════════════════════════════════════════

    private fun setupTextWatchers() {
        etGoal.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateGoal(s?.toString() ?: "")
            }
        })
    }

    private fun validateGoal(text: String) {
        val words = text.trim()
            .split("\\s+".toRegex())
            .filter { it.length >= 2 }
        val wordCount = words.size

        when {
            text.isBlank() -> {
                tvGoalCounter.text      = "Minimum 4 words"
                tvGoalCounter.setTextColor(Color.parseColor("#2A2A2A"))
                setCommitEnabled(false)
            }
            wordCount < 4 -> {
                tvGoalCounter.text      = "${4 - wordCount} more word(s) needed"
                tvGoalCounter.setTextColor(Color.parseColor("#CC3300"))
                setCommitEnabled(false)
            }
            else -> {
                tvGoalCounter.text      = "✓  Goal set"
                tvGoalCounter.setTextColor(Color.parseColor("#00CC44"))
                setCommitEnabled(true)
            }
        }
    }

    private fun setCommitEnabled(enabled: Boolean) {
        btnCommit.isEnabled = enabled
        btnCommit.alpha     = if (enabled) 1f else 0.3f
        if (!enabled) {
            commitProgressBar.visibility = View.INVISIBLE
            commitProgressBar.scaleX     = 0f
        }
    }

    // ══════════════════════════════════════════════════════════
    // HOLD-TO-COMMIT — The Iron Door
    // ══════════════════════════════════════════════════════════

    @Suppress("ClickableViewAccessibility")
    private fun setupCommitButton() {
        btnCommit.setOnTouchListener { _, event ->
            if (!btnCommit.isEnabled) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!commitComplete) {
                        isHolding    = true
                        holdStartMs  = System.currentTimeMillis()
                        handler.post(progressRunnable)
                        btnCommit.text = "HOLD..."
                    }
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!commitComplete) {
                        cancelHold()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun cancelHold() {
        isHolding = false
        handler.removeCallbacks(progressRunnable)

        commitProgressBar.animate()
            .scaleX(0f)
            .setDuration(200)
            .withEndAction {
                commitProgressBar.visibility = View.INVISIBLE
            }
            .start()

        btnCommit.text = "HOLD TO COMMIT"
    }

    private fun onCommitComplete() {
        if (commitComplete || !isAdded) return
        commitComplete = true
        isHolding      = false
        handler.removeCallbacks(progressRunnable)

        // 1. Full progress bar
        commitProgressBar.scaleX = 1f

        // 2. Heavy single haptic — "the door closes"
        deliverIronDoorHaptic()

        // 3. Validate one last time before navigating
        val goalText     = etGoal.text.toString().trim()
        val snapshotText = etSnapshot.text.toString().trim()

        val words = goalText.split("\\s+".toRegex()).filter { it.length >= 2 }
        if (words.size < 4) {
            // Should not happen but be safe
            commitComplete = false
            cancelHold()
            tvValidation.text       = "Please complete your goal first."
            tvValidation.visibility = View.VISIBLE
            return
        }

        // 4. Screen fade to black then navigate
        view?.animate()
            ?.alpha(0f)
            ?.setDuration(300)
            ?.setInterpolator(DecelerateInterpolator())
            ?.withEndAction {
                if (!isAdded) return@withEndAction
                navigateToMission(goalText, snapshotText)
            }
            ?.start()
    }

    private fun navigateToMission(goal: String, snapshot: String) {
        val prefs = requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
        val durationMs = prefs.getLong("default_duration_ms", 3_600_000L)

        val action = PreContractFragmentDirections
            .actionPreContractToMission(
                durationMs        = durationMs,
                microGoal         = goal,
                emotionalSnapshot = snapshot
            )
        findNavController().navigate(action)
    }

    // ══════════════════════════════════════════════════════════
    // HAPTIC — single heavy pulse, 600ms max amplitude
    // ══════════════════════════════════════════════════════════

    private fun deliverIronDoorHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(600L, 255)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(600L)
            }
        } catch (e: Exception) { /* silent */ }
    }

    // ══════════════════════════════════════════════════════════
    // ENTRANCE ANIMATION
    // ══════════════════════════════════════════════════════════

    private fun playEntrance() {
        listOf(
            R.id.tvPreContractHeader,
            R.id.tvPreContractTitle,
            R.id.tvPreContractSubtitle,
            R.id.tvGoalLabel,
            R.id.etMicroGoal,
            R.id.tvSnapshotLabel,
            R.id.etEmotionalSnapshot,
            R.id.btnCommit
        ).forEachIndexed { i, id ->
            view?.findViewById<View>(id)?.apply {
                alpha        = 0f
                translationY = 16f
                animate().alpha(1f).translationY(0f)
                    .setDuration(380)
                    .setStartDelay((i * 70).toLong())
                    .setInterpolator(DecelerateInterpolator(2f))
                    .start()
            }
        }
    }
}

