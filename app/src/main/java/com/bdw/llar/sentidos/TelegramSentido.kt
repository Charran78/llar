package com.bdw.llar.sentidos

import android.util.Log
import com.bdw.llar.BuildConfig
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sentido de Telegram: Escucha mensajes entrantes mediante polling (getUpdates).
 * v1.5: Asegurando import de BuildConfig y simplificando logs.
 */
class TelegramSentido {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
        
    private val tag = "TelegramSentido"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastUpdateId = 0L
    private var isPolling = false

    fun iniciarEscucha() {
        if (isPolling) return
        isPolling = true
        
        scope.launch {
            Log.i(tag, "Iniciando escucha de Telegram...")
            
            // Acceso a constantes de BuildConfig generadas desde gradle.properties
            val token = BuildConfig.TELEGRAM_TOKEN
            val myChatId = BuildConfig.TELEGRAM_CHAT_ID
            
            // Log de seguridad (oculta parte del token)
            val info = if (token.length > 5) "***" + token.substring(token.length - 4) else "VACÍO"
            Log.d(tag, "Configuración: Token=$info, ID=$myChatId")

            while (isActive && isPolling) {
                if (token.isNotEmpty() && myChatId.isNotEmpty()) {
                    pollUpdates(token, myChatId)
                }
                delay(3500)
            }
        }
    }

    private fun pollUpdates(token: String, myChatId: String) {
        val url = "https://api.telegram.org/bot$token/getUpdates?offset=${lastUpdateId + 1}&timeout=20"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val json = JSONObject(body)
                    val result = json.getJSONArray("result")

                    for (i in 0 until result.length()) {
                        val update = result.getJSONObject(i)
                        lastUpdateId = update.getLong("update_id")
                        
                        val message = update.optJSONObject("message") ?: continue
                        val fromId = message.getJSONObject("from").getLong("id").toString()
                        val text = message.optString("text", "")

                        if (fromId == myChatId && text.isNotBlank()) {
                            Log.i(tag, "✅ Telegram: '$text'")
                            BusEventos.publicar(Evento(
                                tipo = "voz.comando",
                                origen = "telegram_sentido",
                                datos = mapOf("texto" to text)
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error en Telegram: ${e.message}")
        }
    }

    fun shutdown() {
        isPolling = false
        scope.cancel()
    }
}
