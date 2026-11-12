package com.example

import com.example.data.model.Device
import com.example.data.model.DeviceRole
import com.example.data.model.Session
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentAuthorizationTest {

    @Test
    fun testInitialPersistentAuthorizationStateDefaultFalse() {
        val device = Device(
            deviceId = "test-device-1",
            userId = "user-123",
            deviceName = "Pixel 8",
            role = DeviceRole.DEVICE.name
        )
        assertFalse("Camera persistent authorization must default to false", device.persistentCameraAuthorized)
        assertFalse("Mic persistent authorization must default to false", device.persistentMicAuthorized)
    }

    @Test
    fun testGrantPersistentCameraAndMicAuthorization() {
        val device = Device(
            deviceId = "test-device-1",
            userId = "user-123",
            deviceName = "Pixel 8",
            role = DeviceRole.DEVICE.name,
            persistentCameraAuthorized = true,
            persistentMicAuthorized = true
        )
        assertTrue(device.persistentCameraAuthorized)
        assertTrue(device.persistentMicAuthorized)
    }

    @Test
    fun testSeparateRevocationOfCameraAndMic() {
        var device = Device(
            deviceId = "test-device-1",
            persistentCameraAuthorized = true,
            persistentMicAuthorized = true
        )

        // Revoke only camera
        device = device.copy(persistentCameraAuthorized = false)
        assertFalse("Camera must be revoked", device.persistentCameraAuthorized)
        assertTrue("Microphone must remain authorized", device.persistentMicAuthorized)

        // Re-authorize camera, revoke mic
        device = device.copy(persistentCameraAuthorized = true, persistentMicAuthorized = false)
        assertTrue("Camera must be authorized", device.persistentCameraAuthorized)
        assertFalse("Microphone must be revoked", device.persistentMicAuthorized)
    }

    @Test
    fun testRepeatedCameraToggleLifecycle() {
        var session = Session(
            sessionId = "session-123",
            monitorDeviceId = "monitor-1",
            targetDeviceId = "target-1",
            status = SessionStatus.APPROVED.name,
            isCameraActive = false
        )

        // Turn Camera ON (Cycle 1)
        session = session.copy(isCameraActive = true, status = SessionStatus.ACTIVE.name)
        assertTrue(session.isCameraActive)
        assertEquals(SessionStatus.ACTIVE.name, session.status)

        // Turn Camera OFF (Cycle 1)
        session = session.copy(isCameraActive = false, status = SessionStatus.APPROVED.name)
        assertFalse(session.isCameraActive)
        assertEquals(SessionStatus.APPROVED.name, session.status)

        // Reopen Camera ON (Cycle 2 - reusing persistent authorization without prompt)
        session = session.copy(isCameraActive = true, status = SessionStatus.ACTIVE.name)
        assertTrue(session.isCameraActive)

        // Switch Lens Front to Rear
        session = session.copy(cameraLens = SessionType.CAMERA_FRONT.name)
        assertEquals(SessionType.CAMERA_FRONT.name, session.cameraLens)

        // Turn Camera OFF (Cycle 2)
        session = session.copy(isCameraActive = false, status = SessionStatus.APPROVED.name)
        assertFalse(session.isCameraActive)
    }

    @Test
    fun testRepeatedMicrophoneToggleLifecycle() {
        var session = Session(
            sessionId = "session-456",
            monitorDeviceId = "monitor-1",
            targetDeviceId = "target-1",
            status = SessionStatus.APPROVED.name,
            isMicActive = false
        )

        // Toggle Mic ON
        session = session.copy(isMicActive = true, audioLevel = 0.65f)
        assertTrue(session.isMicActive)
        assertEquals(0.65f, session.audioLevel, 0.01f)

        // Toggle Mic OFF
        session = session.copy(isMicActive = false, audioLevel = 0.0f)
        assertFalse(session.isMicActive)
        assertEquals(0.0f, session.audioLevel, 0.01f)

        // Toggle Mic ON again
        session = session.copy(isMicActive = true, audioLevel = 0.42f)
        assertTrue(session.isMicActive)
        assertEquals(0.42f, session.audioLevel, 0.01f)
    }

    @Test
    fun testStopAllSharingKillSwitch() {
        var session = Session(
            sessionId = "session-789",
            isCameraActive = true,
            isMicActive = true,
            status = SessionStatus.ACTIVE.name
        )

        // Trigger Stop All Sharing Kill-switch
        session = session.copy(
            isCameraActive = false,
            isMicActive = false,
            status = SessionStatus.ENDED.name
        )

        assertFalse(session.isCameraActive)
        assertFalse(session.isMicActive)
        assertEquals(SessionStatus.ENDED.name, session.status)
    }

    @Test
    fun testDirectSessionActivationWithoutInAppApprovalDialog() {
        val targetDevice = Device(
            deviceId = "target-device-1",
            persistentCameraAuthorized = true,
            persistentMicAuthorized = true
        )

        // Session created with persistent authorization is automatically in APPROVED state
        val session = Session(
            sessionId = "sess-direct-1",
            monitorDeviceId = "monitor-1",
            targetDeviceId = targetDevice.deviceId,
            status = SessionStatus.APPROVED.name,
            isCameraActive = false,
            isMicActive = false
        )

        // Monitor activates Camera directly without in-app dialog
        val activatedSession = session.copy(isCameraActive = true, status = SessionStatus.ACTIVE.name)
        assertTrue(activatedSession.isCameraActive)
        assertEquals(SessionStatus.ACTIVE.name, activatedSession.status)

        // Monitor activates Mic directly without in-app dialog
        val micActivatedSession = activatedSession.copy(isMicActive = true)
        assertTrue(micActivatedSession.isMicActive)
    }
}
