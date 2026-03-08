package com.reda.focusmine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity — الواجهة فقط
 * المنطق كامل انتقل لـ FocusService
 * محسّن لـ Samsung S20 / Android 13+
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var surrenderButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val sessionDurationMs = 2 * 60 * 60 * 1000L  // ساعتين

    // ─── BroadcastReceiver — تستمع للأحداث من FocusService ───────
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                // الإنذار طلع → ظهر زر الاستسلام
                FocusService.ACTION_ALARM_TRIGGERED -> {
                    statusText.text = "💥 فشلت!\nاضغط الزر باش يسكت الإنذار"
                    surrenderButton.visibility = View.VISIBLE
                }

                // المستخدم استسلم → حدث الواجهة
                FocusService.ACTION_SURRENDERED -> {
                    statusText.text = "📝 تم تسجيل الفشل في سجلك"
                    surrenderButton.visibility = View.GONE
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ setContentView أول سطر — قبل أي findViewById
        setContentView(R.layout.activity_main)

        // ✅ ربط العناصر بعد setContentView مباشرة
        statusText      = findViewById(R.id.statusText)
        surrenderButton = findViewById(R.id.surrenderButton)

        // ✅ زر الاستسلام مخفي في البداية — يظهر فقط ملي يتفرقع اللغم
        surrenderButton.visibility = View.GONE
        surrenderButton.setOnClickListener { onSurrenderClicked() }

        // ابدا الخدمة وعداد التحضير
        FocusService.start(this)
        startArmingCountdown()
        startSessionTimer()
    }

    override fun onResume() {
        super.onResume()

        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ALARM_TRIGGERED)
            addAction(FocusService.ACTION_SURRENDERED)
        }

        // ✅ RECEIVER_NOT_EXPORTED — مطلوب فـ Android 13+ (API 33+)
        // يحمي الـ Receiver من التطبيقات الخارجية
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(serviceReceiver)
        } catch (e: IllegalArgumentException) {
            // تفادي الكراش إيلا ما كانش الـ Receiver مسجل
        }
        // الخدمة تبقى خدامة فالخلفية بلا مشكل
    }

    override fun onDestroy() {
        super.onDestroy()
        // ما نوقفوش الخدمة هنا — غتبقى تحرس حتى تسالى الجلسة
    }

    // ─── 1. عداد التحضير (5 ثواني بسلاسة) ──────────────────────
    private fun startArmingCountdown() {
        statusText.text = "⏳ عندك 5 ثواني تحط التيلفون على وجهو..."

        // عداد تنازلي يحدث النص كل ثانية
        var secondsLeft = 5
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (secondsLeft > 0) {
                    statusText.text = "⏳ $secondsLeft..."
                    secondsLeft--
                    handler.postDelayed(this, 1_000)
                } else {
                    // انتهى العداد → فعّل اللغم
                    FocusService.arm(this@MainActivity)
                    statusText.text = "🚨 اللغم تفعّل!\nأي حركة غتفرقعو"
                }
            }
        }
        handler.post(countdownRunnable)
    }

    // ─── 2. مؤقت الجلسة (ساعتين → نهاية ناجحة) ─────────────────
    private fun startSessionTimer() {
        handler.postDelayed({ onSessionCompleted() }, sessionDurationMs)
    }

    // ─── 3. نهاية الجلسة الطبيعية (Gentle Exit) ──────────────────
    private fun onSessionCompleted() {
        FocusService.stop(this)

        // اهتزاز خفيف = دقة على الكتف
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }

        statusText.text = "✅ بطل! دوزتي الجلسة كاملة 🏆"
        surrenderButton.visibility = View.GONE
        // TODO: انتقل لشاشة النجاح الخضراء
    }

    // ─── 4. زر الاستسلام ─────────────────────────────────────────
    private fun onSurrenderClicked() {
        startService(
            Intent(this, FocusService::class.java).apply {
                action = FocusService.ACTION_SURRENDER
            }
        )
        surrenderButton.visibility = View.GONE
        statusText.text = "📝 تم تسجيل الفشل في سجلك"
        // TODO: سجل الفشل فـ Room Database
    }
}
