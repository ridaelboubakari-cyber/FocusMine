package com.reda.focusmine.ui

import android.content.Context
import android.os.*
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.R

/**
 * OnboardingFragment — 3 شاشات تعريفية
 *
 * Page 1: The Hook     — القواعد المرعبة
 * Page 2: The Taste    — تجربة اللغم 5 ثواني
 * Page 3: Commitment   — اختيار مدة الجلسة الأولى
 */
class OnboardingFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext:   MaterialButton
    private lateinit var pageIndicator: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager     = view.findViewById(R.id.onboardingViewPager)
        btnNext       = view.findViewById(R.id.btnOnboardingNext)
        pageIndicator = view.findViewById(R.id.pageIndicator)

        viewPager.adapter = OnboardingPagerAdapter(this)
        viewPager.isUserInputEnabled = false // نتحكم بالـ swipe يدوياً

        updateIndicator(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                updateButtonLabel(position)
            }
        })

        btnNext.setOnClickListener {
            when (viewPager.currentItem) {
                0 -> viewPager.currentItem = 1
                1 -> viewPager.currentItem = 2
                2 -> onOnboardingComplete()
            }
        }
    }

    private fun updateIndicator(page: Int) {
        pageIndicator.text = "${page + 1} / 3"
    }

    private fun updateButtonLabel(page: Int) {
        btnNext.text = when (page) {
            0    -> "I UNDERSTAND THE RULES →"
            1    -> "I FELT IT. CONTINUE →"
            else -> "START MY FIRST MISSION"
        }
    }

    private fun onOnboardingComplete() {
        requireContext()
            .getSharedPreferences("focusmine_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_complete", true)
            .apply()

        findNavController().navigate(R.id.action_onboarding_to_home)
    }

    // ─── ViewPager2 Adapter ───────────────────────────────────────
    private inner class OnboardingPagerAdapter(fragment: Fragment)
        : FragmentStateAdapter(fragment) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> OnboardingPage1Fragment()
            1    -> OnboardingPage2Fragment()
            else -> OnboardingPage3Fragment()
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// Page 1 — The Hook: القواعد المرعبة
// ══════════════════════════════════════════════════════════════════
class OnboardingPage1Fragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_page1, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // دخول متتابع للعناصر
        val elements = listOf<View>(
            view.findViewById(R.id.p1Title),
            view.findViewById(R.id.p1Subtitle),
            view.findViewById(R.id.p1Rule1),
            view.findViewById(R.id.p1Rule2),
            view.findViewById(R.id.p1Rule3),
            view.findViewById(R.id.p1Warning)
        )
        elements.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24f
            v.animate()
                .alpha(1f).translationY(0f)
                .setDuration(400)
                .setStartDelay((i * 150).toLong())
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// Page 2 — The Taste: تجربة اللغم
// ══════════════════════════════════════════════════════════════════
class OnboardingPage2Fragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var vibrator: Vibrator
    private var demoStarted = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_page2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vibrator = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val btnDemo    = view.findViewById<MaterialButton>(R.id.btnStartDemo)
        val demoStatus = view.findViewById<MaterialTextView>(R.id.demoStatus)
        val demoTimer  = view.findViewById<MaterialTextView>(R.id.demoTimer)
        val rootView   = view

        btnDemo.setOnClickListener {
            if (demoStarted) return@setOnClickListener
            demoStarted = true
            btnDemo.visibility = View.GONE
            startMockMineDemo(rootView, demoStatus, demoTimer)
        }
    }

    private fun startMockMineDemo(
        root: View,
        statusText: MaterialTextView,
        timerText: MaterialTextView
    ) {
        // Phase 1 — Arming (2 ثواني)
        statusText.text = "⏳ ARMING..."
        statusText.setTextColor(0xFFCC3300.toInt())

        var countdown = 5
        val countRunnable = object : Runnable {
            override fun run() {
                timerText.text = "00:00:0$countdown"
                if (countdown > 0) {
                    countdown--
                    handler.postDelayed(this, 1_000)
                }
            }
        }
        handler.post(countRunnable)

        // Phase 2 — Armed (بعد ثانيتين)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            statusText.text = "🚨 MINE ARMED"
            triggerGraceVibration()
        }, 2_000)

        // Phase 3 — BOOM (بعد 4 ثواني)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            statusText.text = "💥 MINE TRIGGERED!"
            timerText.setTextColor(0xFFCC3300.toInt())
            triggerExplosionEffect(root, statusText)
        }, 4_000)

        // Phase 4 — Reset (بعد 7 ثواني)
        handler.postDelayed({
            if (!isAdded) return@postDelayed
            statusText.text = "✅ NOW YOU KNOW THE STAKES"
            statusText.setTextColor(0xFF00CC44.toInt())
            timerText.text = "DEMO COMPLETE"
            timerText.setTextColor(0xFF00CC44.toInt())
            root.setBackgroundColor(0xFF080808.toInt())
        }, 7_000)
    }

    private fun triggerGraceVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 200, 100, 200)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
            }
        } catch (e: Exception) { }
    }

    private fun triggerExplosionEffect(root: View, statusText: MaterialTextView) {
        // اهتزاز عنيف
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 500, 150, 500, 150, 800)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 500, 150, 500, 150, 800), -1)
            }
        } catch (e: Exception) { }

        // فلاش أحمر على الخلفية
        val flashRunnable = object : Runnable {
            var count = 0
            override fun run() {
                if (!isAdded || count >= 6) {
                    root.setBackgroundColor(0xFF0D0000.toInt())
                    return
                }
                root.setBackgroundColor(
                    if (count % 2 == 0) 0xFF330000.toInt() else 0xFF080808.toInt()
                )
                count++
                handler.postDelayed(this, 200)
            }
        }
        handler.post(flashRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        try { vibrator.cancel() } catch (e: Exception) { }
    }
}

// ══════════════════════════════════════════════════════════════════
// Page 3 — Commitment: اختيار مدة الجلسة
// ══════════════════════════════════════════════════════════════════
class OnboardingPage3Fragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_page3, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireContext()
            .getSharedPreferences("focusmine_prefs", Context.MODE_PRIVATE)

        val options = listOf(
            view.findViewById<MaterialButton>(R.id.btn30min),
            view.findViewById<MaterialButton>(R.id.btn1h),
            view.findViewById<MaterialButton>(R.id.btn2h),
            view.findViewById<MaterialButton>(R.id.btn3h)
        )
        val durations = listOf(
            30 * 60 * 1000L,
            60 * 60 * 1000L,
            2 * 60 * 60 * 1000L,
            3 * 60 * 60 * 1000L
        )

        // الافتراضي = ساعة وحدة
        prefs.edit().putLong("default_duration_ms", durations[1]).apply()
        highlightSelected(options, 1)

        options.forEachIndexed { i, btn ->
            btn.setOnClickListener {
                prefs.edit().putLong("default_duration_ms", durations[i]).apply()
                highlightSelected(options, i)
            }
        }
    }

    private fun highlightSelected(buttons: List<MaterialButton>, selectedIndex: Int) {
        buttons.forEachIndexed { i, btn ->
            if (i == selectedIndex) {
                btn.setBackgroundColor(0xFF1A0000.toInt())
                btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFFCC3300.toInt())
            } else {
                btn.setBackgroundColor(0xFF0D0D0D.toInt())
                btn.strokeColor = android.content.res.ColorStateList.valueOf(0xFF1E1E1E.toInt())
            }
        }
    }
}
