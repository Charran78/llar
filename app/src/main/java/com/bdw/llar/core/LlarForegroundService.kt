package com.bdw.llar.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bdw.llar.R

class LlarForegroundService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "llar_channel"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creando servicio Llar...")
        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Llar Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal para el servicio en primer plano de Llar"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Llar está activo")
            .setContentText("Escuchando en segundo plano...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Asegúrate de tener este icono
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "LlarForegroundService"
    }
}