package com.bdw.llar.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestor de configuración de Llar.
 * v1.2: Claves de Telegram movidas a BuildConfig.
 */
object Preferencias {
    private const val PREFS_NAME = "llar_prefs"
    
    // Claves
    private const val KEY_OLLAMA_IP = "ollama_ip"
    private const val KEY_MODEL_DEFAULT = "model_default"
    private const val KEY_MODEL_VISION = "model_vision"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOllamaIp(context: Context): String {
        return getPrefs(context).getString(KEY_OLLAMA_IP, "192.168.1.132") ?: "192.168.1.132"
    }

    fun setOllamaIp(context: Context, ip: String) {
        getPrefs(context).edit().putString(KEY_OLLAMA_IP, ip).apply()
    }

    fun getModelDefault(context: Context): String {
        return getPrefs(context).getString(KEY_MODEL_DEFAULT, "llar") ?: "llar"
    }

    fun getModelVision(context: Context): String {
        return getPrefs(context).getString(KEY_MODEL_VISION, "moondream") ?: "moondream"
    }
}
