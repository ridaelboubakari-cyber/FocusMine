package com.reda.focusmine

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * MainActivity — الواجهة الرئيسية لـ FocusMine
 *
 * المسؤوليات:
 *  1. طلب الأذونات (POST_NOTIFICATIONS على Android 13+)
 *  2. تهيئة UIController وربطه بالواجهة
 *  3. إدارة دورة الحياة (BroadcastReceiver, Service)
 *  4. تمرير الأحداث من FocusService → UIController
 *  5. التعامل مع زر ABORT MISSION
 *
 * التواصل مع FocusService:
 *  ← FocusService تبعث Broadcasts عند كل حدث
 *  → MainActivity تستقبلها وتحدث الـ UI
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FocusMine.MainActivity"

        // مدة الجلسة — ساعتين
        private const val SESSION_DURATION_MS = 2 * 60 * 60 * 1000L
    }

    // ─── UIController ─────────────────────────────────────────────
    private lateinit var ui: UIController

    // ─── إدارة الوقت ──────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var sessionStartMs   = 0L
    private var remainingMs      = SESSION_DURATION_MS
    private var isSessionRunning = false

    // Runnable يحدث العداد كل ثانية
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isSessionRunning) return

            val elapsed = System.currentTimeMillis() - sessionStartMs
            remainingMs = (SESSION_DURATION_MS - elapsed).coerceAtLeast(0L)

            ui.updateTimer(remainingMs, SESSION_DURATION_MS)

            if (remainingMs <= 0L) {
                onSessionCompleted()
            } else {
                handler.postDelayed(this, 1_000)
            }
        }
    }

    // ─── BroadcastReceiver ────────────────────────────────────────
    private var isReceiverRegistered = false

    private val focusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                // اللغم تفعّل — بدا العداد
                FocusService.ACTION_ARMED -> {
                    Log.d(TAG, "Mine armed")
                    sessionStartMs   = System.currentTimeMillis()
                    isSessionRunning = true
                    ui.setState(UIController.UIState.ARMED)
                    handler.post(timerRunnable)
                }

                // تحديث قيمة المستشعر (كل ثانية من FocusService)
                FocusService.ACTION_ACCEL_UPDATE -> {
                    val accel = intent.getDoubleExtra(FocusService.EXTRA_ACCEL, 0.0)
                    ui.updateAccel(accel)
                }

                // Grace Period — تيك توك
                FocusService.ACTION_GRACE_START -> {
                    Log.d(TAG, "Grace period started")
                    ui.setState(UIController.UIState.GRACE)
                }

                // رجعو التيلفون خلال الـ 3 ثواني — نجوا
                FocusService.ACTION_GRACE_CANCELLED -> {
                    Log.d(TAG, "Grace period cancelled — safe")
                    ui.setState(UIController.UIState.ARMED)
                }

                // الإنذار — فشل
                FocusService.ACTION_ALARM_TRIGGERED -> {
                    Log.d(TAG, "ALARM TRIGGERED")
                    isSessionRunning = false
                    handler.removeCallbacks(timerRunnable)
                    ui.setState(UIController.UIState.EXPLODED)
                }

                // المستخدم استسلم من إشعار الخدمة مباشرة
                FocusService.ACTION_SURRENDERED -> {
                    Log.d(TAG, "Surrendered from notification")
                    onSurrenderConfirmed()
                }
            }
        }
    }

    // ─── طلب إذن الإشعارات (Android 13+) ─────────────────────────
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "Notification permission granted")
            } else {
                Log.w(TAG, "Notification permission denied — notifications won't show")
                // التطبيق يشتغل بدون إشعارات لكن FocusService ممكن تتأثر
            }
        }

    // ══════════════════════════════════════════════════════════════
    // دورة الحياة
    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ setContentView أول سطر دائماً
        setContentView(R.layout.activity_main)

        // تهيئة UIController بعد setContentView مباشرة
        ui = UIController(this)

        // طلب الأذونات
        requestNotificationPermission()

        // إعداد زر الاستسلام
        ui.surrenderButton.setOnClickListener {
            onSurrenderClicked()
        }

        // بدا الخدمة وعداد التسليح
        FocusService.start(this)
        startArmingSequence()

        // تشغيل أنيماشيونات الدخول
        ui.animateUI()
        ui.setState(UIController.UIState.ARMING)

        Log.d(TAG, "onCreate complete")
    }

    override fun onStart() {
        super.onStart()
        registerFocusReceiver()
    }

    override fun onStop() {
        super.onStop()
        // لا نوقف الـ Receiver هنا — الخدمة تشتغل في الخلفية
        // لكن نحتاج نعيد تسجيله في onStart
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterFocusReceiver()
        Log.d(TAG, "onDestroy — MainActivity cleaned up")
        // ملاحظة: FocusService تبقى خدامة في الخلفية
        // لا نوقفها هنا — فقط عند انتهاء الجلسة أو الاستسلام
    }

    // ══════════════════════════════════════════════════════════════
    // تسجيل BroadcastReceiver
    // ══════════════════════════════════════════════════════════════

    private fun registerFocusReceiver() {
        if (isReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ARMED)
            addAction(FocusService.ACTION_ACCEL_UPDATE)
            addAction(FocusService.ACTION_GRACE_START)
            addAction(FocusService.ACTION_GRACE_CANCELLED)
            addAction(FocusService.ACTION_ALARM_TRIGGERED)
            addAction(FocusService.ACTION_SURRENDERED)
        }

        // ✅ Android 13+ يطلب RECEIVER_NOT_EXPORTED صراحة
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(focusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(focusReceiver, filter)
        }

        isReceiverRegistered = true
        Log.d(TAG, "BroadcastReceiver registered")
    }

    private fun unregisterFocusReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(focusReceiver)
            isReceiverRegistered = false
            Log.d(TAG, "BroadcastReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver was not registered: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // منطق الجلسة
    // ══════════════════════════════════════════════════════════════

    /**
     * عداد التسليح — 5 ثواني قبل تفعيل اللغم
     * يحدث الـ UI بالعد التنازلي ثم يأمر الخدمة بالتسليح
     */
    private fun startArmingSequence() {
        var secondsLeft = 5

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (secondsLeft > 0) {
                    ui.updateArmingCountdown(secondsLeft)
                    secondsLeft--
                    handler.postDelayed(this, 1_000)
                } else {
                    // أرسل أمر ARM للخدمة
                    FocusService.arm(this@MainActivity)
                    Log.d(TAG, "Arming command sent to FocusService")
                    // FocusService غتبعث ACTION_ARMED ملي تتسلح
                }
            }
        }

        handler.post(countdownRunnable)
    }

    /**
     * نهاية الجلسة الطبيعية — Gentle Exit
     */
    private fun onSessionCompleted() {
        isSessionRunning = false
        handler.removeCallbacks(timerRunnable)

        Log.d(TAG, "Session completed successfully")

        // أوقف الخدمة
        FocusService.stop(this)

        // حدث الـ UI
        ui.setState(UIController.UIState.SUCCESS)
        ui.updateTimer(0L, SESSION_DURATION_MS)

        // اهتزاز خفيف — دقة على الكتف
        triggerGentleVibration()
    }

    // ══════════════════════════════════════════════════════════════
    // الاستسلام
    // ══════════════════════════════════════════════════════════════

    /**
     * المستخدم ضغط زر ABORT MISSION في الواجهة
     */
    private fun onSurrenderClicked() {
        Log.d(TAG, "Surrender button clicked")

        // أرسل أمر الاستسلام للخدمة — هي اللي توقف الصوت
        startService(
            Intent(this, FocusService::class.java).apply {
                action = FocusService.ACTION_SURRENDER
            }
        )

        // الـ UI يتحدث عند استقبال ACTION_SURRENDERED من الخدمة
    }

    /**
     * تأكيد الاستسلام — يجي من الخدمة بعد ما تسكت الصوت
     */
    private fun onSurrenderConfirmed() {
        Log.d(TAG, "Surrender confirmed")
        isSessionRunning = false
        handler.removeCallbacks(timerRunnable)

        // سجل الفشل في Room Database
        saveFocusSession(success = false, durationMs = SESSION_DURATION_MS - remainingMs)

        // أوقف الخدمة
        FocusService.stop(this)

        // حدث الـ UI — ارجع لوضع الاستعداد بعد 2 ثانية
        handler.postDelayed({
            ui.setState(UIController.UIState.ARMING)
            ui.updateTimer(SESSION_DURATION_MS, SESSION_DURATION_MS)
        }, 2_000)
    }

    // ══════════════════════════════════════════════════════════════
    // الأذونات
    // ══════════════════════════════════════════════════════════════

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // مساعدات
    // ══════════════════════════════════════════════════════════════

    /**
     * اهتزاز خفيف عند نجاح الجلسة
     */
    private fun triggerGentleVibration() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    350,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(350)
        }
    }

    /**
     * حفظ نتيجة الجلسة في Room Database
     * TODO: استبدل هاد الدالة بـ ViewModel + Repository حقيقيين
     */
    private fun saveFocusSession(success: Boolean, durationMs: Long) {
        val durationMin = durationMs / 60_000
        Log.d(TAG, "Saving session: success=$success, duration=${durationMin}min")
        // مثال:
        // lifecycleScope.launch {
        //     sessionDao.insert(FocusSession(
        //         timestamp = System.currentTimeMillis(),
        //         success   = success,
        //         durationMs = durationMs
        //     ))
        // }
    }
}
