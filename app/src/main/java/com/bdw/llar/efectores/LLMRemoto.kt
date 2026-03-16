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
 * Efector que se comunica con Ollama usando /api/chat con historial estructurado.
 * FIX #1: Reintentos con backoff exponencial.
 * FIX #5/#6: No inyecta personalidad extra — el modelfile ya la define.
 * FIX #8: Acepta messages[] del Cerebro para historial real.
 */
class LLMRemoto : BusEventos.Suscriptor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // IP de tu servidor Ollama — ajusta si cambia
    private val ollamaUrl = "http://192.168.1.132:11434/api/chat"
    private val modelName = "llar"

    init {
        BusEventos.suscribir("llm.solicitar", this)
        Log.i(TAG, "LLMRemoto iniciado con endpoint: $ollamaUrl")
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo != "llm.solicitar") return

        @Suppress("UNCHECKED_CAST")
        val messages = evento.datos["messages"] as? List<Map<String, String>>
        val usuario = (evento.datos["usuario"] as? String) ?: "usuario"
        val esResumen = (evento.datos["es_resumen"] as? Boolean) ?: false

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Solicitud LLM sin mensajes, ignorando.")
            return
        }

        scope.launch {
            procesarSolicitud(messages, usuario, esResumen)
        }
    }

    private fun procesarSolicitud(
        messages: List<Map<String, String>>,
        usuario: String,
        esResumen: Boolean,
        intento: Int = 1
    ) {
        Log.d(TAG, "Enviando a Ollama (intento $intento/${MAX_REINTENTOS}): ${messages.size} mensajes")

        // Construir array JSON de mensajes
        val messagesArray = JSONArray()
        messages.forEach { msg ->
            messagesArray.put(JSONObject().apply {
                put("role", msg["role"] ?: "user")
                put("content", msg["content"] ?: "")
            })
        }

        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.65)
                put("num_predict", if (esResumen) 300 else 180)
            })
        }.toString()

        val request = Request.Builder()
            .url(ollamaUrl)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val respuesta = extraerRespuesta(responseBody)
                if (respuesta.isBlank()) {
                    manejarFallo("Respuesta vacía del LLM", intento, messages, usuario, esResumen)
                    return
                }
                val emocion = detectarEmocion(respuesta)

                if (esResumen) {
                    // Guardar el resumen como recuerdo vectorial
                    BusEventos.publicar(Evento("memoria.vectorizar", "llm_remoto", mapOf(
                        "texto" to respuesta,
                        "metadata" to mapOf("tipo" to "resumen_sesion", "usuario" to usuario)
                    )))
                    BusEventos.publicar(Evento("memoria.compactacion_finalizada", "llm_remoto"))
                    Log.i(TAG, "Resumen de compactación guardado.")
                } else {
                    BusEventos.publicar(Evento(
                        tipo = "llm.respuesta",
                        origen = "llm_remoto",
                        datos = mapOf(
                            "respuesta" to respuesta,
                            "emocion"   to emocion,
                            "usuario"   to usuario
                        )
                    ))
                }
                Log.d(TAG, "Respuesta OK: ${respuesta.take(80)}...")
            } else {
                manejarFallo("HTTP ${response.code}: ${response.message}", intento, messages, usuario, esResumen)
            }
        } catch (e: IOException) {
            manejarFallo("IOException: ${e.message}", intento, messages, usuario, esResumen)
        }
    }

    private fun manejarFallo(
        motivo: String,
        intento: Int,
        messages: List<Map<String, String>>,
        usuario: String,
        esResumen: Boolean
    ) {
        Log.e(TAG, "Error en intento $intento: $motivo")
        if (intento < MAX_REINTENTOS) {
            val delayMs = (1000L * Math.pow(2.0, (intento - 1).toDouble())).toLong() // 1s, 2s, 4s
            Log.i(TAG, "Reintentando en ${delayMs}ms...")
            runBlocking { delay(delayMs) }
            procesarSolicitud(messages, usuario, esResumen, intento + 1)
        } else {
            Log.e(TAG, "Agotados todos los reintentos. Informando al usuario.")
            if (!esResumen) {
                BusEventos.publicar(Evento(
                    tipo = "llm.respuesta",
                    origen = "llm_remoto",
                    datos = mapOf(
                        "respuesta" to "No puedo conectar con mi servidor ahora mismo. Asegúrate de que el PC con Ollama está encendido.",
                        "emocion"   to "preocupado"
                    )
                ))
            }
        }
    }

    private fun extraerRespuesta(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try {
            val obj = JSONObject(json)
            // /api/chat devuelve: {"message": {"role": "assistant", "content": "..."}}
            val rawContent = obj.optJSONObject("message")?.optString("content", "") ?: ""
            
            // Limpiar Markdown: Quitar asteriscos usados para negrita o itálica, que el TTS lee en alto 
            rawContent.replace("*", "")
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando JSON: ${e.message}")
            ""
        }
    }

    private fun detectarEmocion(texto: String): String {
        val t = texto.lowercase()
        return when {
            t.contains("cariñ") || t.contains("amor") || t.contains("cielo") -> "carino"
            t.contains("alegr") || t.contains("feliz")    || t.contains("genial")   || t.contains("perfecto") -> "alegre"
            t.contains("encant") || t.contains("maravill") || t.contains("estupend")                         -> "alegre"
            t.contains("trist") || t.contains("lo siento") || t.contains("lástima")                          -> "preocupado"
            t.contains("preocup") || t.contains("ánimo")                                                      -> "preocupado"
            t.contains("sorpresa") || t.contains("increíble") || t.contains("caramba") || t.contains("vaya") -> "sorpresa"
            t.contains("enfad") || t.contains("molest") || t.contains("basta")                               -> "enfado"
            t.contains("buenas noches") || t.contains("descansar") || t.contains("dormir")                   -> "dormir"
            t.contains("pienso") || t.contains("déjame pensar") || t.contains("a ver")                       -> "pensar"
            else -> "neutral"
        }
    }

    fun shutdown() {
        scope.cancel()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "LLMRemoto"
        private const val MAX_REINTENTOS = 3
    }
}
