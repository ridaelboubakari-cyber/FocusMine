package com.reda.focusmine

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class FocusService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "FocusMine.Service"

        const val ACTION_ARM             = "com.reda.focusmine.ARM"
        const val ACTION_DISARM          = "com.reda.focusmine.DISARM"
        const val ACTION_SURRENDER       = "com.reda.focusmine.SURRENDER"
        const val ACTION_ALARM_TRIGGERED = "com.reda.focusmine.ALARM_TRIGGERED"
        const val ACTION_SURRENDERED     = "com.reda.focusmine.SURRENDERED"
        const val ACTION_ARMED           = "com.reda.focusmine.ARMED"
        const val ACTION_ACCEL_UPDATE    = "com.reda.focusmine.ACCEL_UPDATE"
        const val ACTION_GRACE_START     = "com.reda.focusmine.GRACE_START"
        const val ACTION_GRACE_CANCELLED = "com.reda.focusmine.GRACE_CANCELLED"
        const val EXTRA_ACCEL            = "extra_accel"

        private const val CHANNEL_ID = "focus_mine_channel"
        private const val NOTIF_ID   = 1

        fun start(context: Context) {
            val intent = Intent(context, FocusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun arm(context: Context) {
            context.startService(
                Intent(context, FocusService::class.java).apply { action = ACTION_ARM }
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, FocusService::class.java).apply { action = ACTION_DISARM }
            )
        }
    }

    // ─── المستشعرات ───────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastSensorUpdateMs = 0L

    // ─── الصوت ────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var originalAlarmVolume = 0
    private var originalRingVolume  = 0
    private var originalMusicVolume = 0

    // ─── الاهتزاز ─────────────────────────────────────────────────
    private lateinit var vibrator: Vibrator

    // ─── الحالة ───────────────────────────────────────────────────
    var isArmed        = false
    var alarmTriggered = false
    var isGracePeriod  = false
    private var lastZ  = 0f

    // ─── المؤقتات ─────────────────────────────────────────────────
    private val handler       = Handler(Looper.getMainLooper())
    private var graceRunnable: Runnable? = null

    // ─── ثوابت ────────────────────────────────────────────────────
    private val GRAVITY                  = SensorManager.GRAVITY_EARTH.toDouble()
    private val MOVEMENT_THRESHOLD       = 1.5   // مصحح — أقل حساسية
    private val FACE_DOWN_THRESHOLD      = -7.0
    private val ACCEL_UPDATE_INTERVAL_MS = 1000L // ثانية حقيقية

    // ══════════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        audioManager  = getSystemService(Context.AUDIO_SERVICE)   as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE)  as SensorManager
        vibrator      = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                NOTIF_ID,
                buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(
                NOTIF_ID,
                buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
            else -> startForeground(NOTIF_ID, buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"))
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                isArmed = true
                Log.d(TAG, "Mine ARMED")
                sendBroadcast(Intent(ACTION_ARMED))
            }
            ACTION_DISARM    -> { Log.d(TAG, "Disarmed"); stopSelf() }
            ACTION_SURRENDER -> onSurrenderFromNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        graceRunnable?.let { handler.removeCallbacks(it) }
        safeStopAlarm()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?) = null

    // ══════════════════════════════════════════════════════════════
    // المستشعر
    // ══════════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        lastZ = event.values[2]

        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val acceleration = sqrt(x * x + y * y + z * z) - GRAVITY

        // تحديث الشاشة كل ثانية حقيقية
        val now = System.currentTimeMillis()
        if (now - lastSensorUpdateMs >= ACCEL_UPDATE_INTERVAL_MS) {
            lastSensorUpdateMs = now
            sendBroadcast(Intent(ACTION_ACCEL_UPDATE).apply {
                putExtra(EXTRA_ACCEL, acceleration)
            })
        }

        if (!isArmed || alarmTriggered) return

        if (acceleration > MOVEMENT_THRESHOLD && !isGracePeriod) {
            startGracePeriod()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ══════════════════════════════════════════════════════════════
    // منطق اللغم
    // ══════════════════════════════════════════════════════════════

    private fun startGracePeriod() {
        isGracePeriod = true
        Log.d(TAG, "Grace period started")
        updateNotification("⚠️ تيك... توك... رجع التيلفون!")
        sendBroadcast(Intent(ACTION_GRACE_START))

        graceRunnable = Runnable {
            isGracePeriod = false
            if (isArmed && !alarmTriggered && lastZ > FACE_DOWN_THRESHOLD) {
                Log.d(TAG, "Grace FAILED — triggering alarm")
                triggerAlarm()
            } else if (isArmed) {
                Log.d(TAG, "Grace cancelled — safe")
                updateNotification("🚨 اللغم نشيط — لا تلمس التيلفون!")
                sendBroadcast(Intent(ACTION_GRACE_CANCELLED))
            }
        }
        handler.postDelayed(graceRunnable!!, 3_000)
    }

    // ══════════════════════════════════════════════════════════════
    // الإنذار
    // ══════════════════════════════════════════════════════════════

    fun triggerAlarm() {
        alarmTriggered = true
        isArmed        = false
        Log.d(TAG, "ALARM TRIGGERED")

        forceMaxVolume()

        // يحاول R.raw أولاً، إيلا مالقاهش يروح للـ system URI
        val played = playAlarm() || playFallbackAlarm()
        if (!played) Log.e(TAG, "ALL sound attempts FAILED")

        triggerAlarmVibration()
        updateNotification("💥 فشلت! اضغط هنا باش يسكت الإنذار", showSurrender = true)
        sendBroadcast(Intent(ACTION_ALARM_TRIGGERED))
    }

    // ─── رفع الصوت للحد الأقصى ────────────────────────────────────
    private fun forceMaxVolume() {
        try {
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            originalRingVolume  = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            audioManager.setStreamVolume(AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)

            Log.d(TAG, "Volume forced to max on all streams")
        } catch (e: Exception) {
            Log.e(TAG, "forceMaxVolume: ${e.message}")
        }
    }

    /**
     * playAlarm — يحاول R.raw.alarm_sound
     * يتحقق بلي الملف موجود فعلاً قبل ما يحاول
     */
    private fun playAlarm(): Boolean {
        return try {
            val rawId = resources.getIdentifier("alarm_sound", "raw", packageName)
            if (rawId == 0) {
                Log.d(TAG, "R.raw.alarm_sound not found — skip to fallback")
                return false
            }

            Log.d(TAG, "Playing R.raw.alarm_sound (id=$rawId)")
            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }
                val afd = resources.openRawResourceFd(rawId)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "✅ R.raw.alarm_sound SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "playAlarm failed: ${e.message}")
            safeReleasePlayer()
            false
        }
    }

    /**
     * playFallbackAlarm — صوت التيلفون الافتراضي
     * 3 محاولات: ALARM → RING → MUSIC
     */
    private fun playFallbackAlarm(): Boolean {
        val alarmUri = getSystemAlarmUri() ?: run {
            Log.e(TAG, "No system URI found at all")
            return false
        }
        val ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: alarmUri

        return tryPlayUri(alarmUri, AudioAttributes.USAGE_ALARM,                AudioManager.STREAM_ALARM, "ALARM")
            || tryPlayUri(ringUri,  AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioManager.STREAM_RING,  "RING")
            || tryPlayUri(alarmUri, AudioAttributes.USAGE_MEDIA,                 AudioManager.STREAM_MUSIC, "MUSIC")
    }

    private fun tryPlayUri(uri: Uri, usage: Int, streamType: Int, label: String): Boolean {
        return try {
            Log.d(TAG, "Trying $label stream — $uri")
            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(streamType)
                }
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "✅ $label stream SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "$label stream FAILED: ${e.message}")
            safeReleasePlayer()
            false
        }
    }

    private fun getSystemAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    // ─── اهتزاز ───────────────────────────────────────────────────
    private fun triggerAlarmVibration() {
        try {
            val pattern = longArrayOf(0, 600, 200, 600, 200, 600, 200, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    // إيقاف الإنذار
    // ══════════════════════════════════════════════════════════════

    fun stopAlarm() {
        safeStopAlarm()
        updateNotification("📝 تم تسجيل الفشل")
        Log.d(TAG, "Alarm stopped")
    }

    private fun safeStopAlarm() {
        safeReleasePlayer()
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,  originalRingVolume,  0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
        } catch (e: Exception) { Log.e(TAG, "restoreVolume: ${e.message}") }
        try { vibrator.cancel() } catch (e: Exception) { }
    }

    private fun safeReleasePlayer() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "safeReleasePlayer: ${e.message}")
        } finally { mediaPlayer = null }
    }

    private fun onSurrenderFromNotification() {
        stopAlarm()
        sendBroadcast(Intent(ACTION_SURRENDERED))
    }

    // ══════════════════════════════════════════════════════════════
    // الإشعارات
    // ══════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FocusMine", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "جلسة التركيز النشطة" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, showSurrender: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FocusMine 🔴")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentIntent(openApp)
            .setOngoing(true)

        if (showSurrender) {
            val surrenderIntent = PendingIntent.getService(
                this, 0,
                Intent(this, FocusService::class.java).apply { action = ACTION_SURRENDER },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "أنا أستسلم", surrenderIntent
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String, showSurrender: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, showSurrender))
    }
}
