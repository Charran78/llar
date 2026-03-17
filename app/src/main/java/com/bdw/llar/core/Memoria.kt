package com.bdw.llar.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bdw.llar.modelo.Evento
import android.util.Log
import org.json.JSONObject

/**
 * Gestiona la persistencia de datos de Llar usando SQLite.
 * Almacena hechos, conversaciones (con metadatos), lista de la compra y recuerdos vectoriales.
 * v3: migración incremental (no DROP), soporte completo de recuerdos vectoriales.
 */
class Memoria(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), BusEventos.Suscriptor {

    companion object {
        private const val TAG = "Memoria"
        private const val DATABASE_NAME = "llar.db"
        private const val DATABASE_VERSION = 3 // v3: añade columna metadatos a conversaciones

        private const val TABLE_HECHOS = "hechos"
        private const val TABLE_LISTA = "lista_compra"
        private const val TABLE_CONVERSACIONES = "conversaciones"
        private const val TABLE_RECUERDOS = "recuerdos"

        private const val COL_ID = "id"
        private const val COL_FECHA = "fecha"
        private const val COL_CLAVE = "clave"
        private const val COL_VALOR = "valor"
        private const val COL_ITEM = "item"
        private const val COL_COMPRADO = "comprado"
        private const val COL_USUARIO = "usuario"
        private const val COL_MENSAJE = "mensaje"
        private const val COL_SESION_ID = "sesion_id"
        private const val COL_METADATOS = "metadatos"   // JSON: {nombre, fecha, etc.}
        private const val COL_CONTENIDO = "contenido"
        private const val COL_EMBEDDING = "embedding"
    }

    init {
        BusEventos.suscribir("memoria.guardar", this)
        BusEventos.suscribir("memoria.recuperar", this)
        BusEventos.suscribir("memoria.guardar_mensaje", this)
        BusEventos.suscribir("memoria.obtener_historial", this)
        BusEventos.suscribir("memoria.guardar_recuerdo", this)
        BusEventos.suscribir("memoria.guardar_recuerdo_vectorial", this)
        BusEventos.suscribir("memoria.buscar_recuerdos", this) // FASE 2: Búsqueda Semántica
        BusEventos.suscribir("lista.añadir", this)
        BusEventos.suscribir("lista.consultar", this)
        BusEventos.suscribir("lista.limpiar", this)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_HECHOS ($COL_CLAVE TEXT PRIMARY KEY, $COL_VALOR TEXT)")
        db.execSQL("CREATE TABLE $TABLE_LISTA ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_ITEM TEXT, $COL_COMPRADO INTEGER DEFAULT 0)")

        db.execSQL("""
            CREATE TABLE $TABLE_CONVERSACIONES (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESION_ID TEXT,
                $COL_USUARIO TEXT,
                $COL_MENSAJE TEXT,
                $COL_METADATOS TEXT,
                $COL_FECHA DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_RECUERDOS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTENIDO TEXT,
                $COL_EMBEDDING BLOB,
                $COL_METADATOS TEXT,
                $COL_FECHA DATETIME DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        // Nombre por defecto
        val values = ContentValues().apply {
            put(COL_CLAVE, "nombre_usuario")
            put(COL_VALOR, "Pedro")
        }
        db.insert(TABLE_HECHOS, null, values)
    }

    // FIX #9: Migración incremental — NO se hace DROP de tablas con datos
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Migrando BD de v$oldVersion a v$newVersion")
        if (oldVersion < 2) {
            // Crear tablas nuevas si vienen de v1 que no las tenía
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_CONVERSACIONES (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_SESION_ID TEXT,
                        $COL_USUARIO TEXT,
                        $COL_MENSAJE TEXT,
                        $COL_METADATOS TEXT,
                        $COL_FECHA DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_RECUERDOS (
                        $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                        $COL_CONTENIDO TEXT,
                        $COL_EMBEDDING BLOB,
                        $COL_METADATOS TEXT,
                        $COL_FECHA DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent())
            } catch (e: Exception) {
                Log.e(TAG, "Error en migración v1→v2: ${e.message}")
            }
        }
        if (oldVersion < 3) {
            // Añadir columna metadatos a conversaciones si no existe
            try {
                db.execSQL("ALTER TABLE $TABLE_CONVERSACIONES ADD COLUMN $COL_METADATOS TEXT")
            } catch (e: Exception) {
                Log.w(TAG, "Columna metadatos ya existía o error: ${e.message}")
            }
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "memoria.guardar" -> {
                val clave = evento.datos["clave"] as? String
                val valor = evento.datos["valor"] as? String
                if (clave != null && valor != null) guardarHecho(clave, valor)
            }
            "memoria.recuperar" -> {
                val clave = evento.datos["clave"] as? String
                if (clave != null) {
                    val valor = recuperarHecho(clave)
                    BusEventos.publicar(Evento("memoria.respuesta", "memoria", mapOf("clave" to clave, "valor" to (valor ?: ""))))
                }
            }
            "memoria.guardar_mensaje" -> {
                val usuario = evento.datos["usuario"] as? String ?: "desconocido"
                val mensaje = evento.datos["mensaje"] as? String ?: ""
                val sesion = evento.datos["sesion_id"] as? String ?: "default"
                // Construir metadatos: nombre + fecha ISO
                val metadatos = JSONObject().apply {
                    put("nombre", usuario)
                    put("fecha", evento.datos["fecha"] as? String ?: java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                }.toString()
                guardarMensaje(sesion, usuario, mensaje, metadatos)
            }
            "memoria.obtener_historial" -> {
                val limite = (evento.datos["limite"] as? Int) ?: 10
                val sesionId = evento.datos["sesion_id"] as? String
                val requestId = evento.datos["request_id"] as? String
                val historial = obtenerHistorial(limite, sesionId)
                BusEventos.publicar(Evento("memoria.historial_recuperado", "memoria", mapOf(
                    "mensajes" to historial,
                    "sesion_id" to (sesionId ?: ""),
                    "request_id" to requestId
                )))
            }
            // FIX #8: recuerdos vectoriales ahora se persisten
            "memoria.guardar_recuerdo_vectorial" -> {
                val contenido = evento.datos["contenido"] as? String ?: return
                val vector = evento.datos["vector"] as? String ?: ""
                val meta = evento.datos["metadata"] as? String ?: "{}"
                guardarRecuerdoVectorial(contenido, vector, meta)
            }
            "memoria.buscar_recuerdos" -> {
                val vectorConsultaStr = evento.datos["vector"] as? String ?: return
                val limite = (evento.datos["limite"] as? Int) ?: 3
                val sesionId = evento.datos["sesion_id"] as? String ?: "default"
                val requestId = evento.datos["request_id"] as? String
                
                val resultados = buscarRecuerdosSimilares(vectorConsultaStr, limite)
                BusEventos.publicar(Evento(
                    tipo = "memoria.recuerdos_recuperados",
                    origen = "memoria",
                    datos = mapOf(
                        "recuerdos" to resultados,
                        "sesion_id" to sesionId,
                        "request_id" to requestId
                    )
                ))
            }
            "lista.añadir" -> {
                val item = evento.datos["item"] as? String
                if (item != null) {
                    añadirAListaCompra(item)
                    // Notificar lista actualizada tras añadir
                    val lista = obtenerListaCompra()
                    BusEventos.publicar(Evento("lista.compra_actualizada", "memoria", mapOf("items" to lista)))
                }
            }
            "lista.consultar" -> {
                val lista = obtenerListaCompra()
                BusEventos.publicar(Evento("lista.compra_actualizada", "memoria", mapOf("items" to lista)))
            }
            "lista.limpiar" -> {
                limpiarListaCompra()
                BusEventos.publicar(Evento("voz.hablar", "memoria", mapOf("texto" to "He vaciado la lista de la compra.")))
                BusEventos.publicar(Evento("lista.compra_actualizada", "memoria", mapOf("items" to emptyList<String>())))
            }
        }
    }

    // --- HECHOS ---
    fun guardarHecho(clave: String, valor: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CLAVE, clave)
            put(COL_VALOR, valor)
        }
        db.insertWithOnConflict(TABLE_HECHOS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.d(TAG, "Hecho guardado: $clave = $valor")
    }

    fun recuperarHecho(clave: String): String? {
        val db = readableDatabase
        val cursor = db.query(TABLE_HECHOS, arrayOf(COL_VALOR), "$COL_CLAVE = ?", arrayOf(clave), null, null, null)
        return if (cursor.moveToFirst()) {
            val valor = cursor.getString(0)
            cursor.close()
            valor
        } else {
            cursor.close()
            null
        }
    }

    // --- CONVERSACIONES (historial de chat) ---
    fun guardarMensaje(sesion: String, usuario: String, mensaje: String, metadatos: String = "{}") {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SESION_ID, sesion)
            put(COL_USUARIO, usuario)
            put(COL_MENSAJE, mensaje)
            put(COL_METADATOS, metadatos)
        }
        db.insert(TABLE_CONVERSACIONES, null, values)
        Log.d(TAG, "Mensaje guardado: [$usuario] $mensaje")
    }

    fun obtenerHistorial(limite: Int, sesionId: String? = null): List<Map<String, String>> {
        val mensajes = mutableListOf<Map<String, String>>()
        val db = readableDatabase
        val selection = if (sesionId != null) "$COL_SESION_ID = ?" else null
        val selectionArgs = if (sesionId != null) arrayOf(sesionId) else null
        val cursor = db.query(
            TABLE_CONVERSACIONES,
            arrayOf(COL_USUARIO, COL_MENSAJE, COL_FECHA, COL_METADATOS),
            selection, selectionArgs, null, null,
            "$COL_FECHA DESC", limite.toString()
        )
        while (cursor.moveToNext()) {
            mensajes.add(mapOf(
                "usuario" to cursor.getString(0),
                "mensaje" to cursor.getString(1),
                "fecha" to cursor.getString(2),
                "metadatos" to (cursor.getString(3) ?: "{}")
            ))
        }
        cursor.close()
        return mensajes.reversed() // De más antiguo a más nuevo
    }

    // --- RECUERDOS VECTORIALES ---
    fun guardarRecuerdoVectorial(contenido: String, embedding: String, metadatos: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_CONTENIDO, contenido)
            put(COL_EMBEDDING, embedding) // Se guarda como String (JSON Array) de momento
            put(COL_METADATOS, metadatos)
        }
        db.insert(TABLE_RECUERDOS, null, values)
        Log.d(TAG, "Recuerdo vectorial guardado: ${contenido.take(60)}...")
    }

    /**
     * Búsqueda Semántica: Carga todos los vectores y calcula la Similitud Coseno en memoria.
     */
    private fun buscarRecuerdosSimilares(vectorPreguntaStr: String, limite: Int): List<String> {
        val vectorPregunta = parsearVector(vectorPreguntaStr) ?: return emptyList()
        val resultados = mutableListOf<Pair<String, Double>>()

        val db = readableDatabase
        val cursor = db.query(TABLE_RECUERDOS, arrayOf(COL_CONTENIDO, COL_EMBEDDING), null, null, null, null, null)

        while (cursor.moveToNext()) {
            val contenido = cursor.getString(0) ?: continue
            val vectorDBCadena = cursor.getString(1) ?: continue
            
            val vectorDB = parsearVector(vectorDBCadena) ?: continue
            
            if (vectorPregunta.size == vectorDB.size) {
                val similitud = similitudCoseno(vectorPregunta, vectorDB)
                resultados.add(Pair(contenido, similitud))
            }
        }
        cursor.close()

        // Ordenar de mayor a menor similitud (1.0 es idéntico, -1.0 opuesto)
        return resultados.sortedByDescending { it.second }
            .take(limite)
            .map { it.first }
    }

    private fun parsearVector(jsonStr: String): FloatArray? {
        return try {
            val jsonArray = org.json.JSONArray(jsonStr)
            val floatArray = FloatArray(jsonArray.length())
            for (i in 0 until jsonArray.length()) {
                floatArray[i] = jsonArray.getDouble(i).toFloat()
            }
            floatArray
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando vector JSON: ${e.message}")
            null
        }
    }

    private fun similitudCoseno(v1: FloatArray, v2: FloatArray): Double {
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        return if (normA == 0.0 || normB == 0.0) 0.0 else dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))
    }

    // --- LISTA COMPRA ---
    fun añadirAListaCompra(item: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ITEM, item.trim().lowercase())
        }
        db.insert(TABLE_LISTA, null, values)
        Log.d(TAG, "Item añadido a lista: $item")
    }

    fun obtenerListaCompra(): List<String> {
        val items = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.query(TABLE_LISTA, arrayOf(COL_ITEM), "$COL_COMPRADO = 0", null, null, null, null)
        while (cursor.moveToNext()) {
            items.add(cursor.getString(0))
        }
        cursor.close()
        return items
    }

    fun limpiarListaCompra() {
        val db = writableDatabase
        db.delete(TABLE_LISTA, null, null)
        Log.d(TAG, "Lista de compra vaciada")
    }
}
