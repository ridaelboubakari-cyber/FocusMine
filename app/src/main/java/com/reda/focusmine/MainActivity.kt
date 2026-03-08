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
import androidx.lifecycle.lifecycleScope
import com.reda.focusmine.data.AppDatabase
import com.reda.focusmine.data.FocusSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FocusMine.MainActivity"
        private const val SESSION_DURATION_MS = 2 * 60 * 60 * 1000L
    }

    // ─── UI ───────────────────────────────────────────────────────
    private lateinit var ui: UIController

    // ─── Database ─────────────────────────────────────────────────
    private lateinit var database: AppDatabase

    // ─── Timer ────────────────────────────────────────────────────
    private val handler        = Handler(Looper.getMainLooper())
    private var sessionStartMs = 0L
    private var remainingMs    = SESSION_DURATION_MS
    private var isRunning      = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            remainingMs = (SESSION_DURATION_MS - (System.currentTimeMillis() - sessionStartMs))
                .coerceAtLeast(0L)
            ui.updateTimer(remainingMs, SESSION_DURATION_MS)
            if (remainingMs <= 0L) onSessionCompleted()
            else handler.postDelayed(this, 1_000)
        }
    }

    // ─── BroadcastReceiver ────────────────────────────────────────
    private var receiverRegistered = false

    private val focusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                FocusService.ACTION_ARMED -> {
                    sessionStartMs = System.currentTimeMillis()
                    isRunning      = true
                    ui.setState(UIController.UIState.ARMED)
                    handler.post(timerRunnable)
                    Log.d(TAG, "Session started")
                }
                FocusService.ACTION_ACCEL_UPDATE -> {
                    val accel = intent.getDoubleExtra(FocusService.EXTRA_ACCEL, 0.0)
                    ui.updateAccel(accel)
                }
                FocusService.ACTION_GRACE_START     -> ui.setState(UIController.UIState.GRACE)
                FocusService.ACTION_GRACE_CANCELLED -> ui.setState(UIController.UIState.ARMED)
                FocusService.ACTION_ALARM_TRIGGERED -> {
                    isRunning = false
                    handler.removeCallbacks(timerRunnable)
                    ui.setState(UIController.UIState.EXPLODED)
                }
                FocusService.ACTION_SURRENDERED     -> onSurrenderConfirmed()
            }
        }
    }

    // ─── Permission ───────────────────────────────────────────────
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "Notification permission: $granted")
        }

    // ══════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // تهيئة الـ UI والـ Database
        ui       = UIController(this)
        database = AppDatabase.getInstance(this)

        requestNotificationPermission()

        ui.surrenderButton.setOnClickListener { onSurrenderClicked() }

        FocusService.start(this)
        startArmingSequence()

        ui.animateUI()
        ui.setState(UIController.UIState.ARMING)
    }

    override fun onStart() {
        super.onStart()
        registerFocusReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        unregisterFocusReceiver()
    }

    // ══════════════════════════════════════════════════════════════
    // BroadcastReceiver
    // ══════════════════════════════════════════════════════════════

    private fun registerFocusReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(FocusService.ACTION_ARMED)
            addAction(FocusService.ACTION_ACCEL_UPDATE)
            addAction(FocusService.ACTION_GRACE_START)
            addAction(FocusService.ACTION_GRACE_CANCELLED)
            addAction(FocusService.ACTION_ALARM_TRIGGERED)
            addAction(FocusService.ACTION_SURRENDERED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(focusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(focusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterFocusReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(focusReceiver)
            receiverRegistered = false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // منطق الجلسة
    // ══════════════════════════════════════════════════════════════

    private fun startArmingSequence() {
        var secondsLeft = 5
        val countdown = object : Runnable {
            override fun run() {
                if (secondsLeft > 0) {
                    ui.updateArmingCountdown(secondsLeft)
                    secondsLeft--
                    handler.postDelayed(this, 1_000)
                } else {
                    FocusService.arm(this@MainActivity)
                }
            }
        }
        handler.post(countdown)
    }

    // ─── نجاح — أكمل الجلسة ───────────────────────────────────────
    private fun onSessionCompleted() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)

        ui.setState(UIController.UIState.SUCCESS)
        ui.updateTimer(0L, SESSION_DURATION_MS)

        // ✅ حفظ في Room على IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            database.sessionDao().insertSession(
                FocusSession(
                    startTime  = sessionStartMs,
                    durationMs = SESSION_DURATION_MS,
                    isSuccess  = true
                )
            )
            Log.d(TAG, "✅ Success session saved to DB")
        }

        FocusService.stop(this)
        triggerGentleVibration()
    }

    // ─── الاستسلام ────────────────────────────────────────────────
    private fun onSurrenderClicked() {
        startService(
            Intent(this, FocusService::class.java).apply {
                action = FocusService.ACTION_SURRENDER
            }
        )
    }

    private fun onSurrenderConfirmed() {
        isRunning = false
        handler.removeCallbacks(timerRunnable)

        val elapsed = SESSION_DURATION_MS - remainingMs

        // ✅ حفظ في Room على IO thread
        lifecycleScope.launch(Dispatchers.IO) {
            database.sessionDao().insertSession(
                FocusSession(
                    startTime  = sessionStartMs,
                    durationMs = elapsed,
                    isSuccess  = false
                )
            )
            Log.d(TAG, "❌ Failed session saved — elapsed: ${elapsed / 1000}s")
        }

        FocusService.stop(this)

        // رجوع لوضع الاستعداد بعد ثانيتين
        handler.postDelayed({
            remainingMs = SESSION_DURATION_MS
            ui.setState(UIController.UIState.ARMING)
            ui.updateTimer(SESSION_DURATION_MS, SESSION_DURATION_MS)
        }, 2_000)
    }

    // ══════════════════════════════════════════════════════════════
    // مساعدات
    // ══════════════════════════════════════════════════════════════

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun triggerGentleVibration() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(400,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(400)
        }
    }
}
