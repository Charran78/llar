package com.bdw.llar.core

import com.bdw.llar.modelo.Evento
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Bus de Eventos centralizado (Patrón Pub/Sub).
 * Permite que los módulos se comuniquen sin conocerse entre sí.
 * Thread-safe: usa colecciones concurrentes.
 */
object BusEventos {
    private const val TAG = "BusEventos"

    interface Suscriptor {
        fun alRecibirEvento(evento: Evento)
    }

    private val suscriptoresGenerales = CopyOnWriteArrayList<Suscriptor>()
    private val suscriptoresPorTipo = ConcurrentHashMap<String, CopyOnWriteArrayList<Suscriptor>>()

    /**
     * Suscribe un módulo a TODOS los eventos del sistema.
     */
    fun suscribirTodo(suscriptor: Suscriptor) {
        if (!suscriptoresGenerales.contains(suscriptor)) {
            suscriptoresGenerales.add(suscriptor)
            Log.d(TAG, "Suscriptor general añadido: ${suscriptor::class.simpleName}")
        }
    }

    /**
     * Suscribe un módulo a un tipo específico de evento.
     */
    fun suscribir(tipo: String, suscriptor: Suscriptor) {
        val lista = suscriptoresPorTipo.getOrPut(tipo) { CopyOnWriteArrayList() }
        if (!lista.contains(suscriptor)) {
            lista.add(suscriptor)
            Log.d(TAG, "Suscriptor añadido para tipo '$tipo': ${suscriptor::class.simpleName}")
        }
    }

    /**
     * Elimina la suscripción de un módulo a un tipo específico.
     */
    fun desuscribir(tipo: String, suscriptor: Suscriptor) {
        suscriptoresPorTipo[tipo]?.remove(suscriptor)?.let {
            Log.d(TAG, "Suscriptor removido para tipo '$tipo': ${suscriptor::class.simpleName}")
        }
    }

    /**
     * Elimina todas las suscripciones de un módulo (tanto generales como por tipo).
     * Útil en el shutdown del módulo para evitar fugas.
     */
    fun desuscribirCompleto(suscriptor: Suscriptor) {
        suscriptoresGenerales.remove(suscriptor)
        suscriptoresPorTipo.values.forEach { it.remove(suscriptor) }
        Log.d(TAG, "Suscriptor completamente removido: ${suscriptor::class.simpleName}")
    }

    /**
     * Envía un evento a todos los interesados.
     * Las excepciones en los suscriptores se capturan para no interrumpir la notificación.
     */
    fun publicar(evento: Evento) {
        Log.d(TAG, "Evento publicado: ${evento.tipo} desde ${evento.origen}")

        // Notificar a los suscritos a este tipo concreto
        suscriptoresPorTipo[evento.tipo]?.forEach { suscriptor ->
            try {
                suscriptor.alRecibirEvento(evento)
            } catch (e: Exception) {
                Log.e(TAG, "Error en suscriptor ${suscriptor::class.simpleName} al procesar evento ${evento.tipo}", e)
            }
        }

        // Notificar a los suscritos a todo (ej: Cerebro)
        suscriptoresGenerales.forEach { suscriptor ->
            try {
                suscriptor.alRecibirEvento(evento)
            } catch (e: Exception) {
                Log.e(TAG, "Error en suscriptor general ${suscriptor::class.simpleName} al procesar evento ${evento.tipo}", e)
            }
        }
    }

    fun desuscribirTodo(suscriptor: Suscriptor) {
        suscriptoresGenerales.remove(suscriptor)
    }
}