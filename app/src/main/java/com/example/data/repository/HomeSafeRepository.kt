package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.Device
import com.example.data.model.DeviceRole
import com.example.data.model.LocationData
import com.example.data.model.PairingCode
import com.example.data.model.Session
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.UUID

class HomeSafeRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val random = SecureRandom()

    suspend fun registerOrUpdateDevice(device: Device) {
        val uid = device.userId.ifBlank { device.deviceId }
        val docRef = db.collection("devices").document(device.deviceId)
        val data = hashMapOf(
            "deviceId" to device.deviceId,
            "userId" to uid,
            "deviceName" to device.deviceName,
            "model" to device.model,
            "role" to device.role,
            "pairedWithDeviceId" to device.pairedWithDeviceId,
            "isOnline" to true,
            "lastSeen" to FieldValue.serverTimestamp(),
            "permissions" to device.permissions,
            "persistentCameraAuthorized" to device.persistentCameraAuthorized,
            "persistentMicAuthorized" to device.persistentMicAuthorized,
            "currentLocation" to device.currentLocation,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data, SetOptions.merge()).await()
    }

    fun observeDevice(deviceId: String): Flow<Device?> {
        return db.collection("devices").document(deviceId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) snap.toObject(Device::class.java) else null
            }
    }

    fun observePairedDevicesForMonitor(monitorDeviceId: String): Flow<List<Device>> {
        return db.collection("devices")
            .whereEqualTo("pairedWithDeviceId", monitorDeviceId)
            .snapshots()
            .map { snap ->
                snap.toObjects(Device::class.java)
            }
    }

    suspend fun generatePairingCode(monitorDeviceId: String): PairingCode {
        val uid = monitorDeviceId
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val codeBuilder = StringBuilder()
        repeat(6) {
            codeBuilder.append(chars[random.nextInt(chars.length)])
        }
        val code = codeBuilder.toString()
        val expiresAt = System.currentTimeMillis() + (10 * 60 * 1000) // 10 minutes

        val pairingCode = PairingCode(
            code = code,
            monitorDeviceId = monitorDeviceId,
            monitorUserId = uid,
            expiresAtMillis = expiresAt,
            status = "PENDING"
        )

        val data = hashMapOf(
            "code" to code,
            "monitorDeviceId" to monitorDeviceId,
            "monitorUserId" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAtMillis" to expiresAt,
            "status" to "PENDING",
            "targetDeviceId" to "",
            "targetDeviceName" to ""
        )

        db.collection("pairing_codes").document(code).set(data).await()
        return pairingCode
    }

    fun observePairingCode(code: String): Flow<PairingCode?> {
        return db.collection("pairing_codes").document(code)
            .snapshots()
            .map { snap ->
                if (snap.exists()) snap.toObject(PairingCode::class.java) else null
            }
    }

    suspend fun validateAndClaimPairingCode(
        code: String,
        targetDeviceId: String,
        targetDeviceName: String
    ): Result<PairingCode> {
        return try {
            val upperCode = code.trim().uppercase()
            val docRef = db.collection("pairing_codes").document(upperCode)
            val snapshot = docRef.get().await()

            if (!snapshot.exists()) {
                return Result.failure(IllegalArgumentException("Invalid pairing code. Please verify and try again."))
            }

            val pairingCode = snapshot.toObject(PairingCode::class.java)
                ?: return Result.failure(IllegalStateException("Failed to parse pairing code data."))

            if (pairingCode.status == "CLAIMED") {
                return Result.failure(IllegalStateException("This pairing code has already been used."))
            }

            if (pairingCode.expiresAtMillis > 0 && System.currentTimeMillis() > pairingCode.expiresAtMillis) {
                docRef.update("status", "EXPIRED").await()
                return Result.failure(IllegalStateException("Pairing code has expired. Please request a new code from Monitor."))
            }

            // Claim the code
            docRef.update(
                mapOf(
                    "status" to "CLAIMED",
                    "targetDeviceId" to targetDeviceId,
                    "targetDeviceName" to targetDeviceName
                )
            ).await()

            // Update both devices' pairing relationships
            val monitorDoc = db.collection("devices").document(pairingCode.monitorDeviceId)
            val targetDoc = db.collection("devices").document(targetDeviceId)

            targetDoc.set(
                mapOf(
                    "pairedWithDeviceId" to pairingCode.monitorDeviceId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            monitorDoc.set(
                mapOf(
                    "pairedWithDeviceId" to targetDeviceId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            Result.success(pairingCode.copy(status = "CLAIMED", targetDeviceId = targetDeviceId))
        } catch (e: Exception) {
            Log.e("HomeSafeRepo", "validateAndClaimPairingCode failed", e)
            Result.failure(e)
        }
    }

    suspend fun updateDevicePermissions(deviceId: String, permissions: Map<String, Boolean>) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "permissions" to permissions,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun setPersistentCameraAuth(deviceId: String, value: Boolean) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "persistentCameraAuthorized" to value,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        if (!value) {
            val devDoc = db.collection("devices").document(deviceId).get().await()
            val activeSessionId = devDoc.getString("activeSessionId")
            if (!activeSessionId.isNullOrBlank()) {
                db.collection("sessions").document(activeSessionId).update(
                    mapOf(
                        "isCameraActive" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }
    }

    suspend fun setPersistentMicAuth(deviceId: String, value: Boolean) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "persistentMicAuthorized" to value,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        if (!value) {
            val devDoc = db.collection("devices").document(deviceId).get().await()
            val activeSessionId = devDoc.getString("activeSessionId")
            if (!activeSessionId.isNullOrBlank()) {
                db.collection("sessions").document(activeSessionId).update(
                    mapOf(
                        "isMicActive" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }
    }

    suspend fun setCameraActive(sessionId: String, active: Boolean, lens: String = "rear") {
        val status = if (active) SessionStatus.ACTIVE.name else SessionStatus.APPROVED.name
        val sessionType = if (lens.equals("front", ignoreCase = true)) SessionType.CAMERA_FRONT.name else SessionType.CAMERA_REAR.name
        db.collection("sessions").document(sessionId).set(
            mapOf(
                "isCameraActive" to active,
                "cameraLens" to sessionType,
                "type" to sessionType,
                "status" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun setMicActive(sessionId: String, active: Boolean) {
        db.collection("sessions").document(sessionId).set(
            mapOf(
                "isMicActive" to active,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }


    suspend fun updateLocationOnDevice(deviceId: String, location: LocationData) {
        updateDeviceLocation(deviceId, location)
    }

    suspend fun revokeAllAndStop(deviceId: String) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "persistentCameraAuthorized" to false,
                "persistentMicAuthorized" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        val devDoc = db.collection("devices").document(deviceId).get().await()
        val activeSessionId = devDoc.getString("activeSessionId")
        if (!activeSessionId.isNullOrBlank()) {
            endSession(activeSessionId, deviceId)
        }
    }

    suspend fun updatePersistentAuthorization(
        deviceId: String,
        cameraAuth: Boolean,
        micAuth: Boolean
    ) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "persistentCameraAuthorized" to cameraAuth,
                "persistentMicAuthorized" to micAuth,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()

        // If camera authorization was revoked, stop any camera streaming
        if (!cameraAuth) {
            val devDoc = db.collection("devices").document(deviceId).get().await()
            val activeSessionId = devDoc.getString("activeSessionId")
            if (!activeSessionId.isNullOrBlank()) {
                db.collection("sessions").document(activeSessionId).update(
                    mapOf(
                        "isCameraActive" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }

        // If mic authorization was revoked, stop any mic streaming
        if (!micAuth) {
            val devDoc = db.collection("devices").document(deviceId).get().await()
            val activeSessionId = devDoc.getString("activeSessionId")
            if (!activeSessionId.isNullOrBlank()) {
                db.collection("sessions").document(activeSessionId).update(
                    mapOf(
                        "isMicActive" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        }
    }

    suspend fun updateDeviceLocation(deviceId: String, location: LocationData) {
        db.collection("devices").document(deviceId).set(
            mapOf(
                "currentLocation" to location,
                "lastSeen" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun updateDeviceHeartbeat(deviceId: String, isOnline: Boolean) {
        try {
            db.collection("devices").document(deviceId).set(
                mapOf(
                    "isOnline" to isOnline,
                    "lastSeen" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "Failed to update heartbeat: ${e.message}")
        }
    }

    suspend fun getOrCreateSessionForMonitor(
        monitorDeviceId: String,
        targetDeviceId: String,
        initialLens: SessionType = SessionType.CAMERA_REAR
    ): Session {
        val uid = monitorDeviceId
        // Check target device authorization
        val targetSnap = db.collection("devices").document(targetDeviceId).get().await()
        val targetDevice = targetSnap.toObject(Device::class.java)

        val existingSessionId = targetDevice?.activeSessionId
        if (!existingSessionId.isNullOrBlank()) {
            val sessionSnap = db.collection("sessions").document(existingSessionId).get().await()
            if (sessionSnap.exists()) {
                val existingSession = sessionSnap.toObject(Session::class.java)
                if (existingSession != null && existingSession.status != SessionStatus.ENDED.name) {
                    return existingSession
                }
            }
        }

        // Create new session
        val isPreAuthorized = (targetDevice?.persistentCameraAuthorized == true) || (targetDevice?.persistentMicAuthorized == true)
        val initialStatus = if (isPreAuthorized) SessionStatus.APPROVED.name else SessionStatus.REQUESTED.name

        val sessionId = UUID.randomUUID().toString()
        val newSession = Session(
            sessionId = sessionId,
            monitorDeviceId = monitorDeviceId,
            targetDeviceId = targetDeviceId,
            monitorUserId = uid,
            type = initialLens.name,
            status = initialStatus,
            cameraLens = initialLens.name,
            isCameraActive = false,
            isMicActive = false
        )

        db.collection("sessions").document(sessionId).set(newSession).await()
        db.collection("devices").document(targetDeviceId).set(mapOf("activeSessionId" to sessionId), SetOptions.merge()).await()
        return newSession
    }

    suspend fun toggleCamera(
        sessionId: String,
        targetDeviceId: String,
        active: Boolean,
        lens: SessionType = SessionType.CAMERA_REAR
    ) {
        val status = if (active) SessionStatus.ACTIVE.name else SessionStatus.APPROVED.name
        db.collection("sessions").document(sessionId).set(
            mapOf(
                "isCameraActive" to active,
                "cameraLens" to lens.name,
                "type" to lens.name,
                "status" to status,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun toggleMicrophone(
        sessionId: String,
        targetDeviceId: String,
        active: Boolean
    ) {
        db.collection("sessions").document(sessionId).set(
            mapOf(
                "isMicActive" to active,
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    suspend fun requestSession(
        monitorDeviceId: String,
        targetDeviceId: String,
        type: SessionType
    ): String {
        val session = getOrCreateSessionForMonitor(monitorDeviceId, targetDeviceId, type)
        // If pre-authorized, automatically activate camera
        val targetSnap = db.collection("devices").document(targetDeviceId).get().await()
        val isCamAuth = targetSnap.getBoolean("persistentCameraAuthorized") == true

        if (isCamAuth) {
            toggleCamera(session.sessionId, targetDeviceId, true, type)
        } else {
            db.collection("sessions").document(session.sessionId).update(
                mapOf(
                    "status" to SessionStatus.REQUESTED.name,
                    "type" to type.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        return session.sessionId
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActiveSessionForDevice(targetDeviceId: String): Flow<Session?> {
        return db.collection("devices").document(targetDeviceId)
            .snapshots()
            .map { snap -> snap.getString("activeSessionId") ?: "" }
            .flatMapLatest { sId ->
                if (sId.isNotBlank()) {
                    observeSession(sId)
                } else {
                    flowOf(null)
                }
            }
    }

    fun observeIncomingSession(targetDeviceId: String): Flow<Session?> {
        return db.collection("sessions")
            .whereEqualTo("targetDeviceId", targetDeviceId)
            .whereEqualTo("status", SessionStatus.REQUESTED.name)
            .snapshots()
            .map { snap ->
                snap.documents.firstOrNull()?.toObject(Session::class.java)
            }
    }

    fun observeActiveSessionsForTarget(targetDeviceId: String): Flow<List<Session>> {
        return db.collection("sessions")
            .whereEqualTo("targetDeviceId", targetDeviceId)
            .snapshots()
            .map { snap ->
                snap.toObjects(Session::class.java).filter {
                    it.status != SessionStatus.ENDED.name && it.status != SessionStatus.REJECTED.name
                }
            }
    }

    fun observeSession(sessionId: String): Flow<Session?> {
        return db.collection("sessions").document(sessionId)
            .snapshots()
            .map { snap ->
                if (snap.exists()) snap.toObject(Session::class.java) else null
            }
    }

    suspend fun approveSession(sessionId: String, targetDeviceId: String) {
        db.collection("sessions").document(sessionId).update(
            mapOf(
                "status" to SessionStatus.APPROVED.name,
                "isCameraActive" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
    }

    suspend fun rejectSession(sessionId: String, targetDeviceId: String) {
        db.collection("sessions").document(sessionId).update(
            mapOf(
                "status" to SessionStatus.REJECTED.name,
                "isCameraActive" to false,
                "isMicActive" to false,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()

        db.collection("devices").document(targetDeviceId).update(
            "activeSessionId", ""
        ).await()
    }

    suspend fun endSession(sessionId: String, targetDeviceId: String) {
        try {
            db.collection("sessions").document(sessionId).update(
                mapOf(
                    "status" to SessionStatus.ENDED.name,
                    "isCameraActive" to false,
                    "isMicActive" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "End session update error: ${e.message}")
        }

        try {
            db.collection("devices").document(targetDeviceId).update(
                "activeSessionId", ""
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "Clear active session error: ${e.message}")
        }
    }

    suspend fun updateSessionStatusAndError(sessionId: String, status: String, errorMessage: String) {
        try {
            db.collection("sessions").document(sessionId).set(
                mapOf(
                    "status" to status,
                    "errorMessage" to errorMessage,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "Failed to update session error: ${e.message}")
        }
    }

    suspend fun pushCameraError(sessionId: String, error: String) {
        try {
            db.collection("sessions").document(sessionId).set(
                mapOf(
                    "errorMessage" to error,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "Failed to push camera error: ${e.message}")
        }
    }

    suspend fun clearSessionError(sessionId: String) {
        try {
            db.collection("sessions").document(sessionId).set(
                mapOf(
                    "errorMessage" to "",
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.w("HomeSafeRepo", "Failed to clear session error: ${e.message}")
        }
    }

    suspend fun pushVideoFrame(sessionId: String, frameBase64: String) {
        try {
            db.collection("sessions").document(sessionId).set(
                mapOf(
                    "lastVideoFrameBase64" to frameBase64,
                    "frameTimestamp" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            // Ignore frame update failures if session ended
        }
    }

    suspend fun pushAudioLevel(sessionId: String, level: Float) {
        try {
            db.collection("sessions").document(sessionId).set(
                mapOf("audioLevel" to level),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            // Ignore telemetry error
        }
    }

    suspend fun unpairDevice(myDeviceId: String, pairedDeviceId: String) {
        try {
            db.collection("devices").document(myDeviceId).update(
                mapOf(
                    "pairedWithDeviceId" to "",
                    "activeSessionId" to "",
                    "persistentCameraAuthorized" to false,
                    "persistentMicAuthorized" to false,
                    "unpairedByRemote" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            if (pairedDeviceId.isNotBlank()) {
                db.collection("devices").document(pairedDeviceId).update(
                    mapOf(
                        "pairedWithDeviceId" to "",
                        "activeSessionId" to "",
                        "persistentCameraAuthorized" to false,
                        "persistentMicAuthorized" to false,
                        "unpairedByRemote" to true,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
        } catch (e: Exception) {
            Log.e("HomeSafeRepo", "unpairDevice error", e)
        }
    }

    suspend fun clearUnpairedByRemoteFlag(deviceId: String) {
        try {
            db.collection("devices").document(deviceId).update(
                "unpairedByRemote", false
            ).await()
        } catch (e: Exception) {
            // ignore
        }
    }

    suspend fun createQuickTestCompanionDevice(monitorDeviceId: String): Device {
        val uid = monitorDeviceId
        val companionId = "companion-${UUID.randomUUID().toString().substring(0, 8)}"
        val companionDevice = Device(
            deviceId = companionId,
            userId = uid,
            deviceName = "Target Device (CP B)",
            model = "Companion Phone • Pre-Authorized",
            role = DeviceRole.DEVICE.name,
            pairedWithDeviceId = monitorDeviceId,
            isOnline = true,
            persistentCameraAuthorized = true,
            persistentMicAuthorized = true,
            permissions = mapOf(
                "camera" to true,
                "microphone" to true,
                "location" to true,
                "notifications" to true
            ),
            currentLocation = LocationData(
                latitude = 14.5995,
                longitude = 120.9842,
                address = "Authorized Target Location",
                timestamp = System.currentTimeMillis()
            )
        )
        registerOrUpdateDevice(companionDevice)
        return companionDevice
    }
}
