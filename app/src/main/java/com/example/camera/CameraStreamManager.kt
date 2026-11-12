package com.example.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundStreamLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    init {
        postState(Lifecycle.State.RESUMED)
    }

    fun markResumed() {
        postState(Lifecycle.State.RESUMED)
    }

    fun markDestroyed() {
        postState(Lifecycle.State.DESTROYED)
    }

    private fun postState(state: Lifecycle.State) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            lifecycleRegistry.currentState = state
        } else {
            Handler(Looper.getMainLooper()).post {
                lifecycleRegistry.currentState = state
            }
        }
    }
}

class CameraStreamManager(private val context: Context) {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val isCameraStreaming = AtomicBoolean(false)
    private val isAudioStreaming = AtomicBoolean(false)
    private var lastFrameTime = 0L
    private val frameIntervalMs = 600L // Optimal interval for smooth sync without exceeding bandwidth
    private var streamLifecycleOwner: BackgroundStreamLifecycleOwner? = null

    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startCameraStream(
        lifecycleOwner: LifecycleOwner? = null,
        previewView: PreviewView? = null,
        useFrontCamera: Boolean,
        deviceName: String = "Companion Device",
        onFrameCaptured: (String) -> Unit,
        onError: (String, Throwable?) -> Unit = { _, _ -> }
    ) {
        // Step 1: Check Camera permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            val errorMsg = "Camera permission not granted on target device."
            Log.e("CameraStreamManager", errorMsg)
            isCameraStreaming.set(false)
            onError(errorMsg, SecurityException(errorMsg))
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                provider.unbindAll()

                val cameraSelector = if (useFrontCamera && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    val errorMsg = "No suitable camera found on this device."
                    Log.e("CameraStreamManager", errorMsg)
                    isCameraStreaming.set(false)
                    onError(errorMsg, IllegalStateException(errorMsg))
                    return@addListener
                }

                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImage(imageProxy, onFrameCaptured)
                }

                val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)

                previewView?.let { pv ->
                    try {
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(pv.surfaceProvider)
                        }
                        useCases.add(preview)
                    } catch (e: Exception) {
                        Log.w("CameraStreamManager", "Preview surface attach skipped: ${e.message}")
                    }
                }

                val bgOwner = streamLifecycleOwner ?: BackgroundStreamLifecycleOwner().also {
                    streamLifecycleOwner = it
                }
                bgOwner.markResumed()
                val owner: LifecycleOwner = lifecycleOwner ?: bgOwner

                provider.bindToLifecycle(
                    owner,
                    cameraSelector,
                    *useCases.toTypedArray()
                )
                isCameraStreaming.set(true)
                Log.d("CameraStreamManager", "Camera stream bound successfully (front=$useFrontCamera)")
            } catch (e: Exception) {
                isCameraStreaming.set(false)
                Log.e("CameraStreamManager", "CameraX initialization failed", e)
                val userMsg = when {
                    e is IllegalStateException && e.message?.contains("lifecycle", ignoreCase = true) == true ->
                        "Camera binding error: Invalid lifecycle state."
                    e is SecurityException ->
                        "Camera blocked by Android security/background restriction."
                    else ->
                        "Camera initialization failed: ${e.localizedMessage ?: "Unknown CameraX error"}"
                }
                onError(userMsg, e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImage(imageProxy: ImageProxy, onFrameCaptured: (String) -> Unit) {
        try {
            if (!isCameraStreaming.get()) {
                return
            }

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFrameTime < frameIntervalMs) {
                return
            }
            lastFrameTime = currentTime

            val rotation = imageProxy.imageInfo.rotationDegrees

            val originalBitmap = try {
                val bmp = imageProxy.toBitmap()
                if (rotation != 0) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    if (rotated != bmp) bmp.recycle()
                    rotated
                } else {
                    bmp
                }
            } catch (e: Exception) {
                try {
                    val nv21 = yuv420ToNv21(imageProxy)
                    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
                    val out = ByteArrayOutputStream()
                    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 60, out)
                    val imageBytes = out.toByteArray()
                    val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    if (bmp != null && rotation != 0) {
                        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        if (rotated != bmp) bmp.recycle()
                        rotated
                    } else {
                        bmp
                    }
                } catch (e2: Exception) {
                    Log.w("CameraStreamManager", "YUV conversion failed: ${e2.message}")
                    null
                }
            }

            if (originalBitmap != null) {
                val targetWidth = 480
                val scale = targetWidth.toFloat() / originalBitmap.width.coerceAtLeast(1)
                val newWidth = (originalBitmap.width * scale).toInt().coerceAtLeast(1)
                val newHeight = (originalBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)

                val scaledOut = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, scaledOut)
                val base64 = Base64.encodeToString(scaledOut.toByteArray(), Base64.NO_WRAP)

                onFrameCaptured(base64)
                if (scaledBitmap != originalBitmap) scaledBitmap.recycle()
                originalBitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("CameraStreamManager", "Error processing camera frame", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + image.width * image.height / 2)

        yBuffer.get(nv21, 0, ySize)

        val uvPixelStride = image.planes[1].pixelStride
        if (uvPixelStride == 1) {
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
        } else {
            val vByte = ByteArray(vSize)
            val uByte = ByteArray(uSize)
            vBuffer.get(vByte)
            uBuffer.get(uByte)

            var offset = ySize
            val chromaWidth = image.width / 2
            val chromaHeight = image.height / 2

            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    val vIndex = row * image.planes[2].rowStride + col * uvPixelStride
                    val uIndex = row * image.planes[1].rowStride + col * uvPixelStride

                    if (vIndex < vByte.size && offset < nv21.size) {
                        nv21[offset++] = vByte[vIndex]
                    }
                    if (uIndex < uByte.size && offset < nv21.size) {
                        nv21[offset++] = uByte[uIndex]
                    }
                }
            }
        }
        return nv21
    }

    @SuppressLint("MissingPermission")
    fun startAudioStream(onAudioLevel: (Float) -> Unit) {
        if (isAudioStreaming.get()) return
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w("CameraStreamManager", "Record audio permission not granted.")
            return
        }

        try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isAudioStreaming.set(true)
            audioJob = scope.launch {
                val buffer = ShortArray(bufferSize)
                while (isActive && isAudioStreaming.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        var maxAmp = 0
                        for (i in 0 until read) {
                            val abs = Math.abs(buffer[i].toInt())
                            if (abs > maxAmp) maxAmp = abs
                        }
                        val normalized = (maxAmp / 32768f).coerceIn(0f, 1f)
                        onAudioLevel(normalized)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("CameraStreamManager", "Audio permission missing", e)
        } catch (e: Exception) {
            Log.e("CameraStreamManager", "Failed to start audio record", e)
        }
    }

    fun stopCameraStream() {
        isCameraStreaming.set(false)
        try {
            cameraProvider?.unbindAll()
            streamLifecycleOwner?.markDestroyed()
            streamLifecycleOwner = null
        } catch (e: Exception) {
            Log.w("CameraStreamManager", "Error unbinding camera: ${e.message}")
        }
    }

    fun stopAudioStream() {
        isAudioStreaming.set(false)
        try {
            audioJob?.cancel()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w("CameraStreamManager", "Error releasing audio record: ${e.message}")
        }
    }

    fun stopStreaming() {
        stopCameraStream()
        stopAudioStream()
    }
}
