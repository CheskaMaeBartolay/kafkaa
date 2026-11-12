package com.example.ui.screens.monitor

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Device
import com.example.data.model.Session
import com.example.data.model.SessionLog
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.data.repository.HomeSafeRepository
import com.example.ui.components.AudioWaveformMeter
import com.example.ui.components.OpenStreetMapCompose
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.StreamingCyan
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import androidx.compose.ui.res.painterResource
import com.example.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringDashboardScreen(
    monitorDeviceId: String,
    monitorDeviceName: String,
    repository: HomeSafeRepository,
    onPairNewDevice: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pairedDevices by repository.observePairedDevicesForMonitor(monitorDeviceId).collectAsState(initial = emptyList())

    // Active session state
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var activeTargetDeviceId by remember { mutableStateOf<String?>(null) }
    var activeLens by remember { mutableStateOf(SessionType.CAMERA_REAR) }
    var selectedDeviceForMap by remember { mutableStateOf<Device?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showUnpairDialogForDevice by remember { mutableStateOf<Device?>(null) }
    var isCameraViewerOpen by remember { mutableStateOf(false) }

    val sessionLogs = remember { mutableStateListOf<SessionLog>() }

    LaunchedEffect(monitorDeviceId) {
        repository.updateDeviceHeartbeat(monitorDeviceId, isOnline = true)
    }

    val activeSession by remember(activeSessionId) {
        if (!activeSessionId.isNullOrBlank()) {
            repository.observeSession(activeSessionId!!)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)

    val currentTargetDevice = remember(activeTargetDeviceId, pairedDevices) {
        pairedDevices.find { it.deviceId == activeTargetDeviceId }
    }

    LaunchedEffect(currentTargetDevice?.activeSessionId) {
        val devSessionId = currentTargetDevice?.activeSessionId
        if (!devSessionId.isNullOrBlank() && activeSessionId != devSessionId) {
            activeSessionId = devSessionId
        }
    }

    LaunchedEffect(activeSession?.status) {
        val status = activeSession?.status
        if (status == SessionStatus.ENDED.name || status == SessionStatus.REJECTED.name) {
            activeSessionId = null
            activeTargetDeviceId = null
        }
    }

    Scaffold(
        containerColor = MidnightBg,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, CyberEmerald, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_kafka_logo),
                            contentDescription = "Kafka Logo",
                            modifier = Modifier.size(38.dp),
                            contentScale = ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Kafka Monitor",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "CP A • $monitorDeviceName",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberEmerald
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showHistoryDialog = true }) {
                        Icon(Icons.Default.History, contentDescription = "History", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onPairNewDevice,
                containerColor = CyberEmerald,
                contentColor = Color(0xFF022C22),
                modifier = Modifier.testTag("pair_new_device_fab")
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Pair Device", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (pairedDevices.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .background(SurfaceDark, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = CyberEmerald,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "No Paired Devices Yet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Connect a companion device (CP B) to control camera and microphone streaming, or tap Quick Connect below to test immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 1-Tap Quick Connect Companion Device for immediate testing
                    Button(
                        onClick = {
                            scope.launch {
                                try {
                                    val companion = repository.createQuickTestCompanionDevice(monitorDeviceId)
                                    Toast.makeText(context, "Companion ${companion.deviceName} paired and pre-authorized!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Log.e("MonitorDashboard", "Failed to create companion device", e)
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("quick_connect_companion_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF022C22))
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect Companion Device (Quick Test)", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = onPairNewDevice,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("generate_pairing_code_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = StreamingCyan)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Generate 6-Digit Pairing Code", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Connected Target Devices (${pairedDevices.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(pairedDevices) { device ->
                        val isThisDeviceActive = activeTargetDeviceId == device.deviceId
                        val currentActiveSession = if (isThisDeviceActive) activeSession else null

                        PersistentMonitorDeviceCard(
                            device = device,
                            activeSession = currentActiveSession,
                            activeLens = activeLens,
                            repository = repository,
                            onToggleCamera = { active, lens ->
                                activeLens = lens
                                activeTargetDeviceId = device.deviceId
                                if (active) {
                                    isCameraViewerOpen = true
                                    Toast.makeText(context, "Opening live stream (${if (lens == SessionType.CAMERA_REAR) "REAR" else "FRONT"})...", Toast.LENGTH_SHORT).show()
                                } else {
                                    isCameraViewerOpen = false
                                }
                                scope.launch {
                                    try {
                                        val session = repository.getOrCreateSessionForMonitor(monitorDeviceId, device.deviceId, lens)
                                        activeSessionId = session.sessionId
                                        val isCamAuth = device.persistentCameraAuthorized
                                        if (active) {
                                            if (isCamAuth) {
                                                repository.setCameraActive(session.sessionId, true, lens.name)
                                            } else {
                                                repository.requestSession(monitorDeviceId, device.deviceId, lens)
                                                Toast.makeText(context, "Approval request sent to ${device.deviceName}", Toast.LENGTH_SHORT).show()
                                            }
                                            sessionLogs.add(
                                                SessionLog(
                                                    id = session.sessionId,
                                                    type = lens,
                                                    targetDeviceName = device.deviceName,
                                                    startedAt = System.currentTimeMillis(),
                                                    durationSeconds = 0
                                                )
                                            )
                                        } else {
                                            repository.setCameraActive(session.sessionId, false, lens.name)
                                            Toast.makeText(context, "Camera stream stopped", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MonitorDashboard", "Error toggling camera", e)
                                        Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onToggleMicrophone = { active ->
                                scope.launch {
                                    try {
                                        activeTargetDeviceId = device.deviceId
                                        val session = repository.getOrCreateSessionForMonitor(monitorDeviceId, device.deviceId, activeLens)
                                        activeSessionId = session.sessionId
                                        val isMicAuth = device.persistentMicAuthorized
                                        if (active) {
                                            if (isMicAuth) {
                                                repository.setMicActive(session.sessionId, true)
                                            } else {
                                                repository.requestSession(monitorDeviceId, device.deviceId, activeLens)
                                                Toast.makeText(context, "Mic approval request sent to ${device.deviceName}", Toast.LENGTH_SHORT).show()
                                            }
                                            Toast.makeText(context, "Microphone stream activated", Toast.LENGTH_SHORT).show()
                                        } else {
                                            repository.setMicActive(session.sessionId, false)
                                            Toast.makeText(context, "Microphone muted", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("MonitorDashboard", "Error toggling mic", e)
                                        Toast.makeText(context, "Mic error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onViewMap = { selectedDeviceForMap = device },
                            onUnpair = { showUnpairDialogForDevice = device }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }

            // Live Stream Viewer Overlay (Immediate responsive display)
            if (isCameraViewerOpen || (activeSessionId != null && activeSession?.isCameraActive == true)) {
                val currentDevice = pairedDevices.find { it.deviceId == activeTargetDeviceId }
                val targetName = currentDevice?.deviceName ?: "Companion Device"
                val displaySession = activeSession ?: remember(activeTargetDeviceId, activeLens) {
                    Session(
                        sessionId = activeSessionId ?: "active_session",
                        monitorDeviceId = monitorDeviceId,
                        targetDeviceId = activeTargetDeviceId ?: "",
                        type = activeLens.name,
                        status = SessionStatus.ACTIVE.name,
                        cameraLens = activeLens.name,
                        isCameraActive = true,
                        isMicActive = false
                    )
                }

                LiveStreamViewerOverlay(
                    session = displaySession,
                    sessionType = activeLens,
                    targetDeviceName = targetName,
                    onSwitchLens = { newLens ->
                        activeLens = newLens
                        scope.launch {
                            activeTargetDeviceId?.let { tId ->
                                activeSessionId?.let { sId ->
                                    repository.toggleCamera(sId, tId, true, newLens)
                                }
                            }
                        }
                    },
                    onToggleMic = { micOn ->
                        scope.launch {
                            activeTargetDeviceId?.let { tId ->
                                activeSessionId?.let { sId ->
                                    repository.toggleMicrophone(sId, tId, micOn)
                                }
                            }
                        }
                    },
                    onClose = {
                        isCameraViewerOpen = false
                        scope.launch {
                            val targetId = activeTargetDeviceId ?: activeSession?.targetDeviceId
                            val sId = activeSessionId ?: activeSession?.sessionId
                            if (!targetId.isNullOrBlank() && !sId.isNullOrBlank()) {
                                repository.toggleCamera(sId, targetId, false, activeLens)
                            }
                        }
                    }
                )
            }
        }
    }

    // Map BottomSheet
    if (selectedDeviceForMap != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedDeviceForMap = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${selectedDeviceForMap?.deviceName} • Live GPS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "OpenStreetMap Real-time Vector Tiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberEmerald
                        )
                    }
                    IconButton(onClick = { selectedDeviceForMap = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OpenStreetMapCompose(
                    location = selectedDeviceForMap?.currentLocation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    deviceName = selectedDeviceForMap?.deviceName ?: "Target"
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Unpair Dialog
    if (showUnpairDialogForDevice != null) {
        val target = showUnpairDialogForDevice!!
        AlertDialog(
            onDismissRequest = { showUnpairDialogForDevice = null },
            containerColor = SurfaceDark,
            title = { Text("Unpair ${target.deviceName}?", color = Color.White) },
            text = {
                Text(
                    "This will remove the trusted pairing and revoke all persistent authorizations for ${target.deviceName}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.unpairDevice(monitorDeviceId, target.deviceId)
                            showUnpairDialogForDevice = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertCoral)
                ) {
                    Text("Unpair Device")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showUnpairDialogForDevice = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // History Dialog
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Session History", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                if (sessionLogs.isEmpty()) {
                    Text("No monitoring sessions recorded yet during this session.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sessionLogs) { log ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SurfaceVariantDark,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(log.targetDeviceName, fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                                        Text(log.type.name, color = StreamingCyan, style = MaterialTheme.typography.labelSmall)
                                    }
                                    val dateStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.startedAt))
                                    Text(dateStr, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showHistoryDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald)) {
                    Text("Close", color = Color(0xFF022C22), fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun PersistentMonitorDeviceCard(
    device: Device,
    activeSession: Session?,
    activeLens: SessionType,
    repository: HomeSafeRepository,
    onToggleCamera: (Boolean, SessionType) -> Unit,
    onToggleMicrophone: (Boolean) -> Unit,
    onViewMap: () -> Unit,
    onUnpair: () -> Unit
) {
    // Observe device session directly to guarantee real-time session sync even across multiple devices
    val directSession by remember(device.activeSessionId) {
        if (device.activeSessionId.isNotBlank()) {
            repository.observeSession(device.activeSessionId)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)

    val effectiveSession = activeSession ?: directSession
    val isCameraOn = effectiveSession?.isCameraActive == true
    val isMicOn = effectiveSession?.isMicActive == true
    val isCamAuth = device.persistentCameraAuthorized
    val isMicAuth = device.persistentMicAuthorized

    var selectedLens by remember(activeLens) { mutableStateOf(activeLens) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (isCameraOn || isMicOn) CyberEmerald else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (device.isOnline) CyberEmerald else WarningAmber, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = device.deviceName.ifBlank { "Remote Device" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = device.model.ifBlank { "Android Device" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isCameraOn || isMicOn) AlertCoral.copy(alpha = 0.2f) else if (device.isOnline) CyberEmerald.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = if (isCameraOn || isMicOn) "STREAMING" else if (device.isOnline) "ONLINE" else "OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isCameraOn || isMicOn) AlertCoral else if (device.isOnline) CyberEmerald else WarningAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Persistent Authorization Badges
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCamAuth) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CyberEmerald.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PERSISTENT CAM", color = CyberEmerald, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
                if (isMicAuth) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StreamingCyan.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StreamingCyan.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = StreamingCyan, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PERSISTENT MIC", color = StreamingCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // CAMERA CONTROL CARD WITH PROMINENT BUTTON & LENS SELECTOR
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceVariantDark,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isCameraOn) CyberEmerald else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(if (isCameraOn) CyberEmerald.copy(alpha = 0.2f) else SurfaceDark, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                    contentDescription = null,
                                    tint = if (isCameraOn) CyberEmerald else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Camera Stream",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isCameraOn) "Live Stream Active (${selectedLens.name})" else if (isCamAuth) "Pre-Authorized • Instant Start" else "Ready to Connect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCameraOn) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isCameraOn) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AlertCoral
                            ) {
                                Text(
                                    "LIVE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Lens Selection Pills (Front / Rear)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isRear = selectedLens == SessionType.CAMERA_REAR
                        val isFront = selectedLens == SessionType.CAMERA_FRONT

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isRear) CyberEmerald.copy(alpha = 0.2f) else SurfaceDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isRear) CyberEmerald else Color.DarkGray
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedLens = SessionType.CAMERA_REAR
                                    if (isCameraOn) {
                                        onToggleCamera(true, SessionType.CAMERA_REAR)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isRear) CyberEmerald else Color.LightGray
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Rear Camera",
                                    fontSize = 12.sp,
                                    fontWeight = if (isRear) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isRear) CyberEmerald else Color.LightGray
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isFront) StreamingCyan.copy(alpha = 0.2f) else SurfaceDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isFront) StreamingCyan else Color.DarkGray
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedLens = SessionType.CAMERA_FRONT
                                    if (isCameraOn) {
                                        onToggleCamera(true, SessionType.CAMERA_FRONT)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.FlipCameraAndroid,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isFront) StreamingCyan else Color.LightGray
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Front Camera",
                                    fontSize = 12.sp,
                                    fontWeight = if (isFront) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isFront) StreamingCyan else Color.LightGray
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // PROMINENT DEDICATED CAMERA BUTTON
                    if (isCameraOn) {
                        Button(
                            onClick = {
                                onToggleCamera(true, selectedLens)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("view_stream_button_${device.deviceId}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberEmerald,
                                contentColor = Color(0xFF022C22)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "VIEW LIVE STREAM WINDOW",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = {
                            onToggleCamera(!isCameraOn, selectedLens)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("camera_button_${device.deviceId}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCameraOn) AlertCoral else CyberEmerald,
                            contentColor = if (isCameraOn) Color.White else Color(0xFF022C22)
                        )
                    ) {
                        Icon(
                            imageVector = if (isCameraOn) Icons.Default.VideocamOff else Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isCameraOn) "TURN CAMERA OFF" else "TURN CAMERA ON (${if (selectedLens == SessionType.CAMERA_REAR) "REAR" else "FRONT"})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // MICROPHONE CONTROL CARD WITH PROMINENT BUTTON & WAVEFORM
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = SurfaceVariantDark,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isMicOn) StreamingCyan else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(if (isMicOn) StreamingCyan.copy(alpha = 0.2f) else SurfaceDark, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isMicOn) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = null,
                                    tint = if (isMicOn) StreamingCyan else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Microphone Audio",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = if (isMicOn) "Live Listening Active" else if (isMicAuth) "Pre-Authorized • Instant Start" else "Ready to Connect",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isMicOn) StreamingCyan else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isMicOn) {
                            AudioWaveformMeter(
                                audioLevel = effectiveSession?.audioLevel ?: 0.35f,
                                isMicActive = true
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // PROMINENT DEDICATED MICROPHONE BUTTON
                    Button(
                        onClick = {
                            onToggleMicrophone(!isMicOn)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("microphone_button_${device.deviceId}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isMicOn) AlertCoral else StreamingCyan,
                            contentColor = if (isMicOn) Color.White else Color(0xFF083344)
                        )
                    ) {
                        Icon(
                            imageVector = if (isMicOn) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMicOn) "TURN MICROPHONE OFF (MUTE)" else "TURN MICROPHONE ON",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Last Location Display Card (Master Reprompt)
            val loc = device.currentLocation
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = CyberEmerald,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (loc != null) "📍 Last Location: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}" else "📍 Last Location: Standby / Awaiting GPS",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        if (loc != null && loc.timestamp > 0) {
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(loc.timestamp))
                            Text(
                                text = "Fix: $timeStr • Accuracy ±${loc.accuracy.toInt()}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Secondary Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onViewMap,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp), tint = CyberEmerald)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("View Map", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onUnpair,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertCoral)
                ) {
                    Text("Unpair", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun LiveStreamViewerOverlay(
    session: Session?,
    sessionType: SessionType,
    targetDeviceName: String,
    onSwitchLens: (SessionType) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val isMicOn = session?.isMicActive == true

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = androidx.compose.foundation.BorderStroke(2.dp, CyberEmerald)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(CyberEmerald, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "LIVE • ${if (sessionType == SessionType.CAMERA_REAR) "REAR CAMERA" else "FRONT CAMERA"}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "$targetDeviceName • Persistent Authorization Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberEmerald
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val next = if (sessionType == SessionType.CAMERA_REAR) SessionType.CAMERA_FRONT else SessionType.CAMERA_REAR
                            onSwitchLens(next)
                        }) {
                            Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip Lens", tint = StreamingCyan)
                        }

                        IconButton(onClick = { onToggleMic(!isMicOn) }) {
                            Icon(
                                imageVector = if (isMicOn) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Toggle Mic",
                                tint = if (isMicOn) StreamingCyan else Color.White
                            )
                        }

                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Video Viewport Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF070B12))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val base64Frame = session?.lastVideoFrameBase64
                    val bitmap = remember(base64Frame) {
                        if (!base64Frame.isNullOrBlank()) {
                            try {
                                val bytes = Base64.decode(base64Frame, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } catch (e: Exception) {
                                null
                            }
                        } else null
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Live Remote Stream",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        RealStreamStatusPlaceholder(
                            session = session,
                            targetDeviceName = targetDeviceName
                        )
                    }

                    // Top-left LIVE / STATUS badge
                    val hasLiveFrame = bitmap != null
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = if (hasLiveFrame) AlertCoral else SurfaceVariantDark
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(6.dp).background(if (hasLiveFrame) Color.White else WarningAmber, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasLiveFrame) "LIVE FEED" else "STANDBY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (hasLiveFrame) Color.White else WarningAmber
                            )
                        }
                    }

                    // Top-right Lens Info Badge
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberEmerald.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = if (sessionType == SessionType.CAMERA_REAR) "REAR 1080P" else "FRONT 1080P",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyberEmerald,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Bottom-right Audio Waveform Indicator overlay if mic is on
                    if (isMicOn) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                        ) {
                            AudioWaveformMeter(
                                audioLevel = session?.audioLevel ?: 0.35f,
                                isMicActive = true
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Control Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val next = if (sessionType == SessionType.CAMERA_REAR) SessionType.CAMERA_FRONT else SessionType.CAMERA_REAR
                            onSwitchLens(next)
                        },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = null, modifier = Modifier.size(16.dp), tint = StreamingCyan)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (sessionType == SessionType.CAMERA_REAR) "Switch Front" else "Switch Rear",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = { onToggleMic(!isMicOn) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = if (isMicOn) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isMicOn) AlertCoral else StreamingCyan
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMicOn) "Mute Mic" else "Unmute Mic",
                            fontSize = 12.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Camera OFF Button
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("stop_session_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AlertCoral, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Turn Camera OFF", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RealStreamStatusPlaceholder(
    session: Session?,
    targetDeviceName: String
) {
    val errorMsg = session?.errorMessage
    val isRequested = session?.status == SessionStatus.REQUESTED.name

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!errorMsg.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AlertCoral.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = AlertCoral,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Camera Stream Unavailable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else if (isRequested) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = StreamingCyan,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Awaiting Target Approval",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Approval request sent to $targetDeviceName.\nThe target phone's owner must approve sharing in Kafka.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = CyberEmerald,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Connecting to Camera Hardware...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Live video from $targetDeviceName will appear as soon as camera hardware captures frames.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
