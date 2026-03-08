package com.reda.focusmine.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * AppDatabase — قاعدة البيانات الرئيسية لـ FocusMine
 *
 * Singleton Pattern — instance وحيد في كامل التطبيق
 *
 * الاستخدام:
 *   val db  = AppDatabase.getInstance(context)
 *   val dao = db.sessionDao()
 */
@Database(
    entities  = [FocusSession::class],
    version   = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao

    companion object {
        // @Volatile — أي تغيير يظهر فوراً لكل الـ threads
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // Double-checked locking — thread-safe singleton
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "focusmine_database"
            )
            // fallbackToDestructiveMigration — إيلا تغيرت البنية يمسح ويبدأ من جديد
            // للـ production الحقيقي استبدلو بـ migrations صريحة
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}
