package com.bdw.llar.core

import com.bdw.llar.modelo.Evento
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * El Cerebro es el orquestador de Llar.
 * v2: Historial real pasado al LLM, prompt limpio (sin fecha/nombre en el texto),
 * metadatos guardados como JSON separado, compactación real por LLM.
 */
class Cerebro : BusEventos.Suscriptor {
    private var modoApuntarCompra = false
    private var modoDescanso = false
    private var nombreUsuario: String? = null
    private var usuarioActivo: String = "usuario"
    private var contadorInteracciones = 0
    private val MAX_INTERACCIONES_ANTES_COMPACTAR = 15
    // FASE 4: var para poder resetearse sin reiniciar la app
    var sesionId: String = UUID.randomUUID().toString()

    // Buffer para historial pendiente de enviar al LLM
    private var pendienteContexto: Boolean = false
    private var pendienteHistorial: Boolean = false
    private var textoParaLLM: String = ""
    private var recuerdosRecuperados: String = ""

    init {
        BusEventos.suscribirTodo(this)
        Log.i(TAG, "Cerebro iniciado (Sesión: $sesionId)")

        // Recuperar nombre de usuario de la BD
        BusEventos.publicar(Evento("memoria.recuperar", "cerebro", mapOf("clave" to "nombre_usuario")))
        BusEventos.publicar(Evento("memoria.recuperar", "cerebro", mapOf("clave" to "usuario_activo")))

        // Saludo inicial con un pequeño delay
        Timer().schedule(object : TimerTask() {
            override fun run() { saludoProactivoInicial() }
        }, 2000)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.origen == "cerebro") return

        when (evento.tipo) {
            "wakeword.detectada"          -> procesarWakeWord()
            "voz.comando"                 -> procesarComandoVoz(evento)
            "ubicacion.cambio"            -> procesarCambioUbicacion(evento)
            "memoria.respuesta"           -> procesarRespuestaMemoria(evento)
            "lista.compra_actualizada"    -> procesarListaActualizada(evento)
            "llm.respuesta"               -> procesarRespuestaLLM(evento)
            "memoria.compactar"           -> solicitarCompactacion()
            "memoria.recuerdos_recuperados" -> procesarContextoRecuperado(evento)
            "memoria.historial_recuperado"-> procesarHistorialRecuperado(evento)
            "calendario.eventos_leidos"   -> procesarEventosCalendario(evento) // FASE 2: Recibe lectura calendario
            "memoria.compactacion_finalizada" -> {
                Log.i(TAG, "Compactación finalizada.")
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "neutral")))
            }
            "bluetooth.conectado"         -> procesarBluetoothConectado(evento)
            "usuario.cambiado"            -> procesarCambioUsuario(evento) // FASE 4: Aislar contexto
        }
    }

    private fun saludoProactivoInicial() {
        val hora = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val saludoBase = when (hora) {
            in 6..12  -> "¡Buenos días!"
            in 13..20 -> "¡Buenas tardes!"
            else      -> "¡Buenas noches!"
        }
        val nombre = nombreUsuario ?: ""
        val mensaje = if (nombre.isNotBlank()) "$saludoBase $nombre." else saludoBase

        responder(mensaje)
        
        // FASE 2: Proactividad — Pedir lista de la compra y calendario de hoy
        BusEventos.publicar(Evento("lista.consultar", "cerebro"))
        BusEventos.publicar(Evento("calendario.leer_eventos", "cerebro", mapOf("dias" to 1)))
    }

    private fun procesarWakeWord() {
        if (modoDescanso) {
            modoDescanso = false
            responder("¡Ya estoy despierta! ¿En qué te ayudo?")
        } else {
            val frases = listOf("¿Sí?", "Dime.", "Te escucho.", "Estoy aquí.")
            responder(frases.random())
        }
        BusEventos.publicar(Evento("oido.activar_modo_escucha", "cerebro", mapOf("duracion" to 7)))
    }

    private fun procesarComandoVoz(evento: Evento) {
        val texto = (evento.datos["texto"] as? String) ?: return
        val cmd = texto.lowercase()

        if (modoDescanso && !cmd.contains("despierta")) return

        // Guardar mensaje con metadatos estructurados (no en el texto)
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        BusEventos.publicar(Evento("memoria.guardar_mensaje", "cerebro", mapOf(
            "usuario"    to usuarioActivo,
            "mensaje"    to texto,
            "sesion_id"  to sesionId,
            "fecha"      to fechaIso
        )))

        if (modoApuntarCompra) {
            if (cmd.contains("fin lista") || cmd.contains("terminar")) {
                modoApuntarCompra = false
                responder("Hecho, lo he apuntado.")
            } else {
                BusEventos.publicar(Evento("lista.añadir", "cerebro", mapOf("item" to texto)))
                responder("Apuntado.")
            }
            return
        }

        when {
            cmd.contains("descansa") || cmd.contains("vete a dormir") -> {
                modoDescanso = true
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "dormir")))
                responder("De acuerdo, me pongo a descansar. Dime 'despierta' cuando me necesites.")
            }
            cmd.contains("despierta") -> {
                modoDescanso = false
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "neutral")))
                responder("Ya estoy despierta, ¿qué necesitas?")
                BusEventos.publicar(Evento("lista.consultar", "cerebro"))
            }
            cmd.contains("añade a la lista") || cmd.contains("apunta") -> {
                modoApuntarCompra = true
                responder("Claro, ¿qué quieres que apunte?")
            }
            cmd.contains("qué tengo en la lista") || cmd.contains("ver la lista") -> {
                BusEventos.publicar(Evento("lista.consultar", "cerebro"))
            }
            cmd.contains("vacía la lista") || cmd.contains("borra la lista") -> {
                BusEventos.publicar(Evento("lista.limpiar", "cerebro"))
            }
            cmd.contains("qué tengo hoy") || (cmd.contains("qué") && cmd.contains("calendario")) -> {
                BusEventos.publicar(Evento("calendario.leer_eventos", "cerebro", mapOf("dias" to 1)))
            }
            else -> pedirHistorialYEnviarAlLLM(texto)
        }
    }

    /**
     * FIX #8 y FASE 2: Primero pide buscar el contexto semántico con el texto actual.
     */
    private fun pedirHistorialYEnviarAlLLM(texto: String) {
        textoParaLLM = texto
        pendienteContexto = true
        recuerdosRecuperados = ""
        
        Log.d(TAG, "Buscando contexto semántico para: ${texto.take(20)}")
        BusEventos.publicar(Evento("memoria.buscar_contexto", "cerebro", mapOf(
            "texto" to texto,
            "sesion_id" to sesionId
        )))
    }

    private fun procesarContextoRecuperado(evento: Evento) {
        if (!pendienteContexto) return
        pendienteContexto = false

        @Suppress("UNCHECKED_CAST")
        val recuerdos = evento.datos["recuerdos"] as? List<String> ?: emptyList()
        if (recuerdos.isNotEmpty()) {
            recuerdosRecuperados = recuerdos.joinToString(" | ")
            Log.d(TAG, "Contexto semántico encontrado: ${recuerdosRecuperados.take(40)}")
        }

        // Una vez tenemos el contexto semántico (si lo hay), pedimos el historial reciente
        pendienteHistorial = true
        BusEventos.publicar(Evento("memoria.obtener_historial", "cerebro", mapOf(
            "limite"    to 8,
            "sesion_id" to sesionId
        )))
    }

    private fun procesarHistorialRecuperado(evento: Evento) {
        if (!pendienteHistorial) return
        pendienteHistorial = false

        @Suppress("UNCHECKED_CAST")
        val historial = evento.datos["mensajes"] as? List<Map<String, String>> ?: emptyList()

        // Construir lista de mensajes para /api/chat
        val mensajes = mutableListOf<Map<String, String>>()
        
        // FASE 5: Forzar una personalidad útil, estructurada y sin adornos Markdown por si el Modelfile local falla
        mensajes.add(mapOf(
            "role" to "system",
            "content" to "Eres Llar, mi asistente virtual de inteligencia artificial. Eres hiper-eficiente, muy inteligente y concisa. Respondes con naturalidad pero NUNCA usas formato markdown ni asteriscos para acentuar (la interfaz te lee en voz alta y suena mal). No divagas ni dices cosas excesivamente amorosas a no ser que el contexto lo pida explícitamente. Tu único objetivo es responder a mi demanda de la manera más resolutiva posible."
        ))
        
        // FASE 2: Si hay recuerdos relevantes, inyectarlos al principio como mensaje de sistema
        if (recuerdosRecuperados.isNotBlank()) {
            mensajes.add(mapOf(
                "role" to "system", 
                "content" to "RECUERDO: He encontrado información relevante del pasado sobre lo que me están hablando ahora. Tenla en cuenta para responder de forma natural. Información: $recuerdosRecuperados"
            ))
        }

        // Historial reciente
        mensajes.addAll(historial.map { msg ->
            val role = if (msg["usuario"] == "Llar") "assistant" else "user"
            mapOf("role" to role, "content" to (msg["mensaje"] ?: ""))
        })

        // Mensaje actual del usuario
        mensajes.add(mapOf("role" to "user", "content" to textoParaLLM))

        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages"  to mensajes,
            "usuario"   to usuarioActivo
        )))
    }

    private fun procesarRespuestaLLM(evento: Evento) {
        val respuesta = (evento.datos["respuesta"] as? String) ?: return
        val emocion   = (evento.datos["emocion"]   as? String) ?: "neutral"

        // Guardar respuesta de Llar en historial con metadatos
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        BusEventos.publicar(Evento("memoria.guardar_mensaje", "cerebro", mapOf(
            "usuario"    to "Llar",
            "mensaje"    to respuesta,
            "sesion_id"  to sesionId,
            "fecha"      to fechaIso
        )))

        responder(respuesta)
        // La emoción ya se encarga de reaccionar Llar leyendo llm.respuesta directamente

        contadorInteracciones++
        if (contadorInteracciones >= MAX_INTERACCIONES_ANTES_COMPACTAR) {
            contadorInteracciones = 0
            solicitarCompactacion()
        }
    }

    /**
     * FIX #7: Compactación real — manda el historial al LLM para que haga un resumen.
     * El resultado llega como llm.respuesta normal y se guarda como recuerdo.
     */
    private fun solicitarCompactacion() {
        Log.i(TAG, "Iniciando compactación real del historial...")

        val mensajesCompactacion = listOf(
            mapOf("role" to "user",
                  "content" to "Resume en 3-5 frases concisas los temas y hechos más importantes de nuestra conversación hasta ahora. Solo el resumen, sin introducción.")
        )
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages"    to mensajesCompactacion,
            "usuario"     to "sistema",
            "es_resumen"  to true
        )))
    }

    private fun responder(mensaje: String) {
        BusEventos.publicar(Evento("voz.hablar", "cerebro", mapOf("texto" to mensaje)))
    }

    private fun procesarRespuestaMemoria(evento: Evento) {
        when (evento.datos["clave"]) {
            "nombre_usuario" -> {
                val valor = evento.datos["valor"] as? String
                if (!valor.isNullOrBlank()) nombreUsuario = valor
            }
            "usuario_activo" -> {
                val valor = evento.datos["valor"] as? String
                if (!valor.isNullOrBlank()) usuarioActivo = valor
            }
        }
    }

    private fun procesarListaActualizada(evento: Evento) {
        val items = (evento.datos["items"] as? List<*>)
        val origen = evento.origen

        if (origen == "cerebro") {
            if (!items.isNullOrEmpty()) {
                val frase = if (items.size > 3)
                    "Tienes ${items.size} cosas pendientes en la lista, ¿quieres que las revisemos?"
                else
                    "Recuerda que tienes ${items.size} ${if (items.size == 1) "cosa pendiente" else "cosas pendientes"} en la lista."
                responder(frase)
            }
        } else {
            if (items.isNullOrEmpty()) {
                responder("La lista está vacía.")
            } else {
                responder("En la lista tienes: ${items.joinToString(", ")}.")
            }
        }
    }

    private fun procesarCambioUbicacion(evento: Evento) {
        if (evento.datos["lugar"] == "supermercado") {
            responder("Estás cerca del supermercado. ¿Quieres que te lea la lista?")
        }
    }

    private fun procesarEventosCalendario(evento: Evento) {
        @Suppress("UNCHECKED_CAST")
        val eventos = evento.datos["eventos"] as? List<String> ?: emptyList()
        val origen = evento.origen

        if (eventos.isNotEmpty()) {
            val pluralStr = if (eventos.size == 1) "tienes una cosa programada" else "tienes ${eventos.size} cosas programadas"
            responder("En el calendario hoy $pluralStr: ${eventos.joinToString(", ")}.")
        } else if (origen == "cerebro") { // Si fue proactivo o pedido manual
            Log.d(TAG, "Calendario revisado, sin eventos.")
            // No decimos que no hay eventos si fue proactivo para no molestar,
            // pero si la lista llega vacía, aquí podríamos gestionar el fallback.
        }
    }

    private fun procesarBluetoothConectado(evento: Evento) {
        val dispositivo = (evento.datos["dispositivo"] as? String) ?: return
        val lowerDesc = dispositivo.lowercase()

        // Reglas heurísticas simples de proactividad
        if (lowerDesc.contains("coche") || lowerDesc.contains("car") || lowerDesc.contains("auto")) {
            responder("Veo que has entrado al coche. ¿Quieres que te lea la lista de la compra o los eventos de hoy?")
            BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "alegre")))
        } else if (lowerDesc.contains("buds") || lowerDesc.contains("auricular") || lowerDesc.contains("headset")) {
            responder("Auriculares conectados.")
        } else {
            // Opcional: ignorar otros dispositivos para no ser pesado
            Log.d(TAG, "Dispositivo BT general conectado: $dispositivo")
        }
    }

    private fun procesarCambioUsuario(evento: Evento) {
        val nuevoUser = evento.datos["nuevo_usuario"] as? String ?: return
        usuarioActivo = nuevoUser
        nombreUsuario = nuevoUser
        
        // FASE 4: Aislar contexto al cambiar de perfil. Nueva sesión anula el historial en memoria a corto plazo del LLM.
        sesionId = UUID.randomUUID().toString()
        pendienteContexto = false
        pendienteHistorial = false
        textoParaLLM = ""
        recuerdosRecuperados = ""
        
        Log.i(TAG, "Usuario cambiado a $nuevoUser. Nueva sesión iniciada: $sesionId")
    }

    companion object {
        private const val TAG = "Cerebro"
    }
}
