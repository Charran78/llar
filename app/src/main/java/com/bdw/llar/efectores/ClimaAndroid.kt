package com.bdw.llar.efectores

import android.content.Context
import android.util.Log
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/**
 * Efector de Clima: Consulta el tiempo actual usando la API gratuita de Open-Meteo.
 * No requiere API Key. TAG renombrado. Preferencias eliminadas.
 */
class ClimaAndroid(private val context: Context) : BusEventos.Suscriptor {

    private val client = OkHttpClient()
    private val tag = "ClimaAndroid"

    init {
        BusEventos.suscribir("clima.consultar", this)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "clima.consultar") {
            val ciudad = evento.datos["ciudad"] as? String ?: return
            val requestId = evento.datos["request_id"] as? String
            val toolCallId = evento.datos["tool_call_id"] as? String

            Log.i(tag, "Buscando coordenadas para: $ciudad")
            obtenerCoordenadas(ciudad, requestId, toolCallId)
        }
    }

    /**
     * Primero convertimos el nombre de la ciudad a lat/lon usando la API de geocodificación de Open-Meteo.
     */
    private fun obtenerCoordenadas(ciudad: String, requestId: String?, toolCallId: String?) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=$ciudad&count=1&language=es&format=json"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                enviarResultado("No he podido localizar la ciudad $ciudad.", requestId, toolCallId)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val result = results.getJSONObject(0)
                        val lat = result.getDouble("latitude")
                        val lon = result.getDouble("longitude")
                        val nombreReal = result.getString("name")
                        
                        Log.i(tag, "Coordenadas de $nombreReal: $lat, $lon. Consultando clima...")
                        consultarClimaReal(lat, lon, nombreReal, requestId, toolCallId)
                    } else {
                        enviarResultado("No he encontrado ninguna ciudad llamada $ciudad.", requestId, toolCallId)
                    }
                } else {
                    enviarResultado("Error al buscar la ubicación de $ciudad.", requestId, toolCallId)
                }
            }
        })
    }

    private fun consultarClimaReal(lat: Double, lon: Double, nombreCiudad: String, requestId: String?, toolCallId: String?) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2d,apparent_temperature,weather_code&timezone=auto"

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                enviarResultado("Error al conectar con el servicio de clima.", requestId, toolCallId)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    try {
                        val json = JSONObject(body)
                        val current = json.getJSONObject("current")
                        val temp = current.getDouble("temperature_2d")
                        val feelsLike = current.getDouble("apparent_temperature")
                        val code = current.getInt("weather_code")
                        
                        val descripcion = interpretarCodigoClima(code)
                        val textoInformativo = "En $nombreCiudad hace actualmente $temp grados (sensación de $feelsLike) y está $descripcion."
                        
                        enviarResultado(textoInformativo, requestId, toolCallId)
                    } catch (e: Exception) {
                        enviarResultado("Error al procesar los datos del tiempo.", requestId, toolCallId)
                    }
                }
            }
        })
    }

    private fun interpretarCodigoClima(code: Int): String {
        return when (code) {
            0 -> "despejado"
            1, 2, 3 -> "parcialmente nublado"
            45, 48 -> "con niebla"
            51, 53, 55 -> "con llovizna"
            61, 63, 65 -> "lloviendo"
            71, 73, 75 -> "nevando"
            80, 81, 82 -> "con chubascos"
            95, 96, 99 -> "con tormenta"
            else -> "con condiciones variables"
        }
    }

    private fun enviarResultado(resultado: String, requestId: String?, toolCallId: String?) {
        BusEventos.publicar(Evento(
            tipo = "herramienta.resultado",
            origen = "clima_android",
            datos = mapOf(
                "nombre_herramienta" to "get_weather_forecast",
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
