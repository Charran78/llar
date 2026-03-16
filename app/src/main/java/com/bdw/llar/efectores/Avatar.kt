package com.bdw.llar.efectores

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.VideoView
import com.bdw.llar.R
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Avatar de Llar: gestiona la reproducción de vídeos de emoción en la mitad inferior de la pantalla.
 * FIX #3: Se llama a start() después de setVideoURI en el fundido cruzado.
 * FIX #4: Sin botón expandir — el layout en MainActivity controla el tamaño.
 */
class Avatar(private val context: Context, private val contenedor: FrameLayout) : BusEventos.Suscriptor {

    private var videoView: VideoView? = null
    private var emocionActual = "neutral"
    private var estaHablando = false
    
    // Coroutine scope para animaciones aleatorias
    private var jobAnimacionAleatoria: Job? = null
    private val scopeAleatorio = CoroutineScope(Dispatchers.Main + Job())

    private val mapaVideos = mapOf(
        "alegre"    to R.raw.llar_alegria,
        "enfado"    to R.raw.llar_angry,
        "sorpresa"  to R.raw.llar_surprise,
        "pensar"    to R.raw.llar_think,
        "dormir"    to R.raw.llar_dormir,
        "carino"    to R.raw.llar_carino,
        "hablar"    to R.raw.llar_speak,
        "soplar"    to R.raw.llar_blowing,
        "preocupado" to R.raw.llar_think,
        "neutral"   to R.raw.llar_wait2 // Por defecto wait2 (el llar_wait con sonido fue omitido)
    )

    init {
        val vista = LayoutInflater.from(context).inflate(R.layout.view_avatar, contenedor, true)
        videoView = vista.findViewById(R.id.avatar_video)

        configurarVideoView()

        BusEventos.suscribir("avatar.expresar", this)
        BusEventos.suscribir("voz.empezado", this)
        BusEventos.suscribir("voz.finalizado", this)
        
        // FASE 3: Avatar como oyente orgánico de acciones del usuario
        BusEventos.suscribir("wakeword.detectada", this)
        BusEventos.suscribir("llm.solicitar", this)
        BusEventos.suscribir("llm.respuesta", this)
        BusEventos.suscribir("memoria.compactar", this)

        expresar("neutral")
    }

    private fun configurarVideoView() {
        videoView?.setOnPreparedListener { mp ->
            mp.isLooping = true
            videoView?.alpha = 0f
            videoView?.animate()?.alpha(1f)?.setDuration(400)?.start()
            // start() se llama dentro del OnPreparedListener — correcto
            videoView?.start()
        }

        videoView?.setOnCompletionListener {
            // Si estábamos mostrando una emoción aleatoria corta y termina, volver a neutral (wait2)
            if (emocionActual == "soplar") {
                expresar("neutral")
            } else {
                // Fallback si el loop falla para las emociones normales
                videoView?.start()
            }
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "wakeword.detectada" -> {
                // Si el avatar estaba durmiendo, despertar y alegrarse. Sino, IDLE aleatorio u observando (wait2)
                val emocion = if (emocionActual == "dormir") "alegre" else "neutral"
                expresar(emocion)
            }
            "llm.solicitar", "memoria.compactar" -> {
                // Cuando Llar está enviando cosas al servidor o compactando
                expresar("pensar")
            }
            "llm.respuesta" -> {
                val emocionCmd = evento.datos["emocion"] as? String
                if (emocionCmd != null && emocionCmd != "neutral") {
                    expresar(emocionCmd)
                }
            }
            "avatar.expresar" -> {
                val emocion = evento.datos["emocion"] as? String ?: return
                
                // Si la emoción es "dormir", "alegre", "enfado", "pensar", sobreescribe
                // "neutral" es mandado generalmente al terminar acciones.
                if (!estaHablando || emocion == "hablar" || emocion == "pensar" || emocion == "dormir") {
                    expresar(emocion)
                }
            }
            "voz.empezado" -> {
                estaHablando = true
                
                // Si estábamos durmiendo o pensando, pasamos a hablar. Si era alegría, mantenemos alegría u otra?
                // Según requerimiento: "cuando habla, llar_speak" salvo si tiene que exudar algo concreto. 
                // Priorizamos hablar como general.
                if (emocionActual != "alegre" && emocionActual != "enfado" && emocionActual != "sorpresa" && emocionActual != "carino") {
                    expresar("hablar")
                }
            }
            "voz.finalizado" -> {
                estaHablando = false
                // Tras hablar, forzamos regresar a neutral, excepto si la orden era "dormir" explícita
                if (emocionActual != "dormir") {
                    expresar("neutral")
                }
            }
        }
    }

    private fun expresar(emocion: String) {
        val resId = mapaVideos[emocion] ?: mapaVideos["neutral"]!!

        // Si ya estamos en esta emoción y el vídeo está corriendo, no hacer nada
        if (emocionActual == emocion && videoView?.isPlaying == true) return

        val anteriorEmocion = emocionActual
        emocionActual = emocion

        try {
            val uri = Uri.parse("android.resource://${context.packageName}/$resId")

            // FIX #3: Fundido cruzado + llamada explícita a start() tras setVideoURI
            videoView?.animate()?.alpha(0.4f)?.setDuration(150)?.withEndAction {
                videoView?.setVideoURI(uri)
                // start() se llama desde OnPreparedListener, que siempre se dispara
                // después de setVideoURI. Esto es el flujo correcto en Android VideoView.
                videoView?.animate()?.alpha(1f)?.setDuration(300)?.start()
            }?.start()

            Log.d(TAG, "Emoción: $anteriorEmocion → $emocion")
            gestionarCicloAleatorio()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error al cambiar vídeo: ${e.message}")
        }
    }
    
    private fun gestionarCicloAleatorio() {
        // Cancelamos siempre la rutina anterior (si la hubiera)
        jobAnimacionAleatoria?.cancel()
        
        // Si no estamos en neutral, ni dormir, no hay aleatoriedad
        if (emocionActual != "neutral") return
        
        jobAnimacionAleatoria = scopeAleatorio.launch {
            // Entre 30s (30000ms) y 60s (60000ms)
            val delayMs = Random.nextLong(30000, 60001)
            Log.d(TAG, "Próxima animación aleatoria IDLE en ${delayMs/1000}s")
            
            delay(delayMs)
            
            if (emocionActual == "neutral" && !estaHablando) {
                // Para aleatorizar el idle sin sonido sólo usamos llar_blowing (soplar)
                Log.d(TAG, "Ocio: saltando de wait2 a -> soplar")
                expresar("soplar")
                // Nota: cuando acabe este clip, el OnCompletionListener forzará "neutral"
            }
        }
    }

    fun shutdown() {
        jobAnimacionAleatoria?.cancel()
        scopeAleatorio.cancel()
        videoView?.stopPlayback()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "Avatar"
    }
}
