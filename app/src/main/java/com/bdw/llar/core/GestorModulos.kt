package com.bdw.llar.core

import android.content.Context
import android.util.Log
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import com.bdw.llar.efectores.*
import com.bdw.llar.sentidos.*

/**
 * El Gestor de Módulos se encarga de cargar y mantener vivos
 * los sentidos y efectores de Llar.
 * v2.1: Soporte para Telegram (Efector + Sentido) y limpieza de constructores.
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
    private var llmRemoto: LLMRemoto? = null
    private var dispositivo: DispositivoAndroid? = null
    private var vision: VisionAndroid? = null
    private var avatar: Avatar? = null
    private var clima: ClimaAndroid? = null
    private var noticias: NoticiasAndroid? = null
    private var telegramEfector: TelegramAndroid? = null
    private var telegramSentido: TelegramSentido? = null

    private val tag = "GestorModulos"

    /**
     * Inicia todos los módulos básicos y lógicos del sistema.
     */
    fun iniciarTodo(lifecycleOwner: LifecycleOwner) {
        Log.i(tag, "Iniciando sistema central de Llar...")

        // Forzar inicialización del object GestorHerramientas
        GestorHerramientas

        try {
            if (memoria == null) memoria = Memoria(context)
            if (memoriaSemantica == null) memoriaSemantica = MemoriaSemantica()
            if (cerebro == null) cerebro = Cerebro()
            if (llmRemoto == null) llmRemoto = LLMRemoto(context)
            if (voz == null) voz = Voz(context)
            if (calendario == null) calendario = CalendarioAndroid(context)
            if (dispositivo == null) dispositivo = DispositivoAndroid(context)
            if (vision == null) vision = VisionAndroid(context, lifecycleOwner)
            if (clima == null) clima = ClimaAndroid(context)
            if (noticias == null) noticias = NoticiasAndroid(context)
            
            // Telegram: Efector (enviar) y Sentido (recibir)
            if (telegramEfector == null) telegramEfector = TelegramAndroid()
            if (telegramSentido == null) {
                telegramSentido = TelegramSentido()
                telegramSentido?.iniciarEscucha()
            }

            if (oido == null) oido = Oido(context)
            if (bluetooth == null) bluetooth = SentidoBluetooth(context)
            
            if (wakeWordDetector == null) {
                wakeWordDetector = WakeWordDetector(context)
                wakeWordDetector?.iniciarEscucha()
            }
            
            Log.i(tag, "Módulos lógicos, de hardware y Telegram iniciados.")
        } catch (e: Exception) {
            Log.e(tag, "Fallo crítico al iniciar módulos: ${e.message}", e)
        }
    }

    /**
     * El Avatar requiere un contenedor UI (FrameLayout).
     */
    fun iniciarAvatar(container: FrameLayout) {
        if (avatar == null) {
            avatar = Avatar(context, container)
            Log.i(tag, "Avatar visual iniciado.")
        }
    }

    /**
     * Detiene los módulos y libera recursos.
     */
    fun detenerTodo() {
        Log.i(tag, "Apagando sistema Llar...")
        wakeWordDetector?.shutdown()
        oido?.shutdown()
        bluetooth?.shutdown()
        voz?.shutdown()
        calendario?.shutdown()
        clima?.shutdown()
        noticias?.shutdown()
        telegramEfector?.shutdown()
        telegramSentido?.shutdown()
        memoriaSemantica?.shutdown()
        llmRemoto?.shutdown()
        avatar?.shutdown()
        vision?.shutdown()
        memoria?.close()
        Log.i(tag, "Sistema Llar detenido correctamente.")
    }
}
