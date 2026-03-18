package com.bdw.llar.efectores

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento

/**
 * Efector para controlar el hardware del dispositivo Android.
 * Maneja batería, linterna y otros sensores.
 * v2.1: Robusto, con verificación de permisos y manejo seguro de errores.
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
                val toolCallId = evento.datos["tool_call_id"] as? String
                val nivel = obtenerNivelBateria()
                Log.i(TAG, "Consultando batería: $nivel%")
                
                val resultado = if (nivel >= 0) {
                    "El nivel de batería es del $nivel%."
                } else {
                    "No se pudo obtener el nivel de batería en este momento."
                }

                BusEventos.publicar(Evento(
                    tipo = "herramienta.resultado",
                    origen = "dispositivo_android",
                    datos = mapOf(
                        "nombre_herramienta" to "get_battery_level",
                        "resultado" to resultado,
                        "request_id" to requestId,
                        "tool_call_id" to toolCallId
                    )
                ))
            }
            "dispositivo.linterna" -> {
                val enabled = evento.datos["enabled"] as? Boolean ?: false
                val requestId = evento.datos["request_id"] as? String
                val toolCallId = evento.datos["tool_call_id"] as? String
                
                Log.i(TAG, "Cambiando linterna a: $enabled")
                val (exito, mensaje) = toggleLinterna(enabled)
                
                BusEventos.publicar(Evento(
                    tipo = "herramienta.resultado",
                    origen = "dispositivo_android",
                    datos = mapOf(
                        "nombre_herramienta" to "toggle_flashlight",
                        "resultado" to if (exito) "OK: $mensaje" else "ERROR: $mensaje",
                        "request_id" to requestId,
                        "tool_call_id" to toolCallId
                    )
                ))
            }
        }
    }

    private fun obtenerNivelBateria(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Error al obtener nivel de batería", e)
            -1
        }
    }

    private fun toggleLinterna(enabled: Boolean): Pair<Boolean, String> {
        // 1. Verificar permiso de cámara
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permiso de cámara no concedido para la linterna")
            return Pair(false, "Permiso de cámara denegado")
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        
        return try {
            // 2. Obtener el primer ID de cámara disponible de forma segura
            val cameraId = cameraManager.cameraIdList.firstOrNull() 
                ?: return Pair(false, "No se encontró hardware de cámara")

            // 3. Cambiar estado
            cameraManager.setTorchMode(cameraId, enabled)
            Pair(true, "Linterna ${if (enabled) "encendida" else "apagada"}")
            
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error de acceso a la cámara", e)
            Pair(false, "Cámara ocupada o no disponible")
        } catch (e: Exception) {
            Log.e(TAG, "Error inesperado en linterna", e)
            Pair(false, "Error técnico: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DispositivoAndroid"
    }
}
