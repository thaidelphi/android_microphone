package com.antigravity.androidmic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.antigravity.androidmic.audio.AudioLoopEngine

class MicAmplifierService : Service() {
    private val binder = LocalBinder()
    lateinit var engine: AudioLoopEngine
        private set

    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): MicAmplifierService = this@MicAmplifierService
    }

    override fun onCreate() {
        super.onCreate()
        engine = AudioLoopEngine(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidMIC::AmplifierWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAmplifier()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        startAmplifier()

        return START_STICKY
    }

    private fun startAmplifier() {
        if (!engine.isActive) {
            wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes */)
            engine.start()
        }
    }

    fun stopAmplifier() {
        if (engine.isActive) {
            engine.stop()
        }
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MicAmplifierService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_power, getString(R.string.notif_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "การแจ้งเตือนขณะทำงานส่งเสียงไมค์ออกลำโพง"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopAmplifier()
        engine.deviceManager.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "AndroidMicServiceChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.antigravity.androidmic.ACTION_STOP"
    }
}
