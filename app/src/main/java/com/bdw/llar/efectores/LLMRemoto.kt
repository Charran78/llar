package com.bdw.llar.efectores

import android.content.Context
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.core.Preferencias
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
 * Efector de Ollama con soporte Multimodal (Texto + Visión) y Tools.
 * v3.5: Uso de configuración externa (Preferencias).
 */
class LLMRemoto(private val context: Context) : BusEventos.Suscriptor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        BusEventos.suscribir("llm.solicitar", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo != "llm.solicitar") return

        @Suppress("UNCHECKED_CAST")
        val messages = evento.datos["messages"] as? List<Map<String, String>> ?: return
        val tools = evento.datos["herramientas"] as? JSONArray
        val requestId = evento.datos["request_id"] as? String
        val imageBase64 = evento.datos["image_base64"] as? String
        val modelOverride = evento.datos["model"] as? String

        scope.launch {
            val modelToUse = modelOverride ?: if (imageBase64 != null) {
                Preferencias.getModelVision(context)
            } else {
                Preferencias.getModelDefault(context)
            }
            procesarSolicitud(messages, tools, requestId, imageBase64, modelToUse)
        }
    }

    private suspend fun procesarSolicitud(
        messages: List<Map<String, String>>,
        tools: JSONArray?,
        requestId: String?,
        imageBase64: String?,
        model: String
    ) {
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            val role = msg["role"]
            val content = msg["content"] ?: ""
            val toolCallId = msg["tool_call_id"]
            val toolCallsRaw = msg["tool_calls"]

            val msgObj = JSONObject().apply {
                put("role", role)
                put("content", content)
                
                if (role == "tool" && toolCallId != null) {
                    put("tool_call_id", toolCallId)
                }
                
                if (role == "assistant" && toolCallsRaw != null) {
                    put("tool_calls", JSONArray(toolCallsRaw))
                }

                if (model == Preferencias.getModelVision(context) && imageBase64 != null && role == "user") {
                    put("images", JSONArray().apply { put(imageBase64) })
                }
            }
            messagesArray.put(msgObj)
        }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messagesArray)
            put("stream", false)
            if (tools != null && tools.length() > 0 && model == Preferencias.getModelDefault(context)) {
                put("tools", tools)
            }
        }

        val ip = Preferencias.getOllamaIp(context)
        val url = "http://$ip:11434/api/chat"

        Log.d(TAG, "Enviando a Ollama ($model) en $ip...")

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                val message = json.optJSONObject("message") ?: return
                
                if (message.has("tool_calls")) {
                    val toolCalls = message.getJSONArray("tool_calls")
                    
                    BusEventos.publicar(Evento(
                        tipo = "llm.respuesta_herramienta",
                        origen = "llm_remoto",
                        datos = mapOf(
                            "message" to message.toString(),
                            "request_id" to requestId
                        )
                    ))

                    BusEventos.publicar(Evento(
                        tipo = "herramienta.solicitada",
                        origen = "llm_remoto",
                        datos = mapOf(
                            "tool_calls" to toolCalls.toString(), 
                            "request_id" to requestId
                        )
                    ))
                } else {
                    val texto = message.optString("content", "")
                    BusEventos.publicar(Evento("llm.respuesta", "llm_remoto", mapOf(
                        "respuesta" to texto,
                        "emocion" to detectarEmocion(texto),
                        "request_id" to requestId,
                        "modelo_usado" to model
                    )))
                }
            } else {
                Log.e(TAG, "Error Ollama: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error LLM: ${e.message}")
        }
    }

    private fun detectarEmocion(texto: String): String {
        val t = texto.lowercase()
        return when {
            t.contains("cariñ") || t.contains("amor") -> "carino"
            t.contains("alegr") || t.contains("feliz") -> "alegre"
            t.contains("sorpresa") || t.contains("vaya") -> "sorpresa"
            t.contains("pienso") || t.contains("pensar") -> "think"
            t.contains("enfado") || t.contains("mal") -> "angry"
            else -> "neutral"
        }
    }

    fun shutdown() {
        scope.cancel()
        BusEventos.desuscribirTodo(this)
    }

    companion object {
        private const val TAG = "LLMRemoto"
    }
}
