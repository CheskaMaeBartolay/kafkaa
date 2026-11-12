package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.camera.CameraStreamManager
import com.example.data.model.LocationData
import com.example.data.model.Session
import com.example.data.model.SessionStatus
import com.example.data.repository.HomeSafeRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HomeSafeForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var repository: HomeSafeRepository
    private lateinit var cameraManager: CameraStreamManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var currentDeviceId: String? = null
    private var currentSessionId: String? = null

    private var deviceJob: Job? = null
    private var sessionJob: Job? = null
    private var locationCallback: LocationCallback? = null

    // Track active capture states
    private var isCameraCapturing = false
    private var isMicCapturing = false
    private var currentActiveLens = ""

    companion object {
        const val CHANNEL_ID = "homesafe_active_sharing"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_SERVICE = "com.example.homesafe.START_SERVICE"
        const val ACTION_STOP_ALL = "com.example.homesafe.STOP_ALL"
        const val ACTION_STOP_SHARING = "com.example.homesafe.STOP_SHARING"

        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_SESSION_ID = "extra_session_id"

        fun startService(context: Context, deviceId: String, sessionId: String? = null) {
            try {
                val intent = Intent(context, HomeSafeForegroundService::class.java).apply {
                    action = ACTION_START_SERVICE
                    putExtra(EXTRA_DEVICE_ID, deviceId)
                    if (sessionId != null) {
                        putExtra(EXTRA_SESSION_ID, sessionId)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("HomeSafeService", "Failed to start service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            try {
                val intent = Intent(context, HomeSafeForegroundService::class.java).apply {
                    action = ACTION_STOP_SHARING
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("HomeSafeService", "Failed to stop service: ${e.message}")
            }
        }

        fun revokeAllAndStop(context: Context, deviceId: String) {
            try {
                val intent = Intent(context, HomeSafeForegroundService::class.java).apply {
                    action = ACTION_STOP_ALL
                    putExtra(EXTRA_DEVICE_ID, deviceId)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("HomeSafeService", "Failed to revoke and stop service: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = HomeSafeRepository(applicationContext)
        cameraManager = CameraStreamManager(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kafka::StreamingWakeLock")?.apply {
                acquire(2 * 60 * 60 * 1000L) // 2 hours safety timeout
            }
        } catch (e: Exception) {
            Log.w("HomeSafeService", "WakeLock acquire error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_ALL) {
            val dId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: currentDeviceId
            performRevokeAllAndStop(dId)
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP_SHARING) {
            stopSharingAndShutdown()
            return START_NOT_STICKY
        }

        intent?.getStringExtra(EXTRA_DEVICE_ID)?.let { dId ->
            currentDeviceId = dId
        }
        intent?.getStringExtra(EXTRA_SESSION_ID)?.let { sId ->
            currentSessionId = sId
        }

        startForegroundWithCompositeTypes(
            title = "Kafka — Background Service",
            text = "Kafka background connection active."
        )

        // Start background location updates if permission granted
        startLocationUpdates()

        // Start listening to Device & Session in Firestore
        currentDeviceId?.let { dId ->
            attachDeviceListener(dId)
        }

        return START_STICKY
    }

    private fun computeServiceType(cameraActive: Boolean = false, micActive: Boolean = false): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var serviceType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        val hasLocation = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCamera = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasAudio = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocation) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasCamera && cameraActive && (com.example.MainActivity.isActivityVisible || isCameraCapturing)) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (hasAudio && micActive) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return serviceType
    }

    private fun startForegroundWithCompositeTypes(
        title: String,
        text: String,
        cameraActive: Boolean = false,
        micActive: Boolean = false
    ) {
        val notification = buildPersistentNotification(title, text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = computeServiceType(cameraActive, micActive)
                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("HomeSafeService", "startForeground error, trying fallback", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                Log.e("HomeSafeService", "Fallback startForeground failed", e2)
            }
        }
    }

    private fun updateNotification(
        statusText: String,
        cameraActive: Boolean = isCameraCapturing,
        micActive: Boolean = isMicCapturing
    ) {
        val notif = buildPersistentNotification("Kafka — Active Sharing", statusText)
        val manager = getSystemService(NotificationManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = computeServiceType(cameraActive, micActive)
                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notif, serviceType)
                } else {
                    manager.notify(NOTIFICATION_ID, notif)
                }
            } else {
                manager.notify(NOTIFICATION_ID, notif)
            }
        } catch (e: Exception) {
            try {
                manager.notify(NOTIFICATION_ID, notif)
            } catch (e2: Exception) {
                Log.w("HomeSafeService", "Notify failed: ${e2.message}")
            }
        }
    }

    private fun buildPersistentNotification(title: String, text: String, isStreaming: Boolean = (isCameraCapturing || isMicCapturing)): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true) // Non-dismissible
            .setSilent(true) // Silent: no sound, no popup alert
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPendingIntent)

        if (isStreaming) {
            val stopSharingIntent = Intent(this, HomeSafeForegroundService::class.java).apply {
                action = ACTION_STOP_SHARING
            }
            val stopSharingPendingIntent = PendingIntent.getService(
                this, 2, stopSharingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_notification, "STOP SHARING", stopSharingPendingIntent)
        } else {
            val stopAllIntent = Intent(this, HomeSafeForegroundService::class.java).apply {
                action = ACTION_STOP_ALL
                putExtra(EXTRA_DEVICE_ID, currentDeviceId)
            }
            val stopAllPendingIntent = PendingIntent.getService(
                this, 1, stopAllIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_notification, "DISCONNECT", stopAllPendingIntent)
        }

        return builder.build()
    }

    private fun attachDeviceListener(deviceId: String) {
        deviceJob?.cancel()
        deviceJob = serviceScope.launch {
            repository.observeDevice(deviceId).collect { device ->
                if (device == null) return@collect
                val activeSessionId = device.activeSessionId
                if (activeSessionId.isNotBlank() && currentSessionId != activeSessionId) {
                    currentSessionId = activeSessionId
                }
            }
        }

        sessionJob?.cancel()
        sessionJob = serviceScope.launch {
            repository.observeActiveSessionForDevice(deviceId).collect { activeSession ->
                if (activeSession != null) {
                    currentSessionId = activeSession.sessionId
                    handleSessionUpdate(activeSession)
                } else {
                    stopCamera()
                    stopAudio()
                }
            }
        }
    }

    private fun handleSessionUpdate(session: Session) {
        if (session.status == SessionStatus.ENDED.name || session.status == SessionStatus.REJECTED.name) {
            stopCamera()
            stopAudio()
            return
        }

        val shouldCameraRun = session.isCameraActive
        val shouldMicRun = session.isMicActive
        val requestedLens = session.cameraLens.ifBlank { session.type }.ifBlank { "rear" }

        // Manage Camera
        if (shouldCameraRun) {
            val useFront = requestedLens.contains("front", ignoreCase = true)
            // Android SDK 36 Background Camera Restriction Check:
            // Camera can only be started or promoted if the target activity is currently visible,
            // or if the camera is already actively capturing.
            if (!com.example.MainActivity.isActivityVisible && !isCameraCapturing) {
                Log.w("HomeSafeService", "Camera start disallowed while target app is backgrounded (Android SDK 36 restriction).")
                serviceScope.launch {
                    repository.updateSessionStatusAndError(
                        session.sessionId,
                        SessionStatus.REQUESTED.name,
                        "Open the target app and approve sharing (Android background restriction)."
                    )
                }
                updateNotification("Camera stream requested. Open Kafka to approve.", cameraActive = false, micActive = isMicCapturing)
                return
            }

            if (!isCameraCapturing || currentActiveLens != requestedLens) {
                startCamera(session.sessionId, useFront, requestedLens)
            }
        } else {
            if (isCameraCapturing) {
                stopCamera()
            }
        }

        // Manage Microphone
        if (shouldMicRun) {
            if (!isMicCapturing) {
                startAudio(session.sessionId)
            }
        } else {
            if (isMicCapturing) {
                stopAudio()
            }
        }
    }

    private fun startCamera(sessionId: String, useFront: Boolean, lensName: String) {
        stopCamera()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val err = "Camera permission not granted on target device."
            Log.w("HomeSafeService", err)
            isCameraCapturing = false
            serviceScope.launch { repository.pushCameraError(sessionId, err) }
            return
        }
        currentActiveLens = lensName
        isCameraCapturing = true

        updateNotification("Kafka — Camera Sharing Active", cameraActive = true, micActive = isMicCapturing)

        try {
            cameraManager.startCameraStream(
                lifecycleOwner = null,
                previewView = null,
                useFrontCamera = useFront,
                deviceName = currentDeviceId ?: "Companion Device",
                onFrameCaptured = { base64 ->
                    serviceScope.launch {
                        try {
                            repository.pushVideoFrame(sessionId, base64)
                        } catch (e: Exception) {
                            Log.e("HomeSafeService", "Failed to push frame", e)
                        }
                    }
                },
                onError = { errorMsg, throwable ->
                    Log.e("HomeSafeService", "CameraX stream error: $errorMsg", throwable)
                    isCameraCapturing = false
                    updateNotification("Kafka background connection active.", cameraActive = false, micActive = isMicCapturing)
                    serviceScope.launch {
                        repository.pushCameraError(sessionId, errorMsg)
                    }
                }
            )
            Log.d("HomeSafeService", "CameraX capture started ($lensName)")
        } catch (e: Exception) {
            Log.e("HomeSafeService", "Error starting CameraX stream", e)
            isCameraCapturing = false
            serviceScope.launch {
                repository.pushCameraError(sessionId, "Camera startup failed: ${e.localizedMessage}")
            }
        }
    }

    private fun stopCamera() {
        if (isCameraCapturing) {
            isCameraCapturing = false
            currentActiveLens = ""
            try {
                cameraManager.stopCameraStream()
                Log.d("HomeSafeService", "CameraX capture stopped")
            } catch (e: Exception) {
                Log.w("HomeSafeService", "Error stopping camera: ${e.message}")
            }
            updateNotification("Kafka background connection active.", cameraActive = false, micActive = isMicCapturing)
        }
    }

    private fun startAudio(sessionId: String) {
        if (isMicCapturing) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("HomeSafeService", "Audio permission missing, cannot start audio stream")
            isMicCapturing = false
            return
        }
        isMicCapturing = true

        // Promote foreground service to microphone type
        updateNotification("Kafka — Background Stream Active", cameraActive = isCameraCapturing, micActive = true)

        try {
            cameraManager.startAudioStream { amplitude ->
                serviceScope.launch {
                    try {
                        repository.pushAudioLevel(sessionId, amplitude)
                    } catch (e: Exception) {
                        // ignore transient audio updates
                    }
                }
            }
            Log.d("HomeSafeService", "AudioRecord mic capture started")
        } catch (e: Exception) {
            Log.e("HomeSafeService", "Error starting Audio stream", e)
            isMicCapturing = false
        }
    }

    private fun stopAudio() {
        if (isMicCapturing) {
            isMicCapturing = false
            try {
                cameraManager.stopAudioStream()
                Log.d("HomeSafeService", "AudioRecord mic capture stopped")
            } catch (e: Exception) {
                Log.w("HomeSafeService", "Error stopping audio: ${e.message}")
            }
            updateNotification("Kafka background connection active.", cameraActive = isCameraCapturing, micActive = false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15000L)
                .setMinUpdateIntervalMillis(10000L)
                .build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val dId = currentDeviceId ?: return
                    serviceScope.launch {
                        try {
                            repository.updateLocationOnDevice(
                                dId,
                                LocationData(
                                    latitude = location.latitude,
                                    longitude = location.longitude,
                                    accuracy = location.accuracy,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        } catch (e: Exception) {
                            Log.w("HomeSafeService", "Failed to update location: ${e.message}")
                        }
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: Exception) {
            Log.w("HomeSafeService", "Failed to start location updates: ${e.message}")
        }
    }

    private fun performRevokeAllAndStop(deviceId: String?) {
        serviceScope.launch {
            val dId = deviceId ?: currentDeviceId
            if (!dId.isNullOrBlank()) {
                try {
                    repository.revokeAllAndStop(dId)
                } catch (e: Exception) {
                    Log.e("HomeSafeService", "Error in revokeAllAndStop", e)
                }
            }
            stopCamera()
            stopAudio()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSharingAndShutdown() {
        stopCamera()
        stopAudio()
        val sId = currentSessionId
        val dId = currentDeviceId
        if (!sId.isNullOrBlank() && !dId.isNullOrBlank()) {
            serviceScope.launch {
                try {
                    repository.endSession(sId, dId)
                } catch (e: Exception) {
                    Log.e("HomeSafeService", "Failed to end session on stop", e)
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background device connection service."
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        deviceJob?.cancel()
        sessionJob?.cancel()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        stopCamera()
        stopAudio()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w("HomeSafeService", "Error releasing wake lock: ${e.message}")
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
