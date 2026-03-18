package com.bdw.llar.efectores

import android.content.Context
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import com.bdw.llar.BuildConfig // Importar BuildConfig
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Efector de Telegram: Permite a Llar enviar mensajes al usuario.
 * v1.2: Token y Chat ID obtenidos de BuildConfig (gradle.properties). TAG renombrado. Context eliminado.
 */
class TelegramAndroid : BusEventos.Suscriptor {

    private val client = OkHttpClient()
    private val tag = "TelegramAndroid"

    init {
        BusEventos.suscribir("telegram.enviar_mensaje", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "telegram.enviar_mensaje") {
            val mensaje = evento.datos["mensaje"] as? String ?: return
            val requestId = evento.datos["request_id"] as? String
            
            enviarMensaje(mensaje, requestId)
        }
    }

    private fun enviarMensaje(mensaje: String, requestId: String?) {
        val token = BuildConfig.TELEGRAM_TOKEN
        val chatId = BuildConfig.TELEGRAM_CHAT_ID

        if (token.isBlank() || chatId.isBlank()) {
            Log.w(tag, "Telegram no configurado en gradle.properties. Ignorando envío.")
            return
        }

        val url = "https://api.telegram.org/bot$token/sendMessage"
        
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", mensaje)
            .add("parse_mode", "Markdown")
            .build()

        val request = Request.Builder().url(url).post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(tag, "Error al enviar mensaje a Telegram: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.i(tag, "Mensaje enviado a Telegram correctamente.")
                } else {
                    Log.e(tag, "Fallo al enviar a Telegram: ${response.code}")
                }
                response.close()
            }
        })
    }

    fun shutdown() {
        BusEventos.desuscribirTodo(this)
    }
}
