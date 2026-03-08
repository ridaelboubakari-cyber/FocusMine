package com.reda.focusmine.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reda.focusmine.data.AppDatabase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HomeStats(
    val streak:         Int    = 0,
    val totalHours:     Float  = 0f,
    val failCount:      Int    = 0,
    val successCount:   Int    = 0,
    val totalSessions:  Int    = 0
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = AppDatabase.getInstance(app).sessionDao()

    val stats = dao.getAllSessions().map { sessions ->
        val successes = sessions.filter { it.isSuccess }
        val failures  = sessions.filter { !it.isSuccess }
        val totalMs   = successes.sumOf { it.durationMs }

        // Streak: عدد الجلسات الناجحة المتتالية من آخر جلسة
        var streak = 0
        for (s in sessions) {
            if (s.isSuccess) streak++ else break
        }

        HomeStats(
            streak        = streak,
            totalHours    = totalMs / 3_600_000f,
            failCount     = failures.size,
            successCount  = successes.size,
            totalSessions = sessions.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeStats())
}
