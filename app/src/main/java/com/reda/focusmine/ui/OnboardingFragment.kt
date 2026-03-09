package com.reda.focusmine.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.R
import com.reda.focusmine.data.ModeConfigProvider
import com.reda.focusmine.data.OperativeMode

// ══════════════════════════════════════════════════════════════
// OnboardingFragment — الحاوية الرئيسية (ViewPager2)
//
// PHASE 2 CHANGES:
//   - Added Page 4: Mode Selection (RECRUIT / OPERATIVE / GHOST)
//   - Mode is saved to SharedPrefs and LOCKED — never changeable
//   - Total pages: 4 (was 3)
//
// Layout IDs (fragment_onboarding.xml — unchanged):
//   onboardingPager, btnNext, pageIndicator
// ══════════════════════════════════════════════════════════════

class OnboardingFragment : Fragment() {

    private lateinit var pager:     ViewPager2
    private lateinit var btnNext:   MaterialButton
    private lateinit var indicator: MaterialTextView

    private val totalPages = 4

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pager     = view.findViewById(R.id.onboardingPager)
        btnNext   = view.findViewById(R.id.btnNext)
        indicator = view.findViewById(R.id.pageIndicator)

        pager.adapter = PagerAdapter(this)
        pager.isUserInputEnabled = false

        updateUI(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateUI(position)
        })

        btnNext.setOnClickListener {
            when (pager.currentItem) {
                totalPages - 2 -> {
                    // Page 4 (Mode Selection) — validate mode chosen
                    val prefs = requireContext()
                        .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
                    val modeSet = prefs.getString("operative_mode", null)
                    if (modeSet == null) {
                        // Flash indicator — mode not chosen yet
                        indicator.setTextColor(Color.parseColor("#CC3300"))
                        indicator.text = "CHOOSE YOUR MODE FIRST"
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isAdded) updateUI(pager.currentItem)
                        }, 1_800)
                        return@setOnClickListener
                    }
                    pager.currentItem = pager.currentItem + 1
                }
                totalPages - 1 -> {
                    // Last page — finish onboarding
                    finishOnboarding()
                }
                else -> pager.currentItem = pager.currentItem + 1
            }
        }
    }

    private fun updateUI(position: Int) {
        indicator.setTextColor(Color.parseColor("#666666"))
        indicator.text = "${position + 1} / $totalPages"

        btnNext.text = when (position) {
            totalPages - 1 -> "BEGIN"
            else           -> "NEXT"
        }
    }

    private fun finishOnboarding() {
        requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_done", true)
            .apply()
        findNavController().navigate(R.id.action_onboarding_to_home)
    }

    // ── ViewPager2 Adapter ────────────────────────────────────
    inner class PagerAdapter(f: Fragment) : FragmentStateAdapter(f) {
        override fun getItemCount() = totalPages
        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> OnboardingPage1()
            1    -> OnboardingPage2()
            2    -> OnboardingPage3()
            3    -> OnboardingPageMode()
            else -> OnboardingPage1()
        }
    }
}

// ══════════════════════════════════════════════════════════════
// PAGE 1 — The Manifesto (unchanged from Phase 1)
// Layout: fragment_onboarding_p1.xml
// ══════════════════════════════════════════════════════════════
class OnboardingPage1 : Fragment() {
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_onboarding_p1, c, false)
}

// ══════════════════════════════════════════════════════════════
// PAGE 2 — Demo (unchanged from Phase 1)
// Layout: fragment_onboarding_p2.xml
// ══════════════════════════════════════════════════════════════
class OnboardingPage2 : Fragment() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_onboarding_p2, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnDemo     = view.findViewById<MaterialButton>(R.id.btnDemo)
        val demoOverlay = view.findViewById<View>(R.id.demoRedOverlay)
        val demoTimer   = view.findViewById<MaterialTextView>(R.id.demoTimer)
        val demoStatus  = view.findViewById<MaterialTextView>(R.id.demoStatus)

        btnDemo.setOnClickListener {
            btnDemo.isEnabled = false
            demoStatus.text   = "MINE ARMED"
            var sec = 5
            val r = object : Runnable {
                override fun run() {
                    if (!isAdded) return
                    if (sec > 0) {
                        demoTimer.text = "00:00:0$sec"
                        sec--
                        handler.postDelayed(this, 1_000)
                    } else {
                        demoOverlay.visibility = View.VISIBLE
                        demoOverlay.alpha      = 0f
                        demoOverlay.animate().alpha(0.85f).setDuration(400).start()
                        demoStatus.text = "DETONATED"
                        handler.postDelayed({
                            if (!isAdded) return@postDelayed
                            demoOverlay.animate().alpha(0f).setDuration(600)
                                .withEndAction {
                                    demoOverlay.visibility = View.GONE
                                    demoStatus.text = "TRY AGAIN"
                                    btnDemo.isEnabled = true
                                }.start()
                        }, 1_500)
                    }
                }
            }
            handler.post(r)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}

// ══════════════════════════════════════════════════════════════
// PAGE 3 — Duration Selection (unchanged from Phase 1)
// Layout: fragment_onboarding_p3.xml
// ══════════════════════════════════════════════════════════════
class OnboardingPage3 : Fragment() {
    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_onboarding_p3, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)

        val durations = mapOf(
            R.id.btn30min to 30 * 60_000L,
            R.id.btn1h    to 60 * 60_000L,
            R.id.btn2h    to 120 * 60_000L,
            R.id.btn3h    to 180 * 60_000L
        )

        // Default: 1 hour
        prefs.edit().putLong("default_duration_ms", durations[R.id.btn1h]!!).apply()

        val WHITE = Color.parseColor("#E8E8E8")
        val RED   = Color.parseColor("#CC3300")

        durations.keys.forEach { btnId ->
            view.findViewById<MaterialButton>(btnId)?.setOnClickListener {
                prefs.edit()
                    .putLong("default_duration_ms", durations[btnId]!!)
                    .apply()
                // Visual feedback
                durations.keys.forEach { id ->
                    view.findViewById<MaterialButton>(id)
                        ?.setTextColor(WHITE)
                }
                (it as MaterialButton).setTextColor(RED)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// PAGE 4 — Mode Selection (NEW in Phase 2)
// Layout: fragment_onboarding_page_mode.xml  ← NEW layout needed
//
// CRITICAL: This choice is PERMANENT.
// Once saved, the mode CANNOT be changed.
// ══════════════════════════════════════════════════════════════
class OnboardingPageMode : Fragment() {

    private lateinit var prefs: SharedPreferences
    private var selectedMode: OperativeMode? = null

    private lateinit var btnRecruit:   MaterialButton
    private lateinit var btnOperative: MaterialButton
    private lateinit var btnGhost:     MaterialButton
    private lateinit var tvWarning:    MaterialTextView
    private lateinit var tvModeDesc:   MaterialTextView

    override fun onCreateView(
        i: LayoutInflater, c: ViewGroup?, s: Bundle?
    ): View = i.inflate(R.layout.fragment_onboarding_page_mode, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)

        btnRecruit   = view.findViewById(R.id.btnModeRecruit)
        btnOperative = view.findViewById(R.id.btnModeOperative)
        btnGhost     = view.findViewById(R.id.btnModeGhost)
        tvWarning    = view.findViewById(R.id.tvModeWarning)
        tvModeDesc   = view.findViewById(R.id.tvModeDescription)

        btnRecruit.setOnClickListener   { selectMode(OperativeMode.RECRUIT) }
        btnOperative.setOnClickListener { selectMode(OperativeMode.OPERATIVE) }
        btnGhost.setOnClickListener     { selectMode(OperativeMode.GHOST) }
    }

    private fun selectMode(mode: OperativeMode) {
        selectedMode = mode
        val config   = ModeConfigProvider.get(mode)

        // Save immediately — permanent
        ModeConfigProvider.saveToPrefs(prefs, mode)

        // Update description
        tvModeDesc.text = config.displayDescription

        // Update button states
        val RED   = Color.parseColor("#CC3300")
        val WHITE = Color.parseColor("#E8E8E8")
        val DIM   = Color.parseColor("#333333")

        listOf(
            btnRecruit   to OperativeMode.RECRUIT,
            btnOperative to OperativeMode.OPERATIVE,
            btnGhost     to OperativeMode.GHOST
        ).forEach { (btn, btnMode) ->
            btn.setTextColor(if (btnMode == mode) RED else WHITE)
            btn.setBackgroundColor(if (btnMode == mode) Color.parseColor("#1A0000") else DIM)
        }

        // Show warning — this choice is forever
        tvWarning.visibility = View.VISIBLE
        tvWarning.text       = "YOU CANNOT CHANGE THIS.\n${config.displayName} LOCKED."
        tvWarning.alpha      = 0f
        tvWarning.animate()
            .alpha(1f)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
