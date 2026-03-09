package com.reda.focusmine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

// ══════════════════════════════════════════════════════════════
// FocusService — Phase 2
//
// CHANGED from Phase 1:
//   - Sensor/accelerometer logic REMOVED entirely
//   - Now uses ProcessLifecycleOwner to detect when app
//     goes to background (user left to Instagram etc.)
//   - Tracks appLeftCount and wasCompromised
//   - Broadcasts compromise events to MissionFragment
//   - MissionFragment owns the Iron Door UI logic
// ══════════════════════════════════════════════════════════════

class FocusService : Service() {

    companion object {
        private const val TAG        = "FocusMine.Service"
        private const val CHANNEL_ID = "focus_mine_channel"
        private const val NOTIF_ID   = 1

        // ── Incoming actions ──────────────────────────────────
        const val ACTION_ARM       = "com.reda.focusmine.ARM"
        const val ACTION_DISARM    = "com.reda.focusmine.DISARM"
        const val ACTION_SURRENDER = "com.reda.focusmine.SURRENDER"

        // ── Outgoing broadcasts ───────────────────────────────
        const val ACTION_ARMED           = "com.reda.focusmine.ARMED"
        const val ACTION_SURRENDERED     = "com.reda.focusmine.SURRENDERED"
        const val ACTION_APP_LEFT        = "com.reda.focusmine.APP_LEFT"
        const val ACTION_APP_RETURNED    = "com.reda.focusmine.APP_RETURNED"

        // ── Extras ────────────────────────────────────────────
        const val EXTRA_APP_LEFT_COUNT   = "extra_app_left_count"
        const val EXTRA_WAS_COMPROMISED  = "extra_was_compromised"

        // ── Helpers ───────────────────────────────────────────
        fun arm(context: Context) {
            context.startService(
                Intent(context, FocusService::class.java)
                    .apply { action = ACTION_ARM }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FocusService::class.java)
                    .apply { action = ACTION_DISARM }
            )
        }
    }

    // ── State ─────────────────────────────────────────────────
    private var isArmed          = false
    private var appLeftCount     = 0
    private var wasCompromised   = false
    private var appInForeground  = true

    // ── Vibrator ──────────────────────────────────────────────
    private lateinit var vibrator: Vibrator

    // ── Lifecycle observer — detects app going to background ──
    private val lifecycleObserver = object : DefaultLifecycleObserver {

        override fun onStop(owner: LifecycleOwner) {
            // App went fully to background
            if (!isArmed) return
            appInForeground = false
            appLeftCount++
            wasCompromised = true

            Log.d(TAG, "App left background — count: $appLeftCount")

            // Gentle haptic warning
            buzzCompromise()

            // Notify MissionFragment
            sendBroadcast(Intent(ACTION_APP_LEFT).apply {
                putExtra(EXTRA_APP_LEFT_COUNT,  appLeftCount)
                putExtra(EXTRA_WAS_COMPROMISED, wasCompromised)
                `package` = packageName
            })
        }

        override fun onStart(owner: LifecycleOwner) {
            // App returned to foreground
            if (!isArmed) return
            appInForeground = true

            Log.d(TAG, "App returned to foreground")

            sendBroadcast(Intent(ACTION_APP_RETURNED).apply {
                putExtra(EXTRA_APP_LEFT_COUNT,  appLeftCount)
                putExtra(EXTRA_WAS_COMPROMISED, wasCompromised)
                `package` = packageName
            })
        }
    }

    // ══════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        createNotificationChannel()

        // Register lifecycle observer on main thread
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle
                .addObserver(lifecycleObserver)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("FOCUS MINE — STANDING BY"))

        when (intent?.action) {
            ACTION_ARM -> {
                isArmed        = true
                appLeftCount   = 0
                wasCompromised = false
                updateNotification("MINE ARMED — SESSION ACTIVE")
                Log.d(TAG, "Mine armed")
                sendBroadcast(Intent(ACTION_ARMED).apply {
                    `package` = packageName
                })
            }
            ACTION_DISARM, ACTION_SURRENDER -> {
                isArmed = false
                Log.d(TAG, "Mine disarmed")
                sendBroadcast(Intent(ACTION_SURRENDERED).apply {
                    putExtra(EXTRA_APP_LEFT_COUNT,  appLeftCount)
                    putExtra(EXTRA_WAS_COMPROMISED, wasCompromised)
                    `package` = packageName
                })
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle
                .removeObserver(lifecycleObserver)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ══════════════════════════════════════════════════════════
    // HAPTICS
    // ══════════════════════════════════════════════════════════

    private fun buzzCompromise() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Two short firm pulses — "you left"
                val pattern = longArrayOf(0, 300, 150, 300)
                vibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Buzz failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FocusMine Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description   = "Active focus session"
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FOCUSMINE")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
    }
}
