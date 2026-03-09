package com.reda.focusmine.data

import android.content.SharedPreferences

enum class OperativeMode {
    RECRUIT,
    OPERATIVE,
    GHOST
}

enum class LetterTone {
    ENCOURAGING,
    HONEST,
    BRUTAL
}

data class ModeConfig(
    val minGoalWords:       Int,
    val excuseRequired:     Boolean,
    val excuseMinWords:     Int,
    val excuseFrictionSec:  Int,
    val onStopFlagsSession: Boolean,
    val letterTone:         LetterTone,
    val displayName:        String,
    val displayDescription: String
)

object ModeConfigProvider {

    fun get(mode: OperativeMode): ModeConfig = when (mode) {
        OperativeMode.RECRUIT -> ModeConfig(
            minGoalWords        = 2,
            excuseRequired      = false,
            excuseMinWords      = 0,
            excuseFrictionSec   = 0,
            onStopFlagsSession  = false,
            letterTone          = LetterTone.ENCOURAGING,
            displayName         = "RECRUIT",
            displayDescription  = "I am building the habit. Be patient with me."
        )

        OperativeMode.OPERATIVE -> ModeConfig(
            minGoalWords        = 4,
            excuseRequired      = true,
            excuseMinWords      = 3,
            excuseFrictionSec   = 10,
            onStopFlagsSession  = true,
            letterTone          = LetterTone.HONEST,
            displayName         = "OPERATIVE",
            displayDescription  = "I am serious. Hold me accountable."
        )

        OperativeMode.GHOST -> ModeConfig(
            minGoalWords        = 4,
            excuseRequired      = true,
            excuseMinWords      = 5,
            excuseFrictionSec   = 30,
            onStopFlagsSession  = true,
            letterTone          = LetterTone.BRUTAL,
            displayName         = "GHOST",
            displayDescription  = "Maximum pressure. No mercy."
        )
    }

    fun fromPrefs(prefs: SharedPreferences): OperativeMode {
        val stored = prefs.getString("operative_mode", null)
        return try {
            OperativeMode.valueOf(stored ?: "OPERATIVE")
        } catch (e: IllegalArgumentException) {
            OperativeMode.OPERATIVE
        }
    }

    fun saveToPrefs(prefs: SharedPreferences, mode: OperativeMode) {
        prefs.edit().putString("operative_mode", mode.name).apply()
    }
}
