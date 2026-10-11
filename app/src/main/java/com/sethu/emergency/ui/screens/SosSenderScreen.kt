package com.sethu.emergency.ui.screens

import android.Manifest
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.ai.AiExtractionState
import com.sethu.emergency.ai.OfflineAiExtractor
import com.sethu.emergency.ai.ProposedExtraction
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.sender.SenderDeliveryState
import com.sethu.emergency.comm.sender.SenderReportStore
import com.sethu.emergency.location.LocationData
import com.sethu.emergency.location.LocationHelper
import com.sethu.emergency.speech.OfflineSpeechManager
import com.sethu.emergency.speech.SpeechState
import com.sethu.emergency.ui.components.HonestNoticeBanner
import com.sethu.emergency.ui.components.SethuBottomNavigationBar
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
import com.sethu.emergency.ui.theme.ToggleTrackUnchecked
import com.sethu.emergency.comm.util.TimeFormatter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EmergencyTypeOption(
    val name: String,
    val icon: ImageVector
)

@Composable
fun SosSenderScreen(
    transport: BluetoothRfcommTransport,
    onBack: () -> Unit,
    onTabSelected: (String) -> Unit,
    onOpenOfflineComm: () -> Unit = {},
    senderStore: SenderReportStore? = null,
    linkStatusText: String = "",
    linkNeedsFix: Boolean = false,
    onFixLink: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val actualSenderStore = senderStore ?: remember { SenderReportStore(context) }
    val connectionState by transport.connectionState.collectAsState()
    val storedReports by actualSenderStore.reportsFlow.collectAsState()
    val storePersistenceError by actualSenderStore.persistenceError.collectAsState()

    // Rapid-tap debounce protection for one-tap SOS
    var lastOneTapTimestamp by remember { mutableLongStateOf(0L) }
    var isOneTapTransmitting by remember { mutableStateOf(false) }
    var lastStructuredTimestamp by remember { mutableLongStateOf(0L) }

    // Offline Speech Recognition Manager
    val speechManager = remember { OfflineSpeechManager(coroutineScope) }
    val speechState by speechManager.state.collectAsState()
    val partialText by speechManager.partialText.collectAsState()
    val finalText by speechManager.finalText.collectAsState()

    // Offline AI Emergency Extractor (llama.cpp Qwen2.5-0.5B)
    val aiExtractor = remember { OfflineAiExtractor(coroutineScope) }
    val aiState by aiExtractor.state.collectAsState()

    var editableTranscription by remember { mutableStateOf("") }
    var showTranscriptionBox by remember { mutableStateOf(false) }
    var showMicPermissionRationale by remember { mutableStateOf(false) }

    var selectedType by remember { mutableStateOf("Flood") }
    var selectedCount by remember { mutableStateOf("1") }
    var elderlyInvolved by remember { mutableStateOf(false) }
    var childrenInvolved by remember { mutableStateOf(false) }
    var medicalEmergency by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    var locationData by remember {
        mutableStateOf(
            LocationData(
                status = if (LocationHelper.hasLocationPermission(context)) "Acquiring..." else SosReport.LOCATION_STATUS_PERMISSION_DENIED
            )
        )
    }
    var isFetchingLocation by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }

    // Dialog state after sending SOS
    var activeReport by remember { mutableStateOf<SosReport?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Initialize speech model and clean up on exit
    DisposableEffect(Unit) {
        speechManager.initModel(context)
        onDispose {
            speechManager.release()
            aiExtractor.release()
        }
    }

    // Keep editable transcription updated when speech engine finalizes results
    LaunchedEffect(finalText) {
        if (finalText.isNotBlank()) {
            editableTranscription = finalText
            showTranscriptionBox = true
        }
    }


    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speechManager.startListening(context)
            showTranscriptionBox = true
        }
    }

    fun handleMicButtonClick() {
        if (!speechManager.hasRecordPermission(context)) {
            showMicPermissionRationale = true
        } else {
            if (speechState is SpeechState.Listening) {
                speechManager.stopListening()
            } else {
                val started = speechManager.startListening(context)
                if (started) {
                    showTranscriptionBox = true
                }
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.any { it }
        if (granted) {
            coroutineScope.launch {
                isFetchingLocation = true
                locationData = LocationHelper.fetchCurrentLocation(context)
                isFetchingLocation = false
            }
        } else {
            locationData = LocationData(status = SosReport.LOCATION_STATUS_PERMISSION_DENIED)
        }
    }

    fun requestGpsFix() {
        if (!LocationHelper.hasLocationPermission(context)) {
            showPermissionRationale = true
        } else {
            coroutineScope.launch {
                isFetchingLocation = true
                locationData = LocationHelper.fetchCurrentLocation(context)
                isFetchingLocation = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (LocationHelper.hasLocationPermission(context)) {
            requestGpsFix()
        }
    }

    // One-Tap SOS Action: Instant, non-blocking dispatch with available coordinates
    fun handleOneTapSos() {
        val now = System.currentTimeMillis()
        if (isOneTapTransmitting || (now - lastOneTapTimestamp < 2500L)) {
            return
        }
        lastOneTapTimestamp = now
        isOneTapTransmitting = true

        try {
            // Resolve best instantaneous location without suspending or waiting
            val resolvedLocation = if (locationData.latitude != null) {
                locationData
            } else {
                LocationHelper.getImmediateLastKnownLocation(context)
            }

            val report = SosReport(
                reportId = java.util.UUID.randomUUID().toString(),
                timestamp = now,
                senderDeviceName = transport.getLocalDeviceName(),
                category = "Other",
                peopleCount = "1",
                childrenInvolved = false,
                elderlyInvolved = false,
                medicalEmergency = false,
                notes = "ONE-TAP EMERGENCY SOS",
                latitude = resolvedLocation.latitude,
                longitude = resolvedLocation.longitude,
                accuracyMeters = resolvedLocation.accuracyMeters,
                locationTimestamp = resolvedLocation.timestamp,
                locationStatus = resolvedLocation.status
            )

            // Durably persist to sender outbox BEFORE any transmission. Delivery is then owned by the single
            // StoreAndForwardController (role-validated peer, ACK-gated state changes, automatic retries); the
            // outbox flow wakes it immediately, so there is no second, unvalidated send path racing with it.
            actualSenderStore.saveReport(report)
            activeReport = report
        } finally {
            isOneTapTransmitting = false
        }
    }

    val emergencyOptions = listOf(
        EmergencyTypeOption("Flood", SethuIcons.WaveFlood),
        EmergencyTypeOption("Medical", SethuIcons.MedicalCross),
        EmergencyTypeOption("Fire", SethuIcons.Fire),
        EmergencyTypeOption("Trapped", SethuIcons.Trapped),
        EmergencyTypeOption("Injury", SethuIcons.Pill),
        EmergencyTypeOption("Other", SethuIcons.MoreDots)
    )

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
                    text = "SOS Sender",
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
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Connection Banner
                val isConnected = connectionState is ConnectionState.Connected
                val peerName = (connectionState as? ConnectionState.Connected)?.remoteDeviceName ?: ""

                HonestNoticeBanner(
                    title = if (isConnected) "Connected to $peerName (RFCOMM)" else "Direct Device Link: Not Connected",
                    subtitle = linkStatusText.ifBlank {
                        if (isConnected) "Ready to transmit structured emergency reports." else "Tap to open Direct Device Link to pair/connect."
                    },
                    modifier = Modifier.clickable { if (linkNeedsFix) onFixLink() else onOpenOfflineComm() }
                )
                if (storePersistenceError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Warning: $storePersistenceError. Reports are still queued in memory but may be lost if the app is closed.",
                        color = SosRed,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // One-Tap Emergency SOS Hero Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    SosRed,
                                    Color(0xFFB71C1C)
                                )
                            )
                        )
                        .border(1.5.dp, Color(0xFFFF6E6E), RoundedCornerShape(16.dp))
                        .clickable(enabled = !isOneTapTransmitting) {
                            handleOneTapSos()
                        }
                        .padding(vertical = 18.dp, horizontal = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = SethuIcons.AlertTriangle,
                            contentDescription = "One-Tap SOS",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "ONE-TAP EMERGENCY SOS",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Instant alert with available GPS • No form required",
                                color = Color(0xFFFFCDD2),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // GPS Location Status Card
                GpsLocationStatusCard(
                    locationData = locationData,
                    isFetching = isFetchingLocation,
                    onRequestGps = { requestGpsFix() }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Emergency Report Header
                Text(
                    text = "Emergency Report Details",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Select emergency type",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 2x3 Grid of Emergency Types
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (row in 0..1) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (col in 0..2) {
                                val index = row * 3 + col
                                val option = emergencyOptions[index]
                                val isSelected = option.name == selectedType

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isSelected) AccentGreenContainer else CardBackground
                                        )
                                        .border(
                                            width = 1.5.dp,
                                            color = if (isSelected) CardSelectedBorder else CardBorder,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable { selectedType = option.name }
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = option.icon,
                                            contentDescription = option.name,
                                            tint = if (isSelected) AccentGreen else TextSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = option.name,
                                            color = if (isSelected) AccentGreen else TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // People Count
                Text(
                    text = "People affected",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val counts = listOf("1", "2", "3", "4+")
                    counts.forEach { count ->
                        val isSelected = selectedCount == count
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) AccentGreenContainer else CardBackground
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (isSelected) CardSelectedBorder else CardBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedCount = count }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = count,
                                color = if (isSelected) AccentGreen else TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Toggles
                ToggleRow(
                    title = "Elderly involved",
                    icon = SethuIcons.Trapped,
                    checked = elderlyInvolved,
                    onCheckedChange = { elderlyInvolved = it }
                )

                ToggleRow(
                    title = "Children involved",
                    icon = SethuIcons.CoordinatorTeam,
                    checked = childrenInvolved,
                    onCheckedChange = { childrenInvolved = it }
                )

                ToggleRow(
                    title = "Medical emergency",
                    icon = SethuIcons.MedicalCross,
                    checked = medicalEmergency,
                    onCheckedChange = { medicalEmergency = it }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Notes Header with Offline Microphone Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Notes (optional)",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )

                    // Clearly labelled Microphone Control
                    val isListening = speechState is SpeechState.Listening
                    val isLoadingModel = speechState is SpeechState.LoadingModel

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isListening) Color(0xFFFF3333).copy(alpha = 0.2f) else AccentGreenContainer
                            )
                            .border(
                                1.dp,
                                if (isListening) Color(0xFFFF3333) else CardSelectedBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !isLoadingModel) { handleMicButtonClick() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLoadingModel) {
                                CircularProgressIndicator(
                                    color = AccentGreen,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Loading Model...",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Icon(
                                    imageVector = SethuIcons.Mic,
                                    contentDescription = "Microphone",
                                    tint = if (isListening) Color(0xFFFF5252) else AccentGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isListening) "Stop Recording" else "Speak (Offline STT)",
                                    color = if (isListening) Color(0xFFFF5252) else AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Active Recording / Listening Banner
                if (speechState is SpeechState.Listening) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2B1414))
                            .border(1.dp, Color(0xFFFF3333).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFF3333))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Recording audio... Speak emergency details",
                                        color = Color(0xFFFF8A80),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "Cancel",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { speechManager.cancel() }
                                )
                            }
                            if (partialText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "\"$partialText...\"",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Model Error Banner if speech model fails
                (speechState as? SpeechState.Error)?.let { err ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF261919))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = err.message,
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Transcription Review & Insertion Box
                if (showTranscriptionBox && editableTranscription.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF13221A))
                            .border(1.dp, CardSelectedBorder.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Transcribed Text (Editable Review):",
                                    color = AccentGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    text = "Discard",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable {
                                        editableTranscription = ""
                                        speechManager.clearTranscriptions()
                                        showTranscriptionBox = false
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = editableTranscription,
                                onValueChange = { editableTranscription = it },
                                placeholder = {
                                    Text("Transcribed text appears here. You can edit it before using.", color = TextMuted, fontSize = 12.sp)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF0F1A14),
                                    unfocusedContainerColor = Color(0xFF0F1A14),
                                    focusedBorderColor = CardSelectedBorder,
                                    unfocusedBorderColor = CardBorder,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        notes = if (notes.isBlank()) editableTranscription else "$notes $editableTranscription"
                                        editableTranscription = ""
                                        speechManager.clearTranscriptions()
                                        showTranscriptionBox = false
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Insert into Notes field",
                                        color = DarkBackground,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Existing Manual Notes Field
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = {
                        Text(
                            text = "Add critical situation details (manual typing or voice notes)...",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = TextFieldBackground,
                        unfocusedContainerColor = TextFieldBackground,
                        focusedBorderColor = CardSelectedBorder,
                        unfocusedBorderColor = CardBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Phase 5: Fully Offline AI Emergency Extraction
                val isAiRunning = aiState is AiExtractionState.LoadingModel || aiState is AiExtractionState.Generating
                val currentText = notes.ifBlank { editableTranscription }

                // AI Extract Action Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isAiRunning) Color(0xFF13221A) else CardBackground)
                        .border(
                            1.dp,
                            if (isAiRunning) CardSelectedBorder else CardBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(enabled = !isAiRunning) {
                            if (currentText.isBlank()) {
                                // Prompt user to add text first
                            } else {
                                aiExtractor.extractEmergencyDetails(context, currentText)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = SethuIcons.AutoAwesome,
                                contentDescription = "AI Extraction",
                                tint = if (currentText.isNotBlank()) AccentGreen else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isAiRunning) "Analyzing emergency text..." else "Extract Emergency Details with AI",
                                    color = if (currentText.isNotBlank()) TextPrimary else TextMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isAiRunning) "Running Qwen2.5-0.5B locally" else "Offline local model • Preserves facts & original notes",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (isAiRunning) {
                            CircularProgressIndicator(
                                color = AccentGreen,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Text(
                                text = "Extract",
                                color = if (currentText.isNotBlank()) AccentGreen else TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // AI State Notifications & Errors
                when (val state = aiState) {
                    is AiExtractionState.ModelNotFound -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF261D13))
                                .border(1.dp, Color(0xFFFFB74D).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Offline AI Model Not Found",
                                        color = Color(0xFFFFB74D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Dismiss",
                                        color = TextMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable { aiExtractor.resetState() }
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "To enable local AI extraction, place 'qwen2.5-0.5b-instruct-q4_k_m.gguf' in:\n${aiExtractor.getExpectedModelPath(context)}\nManual SOS entry and Vosk speech-to-text remain fully operational.",
                                    color = Color(0xFFFFE0B2),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }

                    is AiExtractionState.Error -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2A1515))
                                .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = state.message,
                                    color = Color(0xFFFF8A80),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "Dismiss",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.clickable { aiExtractor.resetState() }
                                )
                            }
                        }
                    }

                    is AiExtractionState.Success -> {
                        val proposal = state.proposal
                        var proposedCategory by remember(proposal) { mutableStateOf(proposal.category) }
                        var proposedCount by remember(proposal) { mutableStateOf(proposal.peopleCount ?: selectedCount) }
                        var proposedChildren by remember(proposal) { mutableStateOf(proposal.childrenInvolved) }
                        var proposedElderly by remember(proposal) { mutableStateOf(proposal.elderlyInvolved) }
                        var proposedMedical by remember(proposal) { mutableStateOf(proposal.medicalEmergency) }

                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0F241A))
                                .border(1.5.dp, CardSelectedBorder, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = SethuIcons.AutoAwesome,
                                            contentDescription = null,
                                            tint = AccentGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Proposed AI Extraction",
                                            color = AccentGreen,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = "Discard",
                                        color = TextMuted,
                                        fontSize = 12.sp,
                                        modifier = Modifier.clickable { aiExtractor.resetState() }
                                    )
                                }

                                Text(
                                    text = "Review extracted fields below. Facts are derived strictly from user notes.",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                // Proposed Category Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Category:", color = TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = proposedCategory,
                                        color = AccentGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Proposed People Count Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("People Count:", color = TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = proposal.peopleCount ?: "Not specified (keep $selectedCount)",
                                        color = if (proposal.peopleCount != null) TextPrimary else TextMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Proposed Children Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Children Involved:", color = TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = when (proposal.childrenInvolved) {
                                            true -> "Detected: Yes"
                                            false -> "Stated: No"
                                            null -> "Unknown / Not mentioned"
                                        },
                                        color = when (proposal.childrenInvolved) {
                                            true -> AccentGreen
                                            false -> TextPrimary
                                            null -> TextMuted
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (proposal.childrenInvolved == true) FontWeight.Bold else FontWeight.Normal
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Proposed Elderly Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Elderly Involved:", color = TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = when (proposal.elderlyInvolved) {
                                            true -> "Detected: Yes"
                                            false -> "Stated: No"
                                            null -> "Unknown / Not mentioned"
                                        },
                                        color = when (proposal.elderlyInvolved) {
                                            true -> AccentGreen
                                            false -> TextPrimary
                                            null -> TextMuted
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (proposal.elderlyInvolved == true) FontWeight.Bold else FontWeight.Normal
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Proposed Medical Emergency Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Medical Emergency:", color = TextSecondary, fontSize = 12.sp)
                                    Text(
                                        text = when (proposal.medicalEmergency) {
                                            true -> "Detected: Yes"
                                            false -> "Stated: No"
                                            null -> "Unknown / Not mentioned"
                                        },
                                        color = when (proposal.medicalEmergency) {
                                            true -> AccentGreen
                                            false -> TextPrimary
                                            null -> TextMuted
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (proposal.medicalEmergency == true) FontWeight.Bold else FontWeight.Normal
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Original Notes Preserved Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0A1811))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "Original description: \"${proposal.originalNotes}\"",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Buttons to apply or discard
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Button(
                                        onClick = {
                                            selectedType = proposedCategory
                                            proposal.peopleCount?.let { selectedCount = it }
                                            proposal.childrenInvolved?.let { childrenInvolved = it }
                                            proposal.elderlyInvolved?.let { elderlyInvolved = it }
                                            proposal.medicalEmergency?.let { medicalEmergency = it }
                                            aiExtractor.resetState()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = "Apply to SOS Form",
                                            color = DarkBackground,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {}
                }

                Spacer(modifier = Modifier.height(20.dp))


                // Transmit SOS Report Button (Never sends automatically from speech)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentGreen)
                        .clickable {
                            // Same rapid-tap protection as One-Tap SOS: a double tap must not create two reports.
                            val tapNow = System.currentTimeMillis()
                            if (tapNow - lastStructuredTimestamp < 2500L) return@clickable
                            lastStructuredTimestamp = tapNow
                            val report = SosReport(
                                senderDeviceName = transport.getLocalDeviceName(),
                                category = selectedType,
                                peopleCount = selectedCount,
                                childrenInvolved = childrenInvolved,
                                elderlyInvolved = elderlyInvolved,
                                medicalEmergency = medicalEmergency,
                                notes = notes,
                                latitude = locationData.latitude,
                                longitude = locationData.longitude,
                                accuracyMeters = locationData.accuracyMeters,
                                locationTimestamp = locationData.timestamp,
                                locationStatus = locationData.status
                            )
                            // Durably persist to sender outbox BEFORE any transmission; the delivery engine sends it.
                            actualSenderStore.saveReport(report)
                            activeReport = report
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = SethuIcons.SendPlane,
                                contentDescription = "Send",
                                tint = DarkBackground,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Transmit Structured SOS",
                                color = DarkBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isConnected) "Saved first, then sent via Bluetooth RFCOMM and confirmed by ACK" else "Saved first; sent automatically once you connect to a Relay/Coordinator in Direct Device Link",
                            color = Color(0xFF0F3B25),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Report Transmission Status Dialog
    activeReport?.let { report ->
        // Everything shown here derives from the DURABLE delivery state, never from "a socket is open".
        val deliveryState = storedReports.find { it.report.reportId == report.reportId }?.deliveryState
        val isCoordinatorAcked = deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_COORDINATOR
        val isRelayAcked = deliveryState == SenderDeliveryState.ACKNOWLEDGED_BY_RELAY
        val isInFlight = deliveryState == SenderDeliveryState.TRANSMITTED
        val isConnected = connectionState is ConnectionState.Connected

        AlertDialog(
            onDismissRequest = { activeReport = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCoordinatorAcked -> AccentGreen
                                    isRelayAcked -> Color(0xFF66BB6A)
                                    isInFlight -> Color(0xFFFFB74D)
                                    else -> SosRed
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isCoordinatorAcked -> "SOS Confirmed Delivered!"
                            isRelayAcked -> "SOS Received by Relay (awaiting Coordinator)"
                            isInFlight -> "SOS Sent — Waiting for ACK"
                            else -> "SOS Saved Locally (Pending Peer Link)"
                        },
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Report ID: ${report.reportId.take(12)}...",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "Created: ${TimeFormatter.formatTime(report.timestamp)}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Category: ${report.category} | People: ${report.peopleCount}",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Elderly: ${report.elderlyInvolved} | Children: ${report.childrenInvolved} | Medical: ${report.medicalEmergency}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    if (report.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Notes: \"${report.notes}\"",
                            color = TextPrimary,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Location summary in report
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF141E18))
                            .padding(8.dp)
                    ) {
                        Column {
                            Text(
                                text = "Location Status: ${report.locationStatus}",
                                color = if (report.latitude != null) AccentGreen else Color(0xFFFFB74D),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (report.latitude != null && report.longitude != null) {
                                Text(
                                    text = "Lat: ${"%.5f".format(report.latitude)}, Lon: ${"%.5f".format(report.longitude)}",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Accuracy: ±${report.accuracyMeters?.toInt() ?: 0}m",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Transmission & Acknowledgement Status
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    isCoordinatorAcked -> Color(0xFF11291B)
                                    isRelayAcked -> Color(0xFF162D1C)
                                    isInFlight -> Color(0xFF2B2215)
                                    else -> Color(0xFF2A1515)
                                }
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = when {
                                isCoordinatorAcked -> "Coordinator Confirmed: Emergency coordinator acknowledged receipt of this exact report (ID: ${report.reportId.take(8)})."
                                isRelayAcked -> "Relay Confirmed: An explicit ACK was received from the relay device for this exact report. Final Coordinator confirmation is still pending."
                                isInFlight -> "Waiting for Receiver ACK: Report packet sent over RFCOMM socket. Waiting for receiver confirmation..."
                                else -> "Durable Outbox Stored: The report is saved to persistent storage and queued; it is NOT delivered yet. Link status: ${linkStatusText.ifBlank { "no peer connected" }}. It is sent automatically once you connect to the Relay in Direct Device Link — no need to send it again."
                            },
                            color = when {
                                isCoordinatorAcked -> AccentGreen
                                isRelayAcked -> Color(0xFF81C784)
                                isInFlight -> Color(0xFFFFB74D)
                                else -> SosRed
                            },
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeReport = null }) {
                    Text("OK", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                if (!isConnected) {
                    TextButton(onClick = {
                        activeReport = null
                        onOpenOfflineComm()
                    }) {
                        Text("Open Direct Link", color = AccentGreen)
                    }
                }
            },
            containerColor = CardBackground
        )
    }

    if (showMicPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showMicPermissionRationale = false },
            title = {
                Text(
                    text = "Microphone Permission Required",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Sethu uses your device's microphone for offline speech-to-text so you can dictate emergency notes hands-free.\n\nAll speech recognition runs locally on your device with zero internet connection.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMicPermissionRationale = false
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Text("Grant Permission", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionRationale = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBackground
        )
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = {
                Text(
                    text = "GPS Location Required",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Sethu uses your device's offline GPS hardware to attach exact coordinates to your emergency report so rescue teams can find you.\n\nNo cloud or geocoding services are used.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }) {
                    Text("Allow Location", color = AccentGreen, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text("Cancel", color = TextMuted)
                }
            },
            containerColor = CardBackground
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Phase 4 SOS Help",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "In Phase 4:\n• Tap 'Speak (Offline STT)' to dictate emergency notes offline.\n• Review and edit transcription before clicking 'Insert into Notes field'.\n• Offline GPS coordinates are attached when available.\n• Tap 'Transmit Structured SOS' to send over Bluetooth to the Relay device with verified ACK.",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
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
private fun GpsLocationStatusCard(
    locationData: LocationData,
    isFetching: Boolean,
    onRequestGps: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(
                1.dp,
                if (locationData.latitude != null) CardSelectedBorder.copy(alpha = 0.6f) else CardBorder,
                RoundedCornerShape(16.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10281D)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFetching) {
                        CircularProgressIndicator(
                            color = AccentGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = SethuIcons.LocationBeacon,
                            contentDescription = "GPS",
                            tint = if (locationData.latitude != null) AccentGreen else Color(0xFFFFB74D),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "GPS Coordinates",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )

                    if (locationData.latitude != null && locationData.longitude != null) {
                        Text(
                            text = "${"%.5f".format(locationData.latitude)}, ${"%.5f".format(locationData.longitude)}",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val timeStr = locationData.timestamp?.let {
                            TimeFormatter.formatTime(it)
                        } ?: ""
                        Text(
                            text = if (timeStr.isNotBlank() && timeStr != "Not recorded") "Acc: ±${locationData.accuracyMeters?.toInt() ?: 0}m | GPS Fix: $timeStr" else "Acc: ±${locationData.accuracyMeters?.toInt() ?: 0}m",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    } else {
                        Text(
                            text = locationData.status,
                            color = when (locationData.status) {
                                SosReport.LOCATION_STATUS_PERMISSION_DENIED -> SosRed
                                SosReport.LOCATION_STATUS_AVAILABLE -> AccentGreen
                                else -> Color(0xFFFFB74D)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1D2821))
                    .clickable { onRequestGps() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isFetching) "Locating..." else "Refresh GPS",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentGreen,
                uncheckedThumbColor = TextMuted,
                uncheckedTrackColor = ToggleTrackUnchecked,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}
