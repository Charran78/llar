package com.bdw.llar.core

import com.bdw.llar.modelo.Evento
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * El Cerebro es el orquestador de Llar.
 * v3.8: Logs de depuración mejorados y prioridad de respuesta por canal de origen.
 */
class Cerebro : BusEventos.Suscriptor {
    private var modoApuntarCompra = false
    private var modoDescanso = false
    private var nombreUsuario: String? = null
    private var usuarioActivo: String = "usuario"
    private var contadorInteracciones = 0
    private val MAX_INTERACCIONES_ANTES_COMPACTAR = 8
    var sesionId: String = UUID.randomUUID().toString()

    private val ocupado = AtomicBoolean(false)
    private var esResumenEnCurso = false
    private var ultimoRequestId: String? = null
    private var origenUltimaInteraccion: String = "voz"
    
    private var pendienteContexto: Boolean = false
    private var pendienteHistorial: Boolean = false
    private var textoParaLLM: String = ""
    private var recuerdosRecuperados: String = ""
    
    private var mensajesEnCurso: MutableList<Map<String, String>> = mutableListOf()

    init {
        BusEventos.suscribirTodo(this)
        Log.i(TAG, "Cerebro iniciado.")

        BusEventos.publicar(Evento("memoria.recuperar", "cerebro", mapOf("clave" to "nombre_usuario")))
        BusEventos.publicar(Evento("memoria.recuperar", "cerebro", mapOf("clave" to "usuario_activo")))

        Timer().schedule(object : TimerTask() {
            override fun run() { saludoProactivoInicial() }
        }, 2500)
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.origen == "cerebro") return

        when (evento.tipo) {
            "wakeword.detectada"          -> procesarWakeWord()
            "voz.comando"                 -> {
                Log.d(TAG, "Comando de voz/texto recibido desde ${evento.origen}: '${evento.datos["texto"]}'")
                origenUltimaInteraccion = evento.origen // Guardar el origen
                procesarComandoVoz(evento)
            }
            "llm.respuesta"               -> procesarRespuestaLLM(evento)
            "llm.respuesta_herramienta"   -> procesarRespuestaHerramientaLLM(evento)
            "memoria.recuerdos_recuperados" -> procesarContextoRecuperado(evento)
            "memoria.historial_recuperado"-> procesarHistorialRecuperado(evento)
            "herramienta.resultado"       -> procesarResultadoHerramienta(evento)
            "usuario.cambiado"            -> procesarCambioUsuario(evento)
            "memoria.respuesta"           -> procesarRespuestaMemoria(evento)
            "lista.compra_actualizada"    -> procesarListaActualizada(evento)
            "memoria.compactar"           -> solicitarCompactacion()
            "vision.capturar_foto"        -> logicManualCameraTrigger(evento)
        }
    }

    private fun logicManualCameraTrigger(evento: Evento) {
        if (evento.origen == "ui") {
            Log.i(TAG, "📸 Gatillo manual de cámara detectado desde la UI.")
            pedirHistorialYEnviarAlLLM("Analiza lo que ves frente a ti.", "ui")
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
        responder(if (nombre.isNotBlank()) "$saludoBase $nombre." else saludoBase, "cerebro")
    }

    private fun procesarWakeWord() {
        if (modoDescanso) return
        responder(listOf("¿Sí?", "Dime.", "Te escucho.").random(), "cerebro")
        BusEventos.publicar(Evento("oido.activar_modo_escucha", "cerebro", mapOf("duracion" to 7)))
    }

    private fun procesarComandoVoz(evento: Evento) {
        val texto = (evento.datos["texto"] as? String) ?: return
        val cmd = texto.lowercase()

        if (modoDescanso) {
            if (cmd.contains("despierta") || cmd.contains("levántate")) {
                modoDescanso = false
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "neutral")))
                responder("¡Hola! Ya estoy aquí.", "cerebro")
            }
            return
        }

        if (modoApuntarCompra) {
            if (cmd.contains("fin lista") || cmd.contains("terminar")) {
                modoApuntarCompra = false
                responder("Hecho, lo he apuntado.", "cerebro")
            } else {
                BusEventos.publicar(Evento("lista.añadir", "cerebro", mapOf("item" to texto)))
                responder("Apuntado.", "cerebro")
            }
            return
        }

        when {
            cmd.contains("descansa") || cmd.contains("vete a dormir") || cmd.contains("a dormir") -> {
                modoDescanso = true
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "dormir")))
                responder("De acuerdo, voy a descansar.", "cerebro")
            }
            else -> pedirHistorialYEnviarAlLLM(texto, evento.origen)
        }
    }

    private fun pedirHistorialYEnviarAlLLM(texto: String, origen: String) {
        if (!ocupado.compareAndSet(false, true)) {
            Log.w(TAG, "Intento de interacción ignorado: Cerebro ocupado.")
            // Si está ocupado, podemos responder por el canal original que Llar está ocupada.
            if (origen == "telegram_sentido") {
                BusEventos.publicar(Evento("telegram.enviar_mensaje", "cerebro", mapOf("mensaje" to "Estoy ocupada ahora mismo, Pedro. Dame un momento.")))
            }
            return
        }
        
        Log.i(TAG, "🧠 PENSANDO... [Inicio flujo normal, origen: $origen]")
        BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "think")))

        textoParaLLM = texto
        esResumenEnCurso = false
        pendienteContexto = true
        recuerdosRecuperados = ""
        ultimoRequestId = UUID.randomUUID().toString()
        origenUltimaInteraccion = origen // Guardar el origen para la respuesta final

        BusEventos.publicar(Evento("memoria.buscar_contexto", "cerebro", mapOf(
            "texto" to texto, "sesion_id" to sesionId, "request_id" to ultimoRequestId
        )))
    }

    private fun procesarContextoRecuperado(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return
        pendienteContexto = false

        @Suppress("UNCHECKED_CAST")
        val recuerdos = evento.datos["recuerdos"] as? List<String> ?: emptyList()
        recuerdosRecuperados = recuerdos.joinToString(" | ")

        pendienteHistorial = true
        BusEventos.publicar(Evento("memoria.obtener_historial", "cerebro", mapOf(
            "limite" to 8, "sesion_id" to sesionId, "request_id" to requestId
        )))
    }

    private fun procesarHistorialRecuperado(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return
        pendienteHistorial = false

        val mensajes = mutableListOf<Map<String, String>>()
        val fecha = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es", "ES")).format(Date())
        val contextoInfo = "[SISTEMA: Hoy es $fecha. ${if(recuerdosRecuperados.isNotBlank()) "| Recuerdos: $recuerdosRecuperados" else ""}]"

        mensajes.add(mapOf("role" to "user", "content" to contextoInfo))

        @Suppress("UNCHECKED_CAST")
        val historial = evento.datos["mensajes"] as? List<Map<String, String>> ?: emptyList()
        mensajes.addAll(historial.map { msg ->
            mapOf("role" to if (msg["usuario"] == "Llar") "assistant" else "user", "content" to (msg["mensaje"] ?: ""))
        })

        mensajes.add(mapOf("role" to "user", "content" to textoParaLLM))
        
        mensajesEnCurso = mensajes.toMutableList()
        lanzarSolicitudLLM(requestId)
    }

    private fun lanzarSolicitudLLM(requestId: String?) {
        val herramientas = GestorHerramientas.obtenerCatalogo()
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages" to mensajesEnCurso,
            "herramientas" to herramientas,
            "usuario" to usuarioActivo,
            "request_id" to requestId
        )))
    }

    /**
     * Guarda el mensaje del asistente que contiene las tool_calls en el historial en curso.
     */
    private fun procesarRespuestaHerramientaLLM(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return

        val messageStr = evento.datos["message"] as? String ?: return
        val messageJson = JSONObject(messageStr)
        
        val role = messageJson.optString("role", "assistant")
        val toolCalls = messageJson.optJSONArray("tool_calls")?.toString()

        mensajesEnCurso.add(mapOf(
            "role" to role,
            "content" to messageJson.optString("content", ""),
            "tool_calls" to (toolCalls ?: "[]")
        ))
    }

    private fun procesarResultadoHerramienta(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return

        val nombre = evento.datos["nombre_herramienta"] as? String
        val resultado = evento.datos["resultado"] as? String ?: ""
        val callId = evento.datos["tool_call_id"] as? String
        val imageBase64 = evento.datos["image_base64"] as? String

        Log.i(TAG, "🧠 Resultado Tool '$nombre': $resultado. Reenviando a LLM.")

        val toolMap = mutableMapOf("role" to "tool", "content" to resultado)
        if (callId != null) toolMap["tool_call_id"] = callId
        
        mensajesEnCurso.add(toolMap)

        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages" to mensajesEnCurso,
            "usuario" to usuarioActivo,
            "request_id" to requestId,
            "image_base64" to imageBase64
        )))
    }

    private fun procesarRespuestaLLM(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return

        val respuesta = (evento.datos["respuesta"] as? String) ?: return
        val emocion = (evento.datos["emocion"] as? String) ?: "neutral"
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        if (esResumenEnCurso) {
            Log.i(TAG, "💾 Guardando resumen como recuerdo semántico.")
            BusEventos.publicar(Evento("memoria.vectorizar", "cerebro", mapOf(
                "texto" to respuesta,
                "metadata" to mapOf("tipo" to "resumen", "fecha" to fechaIso)
            )))
            limpiarEstadoRequest()
            return
        }

        if (!modoDescanso) {
            BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to emocion)))
        }
        
        BusEventos.publicar(Evento("memoria.guardar_mensaje", "cerebro", mapOf(
            "usuario" to "Llar", "mensaje" to respuesta, "sesion_id" to sesionId, "fecha" to fechaIso
        )))

        // Responder según el canal de origen de la última interacción
        responder(respuesta, origenUltimaInteraccion)
        
        limpiarEstadoRequest()
        
        contadorInteracciones++
        if (contadorInteracciones >= MAX_INTERACCIONES_ANTES_COMPACTAR) {
            contadorInteracciones = 0
            solicitarCompactacion()
        }
    }

    private fun solicitarCompactacion() {
        if (!ocupado.compareAndSet(false, true)) return
        Log.i(TAG, "📉 Iniciando compactación automática del historial...")
        BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "think")))
        
        val requestId = UUID.randomUUID().toString()
        ultimoRequestId = requestId
        esResumenEnCurso = true
        origenUltimaInteraccion = "cerebro" // La compactación es interna
        
        val msg = listOf(mapOf("role" to "user", "content" to "Resume nuestra charla reciente en 3 frases clave. Solo el resumen."))
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages" to msg, 
            "usuario" to usuarioActivo, 
            "request_id" to requestId
        )))
    }

    private fun limpiarEstadoRequest() {
        ocupado.set(false)
        ultimoRequestId = null
        esResumenEnCurso = false
        mensajesEnCurso.clear()
        origenUltimaInteraccion = "voz" // Resetear al valor por defecto
    }

    private fun responder(mensaje: String, origenRespuesta: String) {
        when (origenRespuesta) {
            "telegram_sentido" -> {
                BusEventos.publicar(Evento("telegram.enviar_mensaje", "cerebro", mapOf("mensaje" to mensaje)))
                Log.i(TAG, "🗣️ Mensaje enviado a Telegram: '$mensaje'")
            }
            // Si no es Telegram, o es un comando de voz, siempre responde por voz
            else -> {
                BusEventos.publicar(Evento("voz.hablar", "cerebro", mapOf("texto" to mensaje)))
                Log.i(TAG, "🗣️ Mensaje hablado por Llar: '$mensaje'")
            }
        }
    }

    private fun procesarRespuestaMemoria(evento: Evento) {
        val clave = evento.datos["clave"] as? String
        val valor = evento.datos["valor"] as? String
        if (clave == "nombre_usuario") nombreUsuario = valor
        if (clave == "usuario_activo") usuarioActivo = valor ?: "usuario"
    }

    private fun procesarListaActualizada(evento: Evento) {
        val items = (evento.datos["items"] as? List<*>)
        // Solo responder si el evento NO viene del propio cerebro y hay items.
        // Si el LLM pide la lista, responderá con una tool.resultado.
        if (evento.origen != "cerebro" && items != null && items.isNotEmpty()) {
            responder(if (items.isNullOrEmpty()) "La lista está vacía." else "En la lista tienes: ${items.joinToString(", ")}.", "cerebro")
        }
    }

    private fun procesarCambioUsuario(evento: Evento) {
        val nuevoUser = evento.datos["nuevo_usuario"] as? String ?: return
        usuarioActivo = nuevoUser
        nombreUsuario = nuevoUser
        sesionId = UUID.randomUUID().toString()
    }

    companion object {
        private const val TAG = "Cerebro"
    }
}
