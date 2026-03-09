package com.reda.focusmine.ui

import android.content.Context
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.reda.focusmine.R
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val vm: HomeViewModel by viewModels()

    // Views
    private lateinit var tvStreak:        MaterialTextView
    private lateinit var tvTotalHours:   MaterialTextView
    private lateinit var tvFailCount:    MaterialTextView
    private lateinit var tvGreeting:      MaterialTextView
    private lateinit var tvMissionCount: MaterialTextView
    private lateinit var btnStart:        MaterialButton

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindViews(view)
        observeStats()
        setupButton()
        playEntrance(view)
    }

    private fun bindViews(v: View) {
        tvStreak       = v.findViewById(R.id.tvStreak)
        tvTotalHours   = v.findViewById(R.id.tvTotalHours)
        tvFailCount    = v.findViewById(R.id.tvFailCount)
        tvGreeting     = v.findViewById(R.id.tvGreeting)
        tvMissionCount = v.findViewById(R.id.tvMissionCount)
        btnStart       = v.findViewById(R.id.btnStartMission)
    }

    private fun observeStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.stats.collect { s ->
                    tvStreak.text     = "${s.streak}"
                    tvTotalHours.text = String.format("%.1f", s.totalHours)
                    tvFailCount.text  = "${s.failCount}"
                    tvMissionCount.text = "MISSIONS: ${s.totalSessions}"

                    tvGreeting.text = when {
                        s.totalSessions == 0 -> "SOLDIER, YOUR FIRST MISSION AWAITS."
                        s.streak >= 7        -> "OPERATOR STATUS CONFIRMED."
                        s.streak >= 3        -> "STEADY. KEEP THE STREAK ALIVE."
                        s.failCount > s.successCount -> "YOU'VE BEEN FAILING. TIME TO CHANGE THAT."
                        else                 -> "READY FOR DEPLOYMENT."
                    }
                }
            }
        }
    }

    private fun setupButton() {
        btnStart.setOnClickListener {
            val prefs = requireContext()
                .getSharedPreferences("fm_prefs", Context.MODE_PRIVATE)
            val duration = prefs.getLong("default_duration_ms", 60 * 60_000L)

            // التعديل ديال PHASE 2: زدنا الخانات الجداد باش ما يوقعش Crash
            val action = HomeFragmentDirections.actionHomeToMission(
                durationMs = duration,
                microGoal = "",
                emotionalSnapshot = ""
            )
            findNavController().navigate(action)
        }
    }

    private fun playEntrance(view: View) {
        val elements = listOf(
            R.id.topBar, R.id.tvGreeting, R.id.statsRow,
            R.id.dividerMid, R.id.tvMissionCount, R.id.btnStartMission
        )
        elements.forEachIndexed { i, id ->
            view.findViewById<View>(id)?.apply {
                alpha = 0f
                translationY = 16f
                animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(380).setStartDelay((i * 90).toLong())
                    .setInterpolator(DecelerateInterpolator(2f)).start()
            }
        }
    }
}
