package com.sethu.emergency

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import com.sethu.emergency.comm.BluetoothPermissionHelper
import com.sethu.emergency.comm.forwarding.EngineStatus
import com.sethu.emergency.ui.components.MapsforgeInitializer
import com.sethu.emergency.ui.screens.CoordinatorScreen
import com.sethu.emergency.ui.screens.DeviceRole
import com.sethu.emergency.ui.screens.HomeScreen
import com.sethu.emergency.ui.screens.OfflineCommScreen
import com.sethu.emergency.ui.screens.OfflineMapScreen
import com.sethu.emergency.ui.screens.RelayDeviceScreen
import com.sethu.emergency.ui.screens.SosSenderScreen
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.CardBackground
import com.sethu.emergency.ui.theme.DarkBackground
import com.sethu.emergency.ui.theme.SethuTheme
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary

enum class AppDestination {
    HOME,
    SOS_SENDER,
    RELAY_DEVICE,
    COORDINATOR,
    OFFLINE_COMM,
    MAP
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapsforgeInitializer.ensureInitialized(this)
        // One runtime (transport + stores + delivery engine) per Activity owner; it survives recreation.
        val runtime = ViewModelProvider(this)[SethuRuntimeViewModel::class.java].runtime
        setContent {
            SethuTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    SethuApp(runtime)
                }
            }
        }
    }
}

@Composable
fun SethuApp(runtime: SethuRuntime) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val transport = runtime.transport
    val coordinatorStore = runtime.coordinatorStore
    val relayStore = runtime.relayStore
    val senderStore = runtime.senderStore
    val forwardController = runtime.controller

    var currentDestination by remember { mutableStateOf(AppDestination.HOME) }
    var selectedRole by remember { mutableStateOf(runtime.loadRole()) }
    var upcomingTabDialog by remember { mutableStateOf<String?>(null) }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val engineStatus by forwardController.status.collectAsState()

    fun refreshLinkAfterPermissionChange() {
        transport.onPermissionsChanged()
        forwardController.notifyOutboxChanged()
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshLinkAfterPermissionChange() }

    // Request Bluetooth ("Nearby devices") permission at startup so the delivery engine can listen/connect
    // without the user first visiting the Direct Link screen.
    LaunchedEffect(Unit) {
        val required = BluetoothPermissionHelper.getRequiredPermissions()
        if (required.isNotEmpty() && !BluetoothPermissionHelper.hasPermissions(context)) {
            permissionRequestedOnce = true
            bluetoothPermissionLauncher.launch(required.toTypedArray())
        }
    }

    // Permission may be granted from system settings while we were in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLinkAfterPermissionChange()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lifecycle-managed Bluetooth state change receiver (balanced register/unregister)
    DisposableEffect(transport) {
        transport.registerBluetoothReceiver(context)
        onDispose { transport.unregisterBluetoothReceiver(context) }
    }

    // Role changes are persisted and applied to the single delivery engine.
    LaunchedEffect(selectedRole) {
        runtime.setRole(selectedRole)
    }

    val onFixLink: () -> Unit = {
        val required = BluetoothPermissionHelper.getRequiredPermissions()
        if (!BluetoothPermissionHelper.hasPermissions(context)) {
            val activity = context as? Activity
            val canPrompt = activity == null || !permissionRequestedOnce ||
                required.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            if (canPrompt) {
                permissionRequestedOnce = true
                bluetoothPermissionLauncher.launch(required.toTypedArray())
            } else {
                // Permanently denied: only system settings can grant it now.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } else if (!transport.isBluetoothEnabled()) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            refreshLinkAfterPermissionChange()
        }
    }

    val handleTabSelected: (String) -> Unit = { tab ->
        when (tab) {
            "Home" -> currentDestination = AppDestination.HOME
            "Messages" -> currentDestination = AppDestination.OFFLINE_COMM
            "Map" -> currentDestination = AppDestination.MAP
            else -> upcomingTabDialog = tab
        }
    }

    if (currentDestination != AppDestination.HOME) {
        BackHandler {
            currentDestination = AppDestination.HOME
        }
    }

    when (currentDestination) {
        AppDestination.HOME -> {
            HomeScreen(
                selectedRole = selectedRole,
                onRoleSelected = { selectedRole = it },
                onNavigateToRole = { role ->
                    // Entering a role screen adopts that role, so the engine and the screen can never disagree.
                    selectedRole = role
                    currentDestination = when (role) {
                        DeviceRole.SOS_SENDER -> AppDestination.SOS_SENDER
                        DeviceRole.RELAY_DEVICE -> AppDestination.RELAY_DEVICE
                        DeviceRole.COORDINATOR -> AppDestination.COORDINATOR
                    }
                },
                onSosClick = {
                    selectedRole = DeviceRole.SOS_SENDER
                    currentDestination = AppDestination.SOS_SENDER
                },
                onTabSelected = handleTabSelected
            )
        }

        AppDestination.SOS_SENDER -> {
            SosSenderScreen(
                transport = transport,
                onBack = { currentDestination = AppDestination.HOME },
                onTabSelected = handleTabSelected,
                onOpenOfflineComm = { currentDestination = AppDestination.OFFLINE_COMM },
                senderStore = senderStore,
                linkStatusText = engineStatus.text,
                linkNeedsFix = engineStatus.kind == EngineStatus.Kind.PERMISSION_REQUIRED ||
                    engineStatus.kind == EngineStatus.Kind.BLUETOOTH_OFF,
                onFixLink = onFixLink
            )
        }

        AppDestination.RELAY_DEVICE -> {
            RelayDeviceScreen(
                transport = transport,
                onBack = { currentDestination = AppDestination.HOME },
                onTabSelected = handleTabSelected,
                onOpenOfflineComm = { currentDestination = AppDestination.OFFLINE_COMM },
                relayStore = relayStore,
                linkStatusText = engineStatus.text,
                linkNeedsFix = engineStatus.kind == EngineStatus.Kind.PERMISSION_REQUIRED ||
                    engineStatus.kind == EngineStatus.Kind.BLUETOOTH_OFF,
                onFixLink = onFixLink
            )
        }

        AppDestination.COORDINATOR -> {
            CoordinatorScreen(
                transport = transport,
                onBack = { currentDestination = AppDestination.HOME },
                onTabSelected = handleTabSelected,
                onOpenOfflineComm = { currentDestination = AppDestination.OFFLINE_COMM },
                onOpenMap = { currentDestination = AppDestination.MAP },
                coordinatorStore = coordinatorStore,
                linkStatusText = engineStatus.text,
                linkNeedsFix = engineStatus.kind == EngineStatus.Kind.PERMISSION_REQUIRED ||
                    engineStatus.kind == EngineStatus.Kind.BLUETOOTH_OFF,
                onFixLink = onFixLink
            )
        }

        AppDestination.OFFLINE_COMM -> {
            OfflineCommScreen(
                transport = transport,
                onBack = { currentDestination = AppDestination.HOME },
                onTabSelected = handleTabSelected
            )
        }

        AppDestination.MAP -> {
            OfflineMapScreen(
                onBack = { currentDestination = AppDestination.HOME },
                onTabSelected = handleTabSelected,
                coordinatorStore = coordinatorStore
            )
        }
    }

    upcomingTabDialog?.let { tabName ->
        AlertDialog(
            onDismissRequest = { upcomingTabDialog = null },
            title = {
                Text(
                    text = "$tabName — Planned Feature",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "The $tabName module will be developed in future phases. In Phase 1, only local role layouts are available. Networking, GPS, and external communications are not implemented.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { upcomingTabDialog = null }) {
                    Text("OK", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            containerColor = CardBackground
        )
    }
}
