package com.reda.focusmine.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "focus_sessions")
data class FocusSession(

    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val startTime:  Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,

    val isSuccess:       Boolean = false,
    val wasCompromised:  Boolean = false,
    val appLeftCount:    Int     = 0,

    val microGoal: String = "",
    val exitExcuse: String = "",
    val emotionalSnapshot: String = "",
    val compromisedAtMs: Long = 0L,
    
    // غيعطينا إيرور صغير حيت مازال ما صايبنا OperativeMode، ولكن غنحلوه فـ الخطوة الجاية
    val operativeMode: String = "OPERATIVE", 

    val weekNumber: Int = getCurrentWeekNumber()
)

fun getCurrentWeekNumber(): Int {
    val cal = java.util.Calendar.getInstance().apply {
        minimalDaysInFirstWeek = 4 
        firstDayOfWeek = java.util.Calendar.MONDAY
    }
    return cal.get(java.util.Calendar.WEEK_OF_YEAR)
}
