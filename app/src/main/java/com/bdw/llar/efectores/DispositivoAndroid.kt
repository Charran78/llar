package com.bdw.llar.efectores

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento

/**
 * Efector para controlar el hardware del dispositivo Android.
 * Maneja batería, linterna y otros sensores.
 */
class DispositivoAndroid(private val context: Context) : BusEventos.Suscriptor {

    init {
        BusEventos.suscribir("dispositivo.consultar_bateria", this)
        BusEventos.suscribir("dispositivo.linterna", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "dispositivo.consultar_bateria" -> {
                val requestId = evento.datos["request_id"] as? String
                val nivel = obtenerNivelBateria()
                Log.i(TAG, "Consultando batería: $nivel%")
                
                BusEventos.publicar(Evento(
                    tipo = "herramienta.resultado",
                    origen = "dispositivo_android",
                    datos = mapOf(
                        "nombre_herramienta" to "get_battery_level",
                        "resultado" to "El nivel de batería es del $nivel%.",
                        "request_id" to requestId
                    )
                ))
            }
            "dispositivo.linterna" -> {
                val enabled = evento.datos["enabled"] as? Boolean ?: false
                val requestId = evento.datos["request_id"] as? String
                val exito = toggleLinterna(enabled)
                
                BusEventos.publicar(Evento(
                    tipo = "herramienta.resultado",
                    origen = "dispositivo_android",
                    datos = mapOf(
                        "nombre_herramienta" to "toggle_flashlight",
                        "resultado" to if (exito) "Linterna ${if (enabled) "encendida" else "apagada"}." else "Error al acceder a la linterna.",
                        "request_id" to requestId
                    )
                ))
            }
        }
    }

    private fun obtenerNivelBateria(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun toggleLinterna(enabled: Boolean): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enabled)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error linterna: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DispositivoAndroid"
    }
}
