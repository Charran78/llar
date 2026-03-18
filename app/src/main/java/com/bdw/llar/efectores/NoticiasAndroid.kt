package com.bdw.llar.efectores

import android.content.Context
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Efector de Noticias: Consulta titulares recientes.
 * Utiliza un proxy RSS a JSON gratuito para facilitar el parseo.
 * TAG renombrado.
 */
class NoticiasAndroid(private val context: Context) : BusEventos.Suscriptor {

    private val client = OkHttpClient()
    private val tag = "NoticiasAndroid"

    init {
        BusEventos.suscribir("noticias.consultar", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "noticias.consultar") {
            val categoria = evento.datos["categoria"] as? String ?: "general"
            val requestId = evento.datos["request_id"] as? String
            val toolCallId = evento.datos["tool_call_id"] as? String

            consultarNoticias(categoria, requestId, toolCallId)
        }
    }

    private fun consultarNoticias(categoria: String, requestId: String?, toolCallId: String?) {
        // Usamos un servicio de noticias en español (RTVE RSS o similar vía agregador)
        val url = "https://api.rss2json.com/v1/api.json?rss_url=https://www.rtve.es/noticias/rss/noticias.xml"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                enviarResultado("No he podido conectar con el servicio de noticias.", requestId, toolCallId)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val json = JSONObject(body)
                        val items = json.getJSONArray("items")
                        val titulares = mutableListOf<String>()
                        
                        // Tomamos los 5 primeros titulares
                        for (i in 0 until minOf(items.length(), 5)) {
                            val item = items.getJSONObject(i)
                            titulares.add("- " + item.getString("title"))
                        }

                        val resultado = "Aquí tienes los titulares más recientes:\n" + titulares.joinToString("\n")
                        enviarResultado(resultado, requestId, toolCallId)
                    } catch (e: Exception) {
                        enviarResultado("He encontrado noticias pero no he podido leer los titulares.", requestId, toolCallId)
                    }
                } else {
                    enviarResultado("No hay noticias disponibles en este momento.", requestId, toolCallId)
                }
            }
        })
    }

    private fun enviarResultado(resultado: String, requestId: String?, toolCallId: String?) {
        BusEventos.publicar(Evento(
            tipo = "herramienta.resultado",
            origen = "noticias_android",
            datos = mapOf(
                "nombre_herramienta" to "get_latest_news",
                "resultado" to resultado,
                "request_id" to requestId,
                "tool_call_id" to toolCallId
            )
        ))
    }

    fun shutdown() {
        BusEventos.desuscribirTodo(this)
    }
}
