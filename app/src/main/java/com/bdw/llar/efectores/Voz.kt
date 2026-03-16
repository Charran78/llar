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
 * Notifica al sistema cuando empieza y termina de hablar para sincronizar el avatar.
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

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    BusEventos.publicar(Evento("voz.finalizado", "voz"))
                }
            })

            // Intentar voz femenina
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val voices = tts?.voices
                val femaleVoice = voices?.find { 
                    it.locale.language == locale.language && it.name.lowercase().contains("female") 
                }
                femaleVoice?.let { tts?.voice = it }
            }

            tts?.setPitch(1.05f) // Un poco menos robótico, más natural
            tts?.setSpeechRate(1.0f) // Velocidad normal para que se entienda mejor
            isReady = true
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "voz.hablar") {
            val texto = evento.datos["texto"] as? String
            if (texto != null) {
                hablar(texto)
            }
        }
    }

    private fun hablar(texto: String) {
        if (isReady) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LlarMsg_${System.currentTimeMillis()}")
            
            // Forzamos el evento voz.empezado incluso antes de que el motor TTS procese,
            // para asegurar que el Avatar cambie a 'hablar' instantáneamente.
            BusEventos.publicar(Evento("voz.empezado", "voz_previa"))

            tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        BusEventos.desuscribir("voz.hablar", this)
    }

    companion object {
        private const val TAG = "Voz"
    }
}
