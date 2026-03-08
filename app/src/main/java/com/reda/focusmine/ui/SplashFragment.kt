package com.reda.focusmine.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.reda.focusmine.R

/**
 * SplashFragment — الشاشة الأولى
 *
 * تبقى ثانية وحدة ثم تقرر:
 * - إيلا المستخدم جديد → Onboarding
 * - إيلا المستخدم قديم → Home
 */
class SplashFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_splash, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext()
            .getSharedPreferences("focusmine_prefs", Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("onboarding_complete", false)

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isAdded) return@postDelayed
            if (onboardingDone) {
                findNavController().navigate(R.id.action_splash_to_home)
            } else {
                findNavController().navigate(R.id.action_splash_to_onboarding)
            }
        }, 1_200)
    }
}
