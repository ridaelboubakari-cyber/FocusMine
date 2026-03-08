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
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class FocusService : Service(), SensorEventListener {

    // ─── المستشعرات ───────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var frameCount = 0L // ✅ إضافة: لحساب الإطارات وإرسال التحديثات للشاشة

    // ─── الصوت ────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var originalAlarmVolume = 0

    // ─── الاهتزاز ─────────────────────────────────────────────────
    private lateinit var vibrator: Vibrator

    // ─── الحالة ───────────────────────────────────────────────────
    var isArmed        = false
    var alarmTriggered = false
    var isGracePeriod  = false
    private var lastZ  = 0f

    // ─── المؤقتات ─────────────────────────────────────────────────
    private val handler          = Handler(Looper.getMainLooper())
    private var graceRunnable: Runnable? = null

    // ─── ثوابت ────────────────────────────────────────────────────
    private val GRAVITY             = SensorManager.GRAVITY_EARTH.toDouble()
    private val MOVEMENT_THRESHOLD  = 0.5
    private val FACE_DOWN_THRESHOLD = -7.0
    private val CHANNEL_ID          = "focus_mine_channel"
    private val NOTIF_ID            = 1

    // ══════════════════════════════════════════════════════════════
    // دورة حياة الخدمة
    // ══════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()

        audioManager  = getSystemService(Context.AUDIO_SERVICE)  as AudioManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        vibrator      = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"))
        }

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM -> {
                isArmed = true
                sendBroadcast(Intent(ACTION_ARMED)) // ✅ إضافة: إرسال إشارة بتفعيل اللغم
            }
            ACTION_DISARM   -> stopSelf()
            ACTION_SURRENDER -> onSurrenderFromNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        graceRunnable?.let { handler.removeCallbacks(it) }
        safeStopAlarm()
    }

    override fun onBind(intent: Intent?) = null

    // ══════════════════════════════════════════════════════════════
    // المستشعر — قلب التطبيق
    // ══════════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        lastZ = event.values[2]

        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val acceleration = sqrt(x * x + y * y + z * z) - GRAVITY

        // ✅ إضافة: إرسال تحديثات التسارع للشاشة كل ثانية تقريباً (لتشغيل الرادار)
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
        updateNotification("⚠️ تيك... توك... رجع التيلفون!")
        sendBroadcast(Intent(ACTION_GRACE_START)) // ✅ إضافة: إرسال إشارة بدء فترة السماح

        graceRunnable = Runnable {
            isGracePeriod = false
            if (isArmed && !alarmTriggered && lastZ > FACE_DOWN_THRESHOLD) {
                triggerAlarm()
            } else if (isArmed) {
                updateNotification("🚨 اللغم نشيط — لا تلمس التيلفون!")
                sendBroadcast(Intent(ACTION_GRACE_CANCELLED)) // ✅ إضافة: إرسال إشارة إلغاء فترة السماح
            }
        }
        handler.postDelayed(graceRunnable!!, 3_000)
    }

    fun triggerAlarm() {
        alarmTriggered = true
        isArmed        = false

        originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        try {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)
            if (mediaPlayer != null) {
                mediaPlayer!!.isLooping = true
                mediaPlayer!!.setAudioStreamType(AudioManager.STREAM_ALARM)
                mediaPlayer!!.start()
            } else {
                playFallbackAlarm()
            }
        } catch (e: Exception) {
            playFallbackAlarm()
        }

        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }

        updateNotification("💥 فشلت! اضغط هنا باش يسكت الإنذار", showSurrender = true)
        sendBroadcast(Intent(ACTION_ALARM_TRIGGERED))
    }

    private fun playFallbackAlarm() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM
            )
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_ALARM)
                setDataSource(this@FocusService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
        }
    }

    private fun safeStopAlarm() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (e: Exception) {
        } finally {
            mediaPlayer = null
        }

        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
        } catch (e: Exception) { }

        vibrator.cancel()
    }

    fun stopAlarm() {
        safeStopAlarm()
        updateNotification("📝 تم تسجيل الفشل")
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

    // ══════════════════════════════════════════════════════════════
    // Companion Object
    // ══════════════════════════════════════════════════════════════

    companion object {
        const val ACTION_ARM             = "com.reda.focusmine.ARM"
        const val ACTION_DISARM          = "com.reda.focusmine.DISARM"
        const val ACTION_SURRENDER       = "com.reda.focusmine.SURRENDER"
        const val ACTION_ALARM_TRIGGERED = "com.reda.focusmine.ALARM_TRIGGERED"
        const val ACTION_SURRENDERED     = "com.reda.focusmine.SURRENDERED"
        
        // ✅ إضافة: الثوابت الجديدة للتواصل مع الشاشة
        const val ACTION_ARMED           = "com.reda.focusmine.ARMED"
        const val ACTION_ACCEL_UPDATE    = "com.reda.focusmine.ACCEL_UPDATE"
        const val ACTION_GRACE_START     = "com.reda.focusmine.GRACE_START"
        const val ACTION_GRACE_CANCELLED = "com.reda.focusmine.GRACE_CANCELLED"
        const val EXTRA_ACCEL            = "extra_accel"

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
}
