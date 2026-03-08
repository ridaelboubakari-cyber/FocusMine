package com.reda.focusmine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity — الواجهة فقط
 *
 * المنطق كامل انتقل لـ FocusService
 * MainActivity دورها: تعرض الحالة وتستقبل الأوامر من المستخدم
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var surrenderButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private var sessionDurationMs = 2 * 60 * 60 * 1000L

    // ─── BroadcastReceiver — تستمع للأحداث من FocusService ───────
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                // الإنذار طلع من الخدمة → ظهر زر الاستسلام
                FocusService.ACTION_ALARM_TRIGGERED -> {
                    statusText.text     = "💥 فشلت!\nاضغط الزر باش يسكت الإنذار"
                    surrenderButton.visibility = View.VISIBLE
                }

                // المستخدم استسلم من إشعار الخدمة → حدث الواجهة
                FocusService.ACTION_SURRENDERED -> {
                    statusText.text     = "📝 تم تسجيل الفشل في سجلك"
                    surrenderButton.visibility = View.GONE
                    // TODO: سجل الفشل فـ Room Database
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText      = findViewById(R.id.statusText)
        surrenderButton = findViewById(R.id.surrenderButton)
        surrenderButton.visibility = View.GONE

        surrenderButton.setOnClickListener { onSurrenderClicked() }

        // ابدا الخدمة
        FocusService.start(this)

        startArmingCountdown()
        startSessionTimer()
    }

    override fun onResume() {
        super.onResume()
        // سجل الـ Receiver باش يستقبل الأحداث من الخدمة
        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ALARM_TRIGGERED)
            addAction(FocusService.ACTION_SURRENDERED)
        }
        registerReceiver(serviceReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        // الغي تسجيل الـ Receiver — الخدمة غتبقى خدامة فالخلفية بلا مشكل
        unregisterReceiver(serviceReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        // ما نوقفوش الخدمة هنا — غتبقى تحرس حتى تسالى الجلسة
    }

    // ─── عداد التحضير (5 ثواني) ──────────────────────────────────
    private fun startArmingCountdown() {
        statusText.text = "⏳ عندك 5 ثواني تحط التيلفون على وجهو..."

        handler.postDelayed({
            FocusService.arm(this)
            statusText.text = "🚨 اللغم تفعّل!\nأي حركة غتفرقعو"
        }, 5_000)
    }

    // ─── مؤقت الجلسة (ساعتين → نهاية ناجحة) ─────────────────────
    private fun startSessionTimer() {
        handler.postDelayed({ onSessionCompleted() }, sessionDurationMs)
    }

    // ─── نهاية الجلسة الطبيعية (Gentle Exit) ─────────────────────
    private fun onSessionCompleted() {
        FocusService.stop(this)

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

    // ─── زر الاستسلام ─────────────────────────────────────────────
    private fun onSurrenderClicked() {
        // أرسل أمر الاستسلام للخدمة — هي اللي تعرف كيف توقف الصوت
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

