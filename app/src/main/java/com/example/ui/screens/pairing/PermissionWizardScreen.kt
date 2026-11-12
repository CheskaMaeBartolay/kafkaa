package com.example.ui.screens.pairing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.example.R
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.repository.DevicePreferences
import com.example.data.repository.HomeSafeRepository
import com.example.ui.theme.AlertCoral
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.StreamingCyan
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

data class PermissionItem(
    val key: String,
    val permission: String,
    val title: String,
    val description: String,
    val icon: ImageVector
)

@Composable
fun PermissionWizardScreen(
    targetDeviceId: String,
    pairedMonitorId: String,
    repository: HomeSafeRepository,
    onPermissionsCompleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devicePreferences = remember { DevicePreferences(context) }

    val permissionItems = remember {
        val list = mutableListOf(
            PermissionItem(
                key = "camera",
                permission = Manifest.permission.CAMERA,
                title = "Camera Permission",
                description = "Required to allow video streaming from front or rear camera.",
                icon = Icons.Default.CameraAlt
            ),
            PermissionItem(
                key = "microphone",
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Microphone Permission",
                description = "Required to allow real-time audio listening.",
                icon = Icons.Default.Mic
            ),
            PermissionItem(
                key = "location",
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Precise Location Permission",
                description = "Enables real-time GPS location tracking on the Monitor map.",
                icon = Icons.Default.LocationOn
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.add(
                PermissionItem(
                    key = "bg_location",
                    permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    title = "Background Location Permission",
                    description = "Keeps location tracking updated when Kafka is minimized.",
                    icon = Icons.Default.LocationOn
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(
                PermissionItem(
                    key = "notifications",
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "System Notifications",
                    description = "Required for non-dismissible active-sharing foreground notifications.",
                    icon = Icons.Default.Notifications
                )
            )
        }
        list
    }

    val permissionStates = remember { mutableStateMapOf<String, Boolean>() }

    // Persistent authorization toggles — default to OFF as specified
    var allowPersistentCamera by remember { mutableStateOf(false) }
    var allowPersistentMic by remember { mutableStateOf(false) }

    fun refreshPermissionStates() {
        for (item in permissionItems) {
            val isGranted = ContextCompat.checkSelfPermission(
                context,
                item.permission
            ) == PackageManager.PERMISSION_GRANTED
            permissionStates[item.key] = isGranted
        }
    }

    // Single burst ActivityResultLauncher with RequestMultiplePermissions()
    val burstPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        for (item in permissionItems) {
            val isGranted = results[item.permission] ?: (
                ContextCompat.checkSelfPermission(context, item.permission) == PackageManager.PERMISSION_GRANTED
            )
            permissionStates[item.key] = isGranted
        }
    }

    // Check permissions and request in a single burst on first mount
    LaunchedEffect(Unit) {
        refreshPermissionStates()
        val missing = permissionItems.map { it.permission }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            burstPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    val openAppSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    val onCompleteWizard = {
        scope.launch {
            // 1. Write Firestore persistent authorization & permissions
            val permMap = permissionStates.toMap()
            repository.updateDevicePermissions(targetDeviceId, permMap)
            repository.setPersistentCameraAuth(targetDeviceId, allowPersistentCamera)
            repository.setPersistentMicAuth(targetDeviceId, allowPersistentMic)

            // 2. Write DataStore "permission_wizard_completed" = true so wizard never shows again
            devicePreferences.setPermissionWizardCompleted(true)

            // 3. Navigate directly to Device Dashboard
            onPermissionsCompleted()
        }
    }

    Scaffold(
        containerColor = MidnightBg,
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Button(
                    onClick = { onCompleteWizard() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("finish_permissions_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberEmerald,
                        contentColor = Color(0xFF022C22)
                    )
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Complete Setup & Authorize",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
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
                            text = "Device Authorization Setup",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "CP B • One-Time Device Grant",
                            style = MaterialTheme.typography.bodySmall,
                            color = StreamingCyan
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Grant the Android hardware permissions below and choose whether to allow your paired Monitor (CP A) to access camera and mic without asking each time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Android Runtime Permissions Status Burst Button
            item {
                val allGranted = permissionItems.all { permissionStates[it.key] == true }
                OutlinedButton(
                    onClick = {
                        val missing = permissionItems.map { it.permission }.filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) {
                            burstPermissionLauncher.launch(missing.toTypedArray())
                        } else {
                            refreshPermissionStates()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (allGranted) CyberEmerald else StreamingCyan
                    )
                ) {
                    Icon(
                        imageVector = if (allGranted) Icons.Default.Check else Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allGranted) "All Android Permissions Granted" else "Grant All Android Permissions",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            items(permissionItems) { item ->
                val isGranted = permissionStates[item.key] == true
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isGranted) CyberEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (isGranted) CyberEmerald.copy(alpha = 0.15f) else SurfaceVariantDark,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    tint = if (isGranted) CyberEmerald else WarningAmber,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        if (isGranted) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CyberEmerald.copy(alpha = 0.2f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = CyberEmerald, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Granted", color = CyberEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = openAppSettings,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Grant", fontSize = 11.sp, color = StreamingCyan)
                            }
                        }
                    }
                }
            }

            // CORE FEATURE — GRANT ONCE, USE ALWAYS IN-APP CONSENT TOGGLES
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberEmerald)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(CyberEmerald.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = CyberEmerald,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Persistent Authorization Options",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Informed, visible, owner-initiated consent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyberEmerald
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Toggle 1: Camera
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (allowPersistentCamera) CyberEmerald else MaterialTheme.colorScheme.outline
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
                                        text = "Allow my monitoring device to access my camera without asking each time?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (allowPersistentCamera) "Persistent Camera: AUTHORIZED" else "Persistent Camera: OFF (Requires prompt)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allowPersistentCamera) CyberEmerald else WarningAmber
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Switch(
                                    checked = allowPersistentCamera,
                                    onCheckedChange = { allowPersistentCamera = it },
                                    modifier = Modifier.testTag("persistent_camera_toggle"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = CyberEmerald,
                                        checkedTrackColor = Color(0xFF064E3B)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Toggle 2: Microphone
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SurfaceVariantDark,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (allowPersistentMic) StreamingCyan else MaterialTheme.colorScheme.outline
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
                                        text = "Allow my monitoring device to access my microphone without asking each time?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (allowPersistentMic) "Persistent Mic: AUTHORIZED" else "Persistent Mic: OFF (Requires prompt)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (allowPersistentMic) StreamingCyan else WarningAmber
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Switch(
                                    checked = allowPersistentMic,
                                    onCheckedChange = { allowPersistentMic = it },
                                    modifier = Modifier.testTag("persistent_mic_toggle"),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = StreamingCyan,
                                        checkedTrackColor = Color(0xFF083344)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "You can revoke either authorization at any time from the Privacy Management panel on your Device Dashboard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(70.dp))
            }
        }
    }
}
