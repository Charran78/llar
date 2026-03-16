package com.bdw.llar.sentidos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * El sentido del Oído utilizando Vosk para reconocimiento de voz offline.
 * Ahora incluye análisis de amplitud en tiempo real para el visualizador de UI.
 */
class Oido(private val context: Context) : BusEventos.Suscriptor {
    private var modelo: Model? = null
    private var reconocedor: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private val escuchando = AtomicBoolean(false)
    private val pausadoPorVoz = AtomicBoolean(false)
    private var hiloEscucha: Thread? = null
    private val SAMPLE_RATE = 16000.0f
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
        scope.launch {
            cargarModelo()
        }
        BusEventos.suscribir("oido.activar_modo_escucha", this)
        BusEventos.suscribir("oido.desactivar", this)
        BusEventos.suscribir("voz.hablar", this)
        BusEventos.suscribir("voz.empezado", this)
        BusEventos.suscribir("voz.finalizado", this)
    }

    private suspend fun cargarModelo() = withContext(Dispatchers.IO) {
        val modeloNombre = "vosk-model-small-es-0.42"
        val modeloDestino = File(context.filesDir, modeloNombre)

        try {
            val assets = context.assets.list(modeloNombre)
            if (assets.isNullOrEmpty() && !modeloDestino.exists()) {
                Log.e(TAG, "ERROR: No se encontró el modelo en assets.")
                return@withContext
            }

            if (!modeloDestino.exists()) {
                Log.i(TAG, "Copiando modelo Vosk...")
                copyAssets(context, modeloNombre, modeloDestino.absolutePath)
            }

            if (modeloDestino.exists()) {
                modelo = Model(modeloDestino.absolutePath)
                Log.i(TAG, "Modelo Vosk cargado.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar modelo Vosk: ${e.message}")
        }
    }

    private fun copyAssets(context: Context, assetPath: String, targetDirPath: String) {
        val assetManager = context.assets
        val assets = assetManager.list(assetPath) ?: return
        val targetDir = File(targetDirPath)
        if (!targetDir.exists()) targetDir.mkdirs()

        for (asset in assets) {
            val fullAssetPath = "$assetPath/$asset"
            val targetFilePath = "$targetDirPath/$asset"
            val children = assetManager.list(fullAssetPath)
            if (children.isNullOrEmpty()) {
                assetManager.open(fullAssetPath).use { input ->
                    File(targetFilePath).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                copyAssets(context, fullAssetPath, targetFilePath)
            }
        }
    }

    override fun alRecibirEvento(evento: Evento) {
        when (evento.tipo) {
            "oido.activar_modo_escucha" -> {
                pausadoPorVoz.set(false)
                iniciarEscucha()
            }
            "oido.desactivar" -> {
                pausadoPorVoz.set(false)
                detenerEscucha()
            }
            "voz.hablar", "voz.empezado" -> {
                if (escuchando.get()) {
                    Log.d(TAG, "Llar va a hablar. Pausando oído...")
                    pausadoPorVoz.set(true)
                    detenerEscucha()
                }
            }
            "voz.finalizado" -> {
                if (pausadoPorVoz.get()) {
                    pausadoPorVoz.set(false)
                    scope.launch {
                        // FIX #2: 1500ms para que el eco del TTS se disipe antes de reanudar Vosk
                        delay(1500)
                        iniciarEscucha()
                    }
                }
            }
        }
    }

    fun iniciarEscucha() {
        if (escuchando.get()) return
        if (modelo == null) return

        try {
            reconocedor = Recognizer(modelo, SAMPLE_RATE)
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE.toInt(),
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return

            audioRecord?.startRecording()
            escuchando.set(true)

            hiloEscucha = Thread {
                val buffer = ShortArray(bufferSize / 2)
                while (escuchando.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 1. Calcular Amplitud para la UI
                        val amplitud = calcularAmplitud(buffer, read)
                        BusEventos.publicar(Evento("oido.amplitud", "oido", mapOf("valor" to amplitud)))

                        // 2. Procesar con Vosk (convertir ShortArray a ByteArray)
                        val byteBuffer = ByteArray(read * 2)
                        for (i in 0 until read) {
                            byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                        }
                        
                        if (reconocedor?.acceptWaveForm(byteBuffer, byteBuffer.size) == true) {
                            procesarResultado(reconocedor?.result)
                        }
                    }
                }
                // Al terminar, enviamos amplitud 0
                BusEventos.publicar(Evento("oido.amplitud", "oido", mapOf("valor" to 0f)))
            }.apply {
                name = "Llar-Oido-Thread"
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar escucha: ${e.message}")
        }
    }

    private fun calcularAmplitud(buffer: ShortArray, read: Int): Float {
        var sum = 0f
        for (i in 0 until read) {
            sum += abs(buffer[i].toInt()).toFloat()
        }
        val avg = sum / read
        // Normalizar a un valor entre 0.0 y 1.0 (aprox) para la UI
        return (avg / 32768f).coerceIn(0f, 1f)
    }

    private fun procesarResultado(jsonStr: String?) {
        if (jsonStr == null) return
        try {
            val json = JSONObject(jsonStr)
            val texto = json.optString("text", "")
            if (texto.isNotBlank() && !pausadoPorVoz.get()) {
                BusEventos.publicar(Evento("voz.comando", "oido", mapOf("texto" to texto)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al procesar: ${e.message}")
        }
    }

    fun detenerEscucha() {
        if (!escuchando.get()) return
        escuchando.set(false)
        try {
            hiloEscucha?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        reconocedor = null
        BusEventos.publicar(Evento("oido.amplitud", "oido", mapOf("valor" to 0f)))
    }

    fun shutdown() {
        detenerEscucha()
        scope.cancel()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "Oido"
    }
}
