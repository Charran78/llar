package com.bdw.llar.sentidos

import android.content.Context
import android.util.Log
import com.bdw.llar.BuildConfig
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import ai.picovoice.porcupine.*

/**
 * Detector de la palabra de activación por defecto "PORCUPINE".
 * Optimizada para liberar el micro inmediatamente tras la detección.
 */
class WakeWordDetector(private val context: Context) : BusEventos.Suscriptor {
    private var porcupineManager: PorcupineManager? = null
    private val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY

    init {
        BusEventos.suscribir("voz.finalizado", this)
        BusEventos.suscribir("oido.activar_modo_escucha", this)
    }

    fun iniciarEscucha() {
        if (porcupineManager != null) return

        if (accessKey.isEmpty() || accessKey.contains("tu_clave")) {
            Log.e(TAG, "AccessKey no válida en gradle.properties")
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.7f)
                .build(context) { keywordIndex ->
                    if (keywordIndex == 0) {
                        Log.i(TAG, "¡WakeWord PORCUPINE detectada!")
                        // Liberamos el micro inmediatamente para que Vosk pueda entrar
                        detenerEscucha()
                        BusEventos.publicar(Evento("wakeword.detectada", "wakeword_detector"))
                    }
                }
            
            porcupineManager?.start()
            Log.i(TAG, "Escuchando palabra clave: PORCUPINE")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "oido.activar_modo_escucha" -> {
                // Si alguien activa el oído manualmente, nosotros soltamos el micro
                detenerEscucha()
            }
            "voz.finalizado" -> {
                // Cuando Llar termina de hablar, volvemos a vigilar la WakeWord
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    iniciarEscucha()
                }, 1000)
            }
        }
    }

    fun detenerEscucha() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: Exception) { }
    }

    fun shutdown() {
        detenerEscucha()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
