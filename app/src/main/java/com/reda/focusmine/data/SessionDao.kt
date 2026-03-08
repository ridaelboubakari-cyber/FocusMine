package com.reda.focusmine.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * SessionDao — واجهة الوصول لقاعدة البيانات
 *
 * كل العمليات تشتغل على Coroutines (suspend/Flow)
 * ما كاينش blocking calls على Main Thread
 */
@Dao
interface SessionDao {

    /**
     * إدخال جلسة جديدة
     * REPLACE — إيلا كان نفس الـ id موجود يبدلو (حماية من crash)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSession)

    /**
     * جلب كل الجلسات مرتبة من الأحدث للأقدم
     * Flow — يتحدث تلقائياً ملي تتغير البيانات
     */
    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<FocusSession>>

    /**
     * عدد الجلسات الناجحة — للـ Streak
     */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isSuccess = 1")
    suspend fun getSuccessCount(): Int

    /**
     * عدد الجلسات الفاشلة — للإحصائيات
     */
    @Query("SELECT COUNT(*) FROM focus_sessions WHERE isSuccess = 0")
    suspend fun getFailureCount(): Int

    /**
     * مجموع الوقت المنجز بنجاح (بالميلي ثانية)
     */
    @Query("SELECT SUM(durationMs) FROM focus_sessions WHERE isSuccess = 1")
    suspend fun getTotalFocusTimeMs(): Long?

    /**
     * آخر 7 جلسات — للـ Dashboard
     */
    @Query("SELECT * FROM focus_sessions ORDER BY startTime DESC LIMIT 7")
    suspend fun getRecentSessions(): List<FocusSession>

    /**
     * حذف كل البيانات — للـ Reset
     */
    @Query("DELETE FROM focus_sessions")
    suspend fun clearAll()
}
