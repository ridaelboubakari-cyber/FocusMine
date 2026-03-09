package com.reda.focusmine.ui

import android.content.Context
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

// ══════════════════════════════════════════════════════════════
// OnboardingFragment — الحاوية الرئيسية (ViewPager2)
// ══════════════════════════════════════════════════════════════
class OnboardingFragment : Fragment() {

    private lateinit var pager:     ViewPager2
    private lateinit var btnNext:   MaterialButton
    private lateinit var indicator: MaterialTextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pager     = view.findViewById(R.id.onboardingPager)
        btnNext   = view.findViewById(R.id.btnNext)
        indicator = view.findViewById(R.id.pageIndicator)

        pager.adapter = PagerAdapter(this)
        pager.isUserInputEnabled = false

        updateUI(0)

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(p: Int) = updateUI(p)
        })

        btnNext.setOnClickListener {
            when (pager.currentItem) {
                0, 1 -> pager.currentItem++
                2    -> finish()
            }
        }
    }

    private fun updateUI(page: Int) {
        indicator.text = "${page + 1} / 3"
        btnNext.text = when (page) {
            0    -> "I ACCEPT THE RULES  →"
            1    -> "I FELT IT. PROCEED  →"
            else -> "START MY FIRST MISSION"
        }
    }

    private fun finish() {
        requireContext()
            .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("onboarding_done", true).apply()
        findNavController().navigate(R.id.action_onboarding_to_home)
    }

    private inner class PagerAdapter(f: Fragment) : FragmentStateAdapter(f) {
        override fun getItemCount() = 3
        override fun createFragment(p: Int): Fragment = when (p) {
            0    -> OnboardingPage1()
            1    -> OnboardingPage2()
            else -> OnboardingPage3()
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Page 1 — THE HOOK: القواعد المرعبة
// ══════════════════════════════════════════════════════════════
class OnboardingPage1 : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding_p1, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ids = listOf(
            R.id.p1Title, R.id.p1Divider, R.id.p1Sub,
            R.id.p1Rule1, R.id.p1Rule2, R.id.p1Rule3, R.id.p1WarningBox
        )
        ids.forEachIndexed { i, id ->
            view.findViewById<View>(id)?.apply {
                alpha = 0f
                translationY = 20f
                animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(420).setStartDelay((i * 110).toLong())
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// Page 2 — THE TASTE: تجربة اللغم المصغرة
// ══════════════════════════════════════════════════════════════
class OnboardingPage2 : Fragment() {

    private val handler  = Handler(Looper.getMainLooper())
    private lateinit var vibrator: Vibrator
    private var running = false

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding_p2, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val root       = view.findViewById<View>(R.id.p2Root)
        val btnDemo    = view.findViewById<MaterialButton>(R.id.btnDemo)
        val statusTv   = view.findViewById<MaterialTextView>(R.id.demoStatus)
        val timerTv    = view.findViewById<MaterialTextView>(R.id.demoTimer)
        val redOverlay = view.findViewById<View>(R.id.demoRedOverlay)

        btnDemo.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            btnDemo.visibility = View.INVISIBLE
            runDemo(root, statusTv, timerTv, redOverlay)
        }
    }

    private fun runDemo(
        root: View, status: MaterialTextView,
        timer: MaterialTextView, overlay: View
    ) {
        val RED   = 0xFFCC3300.toInt()
        val GREEN = 0xFF00CC44.toInt()
        val DIM   = 0xFF555555.toInt()

        // ── Fase 1: Arming ──
        status.text = "ARMING SEQUENCE..."; status.setTextColor(DIM)

        var t = 5
        val tick = object : Runnable {
            override fun run() {
                if (!isAdded) return
                timer.text = "00:00:0$t"
                if (t-- > 0) handler.postDelayed(this, 600)
            }
        }
        handler.post(tick)

        // ── Fase 2: Armed ──
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            status.text = "⚡ MINE ARMED — DO NOT MOVE"; status.setTextColor(RED)
            buzz(longArrayOf(0, 150, 80, 150), -1)
        }, 2_000)

        // ── Fase 3: BOOM ──
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            status.text = "💥  MINE DETONATED"; status.setTextColor(RED)
            timer.setTextColor(RED)
            buzz(longArrayOf(0, 600, 150, 600, 150, 800), -1)
            flashRed(overlay)
        }, 4_200)

        // ── Fase 4: Done ──
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            overlay.animate().alpha(0f).setDuration(500).start()
            status.text = "NOW YOU KNOW THE STAKES"; status.setTextColor(GREEN)
            timer.text = "DEMO COMPLETE"; timer.setTextColor(GREEN)
        }, 7_500)
    }

    private fun flashRed(overlay: View) {
        overlay.alpha = 0f; overlay.visibility = View.VISIBLE
        var count = 0
        val flash = object : Runnable {
            override fun run() {
                if (!isAdded || count >= 8) return
                overlay.animate().alpha(if (count % 2 == 0) 0.55f else 0.05f)
                    .setDuration(180).start()
                count++
                handler.postDelayed(this, 200)
            }
        }
        handler.post(flash)
    }

    private fun buzz(pattern: LongArray, repeat: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            else @Suppress("DEPRECATION") vibrator.vibrate(pattern, repeat)
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        try { vibrator.cancel() } catch (_: Exception) {}
    }
}

// ══════════════════════════════════════════════════════════════
// Page 3 — COMMITMENT: اختيار مدة الجلسة
// ══════════════════════════════════════════════════════════════
class OnboardingPage3 : Fragment() {

    private val durations = linkedMapOf(
        R.id.btn30min to 30 * 60_000L,
        R.id.btn1h    to  60 * 60_000L,
        R.id.btn2h    to 120 * 60_000L,
        R.id.btn3h    to 180 * 60_000L
    )
    private var selectedId = R.id.btn1h

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_onboarding_p3, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireContext().getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("default_duration_ms", durations[R.id.btn1h]!!).apply()

        durations.keys.forEach { id ->
            view.findViewById<MaterialButton>(id)?.setOnClickListener {
                selectedId = id
                prefs.edit().putLong("default_duration_ms", durations[id]!!).apply()
                refreshButtons(view)
            }
        }
        refreshButtons(view)
    }

    private fun refreshButtons(view: View) {
        val RED_STROKE  = android.content.res.ColorStateList.valueOf(0xFFCC3300.toInt())
        val GREY_STROKE = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
        durations.keys.forEach { id ->
            view.findViewById<MaterialButton>(id)?.apply {
                if (id == selectedId) {
                    strokeColor = RED_STROKE
                    setBackgroundColor(0xFF150000.toInt())
                } else {
                    strokeColor = GREY_STROKE
                    setBackgroundColor(0xFF0D0D0D.toInt())
                }
            }
        }
    }
}
