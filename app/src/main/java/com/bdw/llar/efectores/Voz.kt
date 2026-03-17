package com.bdw.llar.efectores

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import android.util.Log
import java.util.Locale

/**
 * El Efector de Voz utiliza el motor de TextToSpeech de Android.
 * v2.1: Limpieza de asteriscos para evitar locución de símbolos.
 */
class Voz(context: Context) : BusEventos.Suscriptor, TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context, this)
        BusEventos.suscribir("voz.hablar", this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val locale = Locale("es", "ES")
            tts?.language = locale
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "Empezando a hablar...")
                    BusEventos.publicar(Evento("voz.empezado", "voz"))
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "Fin de la locución.")
                    BusEventos.publicar(Evento("voz.finalizado", "voz"))
                }

                override fun onError(utteranceId: String?) {
                    BusEventos.publicar(Evento("voz.finalizado", "voz"))
                }
            })

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val voices = tts?.voices
                val femaleVoice = voices?.find { 
                    it.locale.language == locale.language && it.name.lowercase().contains("female") 
                }
                femaleVoice?.let { tts?.voice = it }
            }

            tts?.setPitch(1.05f)
            tts?.setSpeechRate(1.0f)
            isReady = true
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "voz.hablar") {
            val texto = evento.datos["texto"] as? String
            if (texto != null) {
                // LIMPIEZA DE ASTERISCOS: Evita que el TTS diga "asterisco"
                val textoLimpio = texto.replace("*", "")
                hablar(textoLimpio)
            }
        }
    }

    private fun hablar(texto: String) {
        if (isReady && texto.isNotBlank()) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LlarMsg_${System.currentTimeMillis()}")
            
            // Notificamos inicio
            BusEventos.publicar(Evento("voz.empezado", "voz_previa"))

            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "Voz"
    }
}
