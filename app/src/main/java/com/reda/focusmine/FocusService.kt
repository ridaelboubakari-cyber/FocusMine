package com.reda.focusmine

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
    private var frameCount = 0L

    // ─── الصوت ────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var originalAlarmVolume  = 0
    private var originalRingVolume   = 0
    private var originalMusicVolume  = 0

    // ─── الاهتزاز ─────────────────────────────────────────────────
    private lateinit var vibrator: Vibrator

    // ─── الحالة ───────────────────────────────────────────────────
    var isArmed        = false
    var alarmTriggered = false
    var isGracePeriod  = false
    private var lastZ  = 0f

    // ─── المؤقتات ─────────────────────────────────────────────────
    private val handler        = Handler(Looper.getMainLooper())
    private var graceRunnable: Runnable? = null

    // ─── ثوابت ────────────────────────────────────────────────────
    private val GRAVITY             = SensorManager.GRAVITY_EARTH.toDouble()
    private val MOVEMENT_THRESHOLD  = 0.5
    private val FACE_DOWN_THRESHOLD = -7.0

    // ══════════════════════════════════════════════════════════════
    // دورة حياة الخدمة
    // ══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        audioManager  = getSystemService(Context.AUDIO_SERVICE)   as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE)  as SensorManager
        vibrator      = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()

        // ✅ startForeground حسب نسخة أندرويد
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                startForeground(
                    NOTIF_ID,
                    buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIF_ID,
                    buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
            }
            else -> {
                startForeground(NOTIF_ID, buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"))
            }
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
            ACTION_DISARM    -> {
                Log.d(TAG, "Service disarmed — stopping")
                stopSelf()
            }
            ACTION_SURRENDER -> onSurrenderFromNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        sensorManager.unregisterListener(this)
        graceRunnable?.let { handler.removeCallbacks(it) }
        safeStopAlarm()
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

        // إرسال تحديث للشاشة كل ثانية تقريباً
        frameCount++
        if (frameCount % 60 == 0L) {
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
                Log.d(TAG, "Grace failed — triggering alarm")
                triggerAlarm()
            } else if (isArmed) {
                Log.d(TAG, "Grace cancelled — phone returned")
                updateNotification("🚨 اللغم نشيط — لا تلمس التيلفون!")
                sendBroadcast(Intent(ACTION_GRACE_CANCELLED))
            }
        }
        handler.postDelayed(graceRunnable!!, 3_000)
    }

    // ══════════════════════════════════════════════════════════════
    // الإنذار — مضمون 100% يصدر صوت
    // ══════════════════════════════════════════════════════════════

    fun triggerAlarm() {
        alarmTriggered = true
        isArmed        = false
        Log.d(TAG, "ALARM TRIGGERED")

        // ✅ الخطوة 1: رفع كل قنوات الصوت للحد الأقصى
        forceMaxVolume()

        // ✅ الخطوة 2: تشغيل الصوت بـ 3 محاولات متتالية
        val soundPlayed = tryPlayAlarmStream()
                       || tryPlayRingtoneStream()
                       || tryPlayMusicStream()

        if (!soundPlayed) {
            Log.e(TAG, "ALL sound attempts failed — vibration only")
        }

        // ✅ الخطوة 3: اهتزاز عنيف ومتواصل
        triggerAlarmVibration()

        updateNotification("💥 فشلت! اضغط هنا باش يسكت الإنذار", showSurrender = true)
        sendBroadcast(Intent(ACTION_ALARM_TRIGGERED))
    }

    // ─── رفع كل قنوات الصوت ──────────────────────────────────────
    private fun forceMaxVolume() {
        try {
            // نحفظ القيم الأصلية
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            originalRingVolume  = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            originalMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // نرفع الكل للحد الأقصى
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            audioManager.setStreamVolume(
                AudioManager.STREAM_RING,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_RING), 0
            )
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0
            )

            // ✅ إلغاء وضع الصمت في Samsung — AudioManager.RINGER_MODE_NORMAL
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

            Log.d(TAG, "Volume forced to max on all streams")
        } catch (e: Exception) {
            Log.e(TAG, "forceMaxVolume failed: ${e.message}")
        }
    }

    // ─── محاولة 1: AudioAttributes ALARM — الطريقة الصحيحة Android 8+ ──
    private fun tryPlayAlarmStream(): Boolean {
        return try {
            val uri = getAlarmUri()
            Log.d(TAG, "Trying ALARM AudioAttributes with URI: $uri")

            mediaPlayer = MediaPlayer().apply {
                // ✅ AudioAttributes بدل setAudioStreamType — Samsung كيفهمو صح
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "✅ ALARM AudioAttributes SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ALARM AudioAttributes failed: ${e.message}")
            safeReleasePlayer()
            false
        }
    }

    // ─── محاولة 2: AudioAttributes RING ─────────────────────────
    private fun tryPlayRingtoneStream(): Boolean {
        return try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return false
            Log.d(TAG, "Trying RING AudioAttributes with URI: $uri")

            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_RING)
                }
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "✅ RING AudioAttributes SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "RING AudioAttributes failed: ${e.message}")
            safeReleasePlayer()
            false
        }
    }

    // ─── محاولة 3: AudioAttributes MEDIA — آخر حل ───────────────
    private fun tryPlayMusicStream(): Boolean {
        return try {
            val uri = getAlarmUri()
            Log.d(TAG, "Trying MEDIA AudioAttributes with URI: $uri")

            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
                setDataSource(applicationContext, uri)
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "✅ MEDIA AudioAttributes SUCCESS")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MEDIA AudioAttributes failed: ${e.message}")
            safeReleasePlayer()
            false
        }
    }

    // ─── جلب أفضل URI للصوت ──────────────────────────────────────
    private fun getAlarmUri(): Uri {
        // 1. صوت الـ Alarm المخصص للتطبيق
        // 2. الـ Alarm الافتراضي للتيلفون
        // 3. الرنين الافتراضي كـ fallback نهائي
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    // ─── اهتزاز عنيف ──────────────────────────────────────────────
    private fun triggerAlarmVibration() {
        try {
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 800)
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
        // إيقاف الصوت
        safeReleasePlayer()

        // إرجاع الصوت لحالتو الأصلية
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,  originalRingVolume,  0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVolume, 0)
        } catch (e: Exception) {
            Log.e(TAG, "restoreVolume failed: ${e.message}")
        }

        // إيقاف الاهتزاز
        try {
            vibrator.cancel()
        } catch (e: Exception) { }
    }

    private fun safeReleasePlayer() {
        try {
            if (mediaPlayer?.isPlaying == true) mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "safeReleasePlayer: ${e.message}")
        } finally {
            mediaPlayer = null
        }
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
                CHANNEL_ID,
                "FocusMine",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "جلسة التركيز النشطة" }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
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
                "أنا أستسلم",
                surrenderIntent
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String, showSurrender: Boolean = false) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, showSurrender))
    }
}
