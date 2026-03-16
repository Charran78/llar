package com.bdw.llar.sentidos

import android.content.Context
import android.util.Log
import com.bdw.llar.BuildConfig
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import ai.picovoice.porcupine.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detector de la palabra de activación "Llar" usando Porcupine.
 * Se pausa automáticamente cuando Llar habla para evitar falsos positivos.
 */
class WakeWordDetector(private val context: Context) : BusEventos.Suscriptor {
    private var porcupineManager: PorcupineManager? = null
    private val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
    private val pausadoPorVoz = AtomicBoolean(false)

    init {
        BusEventos.suscribir("voz.empezado", this)
        BusEventos.suscribir("voz.finalizado", this)
    }

    fun iniciarEscucha() {
        if (porcupineManager != null) {
            Log.w(TAG, "Ya estaba escuchando. Ignorando inicio.")
            return
        }

        if (accessKey.isEmpty() || accessKey == "null") {
            Log.e(TAG, "AccessKey no configurada en gradle.properties")
            return
        }

        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE) // Usamos 'porcupine' por ahora
                .setSensitivity(0.7f)
                .build(context) { keywordIndex ->
                    if (keywordIndex == 0) {
                        Log.i(TAG, "WakeWord Detectada!")
                        BusEventos.publicar(Evento("wakeword.detectada", "wakeword_detector"))
                    }
                }
            
            porcupineManager?.start()
            Log.i(TAG, "Porcupine iniciado correctamente.")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error al iniciar Porcupine: ${e.message}")
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "voz.empezado" -> {
                if (porcupineManager != null) {
                    Log.d(TAG, "Pausando Porcupine por voz...")
                    pausadoPorVoz.set(true)
                    detenerEscucha()
                }
            }
            "voz.finalizado" -> {
                if (pausadoPorVoz.get()) {
                    Log.d(TAG, "Reanudando Porcupine tras voz...")
                    pausadoPorVoz.set(false)
                    // Añadimos un pequeño margen para que el audio termine de salir físicamente
                    Thread {
                        Thread.sleep(300)
                        iniciarEscucha()
                    }.start()
                }
            }
        }
    }

    fun detenerEscucha() {
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
            porcupineManager = null
        } catch (e: PorcupineException) {
            Log.e(TAG, "Error al detener Porcupine: ${e.message}")
        }
    }

    fun shutdown() {
        detenerEscucha()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
