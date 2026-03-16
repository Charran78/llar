package com.bdw.llar.core

import android.content.Context
import com.bdw.llar.efectores.Voz
import com.bdw.llar.efectores.CalendarioAndroid
import com.bdw.llar.sentidos.Oido
import com.bdw.llar.sentidos.SentidoBluetooth
import com.bdw.llar.sentidos.WakeWordDetector
import android.util.Log

/**
 * El Gestor de Módulos se encarga de cargar y mantener vivos
 * los sentidos y efectores de Llar.
 */
class GestorModulos(private val context: Context) {

    private var voz: Voz? = null
    private var memoria: Memoria? = null
    private var cerebro: Cerebro? = null
    private var memoriaSemantica: MemoriaSemantica? = null
    private var calendario: CalendarioAndroid? = null
    private var oido: Oido? = null
    private var bluetooth: SentidoBluetooth? = null
    private var wakeWordDetector: WakeWordDetector? = null

    /**
     * Inicia todos los módulos básicos del sistema.
     */
    fun iniciarTodo() {
        Log.i(TAG, "Iniciando módulos de Llar...")

        // 1. Iniciar Memoria (Persistencia)
        memoria = Memoria(context)

        // 1.5. Iniciar Memoria Semántica (Vectores)
        memoriaSemantica = MemoriaSemantica()

        // 2. Iniciar Cerebro (Lógica)
        cerebro = Cerebro()

        // 3. Iniciar Efectores (Salida)
        voz = Voz(context)
        calendario = CalendarioAndroid(context)

        // 4. Iniciar Sentidos (Oído y WakeWord)
        oido = Oido(context)
        bluetooth = SentidoBluetooth(context)
        
        wakeWordDetector = WakeWordDetector(context)
        wakeWordDetector?.iniciarEscucha()

        Log.i(TAG, "Módulos base iniciados.")
    }

    /**
     * Detiene los módulos y libera recursos.
     */
    fun detenerTodo() {
        Log.i(TAG, "Deteniendo módulos...")
        wakeWordDetector?.shutdown()
        oido?.shutdown()
        bluetooth?.shutdown()
        voz?.shutdown()
        calendario?.shutdown()
        memoriaSemantica?.shutdown()
        memoria?.close()
        Log.i(TAG, "Sistema detenido.")
    }

    companion object {
        private const val TAG = "GestorModulos"
    }
}
