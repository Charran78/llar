package com.bdw.llar.core

import android.util.Log
import com.bdw.llar.modelo.Evento
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Gestiona la generación de embeddings y la búsqueda semántica.
 * Se comunica con Ollama para convertir texto en vectores.
 */
class MemoriaSemantica : BusEventos.Suscriptor {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Dirección de tu servidor Ollama
    private val ollamaEmbedUrl = "http://192.168.1.132:11434/api/embeddings"
    // Usamos all-minilm por ser extremadamente rápido (45MB)
    private val modelName = "all-minilm:latest" 

    init {
        BusEventos.suscribir("memoria.vectorizar", this)
        BusEventos.suscribir("memoria.buscar_contexto", this) // FASE 2: Entrada de búsqueda
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "memoria.vectorizar" -> {
                val texto = evento.datos["texto"] as? String ?: return
                val metadata = evento.datos["metadata"] as? Map<*, *> ?: emptyMap<String, Any>()
                scope.launch { generarYGuardar(texto, metadata) }
            }
            "memoria.buscar_contexto" -> {
                val texto = evento.datos["texto"] as? String ?: return
                val sesionId = evento.datos["sesion_id"] as? String ?: "default"
                scope.launch { generarVectorParaBusqueda(texto, sesionId) }
            }
        }
    }

    private fun generarYGuardar(texto: String, metadata: Map<*, *>) {
        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("prompt", texto)
        }.toString()

        val request = Request.Builder()
            .url(ollamaEmbedUrl)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val jsonResponse = JSONObject(responseBody ?: "{}")
                val embedding = jsonResponse.optJSONArray("embedding")
                
                if (embedding != null) {
                    BusEventos.publicar(Evento(
                        tipo = "memoria.guardar_recuerdo_vectorial",
                        origen = "memoria_semantica",
                        datos = mapOf(
                            "contenido" to texto,
                            "vector" to embedding.toString(),
                            "metadata" to JSONObject(metadata).toString()
                        )
                    ))
                    Log.d(TAG, "Embedding generado y guardado ($modelName)")
                }
            } else {
                Log.e(TAG, "Error Ollama Embeddings: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error MemoriaSemantica (guardar): ${e.message}")
        }
    }

    private fun generarVectorParaBusqueda(texto: String, sesionId: String) {
        val requestBody = JSONObject().apply {
            put("model", modelName)
            put("prompt", texto)
        }.toString()

        val request = Request.Builder()
            .url(ollamaEmbedUrl)
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonResponse = JSONObject(response.body?.string() ?: "{}")
                val embedding = jsonResponse.optJSONArray("embedding")
                
                if (embedding != null) {
                    // Pedir a la Memoria (SQLite) que busque con este vector
                    BusEventos.publicar(Evento(
                        tipo = "memoria.buscar_recuerdos",
                        origen = "memoria_semantica",
                        datos = mapOf(
                            "vector" to embedding.toString(),
                            "limite" to 3,
                            "sesion_id" to sesionId
                        )
                    ))
                }
            } else {
                Log.e(TAG, "Error buscando contexto semántico: ${response.code}")
                // Fallback: Si falla la semántica, continuar de todos modos sin contexto
                BusEventos.publicar(Evento("memoria.recuerdos_recuperados", "memoria_semantica", mapOf("recuerdos" to emptyList<String>(), "sesion_id" to sesionId)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error MemoriaSemantica (busqueda): ${e.message}")
            BusEventos.publicar(Evento("memoria.recuerdos_recuperados", "memoria_semantica", mapOf("recuerdos" to emptyList<String>(), "sesion_id" to sesionId)))
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "MemoriaSemantica"
    }
}
