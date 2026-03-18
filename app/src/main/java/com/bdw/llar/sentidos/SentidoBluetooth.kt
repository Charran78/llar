package com.bdw.llar.sentidos

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento

/**
 * Sentido de Bluetooth.
 * Escucha cuando un dispositivo Bluetooth se conecta o desconecta.
 * Emite eventos para que Llar sea proactiva (ej: leer lista al entrar al coche).
 * TAG renombrado.
 */
class SentidoBluetooth(private val context: Context) {

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val device = intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            
            // Si falta el permiso (Android 12+), name puede ser null, pero extraemos lo que podamos
            val deviceName = try { device?.name ?: "Dispositivo desconocido" } catch (e: SecurityException) { "Dispositivo Bluetooth" }

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.i(tag, "Bluetooth conectado: $deviceName")
                    BusEventos.publicar(Evento(
                        tipo = "bluetooth.conectado",
                        origen = "sentido_bluetooth",
                        datos = mapOf("dispositivo" to deviceName)
                    ))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(tag, "Bluetooth desconectado: $deviceName")
                    BusEventos.publicar(Evento(
                        tipo = "bluetooth.desconectado",
                        origen = "sentido_bluetooth",
                        datos = mapOf("dispositivo" to deviceName)
                    ))
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
        Log.i(tag, "SentidoBluetooth iniciado y escuchando.")
    }

    fun shutdown() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error al desregistrar receiver de Bluetooth: ${e.message}")
        }
    }

    companion object {
        private const val tag = "SentidoBluetooth"
    }
}
