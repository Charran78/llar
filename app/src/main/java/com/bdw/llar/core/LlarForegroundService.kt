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
import androidx.core.app.ServiceCompat
import com.bdw.llar.R

/**
 * Servicio en primer plano que mantiene a Llar activo.
 * Permite que el micrófono siga funcionando cuando la app no está visible.
 */
class LlarForegroundService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "llar_channel"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Iniciando servicio persistente de Llar...")
        createNotificationChannel()
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Deteniendo servicio por orden del sistema.")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID, 
                    notification, 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        0
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar startForeground con tipo MICROPHONE", e)
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Llar en ejecución",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene a Llar escuchando en segundo plano"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Llar")
            .setContentText("Estoy aquí, te escucho.")
            .setSmallIcon(R.drawable.ic_llar_icon) // Usamos el icono principal de la app
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LlarForegroundService"
        const val ACTION_STOP = "com.bdw.llar.ACTION_STOP_SERVICE"
    }
}
