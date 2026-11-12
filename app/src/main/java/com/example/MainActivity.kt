package com.example

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.example.data.model.Device
import com.example.data.model.DeviceRole
import com.example.data.repository.DevicePreferences
import com.example.data.repository.HomeSafeRepository
import com.example.ui.screens.device.DeviceDashboardScreen
import com.example.ui.screens.monitor.MonitoringDashboardScreen
import com.example.ui.screens.pairing.EnterPairingCodeScreen
import com.example.ui.screens.pairing.GeneratePairingCodeScreen
import com.example.ui.screens.pairing.PairingSuccessScreen
import com.example.ui.screens.pairing.PermissionWizardScreen
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.welcome.ModeSelectionScreen
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

sealed class Screen {
    object ModeSelection : Screen()
    object GenerateCode : Screen()
    object EnterCode : Screen()
    data class PermissionWizard(val pairedMonitorId: String) : Screen()
    data class PairingSuccess(val companionName: String, val roleTitle: String) : Screen()
    object MonitoringDashboard : Screen()
    object DeviceDashboard : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isActivityVisible: Boolean = false
            private set
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                HomeSafeApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isActivityVisible = true
    }

    override fun onStop() {
        super.onStop()
        isActivityVisible = false
    }
}

@Composable
fun HomeSafeApp() {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MidnightBg
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            MainNavigationFlow()
        }
    }
}

@Composable
fun MainNavigationFlow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { HomeSafeRepository(context) }
    val devicePreferences = remember { DevicePreferences(context) }

    var deviceId by remember { mutableStateOf<String?>(null) }
    val savedRole by devicePreferences.roleFlow.collectAsState(initial = null)
    val deviceName by devicePreferences.deviceNameFlow.collectAsState(initial = "Android Device")
    val pairedDeviceId by devicePreferences.pairedDeviceIdFlow.collectAsState(initial = null)

    var currentScreen by remember { mutableStateOf<Screen?>(null) }

    // Initialize Device ID on startup
    LaunchedEffect(Unit) {
        val id = devicePreferences.getOrCreateDeviceId()
        deviceId = id
    }

    // Register/update device profile in Firestore whenever deviceId and role are resolved
    LaunchedEffect(deviceId, savedRole, deviceName) {
        val id = deviceId ?: return@LaunchedEffect
        val role = savedRole ?: DeviceRole.DEVICE
        val device = Device(
            deviceId = id,
            userId = id,
            deviceName = deviceName,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            role = role.name,
            pairedWithDeviceId = pairedDeviceId ?: "",
            isOnline = true
        )
        try {
            repository.registerOrUpdateDevice(device)
        } catch (e: Exception) {
            // Ignore if transient
        }
    }

    // Determine default screen on initial load
    LaunchedEffect(savedRole, deviceId) {
        if (deviceId == null) return@LaunchedEffect
        if (currentScreen == null) {
            currentScreen = when (savedRole) {
                DeviceRole.MONITOR -> Screen.MonitoringDashboard
                DeviceRole.DEVICE -> Screen.DeviceDashboard
                null -> Screen.ModeSelection
            }
        }
    }

    if (deviceId == null || currentScreen == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MidnightBg),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = CyberEmerald)
        }
        return
    }

    val activeDeviceId = deviceId!!

    when (val screen = currentScreen!!) {
        is Screen.ModeSelection -> {
            ModeSelectionScreen(
                currentDeviceName = deviceName,
                onRoleSelected = { role ->
                    scope.launch {
                        devicePreferences.setRole(role)
                        currentScreen = when (role) {
                            DeviceRole.MONITOR -> Screen.MonitoringDashboard
                            DeviceRole.DEVICE -> Screen.DeviceDashboard
                        }
                    }
                }
            )
        }

        is Screen.GenerateCode -> {
            BackHandler {
                currentScreen = Screen.MonitoringDashboard
            }
            GeneratePairingCodeScreen(
                monitorDeviceId = activeDeviceId,
                repository = repository,
                onBack = { currentScreen = Screen.MonitoringDashboard },
                onPairingSuccess = { companionName ->
                    scope.launch {
                        currentScreen = Screen.PairingSuccess(
                            companionName = companionName,
                            roleTitle = "Monitoring Phone (CP A)"
                        )
                    }
                }
            )
        }

        is Screen.EnterCode -> {
            BackHandler {
                currentScreen = Screen.DeviceDashboard
            }
            EnterPairingCodeScreen(
                targetDeviceId = activeDeviceId,
                targetDeviceName = deviceName,
                repository = repository,
                onBack = { currentScreen = Screen.DeviceDashboard },
                onCodeValidated = { pairedMonitorId ->
                    scope.launch {
                        devicePreferences.setPairedDeviceId(pairedMonitorId)
                        currentScreen = Screen.PermissionWizard(pairedMonitorId)
                    }
                }
            )
        }

        is Screen.PermissionWizard -> {
            BackHandler {
                currentScreen = Screen.DeviceDashboard
            }
            PermissionWizardScreen(
                targetDeviceId = activeDeviceId,
                pairedMonitorId = screen.pairedMonitorId,
                repository = repository,
                onPermissionsCompleted = {
                    currentScreen = Screen.DeviceDashboard
                }
            )
        }

        is Screen.PairingSuccess -> {
            BackHandler {
                currentScreen = if (savedRole == DeviceRole.MONITOR) Screen.MonitoringDashboard else Screen.DeviceDashboard
            }
            PairingSuccessScreen(
                pairedDeviceName = screen.companionName,
                roleTitle = screen.roleTitle,
                onContinue = {
                    currentScreen = if (savedRole == DeviceRole.MONITOR) Screen.MonitoringDashboard else Screen.DeviceDashboard
                }
            )
        }

        is Screen.MonitoringDashboard -> {
            MonitoringDashboardScreen(
                monitorDeviceId = activeDeviceId,
                monitorDeviceName = deviceName,
                repository = repository,
                onPairNewDevice = { currentScreen = Screen.GenerateCode },
                onOpenSettings = { currentScreen = Screen.Settings }
            )
        }

        is Screen.DeviceDashboard -> {
            DeviceDashboardScreen(
                targetDeviceId = activeDeviceId,
                targetDeviceName = deviceName,
                pairedMonitorId = pairedDeviceId,
                repository = repository,
                onPairWithMonitor = { currentScreen = Screen.EnterCode },
                onOpenSettings = { currentScreen = Screen.Settings },
                onOpenPermissions = { currentScreen = Screen.PermissionWizard(pairedDeviceId ?: "") },
                onUnpaired = {
                    scope.launch {
                        devicePreferences.clearPairing()
                        devicePreferences.clearRole()
                        devicePreferences.clearWizardFlag()
                        currentScreen = Screen.ModeSelection
                    }
                }
            )
        }

        is Screen.Settings -> {
            BackHandler {
                currentScreen = if (savedRole == DeviceRole.MONITOR) Screen.MonitoringDashboard else Screen.DeviceDashboard
            }
            SettingsScreen(
                deviceId = activeDeviceId,
                currentRole = savedRole ?: DeviceRole.MONITOR,
                currentDeviceName = deviceName,
                devicePreferences = devicePreferences,
                onBack = {
                    currentScreen = if (savedRole == DeviceRole.MONITOR) Screen.MonitoringDashboard else Screen.DeviceDashboard
                },
                onRoleChanged = { newRole ->
                    currentScreen = when (newRole) {
                        DeviceRole.MONITOR -> Screen.MonitoringDashboard
                        DeviceRole.DEVICE -> Screen.DeviceDashboard
                    }
                },
                onResetToModeSelection = {
                    currentScreen = Screen.ModeSelection
                }
            )
        }
    }
}
