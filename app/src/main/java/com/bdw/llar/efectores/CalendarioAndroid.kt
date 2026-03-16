package com.bdw.llar.efectores

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import java.text.SimpleDateFormat
import java.util.*

/**
 * Módulo que interactúa con el Calendario de Android.
 * Permite leer eventos del día actual y crear nuevos eventos.
 */
class CalendarioAndroid(private val context: Context) : BusEventos.Suscriptor {

    init {
        BusEventos.suscribir("calendario.leer_eventos", this)
        BusEventos.suscribir("calendario.crear_evento", this)
        Log.i(TAG, "CalendarioAndroid iniciado.")
    }

    override fun alRecibirEvento(evento: Evento) {
        if (!tienePermisos()) {
            Log.w(TAG, "No hay permisos de calendario.")
            if (evento.tipo == "calendario.leer_eventos") {
                BusEventos.publicar(Evento("calendario.eventos_leidos", "calendario", mapOf("eventos" to emptyList<String>())))
            }
            return
        }

        when (evento.tipo) {
            "calendario.leer_eventos" -> {
                val dias = (evento.datos["dias"] as? Int) ?: 1 // Por defecto, hoy
                val eventos = leerEventosProximos(dias)
                BusEventos.publicar(Evento("calendario.eventos_leidos", "calendario", mapOf("eventos" to eventos)))
            }
            "calendario.crear_evento" -> {
                val titulo = evento.datos["titulo"] as? String ?: return
                val inicioMs = (evento.datos["inicio_ms"] as? Long) ?: System.currentTimeMillis()
                val duracionMin = (evento.datos["duracion_min"] as? Int) ?: 60
                
                crearEvento(titulo, inicioMs, duracionMin)
            }
        }
    }

    private fun tienePermisos(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    private fun leerEventosProximos(diasFiltro: Int): List<String> {
        val eventos = mutableListOf<String>()
        
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.ALL_DAY
        )

        val ahora = Calendar.getInstance()
        val inicioDia = ahora.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        val finFiltro = ahora.apply {
            add(Calendar.DAY_OF_YEAR, diasFiltro)
        }.timeInMillis

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(inicioDia.toString(), finFiltro.toString())

        try {
            val cursor: Cursor? = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val allDayIndex = it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)

                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

                while (it.moveToNext()) {
                    val titulo = it.getString(titleIndex)
                    val fechaMs = it.getLong(startIndex)
                    val todoElDia = it.getInt(allDayIndex) == 1

                    if (todoElDia) {
                        eventos.add("$titulo (Todo el día)")
                    } else {
                        val horaStr = sdf.format(Date(fechaMs))
                        eventos.add("$titulo a las $horaStr")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo calendario: ${e.message}")
        }

        return eventos
    }

    private fun crearEvento(titulo: String, inicioMs: Long, duracionMin: Int) {
        val finMs = inicioMs + (duracionMin * 60 * 1000)
        
        // Primero obtener el ID del calendario local principal
        val calId = obtenerIdCalendarioPrimario()
        if (calId == -1L) {
            Log.e(TAG, "No se encontró un calendario válido para insertar.")
            return
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, inicioMs)
            put(CalendarContract.Events.DTEND, finMs)
            put(CalendarContract.Events.TITLE, titulo)
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.i(TAG, "Evento creado: $titulo")
                BusEventos.publicar(Evento("voz.hablar", "calendario", mapOf("texto" to "He añadido $titulo al calendario.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creando evento: ${e.message}")
        }
    }

    private fun obtenerIdCalendarioPrimario(): Long {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1" // Solo calendarios visibles
        
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                null,
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando calendarios: ${e.message}")
        }
        return 1L // Fallback ID muy común
    }

    fun shutdown() {
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "CalendarioAndroid"
    }
}
