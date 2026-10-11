package com.sethu.emergency.ui.screens

import android.annotation.SuppressLint
import com.sethu.emergency.comm.bluetooth.PeerDevice
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.sethu.emergency.ui.components.SethuBottomNavigationBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.comm.BluetoothPermissionHelper
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.ServerState
import com.sethu.emergency.comm.TransferStatus
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.ui.components.SethuIcons
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.AccentGreenContainer
import com.sethu.emergency.ui.theme.CardBackground
import com.sethu.emergency.ui.theme.CardBorder
import com.sethu.emergency.ui.theme.CardSelectedBorder
import com.sethu.emergency.ui.theme.DarkBackground
import com.sethu.emergency.ui.theme.SosRed
import com.sethu.emergency.ui.theme.TextFieldBackground
import com.sethu.emergency.ui.theme.TextMuted
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary
import com.sethu.emergency.comm.util.TimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
@Composable
fun OfflineCommScreen(
    transport: BluetoothRfcommTransport,
    onBack: () -> Unit,
    onTabSelected: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val connectionState by transport.connectionState.collectAsState()
    val transferStatus by transport.transferStatus.collectAsState()
    val receivedMessages by transport.receivedMessages.collectAsState()
    val sentMessages by transport.sentMessages.collectAsState()
    val serverState by transport.serverState.collectAsState()
    val peerRole by transport.remotePeerRole.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showHelpDialog by remember { mutableStateOf(false) }
    var bondedDevices by remember { mutableStateOf(transport.getBondedDevices()) }
    var hasPermissions by remember { mutableStateOf(BluetoothPermissionHelper.hasPermissions(context)) }
    // The user asked for the server but permission was missing: finish that same request once it is granted.
    var startServerAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            bondedDevices = transport.getBondedDevices()
            transport.onPermissionsChanged()
            if (startServerAfterPermission) transport.startServer()
        }
        startServerAfterPermission = false
    }

    // Combine sent & received for unified conversation display
    val allMessages = remember(sentMessages, receivedMessages) {
        (sentMessages + receivedMessages).sortedBy { it.timestamp }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            Surface(
                color = DarkBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = SethuIcons.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Direct Device Link",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Bluetooth Classic RFCOMM",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            imageVector = SethuIcons.HelpOutline,
                            contentDescription = "Help",
                            tint = TextSecondary
                        )
                    }
                }
            }
        },
        bottomBar = {
            SethuBottomNavigationBar(
                currentTab = "Messages",
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Device & Permission Banner
            item {
                DeviceIdentityCard(
                    localDeviceName = transport.getLocalDeviceName(),
                    hasPermissions = hasPermissions,
                        onRequestPermissions = {
                            permissionLauncher.launch(
                                BluetoothPermissionHelper.getRequiredPermissions().toTypedArray()
                            )
                        }
                    )
                }

                // Honest Connection State Indicator
                item {
                    HonestConnectionStateCard(
                        state = connectionState,
                        transferStatus = transferStatus,
                        peerRole = peerRole,
                        onDisconnect = {
                            if (connectionState is ConnectionState.Listening) transport.stopServer() else transport.disconnect()
                        },
                        onClearTransfer = { transport.clearTransferStatus() }
                    )
                }

                // Start/Stop Server is always available; it reports its real outcome (Starting/Listening/Failed).
                item {
                    HostServerCard(
                        serverState = serverState,
                        onHostServer = {
                            if (DirectLinkActions.onStartServerTapped(hasPermissions, transport)) {
                                startServerAfterPermission = true
                                permissionLauncher.launch(
                                    BluetoothPermissionHelper.getRequiredPermissions().toTypedArray()
                                )
                            }
                        },
                        onStopHosting = { DirectLinkActions.onStopServerTapped(transport) }
                    )
                }

                // Outbound connections happen only when the user taps a paired device here.
                if (connectionState !is ConnectionState.Connected) {
                    item {
                        PairedDevicesCard(
                            devices = bondedDevices,
                            onRefresh = {
                                bondedDevices = transport.getBondedDevices()
                            },
                            onDeviceClick = { device ->
                                if (DirectLinkActions.onPairedDeviceTapped(hasPermissions, transport, device)) {
                                    permissionLauncher.launch(
                                        BluetoothPermissionHelper.getRequiredPermissions().toTypedArray()
                                    )
                                }
                            },
                            onOpenSettings = {
                                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        )
                    }
                }

                // Message Exchange Panel
                item {
                    MessageComposerCard(
                        isConnected = connectionState is ConnectionState.Connected,
                        textInput = textInput,
                        onTextInputChange = { textInput = it },
                        onSendMessage = { text, type ->
                            if (transport.sendMessage(text, type)) {
                                textInput = ""
                            }
                        }
                    )
                }

                // Messages Timeline
                item {
                    Text(
                        text = "Message History (${allMessages.size})",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (allMessages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBackground)
                                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages exchanged yet.\nConnect devices and send a test ping.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    }
                } else {
                    items(allMessages) { msg ->
                        val isSentByMe = msg.senderDeviceName == transport.getLocalDeviceName()
                        MessageBubble(
                            message = msg,
                            isSentByMe = isSentByMe,
                            onReply = { replyPayload ->
                                textInput = replyPayload
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Offline RFCOMM Setup Guide",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "Connections are made only by you; nothing dials or listens automatically.\n\n" +
                                "1. Pair the phones in Android Settings > Bluetooth.\n" +
                                "2. On the phone that should wait, tap 'Start Server' and check it says Listening.\n" +
                                "3. On the other phone, tap the paired device to connect.\n" +
                                "4. Status shows 'Connected' and the peer's role. Role pairs allowed: Sender-Relay, Relay-Coordinator.\n" +
                                "5. Stop Server only stops listening; use Disconnect to end a link.\n" +
                                "6. Queued SOS reports are sent automatically as soon as a valid link exists.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Got it", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            containerColor = CardBackground
        )
    }
}

@Composable
private fun DeviceIdentityCard(
    localDeviceName: String,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10281D)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SethuIcons.BroadcastTower,
                        contentDescription = "Device",
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "This Device",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = localDeviceName,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!hasPermissions) {
                Button(
                    onClick = onRequestPermissions,
                    colors = ButtonDefaults.buttonColors(containerColor = SosRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Grant Permissions", color = Color.White, fontSize = 11.sp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AccentGreenContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Permissions OK", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun HonestConnectionStateCard(
    state: ConnectionState,
    transferStatus: TransferStatus,
    peerRole: String?,
    onDisconnect: () -> Unit,
    onClearTransfer: () -> Unit
) {
    val (statusColor, statusTitle, statusDesc) = when (state) {
        is ConnectionState.NotConnected -> Triple(
            TextSecondary,
            "Not connected",
            "Select a paired device below or host as server."
        )
        is ConnectionState.Listening -> Triple(
            Color(0xFFFFB74D),
            "Waiting for connection (Server)",
            "Listening on SPP RFCOMM for peer to connect."
        )
        is ConnectionState.Connecting -> Triple(
            Color(0xFF64B5F6),
            "Connecting to ${state.targetDeviceName}...",
            "Establishing RFCOMM socket handshake."
        )
        is ConnectionState.Connected -> Triple(
            AccentGreen,
            "Connected to ${state.remoteDeviceName}",
            "Active Bluetooth Classic RFCOMM channel. Peer role: ${peerRole ?: "verifying…"}"
        )
        is ConnectionState.Failed -> Triple(
            SosRed,
            "Connection Failed",
            state.reason
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(
                1.5.dp,
                if (state is ConnectionState.Connected) CardSelectedBorder else CardBorder,
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = statusTitle,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (state is ConnectionState.Connected || state is ConnectionState.Listening) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SosRed)
                    ) {
                        Text(
                            if (state is ConnectionState.Listening) "Cancel" else "Disconnect",
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusDesc,
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            // Honest Transfer Status
            if (transferStatus !is TransferStatus.Idle) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1B231F))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = transferStatus.toString(),
                            color = when (transferStatus) {
                                is TransferStatus.MessageSent -> AccentGreen
                                is TransferStatus.MessageReceived -> Color(0xFF64B5F6)
                                is TransferStatus.Failed -> SosRed
                                else -> TextSecondary
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Dismiss",
                            color = TextMuted,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { onClearTransfer() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HostServerCard(
    serverState: ServerState,
    onHostServer: () -> Unit,
    onStopHosting: () -> Unit
) {
    val (statusText, statusColor) = when (serverState) {
        is ServerState.Stopped -> "Server not running" to TextSecondary
        is ServerState.Starting -> "Starting Bluetooth server…" to Color(0xFF64B5F6)
        is ServerState.Listening -> "Listening — the other phone can now connect" to AccentGreen
        is ServerState.Busy -> "Server on — in use by a link, resumes listening when it ends" to Color(0xFFFFB74D)
        is ServerState.Failed -> "Server failed: ${serverState.reason}" to SosRed
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Host as RFCOMM Server",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }

            val active = serverState.isActive
            Button(
                onClick = if (active) onStopHosting else onHostServer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Color(0xFF4A3B2C) else AccentGreenContainer
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (active) "Stop Server" else "Start Server",
                    color = if (active) Color(0xFFFFB74D) else AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun PairedDevicesCard(
    devices: List<PeerDevice>,
    onRefresh: () -> Unit,
    onDeviceClick: (PeerDevice) -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Paired Devices (${devices.size})",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Refresh",
                    color = AccentGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (devices.isEmpty()) {
                Text(
                    text = "No paired devices found.\nPlease pair your phones in Android Settings first.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    devices.forEach { device ->
                        val name = device.name.ifBlank { "Unknown Device" }
                        val address = device.address

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF19201C))
                                .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
                                .clickable { onDeviceClick(device) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = name,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = address,
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Text(
                                text = "Connect",
                                color = AccentGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Open Bluetooth Settings to pair new phone",
                color = TextSecondary,
                fontSize = 12.sp,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                modifier = Modifier.clickable { onOpenSettings() }
            )
        }
    }
}

@Composable
private fun MessageComposerCard(
    isConnected: Boolean,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onSendMessage: (String, String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "Send Test Message",
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Preset Quick Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickPresetChip(
                    label = "Ping Test",
                    onClick = { onTextInputChange("Test Ping from Sethu Device") }
                )
                QuickPresetChip(
                    label = "Ack / Reply",
                    onClick = { onTextInputChange("ACK: Message received loud & clear!") }
                )
                QuickPresetChip(
                    label = "SOS Relay",
                    onClick = { onTextInputChange("SOS Relay packet test: Flood alert") }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = textInput,
                onValueChange = onTextInputChange,
                placeholder = {
                    Text(
                        if (isConnected) "Enter payload text..." else "Connect devices to enable sending...",
                        color = TextMuted,
                        fontSize = 13.sp
                    )
                },
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = TextFieldBackground,
                    unfocusedContainerColor = TextFieldBackground,
                    disabledContainerColor = TextFieldBackground,
                    focusedBorderColor = CardSelectedBorder,
                    unfocusedBorderColor = CardBorder,
                    disabledBorderColor = CardBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput, SethuMessage.TYPE_TEXT)
                    }
                },
                enabled = isConnected && textInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGreen,
                    disabledContainerColor = Color(0xFF1E2E25)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = SethuIcons.SendPlane,
                        contentDescription = "Send",
                        tint = if (isConnected && textInput.isNotBlank()) DarkBackground else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Transmit Message",
                        color = if (isConnected && textInput.isNotBlank()) DarkBackground else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickPresetChip(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B2420))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = AccentGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun MessageBubble(
    message: SethuMessage,
    isSentByMe: Boolean,
    onReply: (String) -> Unit
) {
    val formattedTime = remember(message.timestamp) { TimeFormatter.formatTime(message.timestamp) }
    val timeLabel = remember(message.messageType, formattedTime) {
        when (message.messageType) {
            SethuMessage.TYPE_SOS_REPORT -> "Created: $formattedTime"
            SethuMessage.TYPE_COORDINATOR_ACK -> "Coord ACK: $formattedTime"
            SethuMessage.TYPE_SOS_ACK -> "Relay ACK: $formattedTime"
            else -> formattedTime
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSentByMe) Color(0xFF10261C) else CardBackground)
            .border(
                1.dp,
                if (isSentByMe) CardSelectedBorder.copy(alpha = 0.5f) else CardBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isSentByMe) "Sent by: ${message.senderDeviceName}" else "Received from: ${message.senderDeviceName}",
                        color = if (isSentByMe) AccentGreen else Color(0xFF64B5F6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF202A24))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v${message.version}",
                            color = TextMuted,
                            fontSize = 9.sp
                        )
                    }
                }

                Text(
                    text = timeLabel,
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = message.payload,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${message.messageId.take(8)}...",
                    color = TextMuted,
                    fontSize = 10.sp
                )

                if (!isSentByMe) {
                    Text(
                        text = "Reply",
                        color = AccentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            onReply("Ack from receiver: got '${message.payload.take(15)}...'")
                        }
                    )
                }
            }
        }
    }
}
