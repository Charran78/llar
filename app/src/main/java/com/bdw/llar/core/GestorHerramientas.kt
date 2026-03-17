package com.bdw.llar.core

import android.util.Log
import com.bdw.llar.modelo.Evento
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestor de Herramientas (Tooling) para Llar.
 * Define el catálogo de capacidades y traduce las llamadas del LLM a eventos de Android.
 */
object GestorHerramientas : BusEventos.Suscriptor {

    private const val TAG = "GestorHerramientas"

    init {
        BusEventos.suscribir("herramienta.solicitada", this)
    }

    /**
     * Devuelve el catálogo de herramientas en formato JSON para Ollama.
     */
    fun obtenerCatalogo(): JSONArray {
        val tools = JSONArray()

        // Herramienta: Consultar Batería
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "get_battery_level")
                put("description", "Obtiene el porcentaje actual de batería del dispositivo móvil.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })

        // Herramienta: Linterna
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "toggle_flashlight")
                put("description", "Enciende o apaga la linterna del teléfono.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "True para encender, false para apagar.")
                        })
                    })
                    put("required", JSONArray().apply { put("enabled") })
                })
            })
        })

        return tools
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "herramienta.solicitada") {
            val toolCallsStr = evento.datos["tool_calls"] as? String ?: return
            val requestId = evento.datos["request_id"] as? String
            
            try {
                val toolCalls = JSONArray(toolCallsStr)
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val function = call.getJSONObject("function")
                    val name = function.getString("name")
                    val argsStr = function.optString("arguments", "{}")
                    val args = JSONObject(argsStr)

                    Log.i(TAG, "🔧 Procesando llamada a herramienta: $name con args: $argsStr")

                    when (name) {
                        "get_battery_level" -> {
                            BusEventos.publicar(Evento("dispositivo.consultar_bateria", "gestor_herramientas", mapOf("request_id" to requestId)))
                        }
                        "toggle_flashlight" -> {
                            val enabled = args.optBoolean("enabled", false)
                            BusEventos.publicar(Evento("dispositivo.linterna", "gestor_herramientas", mapOf(
                                "enabled" to enabled,
                                "request_id" to requestId
                            )))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parseando tool_calls: ${e.message}")
            }
        }
    }
}
