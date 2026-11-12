package com.example.ui.screens.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.Device
import com.example.data.model.Session
import com.example.data.model.SessionStatus
import com.example.data.model.SessionType
import com.example.data.repository.HomeSafeRepository
import com.example.service.HomeSafeForegroundService
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.StreamingCyan
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun DeviceDashboardScreen(
    targetDeviceId: String,
    targetDeviceName: String,
    pairedMonitorId: String?,
    repository: HomeSafeRepository,
    onPairWithMonitor: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Real-time Firestore listeners for Device and Session
    val myDevice by repository.observeDevice(targetDeviceId).collectAsState(initial = null)
    val pairedMonitorDevice by remember(pairedMonitorId) {
        if (!pairedMonitorId.isNullOrBlank()) {
            repository.observeDevice(pairedMonitorId)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)

    val activeSession by repository.observeActiveSessionForDevice(targetDeviceId).collectAsState(initial = null)

    var isStreamingApprovedByOwner by remember { mutableStateOf(false) }
    var showIncomingApprovalDialog by remember { mutableStateOf(false) }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }
    var rememberPersistentChoice by remember { mutableStateOf(false) }

    // Inspect Android OS permissions separately - Android may grant or deny each independently
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted && isStreamingApprovedByOwner) {
            val sId = activeSession?.sessionId
            if (!sId.isNullOrBlank()) {
                scope.launch {
                    repository.approveSession(sId, targetDeviceId)
                }
            }
        } else if (!granted) {
            showPermissionRationaleDialog = true
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    fun refreshPermissions() {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
    }

    val isPersistentCamAuth = myDevice?.persistentCameraAuthorized == true
    val isPersistentMicAuth = myDevice?.persistentMicAuthorized == true

    // React to incoming session request:
    // Only skip dialog when persistent authorization is explicitly ON AND Android camera permission is granted.
    LaunchedEffect(activeSession?.status, activeSession?.isCameraActive, myDevice?.persistentCameraAuthorized) {
        val session = activeSession ?: run {
            showIncomingApprovalDialog = false
            return@LaunchedEffect
        }
        val isCamAuth = myDevice?.persistentCameraAuthorized == true
        val hasCamPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // Session ended or rejected -> dismiss dialog and reset approved state
        if (session.status == SessionStatus.ENDED.name || session.status == SessionStatus.REJECTED.name) {
            isStreamingApprovedByOwner = false
            showIncomingApprovalDialog = false
            return@LaunchedEffect
        }

        // When monitor requests camera (status == REQUESTED or isCameraActive requested)
        if (session.status == SessionStatus.REQUESTED.name || (session.isCameraActive && !isStreamingApprovedByOwner)) {
            if (isCamAuth && hasCamPerm) {
                // Persistent authorization is explicitly ON and OS permission is granted:
                // NEVER show the in-app per-session approval dialog!
                showIncomingApprovalDialog = false
                isStreamingApprovedByOwner = true
                if (session.status != SessionStatus.ACTIVE.name && session.status != SessionStatus.APPROVED.name) {
                    repository.approveSession(session.sessionId, targetDeviceId)
                }
            } else if (isCamAuth && !hasCamPerm) {
                // Saved authorization is ON, but Android OS camera permission is missing or revoked:
                // Request OS permission without showing in-app approval prompt
                showIncomingApprovalDialog = false
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                // Saved authorization is OFF, revoked, or missing:
                // Do NOT start streaming. Show in-app approval dialog so owner can approve or reject.
                showIncomingApprovalDialog = true
            }
        } else if (session.status == SessionStatus.APPROVED.name || session.status == SessionStatus.ACTIVE.name) {
            showIncomingApprovalDialog = false
            isStreamingApprovedByOwner = true
        }
    }

    // Intercept back button when paired: Cannot exit device mode until unpaired on monitoring device
    BackHandler(enabled = !pairedMonitorId.isNullOrBlank()) {
        Toast.makeText(
            context,
            "Device Mode is active. Unpair from your monitoring phone to exit.",
            Toast.LENGTH_SHORT
        ).show()
    }

    // Automatically detect when monitoring phone unpairs this device
    LaunchedEffect(myDevice?.unpairedByRemote, myDevice?.pairedWithDeviceId) {
        val dev = myDevice ?: return@LaunchedEffect
        if (!pairedMonitorId.isNullOrBlank()) {
            if (dev.unpairedByRemote || dev.pairedWithDeviceId.isBlank()) {
                Toast.makeText(
                    context,
                    "Unpaired by Monitoring device. Exiting Device Mode.",
                    Toast.LENGTH_LONG
                ).show()
                repository.clearUnpairedByRemoteFlag(targetDeviceId)
                onUnpaired()
            }
        }
    }

    // Update heartbeat and keep foreground service active
    LaunchedEffect(targetDeviceId) {
        try {
            repository.updateDeviceHeartbeat(targetDeviceId, isOnline = true)
        } catch (e: Exception) {
            android.util.Log.w("DeviceDashboard", "Heartbeat update: ${e.message}")
        }
        try {
            HomeSafeForegroundService.startService(context, targetDeviceId)
        } catch (e: Exception) {
            android.util.Log.w("DeviceDashboard", "Foreground service start: ${e.message}")
        }
    }

    val isActuallyStreaming = (activeSession?.isCameraActive == true) &&
        (activeSession?.status == SessionStatus.ACTIVE.name || activeSession?.status == SessionStatus.APPROVED.name)

    // Pulsing animation for streaming indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Explicit approval dialog when monitor requests camera and persistent authorization is OFF
    if (showIncomingApprovalDialog && activeSession != null) {
        val session = activeSession!!
        val lensName = if (session.cameraLens.contains("front", ignoreCase = true)) "Front" else "Rear"
        val monitorName = pairedMonitorDevice?.deviceName ?: "Paired Monitoring Phone"

        AlertDialog(
            onDismissRequest = { /* user must choose explicitly */ },
            containerColor = SurfaceDark,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = StreamingCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Camera Sharing Request", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "$monitorName is requesting to view your $lensName Camera live feed.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Android privacy requirements require your explicit authorization to start camera sharing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Option to remember choice as persistent authorization
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SurfaceVariantDark,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { rememberPersistentChoice = !rememberPersistentChoice }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = rememberPersistentChoice,
                                onCheckedChange = { rememberPersistentChoice = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = CyberEmerald,
                                    uncheckedColor = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier.testTag("remember_persistent_camera_checkbox")
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Enable Persistent Authorization",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Allow camera without asking each session",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIncomingApprovalDialog = false
                        isStreamingApprovedByOwner = true
                        scope.launch {
                            if (rememberPersistentChoice) {
                                repository.setPersistentCameraAuth(targetDeviceId, true)
                            }
                            if (hasCameraPermission) {
                                repository.approveSession(session.sessionId, targetDeviceId)
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF022C22)),
                    modifier = Modifier.testTag("approve_camera_button")
                ) {
                    Text("Approve & Start Stream", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showIncomingApprovalDialog = false
                        isStreamingApprovedByOwner = false
                        scope.launch {
                            repository.rejectSession(session.sessionId, targetDeviceId)
                        }
                    },
                    modifier = Modifier.testTag("decline_camera_button")
                ) {
                    Text("Decline", color = AlertCoral)
                }
            }
        )
    }

    // Permission rationale dialog with button to open Android Settings
    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Camera Permission Required", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Camera access was denied. To enable camera sharing, grant Camera permission in Android App Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRationaleDialog = false
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF022C22))
                ) {
                    Text("Open App Settings", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPermissionRationaleDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
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
                            text = "Kafka Device",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "CP B • $targetDeviceName",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamingCyan
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pairedMonitorId.isNullOrBlank()) {
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("device_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WarningAmber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("DEVICE ACTIVE", color = WarningAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
            }

            // IN-APP STREAMING ACTIVE INDICATOR WITH EXPLICIT STOP ACTION
            if (isActuallyStreaming) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("active_stream_indicator_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E0E0E)),
                        border = androidx.compose.foundation.BorderStroke(2.dp, AlertCoral)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(AlertCoral.copy(alpha = pulseAlpha), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "LIVE CAMERA STREAMING ACTIVE",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = AlertCoral
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Transmitting ${if (activeSession?.cameraLens?.contains("front", ignoreCase = true) == true) "Front" else "Rear"} camera to ${pairedMonitorDevice?.deviceName ?: "Monitoring Phone"}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    isStreamingApprovedByOwner = false
                                    scope.launch {
                                        val sId = activeSession?.sessionId
                                        if (!sId.isNullOrBlank()) {
                                            repository.toggleCamera(sId, targetDeviceId, false)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("stop_sharing_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AlertCoral, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Stop Sharing Now", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else if (!pairedMonitorId.isNullOrBlank()) {
                // OPT-IN: Start Camera Sharing button on Target Phone
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Videocam, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Camera Sharing Controls",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Target Owner Opt-In Control",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyberEmerald
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Android requires camera sharing to be explicitly approved or started while the Kafka app is visible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (hasCameraPermission) {
                                        isStreamingApprovedByOwner = true
                                        scope.launch {
                                            val session = repository.getOrCreateSessionForMonitor(pairedMonitorId, targetDeviceId, SessionType.CAMERA_REAR)
                                            repository.approveSession(session.sessionId, targetDeviceId)
                                        }
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("start_camera_opt_in_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF022C22))
                            ) {
                                Icon(Icons.Default.Videocam, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Start Camera Stream (Owner Opt-In)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // PAIRED MONITOR INFO CARD
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = StreamingCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Paired Monitor (CP A)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            if (!pairedMonitorId.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyberEmerald.copy(alpha = 0.2f)
                                ) {
                                    Text(
                                        text = "PAIRED",
                                        color = CyberEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (!pairedMonitorId.isNullOrBlank()) {
                            Text(
                                text = pairedMonitorDevice?.deviceName ?: "Monitoring Phone",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Device ID: $pairedMonitorId",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceVariantDark,
                                border = androidx.compose.foundation.BorderStroke(1.dp, WarningAmber.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = WarningAmber,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Device Mode Active",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Unpair from the monitoring phone or change role in Settings to exit.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No monitoring device currently paired.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onPairWithMonitor,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color(0xFF022C22))
                            ) {
                                Text("Pair With Monitor", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // PERSISTENT REMOTE ACCESS AUTHORIZATION CARD (CORE REQUIREMENT)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isPersistentCamAuth || isPersistentMicAuth) CyberEmerald else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        if (isPersistentCamAuth) CyberEmerald.copy(alpha = 0.15f) else SurfaceVariantDark,
                                        RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = if (isPersistentCamAuth) CyberEmerald else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Persistent Remote Access",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Informed, visible, owner-controlled consent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isPersistentCamAuth) CyberEmerald else StreamingCyan
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Persistent authorization allows your paired monitoring phone to view your camera feed and listen to audio without asking you for in-app approval on every single session. You can turn this off or revoke access at any time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Switch 1: Persistent Camera Authorization
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isPersistentCamAuth) CyberEmerald.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Persistent Camera Authorization",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isPersistentCamAuth) "AUTHORIZED (No prompt needed)" else "OFF (Requires in-app prompt per session)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isPersistentCamAuth) CyberEmerald else WarningAmber
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Switch(
                                    checked = isPersistentCamAuth,
                                    onCheckedChange = { enable ->
                                        scope.launch {
                                            if (enable && !hasCameraPermission) {
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            }
                                            repository.setPersistentCameraAuth(targetDeviceId, enable)
                                        }
                                    },
                                    modifier = Modifier.testTag("persistent_camera_switch"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyberEmerald,
                                        checkedTrackColor = Color(0xFF064E3B)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Switch 2: Persistent Microphone Authorization
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isPersistentMicAuth) StreamingCyan.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Persistent Microphone Authorization",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isPersistentMicAuth) "AUTHORIZED (No prompt needed)" else "OFF (Requires in-app prompt per session)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isPersistentMicAuth) StreamingCyan else WarningAmber
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Switch(
                                    checked = isPersistentMicAuth,
                                    onCheckedChange = { enable ->
                                        scope.launch {
                                            if (enable && !hasMicPermission) {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                            repository.setPersistentMicAuth(targetDeviceId, enable)
                                        }
                                    },
                                    modifier = Modifier.testTag("persistent_mic_switch"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = StreamingCyan,
                                        checkedTrackColor = Color(0xFF083344)
                                    )
                                )
                            }
                        }

                        if (isPersistentCamAuth || isPersistentMicAuth) {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.revokeAllAndStop(targetDeviceId)
                                        Toast.makeText(context, "All persistent authorizations revoked.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .testTag("revoke_all_auth_button"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertCoral)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Revoke All Authorizations", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // ANDROID OS HARDWARE PERMISSIONS CARD (SEPARATE & ACCURATE)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Android OS Hardware Permissions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Android OS permissions are separate from app authorization and are requested independently.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Camera OS Permission
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = if (hasCameraPermission) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Camera Permission", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                            if (hasCameraPermission) {
                                Surface(shape = RoundedCornerShape(6.dp), color = CyberEmerald.copy(alpha = 0.2f)) {
                                    Text("Granted", color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, color = AlertCoral)
                                }
                            }
                        }

                        // Microphone OS Permission
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, contentDescription = null, tint = if (hasMicPermission) StreamingCyan else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Microphone Permission", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                            if (hasMicPermission) {
                                Surface(shape = RoundedCornerShape(6.dp), color = StreamingCyan.copy(alpha = 0.2f)) {
                                    Text("Granted", color = StreamingCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, color = AlertCoral)
                                }
                            }
                        }

                        // Location OS Permission
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = if (hasLocationPermission) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Precise Location", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                            if (hasLocationPermission) {
                                Surface(shape = RoundedCornerShape(6.dp), color = CyberEmerald.copy(alpha = 0.2f)) {
                                    Text("Granted", color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp, color = AlertCoral)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onOpenPermissions,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberEmerald)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Full Setup Wizard", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}
