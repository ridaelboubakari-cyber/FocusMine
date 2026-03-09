package com.reda.focusmine.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    // 1. WRITE OPERATIONS
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSession)

    @Update
    suspend fun updateSession(session: FocusSession)

    @Delete
    suspend fun deleteSession(session: FocusSession)

    @Query("DELETE FROM focus_sessions")
    suspend fun clearAll()

    // 2. HOME DASHBOARD
    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<FocusSession>>

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT 50")
    suspend fun getRecentSessionsForStreak(): List<FocusSession>

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isSuccess = 1")
    suspend fun getSuccessCount(): Int

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isSuccess = 0")
    suspend fun getFailureCount(): Int

    @Query("SELECT SUM(durationMs) FROM focus_sessions WHERE isSuccess = 1")
    suspend fun getTotalSuccessMs(): Long?

    // 3. TIME CAPSULE
    @Query("SELECT * FROM focus_sessions ORDER BY startTime ASC")
    suspend fun getAllSessionsForCapsule(): List<FocusSession>

    @Query("SELECT * FROM focus_sessions WHERE isSuccess = 1 ORDER BY durationMs DESC LIMIT 1")
    suspend fun getHardestSession(): FocusSession?

    @Query("SELECT * FROM focus_sessions ORDER BY startTime ASC LIMIT 1")
    suspend fun getFirstSession(): FocusSession?

    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastSession(): FocusSession?

    @Query("SELECT exitExcuse FROM focus_sessions WHERE isSuccess = 0 AND exitExcuse != '' ORDER BY startTime DESC")
    suspend fun getAllExcuses(): List<String>

    @Query("SELECT exitExcuse FROM focus_sessions WHERE isSuccess = 0 AND exitExcuse != '' ORDER BY startTime DESC LIMIT 5")
    suspend fun getRecentExcuses(): List<String>

    // 4. WEEKLY REPORT
    @Query("SELECT * FROM focus_sessions WHERE startTime >= :sinceMs ORDER BY startTime ASC")
    suspend fun getSessionsAfter(sinceMs: Long): List<FocusSession>

    @Query("SELECT * FROM focus_sessions WHERE weekNumber = :weekNum ORDER BY startTime ASC")
    suspend fun getSessionsByWeek(weekNum: Int): List<FocusSession>

    @Query("""
        SELECT weekNumber, SUM(durationMs) as totalMs, 
               COUNT(*) as total,
               SUM(CASE WHEN isSuccess = 1 THEN 1 ELSE 0 END) as successes
        FROM focus_sessions 
        GROUP BY weekNumber 
        ORDER BY weekNumber ASC
    """)
    suspend fun getWeeklyAggregates(): List<WeeklyAggregate>

    @Query("SELECT * FROM focus_sessions WHERE weekNumber = :weekNum AND isSuccess = 1 ORDER BY durationMs DESC LIMIT 1")
    suspend fun getBestSessionOfWeek(weekNum: Int): FocusSession?

    // 5. BACKUP & RESTORE
    @Query("SELECT COUNT(*) FROM focus_sessions")
    suspend fun getTotalCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllSessions(sessions: List<FocusSession>)
}

data class WeeklyAggregate(
    val weekNumber: Int,
    val totalMs:    Long,
    val total:      Int,
    val successes:  Int
) {
    val failCount: Int
        get() = total - successes

    val successRatePct: Int
        get() = if (total == 0) 0 else (successes * 100 / total)

    val totalMinutes: Long
        get() = totalMs / 60_000
}
