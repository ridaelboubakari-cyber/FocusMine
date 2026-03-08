package com.reda.focusmine

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

/**
 * FocusService — الخدمة اللي كتخلي اللغم يخدم حتى ملي الشاشة تطفى
 *
 * Foreground Service = التطبيق كيبقى حي فالخلفية
 * بدونها: أندرويد (Doze Mode) كيقتل المستشعر بعد دقيقة
 * بيها: المستشعر خدام طول الجلسة — ساعتين كاملين
 */
class FocusService : Service(), SensorEventListener {

    // ─── المستشعرات ───────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // ─── الصوت ────────────────────────────────────────────────────
    private lateinit var audioManager: AudioManager
    private var alarmRingtone: Ringtone? = null
    private var originalVolume = 0

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
    private val MOVEMENT_THRESHOLD  = 2.5
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
        startForeground(NOTIF_ID, buildNotification("🚨 اللغم نشيط — لا تلمس التيلفون!"))

        // تسجيل المستشعر — هنا فالService مانقلقوش على Doze Mode
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ARM      -> isArmed = true
            ACTION_DISARM   -> stopSelf()
            ACTION_SURRENDER -> onSurrenderFromNotification()
        }
        // START_STICKY = إيلا قتل أندرويد الخدمة، تعاود تبدا وحدها
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        alarmRingtone?.stop()
        vibrator.cancel()
        graceRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onBind(intent: Intent?) = null  // ماشي Bound Service

    // ══════════════════════════════════════════════════════════════
    // المستشعر
    // ══════════════════════════════════════════════════════════════

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        lastZ = event.values[2]

        if (!isArmed || alarmTriggered) return

        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()

        val acceleration = sqrt(x * x + y * y + z * z) - GRAVITY

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

        // إشعار تحذيري
        updateNotification("⚠️ تيك... توك... رجع التيلفون!")

        graceRunnable = Runnable {
            isGracePeriod = false
            if (isArmed && !alarmTriggered && lastZ > FACE_DOWN_THRESHOLD) {
                triggerAlarm()
            } else if (isArmed) {
                updateNotification("🚨 اللغم نشيط — لا تلمس التيلفون!")
            }
        }
        handler.postDelayed(graceRunnable!!, 3_000)
    }

    fun triggerAlarm() {
        alarmTriggered = true
        isArmed        = false

        // رفع الصوت للحد الأقصى
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        // تشغيل الإنذار
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        alarmRingtone = RingtoneManager.getRingtone(this, uri)
        alarmRingtone?.play()

        // اهتزاز عنيف ومتواصل
        val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }

        // إشعار مع زر الاستسلام
        updateNotification("💥 فشلت! اضغط هنا باش يسكت الإنذار", showSurrender = true)

        // إعلام MainActivity باش تظهر زر الاستسلام
        sendBroadcast(Intent(ACTION_ALARM_TRIGGERED))
    }

    fun stopAlarm() {
        alarmRingtone?.stop()
        alarmRingtone = null
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
        vibrator.cancel()
        updateNotification("📝 تم تسجيل الفشل")
    }

    private fun onSurrenderFromNotification() {
        stopAlarm()
        sendBroadcast(Intent(ACTION_SURRENDERED))
    }

    // ══════════════════════════════════════════════════════════════
    // الإشعارات (Notification)
    // ══════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FocusMine",
                NotificationManager.IMPORTANCE_LOW  // LOW = بلا صوت، ما يزعجش
            ).apply { description = "جلسة التركيز" }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, showSurrender: Boolean = false): Notification {
        // Intent باش تفتح التطبيق ملي تكليك على الإشعار
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
            .setOngoing(true)  // الإشعار ما يتمسحش بالسحب

        // زر الاستسلام فالإشعار — يظهر فقط ملي يطلع الإنذار
        if (showSurrender) {
            val surrenderIntent = PendingIntent.getService(
                this, 0,
                Intent(this, FocusService::class.java).apply { action = ACTION_SURRENDER },
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "أنا أستسلم", surrenderIntent)
        }

        return builder.build()
    }

    private fun updateNotification(text: String, showSurrender: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text, showSurrender))
    }

    // ══════════════════════════════════════════════════════════════
    // Actions (باش MainActivity تتواصل مع الخدمة)
    // ══════════════════════════════════════════════════════════════

    companion object {
        const val ACTION_ARM             = "com.reda.focusmine.ARM"
        const val ACTION_DISARM          = "com.reda.focusmine.DISARM"
        const val ACTION_SURRENDER       = "com.reda.focusmine.SURRENDER"
        const val ACTION_ALARM_TRIGGERED = "com.reda.focusmine.ALARM_TRIGGERED"
        const val ACTION_SURRENDERED     = "com.reda.focusmine.SURRENDERED"

        // باش تبدا الخدمة من MainActivity
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

