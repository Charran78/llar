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

/**
 * Avatar de Llar: Gestiona la secuencia de estados visuales.
 * v2.1: Bloqueo de estado 'dormir' para persistencia.
 */
class Avatar(private val context: Context, private val contenedor: FrameLayout) : BusEventos.Suscriptor {

    private var videoView: VideoView? = null
    private var emocionActual = "neutral"
    private var estaHablando = false
    private var emocionPendiente: String? = null
    private var modoDormirActivo = false
    
    private val mapaVideos = mapOf(
        "alegre"    to R.raw.llar_alegria,
        "enfado"    to R.raw.llar_angry,
        "sorpresa"  to R.raw.llar_surprise,
        "pensar"    to R.raw.llar_think,
        "think"     to R.raw.llar_think,
        "dormir"    to R.raw.llar_dormir,
        "carino"    to R.raw.llar_carino,
        "hablar"    to R.raw.llar_speak,
        "speak"     to R.raw.llar_speak,
        "soplar"    to R.raw.llar_blowing,
        "preocupado" to R.raw.llar_think,
        "neutral"   to R.raw.llar_wait2, 
        "espera"    to R.raw.llar_wait   
    )

    init {
        val vista = LayoutInflater.from(context).inflate(R.layout.view_avatar, contenedor, true)
        videoView = vista.findViewById(R.id.avatar_video)

        configurarVideoView()

        BusEventos.suscribir("avatar.expresar", this)
        BusEventos.suscribir("voz.empezado", this)
        BusEventos.suscribir("voz.finalizado", this)
        BusEventos.suscribir("wakeword.detectada", this)
        BusEventos.suscribir("llm.solicitar", this)
        BusEventos.suscribir("oido.activar_modo_escucha", this)

        expresar("espera")
    }

    private fun configurarVideoView() {
        videoView?.setOnPreparedListener { mp ->
            val esEmocionPuntual = listOf("alegre", "enfado", "sorpresa", "carino", "soplar").contains(emocionActual)
            mp.isLooping = !esEmocionPuntual
            videoView?.start()
        }

        videoView?.setOnCompletionListener {
            val esEmocionPuntual = listOf("alegre", "enfado", "sorpresa", "carino", "soplar").contains(emocionActual)
            if (esEmocionPuntual) {
                // Si estamos en modo dormir pero saltó una emoción (raro pero posible), volvemos a dormir
                if (modoDormirActivo) expresar("dormir") else expresar("neutral")
            }
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "wakeword.detectada", "oido.activar_modo_escucha" -> {
                if (modoDormirActivo) return // Bloqueado si duerme
                emocionPendiente = null
                expresar("neutral")
            }
            "llm.solicitar" -> {
                if (modoDormirActivo) return // Bloqueado si duerme
                expresar("think")
            }
            "voz.empezado" -> {
                if (modoDormirActivo) return // Si está durmiendo, no mueve la boca al despedirse
                estaHablando = true
                expresar("speak")
            }
            "voz.finalizado" -> {
                if (modoDormirActivo) return
                estaHablando = false
                if (emocionPendiente != null) {
                    expresar(emocionPendiente!!)
                    emocionPendiente = null
                } else {
                    expresar("neutral")
                }
            }
            "avatar.expresar" -> {
                val emocion = evento.datos["emocion"] as? String ?: return
                
                if (emocion == "dormir") {
                    modoDormirActivo = true
                    expresar("dormir")
                } else if (emocion == "neutral" && modoDormirActivo) {
                    // Solo despertamos si el evento es explícitamente neutral tras estar dormida
                    modoDormirActivo = false
                    expresar("neutral")
                } else if (!modoDormirActivo) {
                    expresar(emocion)
                }
            }
        }
    }

    private fun expresar(emocion: String) {
        val resId = mapaVideos[emocion] ?: mapaVideos["neutral"]!!
        if (emocionActual == emocion && videoView?.isPlaying == true) return

        emocionActual = emocion
        val uri = Uri.parse("android.resource://${context.packageName}/$resId")
        videoView?.setVideoURI(uri)
    }

    fun shutdown() {
        videoView?.stopPlayback()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "Avatar"
    }
}
