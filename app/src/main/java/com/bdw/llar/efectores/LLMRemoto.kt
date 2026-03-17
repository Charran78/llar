package com.bdw.llar.efectores

import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Efector de Ollama con soporte para Herramientas (Tooling).
 */
class LLMRemoto : BusEventos.Suscriptor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val ollamaUrl = "http://192.168.1.132:11434/api/chat"
    private val modelName = "llar"

    init {
        BusEventos.suscribir("llm.solicitar", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo != "llm.solicitar") return

        @Suppress("UNCHECKED_CAST")
        val messages = evento.datos["messages"] as? List<Map<String, String>> ?: return
        val tools = evento.datos["herramientas"] as? JSONArray
        val requestId = evento.datos["request_id"] as? String
        val esResumen = (evento.datos["es_resumen"] as? Boolean) ?: false

        scope.launch {
            procesarSolicitud(messages, tools, requestId, esResumen)
        }
    }

    private suspend fun procesarSolicitud(
        messages: List<Map<String, String>>,
        tools: JSONArray?,
        requestId: String?,
        esResumen: Boolean
    ) {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }

        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", false)
            if (tools != null && tools.length() > 0) {
                put("tools", tools)
            }
        }

        val request = Request.Builder()
            .url(ollamaUrl)
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val message = json.optJSONObject("message") ?: return
                
                // DETECCIÓN DE LLAMADA A HERRAMIENTA
                if (message.has("tool_calls")) {
                    val toolCalls = message.getJSONArray("tool_calls")
                    Log.i(TAG, "🤖 El modelo solicita herramientas: $toolCalls")
                    BusEventos.publicar(Evento(
                        tipo = "herramienta.solicitada",
                        origen = "llm_remoto",
                        datos = mapOf("tool_calls" to toolCalls.toString(), "request_id" to requestId)
                    ))
                } else {
                    // RESPUESTA NORMAL
                    val texto = message.optString("content", "")
                    val emocion = detectarEmocion(texto)
                    
                    if (esResumen) {
                        BusEventos.publicar(Evento("memoria.vectorizar", "llm_remoto", mapOf("texto" to texto)))
                        BusEventos.publicar(Evento("memoria.compactacion_finalizada", "llm_remoto", mapOf("request_id" to requestId)))
                    } else {
                        BusEventos.publicar(Evento("llm.respuesta", "llm_remoto", mapOf(
                            "respuesta" to texto, "emocion" to emocion, "request_id" to requestId
                        )))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
        }
    }

    private fun detectarEmocion(texto: String): String {
        val t = texto.lowercase()
        return when {
            t.contains("cariñ") || t.contains("amor") -> "carino"
            t.contains("alegr") || t.contains("feliz") -> "alegre"
            t.contains("dormir") -> "dormir"
            t.contains("pensar") || t.contains("pienso") -> "think"
            else -> "neutral"
        }
    }

    companion object {
        private const val TAG = "LLMRemoto"
    }
}
