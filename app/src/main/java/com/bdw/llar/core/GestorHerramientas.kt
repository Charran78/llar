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
     * Nombres de las herramientas para evitar errores tipográficos.
     */
    private object Nombres {
        const val GET_BATTERY = "get_battery_level"
        const val TOGGLE_FLASHLIGHT = "toggle_flashlight"
        const val ANALIZAR_ENTORNO = "analizar_entorno"
        const val GET_WEATHER = "get_weather_forecast"
        const val GET_NEWS = "get_latest_news"
    }

    /**
     * Devuelve el catálogo de herramientas en formato JSON para Ollama.
     */
    fun obtenerCatalogo(): JSONArray {
        val tools = JSONArray()

        // Batería
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", Nombres.GET_BATTERY)
                put("description", "Obtiene el porcentaje actual de batería del dispositivo.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })

        // Linterna
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", Nombres.TOGGLE_FLASHLIGHT)
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

        // VISIÓN: Analizar entorno
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", Nombres.ANALIZAR_ENTORNO)
                put("description", "Usa la cámara frontal para describir visualmente qué hay frente al dispositivo.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                })
            })
        })

        // CLIMA: Consultar tiempo
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", Nombres.GET_WEATHER)
                put("description", "Obtiene el pronóstico del tiempo para una ciudad específica.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("ciudad", JSONObject().apply {
                            put("type", "string")
                            put("description", "Nombre de la ciudad (ej: Madrid, Oviedo).")
                        })
                    })
                    put("required", JSONArray().apply { put("ciudad") })
                })
            })
        })

        // NOTICIAS: Consultar últimas noticias
        tools.put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", Nombres.GET_NEWS)
                put("description", "Obtiene los titulares de las últimas noticias.")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("categoria", JSONObject().apply {
                            put("type", "string")
                            put("description", "Categoría opcional (ej: tecnología, deportes, general).")
                        })
                    })
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
                Log.d(TAG, "Recibidas ${toolCalls.length()} tool calls para request $requestId")

                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.getJSONObject(i)
                    val callId = call.optString("id", "call_${System.currentTimeMillis()}")
                    val function = call.getJSONObject("function")
                    val name = function.getString("name")
                    val argsStr = function.optString("arguments", "{}")
                    val args = JSONObject(argsStr)

                    when (name) {
                        Nombres.GET_BATTERY -> {
                            BusEventos.publicar(Evento("dispositivo.consultar_bateria", "gestor_herramientas", mapOf(
                                "request_id" to requestId,
                                "tool_call_id" to callId
                            )))
                        }
                        Nombres.TOGGLE_FLASHLIGHT -> {
                            val enabled = args.optBoolean("enabled", false)
                            BusEventos.publicar(Evento("dispositivo.linterna", "gestor_herramientas", mapOf(
                                "enabled" to enabled, 
                                "request_id" to requestId,
                                "tool_call_id" to callId
                            )))
                        }
                        Nombres.ANALIZAR_ENTORNO -> {
                            BusEventos.publicar(Evento("vision.capturar_foto", "gestor_herramientas", mapOf(
                                "request_id" to requestId,
                                "tool_call_id" to callId
                            )))
                        }
                        Nombres.GET_WEATHER -> {
                            val ciudad = args.optString("ciudad", "Madrid")
                            BusEventos.publicar(Evento("clima.consultar", "gestor_herramientas", mapOf(
                                "ciudad" to ciudad,
                                "request_id" to requestId,
                                "tool_call_id" to callId
                            )))
                        }
                        Nombres.GET_NEWS -> {
                            val categoria = args.optString("categoria", "general")
                            BusEventos.publicar(Evento("noticias.consultar", "gestor_herramientas", mapOf(
                                "categoria" to categoria,
                                "request_id" to requestId,
                                "tool_call_id" to callId
                            )))
                        }
                        else -> {
                            Log.w(TAG, "Herramienta desconocida solicitada por el LLM: $name")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando tool calls", e)
            }
        }
    }
}
