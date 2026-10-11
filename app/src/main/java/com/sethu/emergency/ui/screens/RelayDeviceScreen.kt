package com.sethu.emergency.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.comm.BluetoothPermissionHelper
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.relay.DeliveryState
import com.sethu.emergency.comm.relay.RelayReportItem
import com.sethu.emergency.comm.relay.RelayReportStore
import com.sethu.emergency.ui.components.HonestNoticeBanner
import com.sethu.emergency.ui.components.SethuBottomNavigationBar
import com.sethu.emergency.ui.components.SethuIcons
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.AccentGreenContainer
import com.sethu.emergency.ui.theme.CardBackground
import com.sethu.emergency.ui.theme.CardBorder
import com.sethu.emergency.ui.theme.CardSelectedBorder
import com.sethu.emergency.ui.theme.DarkBackground
import com.sethu.emergency.ui.theme.TextMuted
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary
import com.sethu.emergency.comm.util.TimeFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RelayDeviceScreen(
    transport: BluetoothRfcommTransport,
    onBack: () -> Unit,
    onTabSelected: (String) -> Unit,
    onOpenOfflineComm: () -> Unit = {},
    relayStore: RelayReportStore? = null,
    linkStatusText: String = "",
    linkNeedsFix: Boolean = false,
    onFixLink: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showHelpDialog by remember { mutableStateOf(false) }

    val connectionState by transport.connectionState.collectAsState()

    // Persistent Relay Report Store (session-level with fallback)
    val actualStore = relayStore ?: remember { RelayReportStore(context) }
    val storedReports by actualStore.reportsFlow.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected
    val peerName = (connectionState as? ConnectionState.Connected)?.remoteDeviceName ?: ""

    val pendingCount = storedReports.count {
        it.deliveryState != DeliveryState.DELIVERED_TO_COORDINATOR
    }
    val deliveredCount = storedReports.count {
        it.deliveryState == DeliveryState.DELIVERED_TO_COORDINATOR
    }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            SethuBottomNavigationBar(
                currentTab = "Home",
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
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

                Text(
                    text = "Relay Device (Vivo Y15)",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )

                IconButton(onClick = { showHelpDialog = true }) {
                    Icon(
                        imageVector = SethuIcons.HelpOutline,
                        contentDescription = "Help",
                        tint = TextSecondary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Connection Banner
                HonestNoticeBanner(
                    title = if (isConnected) "Active Link: Connected to $peerName (RFCOMM)" else "Relay Mode: Standby",
                    subtitle = linkStatusText.ifBlank {
                        if (isConnected) "Ready to exchange or forward emergency packets." else "Tap to open Direct Device Link to pair or switch peer."
                    },
                    modifier = Modifier.clickable { if (linkNeedsFix) onFixLink() else onOpenOfflineComm() }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // RFCOMM Server Control for Stage A (listen for incoming SOS from iQOO Sender)
                val serverState by transport.serverState.collectAsState()
                val hasPermissions = remember(context) { BluetoothPermissionHelper.hasPermissions(context) }
                HostServerCard(
                    serverState = serverState,
                    onHostServer = {
                        if (DirectLinkActions.onStartServerTapped(hasPermissions, transport)) {
                            onFixLink()
                        }
                    },
                    onStopHosting = { DirectLinkActions.onStopServerTapped(transport) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onOpenOfflineComm,
                    colors = ButtonDefaults.buttonColors(containerColor = CardBackground),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Direct Device Link (Connect to Peers)", color = AccentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Relay Status Circle
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentGreenContainer else Color(0xFF13221A))
                        .border(1.5.dp, if (isConnected) CardSelectedBorder else CardBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = SethuIcons.RelayNodes,
                        contentDescription = "Relay",
                        tint = AccentGreen,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = if (isConnected) "Relay Linked: $peerName" else "Relay Standby",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Single-socket RFCOMM: Receive SOS from iQOO Sender, then connect to Motorola Coordinator to forward queued reports.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Forwarding Management Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBackground)
                        .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Forwarding Queue",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Pending: $pendingCount",
                                    color = if (pendingCount > 0) Color(0xFFFFB74D) else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Delivered: $deliveredCount",
                                    color = AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Switch connection button
                            Button(
                                onClick = onOpenOfflineComm,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E2F26)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "Switch Peer Link",
                                    color = AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // No manual Forward: pending reports are sent automatically once a Coordinator link exists.
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF1B231F))
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (pendingCount == 0) "Queue empty"
                                    else if (isConnected) "Auto-forwarding $pendingCount…"
                                    else "$pendingCount pending Coordinator delivery",
                                    color = if (pendingCount > 0) Color(0xFFFFB74D) else TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Received Emergency Reports Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Relayed Reports (${storedReports.size})",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (storedReports.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentGreenContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Durable Local Storage Active",
                                color = AccentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (storedReports.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardBackground)
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No SOS reports in relay storage.\nConnect to iQOO SOS Sender to receive reports,\nthen connect to Motorola Coordinator to forward.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        storedReports.reversed().forEach { item ->
                            RelayReportItemCard(item = item)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Relay & Delivery Semantics",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Phase 6 Store-and-Forward:\n" +
                            "1. Connect to the Sender in Direct Device Link -> SOS is saved on this phone, then acknowledged.\n" +
                            "2. The report stays PENDING COORDINATOR DELIVERY even if the Sender disconnects.\n" +
                            "3. Connect to the Coordinator in Direct Device Link (your tap).\n" +
                            "4. Pending reports are forwarded automatically - no Forward button.\n" +
                            "5. Only the Coordinator's ACK (sent after it saved the report) marks it DELIVERED.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) {
                    Text("Close", color = AccentGreen)
                }
            },
            containerColor = CardBackground
        )
    }
}

@Composable
private fun RelayReportItemCard(item: RelayReportItem) {
    val report = item.report
    val createdTimeStr = remember(report.timestamp) { TimeFormatter.formatTime(report.timestamp) }
    val receivedTimeStr = remember(item.receivedAt) { TimeFormatter.formatTime(item.receivedAt) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141F1A))
            .border(
                1.5.dp,
                when (item.deliveryState) {
                    DeliveryState.DELIVERED_TO_COORDINATOR -> AccentGreen
                    DeliveryState.FORWARD_FAILED -> Color(0xFFFF5252)
                    DeliveryState.FORWARDED_TO_COORDINATOR -> Color(0xFF64B5F6)
                    else -> CardSelectedBorder
                },
                RoundedCornerShape(14.dp)
            )
            .padding(14.dp)
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFF3333).copy(alpha = 0.2f))
                            .border(1.dp, Color(0xFFFF3333), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = report.category.uppercase(),
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "From: ${report.senderDeviceName}",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Created: $createdTimeStr",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Relay Recv: $receivedTimeStr",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "People affected: ${report.peopleCount}  |  Children: ${report.childrenInvolved}  |  Elderly: ${report.elderlyInvolved}  |  Medical: ${report.medicalEmergency}",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            if (report.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Notes: \"${report.notes}\"",
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Location Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F1713))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = SethuIcons.LocationBeacon,
                        contentDescription = "Location",
                        tint = if (report.latitude != null) AccentGreen else Color(0xFFFFB74D),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        if (report.latitude != null && report.longitude != null) {
                            Text(
                                text = "Lat: ${"%.5f".format(report.latitude)}, Lon: ${"%.5f".format(report.longitude)} (±${report.accuracyMeters?.toInt() ?: 0}m)",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "Location Status: ${report.locationStatus}",
                                color = Color(0xFFFFB74D),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Delivery State Badge & Hop Tracking
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        when (item.deliveryState) {
                            DeliveryState.DELIVERED_TO_COORDINATOR -> AccentGreenContainer
                            DeliveryState.FORWARD_FAILED -> Color(0xFF2A1515)
                            DeliveryState.FORWARDED_TO_COORDINATOR -> Color(0xFF142433)
                            else -> Color(0xFF211D14)
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = when (item.deliveryState) {
                                DeliveryState.RECEIVED_BY_RELAY -> "RECEIVED BY RELAY"
                                DeliveryState.SAVED_BY_RELAY -> "RECEIVED — SAVED LOCALLY"
                                DeliveryState.PENDING_FORWARD -> "RECEIVED — PENDING COORDINATOR DELIVERY"
                                DeliveryState.FORWARDED_TO_COORDINATOR -> "FORWARDED TO COORDINATOR"
                                DeliveryState.DELIVERED_TO_COORDINATOR -> "DELIVERED TO COORDINATOR"
                                DeliveryState.FORWARD_FAILED -> "PENDING — COORDINATOR DELIVERY NOT CONFIRMED"
                            },
                            color = when (item.deliveryState) {
                                DeliveryState.DELIVERED_TO_COORDINATOR -> AccentGreen
                                DeliveryState.FORWARD_FAILED -> Color(0xFFFF5252)
                                DeliveryState.FORWARDED_TO_COORDINATOR -> Color(0xFF64B5F6)
                                else -> Color(0xFFFFB74D)
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (item.deliveryState) {
                                DeliveryState.DELIVERED_TO_COORDINATOR -> "Delivered to Coordinator at ${TimeFormatter.formatTime(item.deliveredAt)} (ACK Confirmed)"
                                DeliveryState.FORWARDED_TO_COORDINATOR -> "Forwarded to socket at ${TimeFormatter.formatTime(item.forwardedAt)}; waiting for ACK"
                                DeliveryState.FORWARD_FAILED -> "Forward failed: ${item.lastError ?: "Link disconnected"}"
                                else -> "Saved on this phone at $receivedTimeStr; waits for a Coordinator link"
                            },
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }

                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ID: ${report.reportId.take(16)}...",
                    color = TextMuted,
                    fontSize = 10.sp
                )
                Text(
                    text = "✓ Hop 1 ACK Confirmed",
                    color = AccentGreen,
                    fontSize = 10.sp
                )
            }
        }
    }
}
