package com.bdw.llar.core

import com.bdw.llar.modelo.Evento
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import android.os.Handler
import android.os.Looper

/**
 * El Cerebro es el orquestador de Llar.
 * v3.2: Auditoría de permisos proactiva y Copia Defensiva.
 */
class Cerebro : BusEventos.Suscriptor {
    private var modoApuntarCompra = false
    private var modoDescanso = false
    private var nombreUsuario: String? = null
    private var usuarioActivo: String = "usuario"
    private var contadorInteracciones = 0
    private val MAX_INTERACCIONES_ANTES_COMPACTAR = 8
    var sesionId: String = UUID.randomUUID().toString()

    private var ocupado = false
    private var ultimoRequestId: String? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val TIMEOUT_MS = 90000L

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
            "voz.comando"                 -> procesarComandoVoz(evento)
            "llm.respuesta"               -> procesarRespuestaLLM(evento)
            "memoria.recuerdos_recuperados" -> procesarContextoRecuperado(evento)
            "memoria.historial_recuperado"-> procesarHistorialRecuperado(evento)
            "herramienta.resultado"       -> procesarResultadoHerramienta(evento)
            "usuario.cambiado"            -> procesarCambioUsuario(evento)
            "memoria.respuesta"           -> procesarRespuestaMemoria(evento)
            "lista.compra_actualizada"    -> procesarListaActualizada(evento)
            "memoria.compactar"           -> solicitarCompactacion()
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
        responder(if (nombre.isNotBlank()) "$saludoBase $nombre." else saludoBase)
    }

    private fun procesarWakeWord() {
        if (modoDescanso) return
        responder(listOf("¿Sí?", "Dime.", "Te escucho.").random())
        BusEventos.publicar(Evento("oido.activar_modo_escucha", "cerebro", mapOf("duracion" to 7)))
    }

    private fun procesarComandoVoz(evento: Evento) {
        val texto = (evento.datos["texto"] as? String) ?: return
        val cmd = texto.lowercase()

        if (modoDescanso) {
            if (cmd.contains("despierta") || cmd.contains("levántate")) {
                modoDescanso = false
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "neutral")))
                responder("¡Hola! Ya estoy aquí.")
            }
            return
        }

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
            cmd.contains("descansa") || cmd.contains("vete a dormir") || cmd.contains("a dormir") -> {
                modoDescanso = true
                BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "dormir")))
                responder("De acuerdo, voy a descansar.")
            }
            else -> pedirHistorialYEnviarAlLLM(texto)
        }
    }

    private fun pedirHistorialYEnviarAlLLM(texto: String) {
        if (ocupado) return
        
        Log.i(TAG, "🧠 PENSANDO... [Iniciando flujo de respuesta]")
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf()))

        textoParaLLM = texto
        pendienteContexto = true
        recuerdosRecuperados = ""
        ocupado = true
        ultimoRequestId = UUID.randomUUID().toString()

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
        val contextoInfo = "[INFO_SISTEMA: Hoy es $fecha. ${if(recuerdosRecuperados.isNotBlank()) "| Recuerdos: $recuerdosRecuperados" else ""}]"

        mensajes.add(mapOf("role" to "user", "content" to contextoInfo))

        @Suppress("UNCHECKED_CAST")
        val historial = evento.datos["mensajes"] as? List<Map<String, String>> ?: emptyList()
        mensajes.addAll(historial.map { msg ->
            mapOf("role" to if (msg["usuario"] == "Llar") "assistant" else "user", "content" to (msg["mensaje"] ?: ""))
        })

        mensajes.add(mapOf("role" to "user", "content" to textoParaLLM))
        
        // 1. Obtener el catálogo de herramientas de forma segura
        val herramientas = GestorHerramientas.obtenerCatalogo()

        // 2. SOLUCIÓN AL CRASH: Auditoría de permisos proactiva
        val permisosNecesarios = mutableSetOf<String>()
        for (i in 0 until herramientas.length()) {
            val hStr = herramientas.getJSONObject(i).toString().lowercase()
            if (hStr.contains("calendar") || hStr.contains("calendario")) {
                permisosNecesarios.add(android.Manifest.permission.READ_CALENDAR)
                permisosNecesarios.add(android.Manifest.permission.WRITE_CALENDAR)
            }
            if (hStr.contains("flashlight") || hStr.contains("linterna")) {
                permisosNecesarios.add(android.Manifest.permission.CAMERA)
            }
        }

        if (permisosNecesarios.isNotEmpty()) {
            Log.d(TAG, "🛡️ Verificando permisos necesarios: $permisosNecesarios")
            BusEventos.publicar(Evento("sistema.verificar_permisos", "cerebro", mapOf(
                "permisos" to permisosNecesarios.toTypedArray()
            )))
        }

        // 3. MEJORA DE FLUIDEZ: Copia defensiva
        mensajesEnCurso = mensajes.toMutableList()

        Log.i(TAG, "🧠 Enviando a LLM ($requestId) con ${herramientas.length()} herramientas.")

        // 4. Lanzar la petición formal al LLM
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages" to mensajesEnCurso,
            "herramientas" to herramientas,
            "usuario" to usuarioActivo,
            "request_id" to requestId
        )))
    }

    private fun procesarResultadoHerramienta(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return

        val nombre = evento.datos["nombre_herramienta"] as? String
        val resultado = evento.datos["resultado"] as? String ?: ""

        Log.i(TAG, "🧠 PENSANDO... [Resultado Tool '$nombre': $resultado]")

        mensajesEnCurso.add(mapOf(
            "role" to "user",
            "content" to "[RESULTADO_HERRAMIENTA '$nombre': $resultado]"
        ))

        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf(
            "messages" to mensajesEnCurso,
            "usuario" to usuarioActivo,
            "request_id" to requestId
        )))
    }

    private fun procesarRespuestaLLM(evento: Evento) {
        val requestId = evento.datos["request_id"] as? String
        if (requestId != ultimoRequestId) return

        val respuesta = (evento.datos["respuesta"] as? String) ?: return
        val emocion = (evento.datos["emocion"] as? String) ?: "neutral"

        if (!modoDescanso) {
            BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to emocion)))
        }
        
        val fechaIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        BusEventos.publicar(Evento("memoria.guardar_mensaje", "cerebro", mapOf(
            "usuario" to "Llar", "mensaje" to respuesta, "sesion_id" to sesionId, "fecha" to fechaIso
        )))

        responder(respuesta)
        
        ocupado = false
        ultimoRequestId = null
        contadorInteracciones++
        if (contadorInteracciones >= MAX_INTERACCIONES_ANTES_COMPACTAR) {
            contadorInteracciones = 0
            solicitarCompactacion()
        }
    }

    private fun solicitarCompactacion() {
        if (ocupado) return
        Log.i(TAG, "Compactando...")
        BusEventos.publicar(Evento("avatar.expresar", "cerebro", mapOf("emocion" to "think")))
        ocupado = true
        val requestId = UUID.randomUUID().toString()
        ultimoRequestId = requestId
        val msg = listOf(mapOf("role" to "user", "content" to "Resume nuestra charla en 3 frases. Solo el resumen."))
        BusEventos.publicar(Evento("llm.solicitar", "cerebro", mapOf("messages" to msg, "usuario" to usuarioActivo, "es_resumen" to true, "request_id" to requestId)))
    }

    private fun responder(mensaje: String) {
        BusEventos.publicar(Evento("voz.hablar", "cerebro", mapOf("texto" to mensaje)))
    }

    private fun procesarRespuestaMemoria(evento: Evento) {
        val clave = evento.datos["clave"] as? String
        val valor = evento.datos["valor"] as? String
        if (clave == "nombre_usuario") nombreUsuario = valor
        if (clave == "usuario_activo") usuarioActivo = valor ?: "usuario"
    }

    private fun procesarListaActualizada(evento: Evento) {
        val items = (evento.datos["items"] as? List<*>)
        if (evento.origen != "cerebro") {
            responder(if (items.isNullOrEmpty()) "La lista está vacía." else "En la lista tienes: ${items.joinToString(", ")}.")
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
