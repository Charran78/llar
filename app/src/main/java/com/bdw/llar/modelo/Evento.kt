package com.bdw.llar.modelo

/**
 * Representa un mensaje que viaja por el Bus de Eventos.
 * @param tipo El identificador del evento (ej: "voz.hablar", "wakeword.detectada")
 * @param origen Qué módulo generó el evento (ej: "cerebro", "oido")
 * @param datos Mapa de datos asociados al evento (opcional)
 */
data class Evento(
    val tipo: String,
    val origen: String,
    val datos: Map<String, Any> = emptyMap()
)
