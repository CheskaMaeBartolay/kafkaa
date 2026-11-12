package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class DeviceRole {
    MONITOR, // CP A
    DEVICE   // CP B
}

enum class SessionType {
    CAMERA_REAR,
    CAMERA_FRONT,
    AUDIO_ONLY
}

enum class SessionStatus {
    REQUESTED,
    APPROVED,
    REJECTED,
    ACTIVE,
    ENDED
}

@IgnoreExtraProperties
data class LocationData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = 0L,
    val address: String = ""
)

@IgnoreExtraProperties
data class Device(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val deviceName: String = "",
    val model: String = "",
    val role: String = DeviceRole.DEVICE.name,
    val pairedWithDeviceId: String = "",
    val isOnline: Boolean = true,
    @ServerTimestamp val lastSeen: Timestamp? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val persistentCameraAuthorized: Boolean = false,
    val persistentMicAuthorized: Boolean = false,
    val currentLocation: LocationData? = null,
    val activeSessionId: String = "",
    val unpairedByRemote: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PairingCode(
    @DocumentId val id: String = "",
    val code: String = "",
    val monitorDeviceId: String = "",
    val monitorUserId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val expiresAtMillis: Long = 0L,
    val status: String = "PENDING", // PENDING, CLAIMED, EXPIRED
    val targetDeviceId: String = "",
    val targetDeviceName: String = ""
)

@IgnoreExtraProperties
data class Session(
    @DocumentId val id: String = "",
    val sessionId: String = "",
    val monitorDeviceId: String = "",
    val targetDeviceId: String = "",
    val monitorUserId: String = "",
    val type: String = SessionType.CAMERA_REAR.name,
    val status: String = SessionStatus.REQUESTED.name,
    val isCameraActive: Boolean = false,
    val isMicActive: Boolean = false,
    val cameraLens: String = SessionType.CAMERA_REAR.name,
    val audioLevel: Float = 0f,
    val offerSdp: String = "",
    val answerSdp: String = "",
    val lastVideoFrameBase64: String = "",
    val frameTimestamp: Long = 0L,
    val errorMessage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class IceCandidateModel(
    @DocumentId val id: String = "",
    val candidateId: String = "",
    val senderRole: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Int = 0,
    val sdp: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class SessionLog(
    val id: String,
    val type: SessionType,
    val targetDeviceName: String,
    val startedAt: Long,
    val durationSeconds: Long
)
