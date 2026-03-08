package com.reda.focusmine.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * FocusSession — Entity يمثل جلسة تركيز وحدة في قاعدة البيانات
 *
 * @param id         مفتاح أساسي يتولد تلقائياً
 * @param startTime  وقت بداية الجلسة (Unix timestamp بالميلي ثانية)
 * @param durationMs المدة الفعلية للجلسة (كم وقت خدم قبل النجاح أو الفشل)
 * @param isSuccess  true = أكمل الجلسة / false = استسلم أو تفجر اللغم
 */
@Entity(tableName = "focus_sessions")
data class FocusSession(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    val startTime: Long,
    val durationMs: Long,
    val isSuccess: Boolean
)
