package com.bdw.llar.efectores

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bdw.llar.core.BusEventos
import com.bdw.llar.modelo.Evento
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Efector de Visión: Captura una imagen atómica usando CameraX.
 * Se activa bajo demanda, captura un frame y se cierra.
 * v2.1: Soporte para fallback a cámara trasera si la frontal no está disponible.
 * NOTA: Requiere LifecycleOwner activo (App en primer plano) debido a restricciones de Android.
 */
class VisionAndroid(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) : BusEventos.Suscriptor {

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val isCapturing = AtomicBoolean(false)

    init {
        BusEventos.suscribir("vision.capturar_foto", this)
        Log.i(TAG, "VisionAndroid listo y esperando gatillo.")
    }

    override fun alRecibirEvento(evento: Evento) {
        if (evento.tipo == "vision.capturar_foto") {
            val requestId = evento.datos["request_id"] as? String
            Log.i(TAG, "📸 Gatillo de visión recibido. Capturando...")
            capturarFotoAtomica(requestId)
        }
    }

    private fun capturarFotoAtomica(requestId: String?) {
        // Evitar capturas concurrentes
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "Ya hay una captura en curso, ignorando nueva solicitud")
            enviarError(requestId, "Ya se está procesando una captura anterior.")
            return
        }

        // Verificar permiso de cámara
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permiso de cámara no concedido")
            isCapturing.set(false)
            enviarError(requestId, "No tengo permiso para usar la cámara.")
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()

                // Intentar cámara frontal, fallback a trasera si falla
                val cameraSelector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                        Log.d(TAG, "Usando cámara frontal")
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    }
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                        Log.d(TAG, "Cámara frontal no encontrada, usando trasera")
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    else -> {
                        throw Exception("No se detectaron cámaras en el dispositivo")
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                tomarFoto(cameraProvider, requestId)

            } catch (e: Exception) {
                Log.e(TAG, "Error al inicializar cámara: ${e.message}")
                isCapturing.set(false)
                enviarError(requestId, "Error al acceder a la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun tomarFoto(cameraProvider: ProcessCameraProvider, requestId: String?) {
        val imageCapture = this.imageCapture ?: run {
            isCapturing.set(false)
            enviarError(requestId, "Error interno: cámara no lista")
            return
        }

        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                Log.d(TAG, "Foto capturada con éxito. Procesando...")
                
                val base64 = imageProxyToBase64(image)
                image.close()

                // Liberar cámara inmediatamente
                ContextCompat.getMainExecutor(context).execute {
                    cameraProvider.unbindAll()
                }

                isCapturing.set(false)

                if (base64 != null) {
                    BusEventos.publicar(Evento(
                        tipo = "herramienta.resultado",
                        origen = "vision_android",
                        datos = mapOf(
                            "nombre_herramienta" to "analizar_entorno",
                            "resultado" to "IMAGEN_CAPTURADA",
                            "image_base64" to base64,
                            "request_id" to requestId
                        )
                    ))
                } else {
                    enviarError(requestId, "Error al procesar la imagen capturada.")
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Error capturando foto: ${exception.message}")
                cameraProvider.unbindAll()
                isCapturing.set(false)
                enviarError(requestId, "Fallo técnico al disparar la cámara.")
            }
        })
    }

    private fun imageProxyToBase64(image: ImageProxy): String? {
        return try {
            if (image.planes.isEmpty()) return null
            
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Decodificación defensiva (RGB_565 usa la mitad de memoria que el estándar)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2 
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

            // Rotación
            val matrix = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (bitmap != rotatedBitmap) bitmap.recycle()

            // Escalado final para Moondream o similar
            val scaledBitmap = Bitmap.createScaledBitmap(rotatedBitmap, 400, 400, true)
            if (rotatedBitmap != scaledBitmap) rotatedBitmap.recycle()

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            val finalBytes = outputStream.toByteArray()
            scaledBitmap.recycle()

            Base64.encodeToString(finalBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Error al procesar imagen: ${e.message}")
            null
        }
    }

    private fun enviarError(requestId: String?, motivo: String) {
        BusEventos.publicar(Evento(
            tipo = "herramienta.resultado",
            origen = "vision_android",
            datos = mapOf(
                "nombre_herramienta" to "analizar_entorno",
                "resultado" to "Error: $motivo",
                "request_id" to requestId
            )
        ))
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        BusEventos.desuscribirCompleto(this)
    }

    companion object {
        private const val TAG = "VisionAndroid"
    }
}
