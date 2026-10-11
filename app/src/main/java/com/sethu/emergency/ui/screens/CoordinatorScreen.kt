package com.sethu.emergency.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.comm.ConnectionState
import com.sethu.emergency.comm.BluetoothPermissionHelper
import com.sethu.emergency.comm.bluetooth.BluetoothRfcommTransport
import com.sethu.emergency.comm.coordinator.CoordinatorDashboard
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.coordinator.IncidentPriority
import com.sethu.emergency.comm.coordinator.PriorityClassifier
import com.sethu.emergency.comm.coordinator.PriorityEvaluation
import com.sethu.emergency.comm.model.SethuMessage
import com.sethu.emergency.comm.model.SosReport
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DashboardSortOrder(val label: String) {
    PRIORITY_FIRST("Priority Triage"),
    NEWEST_FIRST("Newest First")
}

@Composable
fun CoordinatorScreen(
    transport: BluetoothRfcommTransport,
    onBack: () -> Unit,
    onTabSelected: (String) -> Unit,
    onOpenOfflineComm: () -> Unit = {},
    onOpenMap: () -> Unit = {},
    coordinatorStore: CoordinatorReportStore,
    linkStatusText: String = "",
    linkNeedsFix: Boolean = false,
    onFixLink: () -> Unit = {}
) {
    val context = LocalContext.current
    var showHelpDialog by remember { mutableStateOf(false) }
    var selectedReportForDetails by remember { mutableStateOf<SosReport?>(null) }
    var selectedPriorityFilter by remember { mutableStateOf<IncidentPriority?>(null) }
    var sortOrder by remember { mutableStateOf(DashboardSortOrder.NEWEST_FIRST) }

    val connectionState by transport.connectionState.collectAsState()
    val receivedMessages by transport.receivedMessages.collectAsState()

    // Canonical persistent Coordinator Report Store from session runtime
    val actualStore = coordinatorStore
    val receivedItems by actualStore.itemsFlow.collectAsState()
    val receivedReports = remember(receivedItems) { receivedItems.map { it.report } }
    val receivedAtById = remember(receivedItems) { receivedItems.associate { it.report.reportId to it.receivedAt } }

    val isConnected = connectionState is ConnectionState.Connected
    val peerName = (connectionState as? ConnectionState.Connected)?.remoteDeviceName ?: ""

    // Evaluate priorities for all received reports
    val evaluatedReports = remember(receivedReports) {
        receivedReports.map { report ->
            report to PriorityClassifier.evaluate(report)
        }
    }

    // Latest incoming incident (most recent timestamp / reception)
    val latestIncident = remember(evaluatedReports, receivedAtById) {
        evaluatedReports.maxWithOrNull(
            compareBy<Pair<SosReport, PriorityEvaluation>> { receivedAtById[it.first.reportId] ?: 0L }
                .thenBy { it.first.timestamp }
        )
    }

    // Summary counts calculated deterministically from stored reports
    val totalCount = receivedReports.size
    val criticalCount = remember(evaluatedReports) {
        evaluatedReports.count { it.second.priority == IncidentPriority.CRITICAL }
    }
    val highCount = remember(evaluatedReports) {
        evaluatedReports.count { it.second.priority == IncidentPriority.HIGH }
    }
    val mediumCount = remember(evaluatedReports) {
        evaluatedReports.count { it.second.priority == IncidentPriority.MEDIUM }
    }
    val unknownCount = remember(evaluatedReports) {
        evaluatedReports.count { it.second.priority == IncidentPriority.UNKNOWN }
    }
    val noLocationCount = remember(receivedReports) {
        receivedReports.count { it.latitude == null || it.longitude == null }
    }

    // Filter and Sort: supports both Newest-First (recency) and Priority-First triage
    val filteredSortedReports = remember(evaluatedReports, selectedPriorityFilter, sortOrder, receivedAtById) {
        val filtered = if (selectedPriorityFilter == null) {
            evaluatedReports
        } else {
            evaluatedReports.filter { it.second.priority == selectedPriorityFilter }
        }
        when (sortOrder) {
            DashboardSortOrder.PRIORITY_FIRST -> filtered.sortedWith(
                compareBy<Pair<SosReport, PriorityEvaluation>> { it.second.priority.rank }
                    .thenByDescending { receivedAtById[it.first.reportId] ?: 0L }
            )
            DashboardSortOrder.NEWEST_FIRST -> filtered.sortedByDescending { receivedAtById[it.first.reportId] ?: 0L }
        }
    }

    // A newly received SOS must never be silently hidden by an active priority filter.
    var knownReportIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(receivedItems) {
        val previous = knownReportIds
        if (previous != null) {
            val arrived = CoordinatorDashboard.arrivals(previous, receivedItems).map { it.report }
            if (CoordinatorDashboard.shouldClearFilter(selectedPriorityFilter, arrived)) selectedPriorityFilter = null
        }
        knownReportIds = receivedItems.map { it.report.reportId }.toSet()
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

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Emergency Coordinator",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Priority Triage Dashboard",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val count = actualStore.reloadFromDisk()
                            Toast.makeText(context, "Durable store synced ($count incidents)", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = SethuIcons.Refresh,
                                contentDescription = "Sync",
                                tint = AccentGreen,
                                modifier = Modifier.size(20.dp)
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
            }
        },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                // Connection Banner
                HonestNoticeBanner(
                    title = if (isConnected) "Coordinator Linked: $peerName (RFCOMM)" else "Coordinator Link: Standby",
                    subtitle = linkStatusText.ifBlank {
                        if (isConnected) "Listening for forwarded emergency reports from Relay." else "Tap to open Direct Link to pair or connect to Vivo Relay."
                    },
                    modifier = Modifier.clickable { if (linkNeedsFix) onFixLink() else onOpenOfflineComm() }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // RFCOMM Server Control for Stage D (Motorola Coordinator listens for incoming SOS from Vivo Relay)
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

                Spacer(modifier = Modifier.height(14.dp))

                // Dashboard Overview Metric Cards (Requirement A)
                DashboardOverviewCards(
                    totalCount = totalCount,
                    criticalCount = criticalCount,
                    highCount = highCount,
                    noLocationCount = noLocationCount
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Map Shortcut Button
                Button(
                    onClick = onOpenMap,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF14241B)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardSelectedBorder.copy(alpha = 0.6f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = SethuIcons.NavMap,
                            contentDescription = "Map",
                            tint = AccentGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "View Incident Spatial Map (${totalCount - noLocationCount} Mapped)",
                            color = AccentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Latest Incoming Incident Highlight Card (Requirement B)
                latestIncident?.let { (latestReport, latestEval) ->
                    val recencyStr = remember(latestReport.timestamp) { TimeFormatter.formatTime(latestReport.timestamp) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF14241B))
                            .border(1.dp, CardSelectedBorder, RoundedCornerShape(12.dp))
                            .clickable { selectedReportForDetails = latestReport }
                            .padding(12.dp)
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
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentGreenContainer)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "LATEST RECEIVED",
                                            color = AccentGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = latestReport.category,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = recencyStr,
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "From ${latestReport.senderDeviceName} • [${latestEval.priority.label}] ${latestEval.reason.take(50)}...",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Priority Filter Chips (Requirement E)
                PriorityFilterChips(
                    selectedFilter = selectedPriorityFilter,
                    totalCount = totalCount,
                    criticalCount = criticalCount,
                    highCount = highCount,
                    mediumCount = mediumCount,
                    unknownCount = unknownCount,
                    onSelectFilter = { selectedPriorityFilter = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Section Header with Sort Toggle (Requirement B)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (selectedPriorityFilter == null) "Triage Incidents ($totalCount)" else "${selectedPriorityFilter?.label} Incidents (${filteredSortedReports.size})",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedPriorityFilter != null) {
                            Text(
                                text = "Filtered (${filteredSortedReports.size} of $totalCount) • Tap chip to show all",
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sortOrder == DashboardSortOrder.NEWEST_FIRST) AccentGreen.copy(alpha = 0.2f) else CardBackground)
                                .border(1.dp, if (sortOrder == DashboardSortOrder.NEWEST_FIRST) AccentGreen else CardBorder, RoundedCornerShape(6.dp))
                                .clickable { sortOrder = DashboardSortOrder.NEWEST_FIRST }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Newest",
                                color = if (sortOrder == DashboardSortOrder.NEWEST_FIRST) AccentGreen else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sortOrder == DashboardSortOrder.PRIORITY_FIRST) AccentGreen.copy(alpha = 0.2f) else CardBackground)
                                .border(1.dp, if (sortOrder == DashboardSortOrder.PRIORITY_FIRST) AccentGreen else CardBorder, RoundedCornerShape(6.dp))
                                .clickable { sortOrder = DashboardSortOrder.PRIORITY_FIRST }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Priority",
                                color = if (sortOrder == DashboardSortOrder.PRIORITY_FIRST) AccentGreen else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Incidents List or Empty State (Requirement C)
                if (filteredSortedReports.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(CardBackground)
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = SethuIcons.DocumentList,
                                contentDescription = "Empty",
                                tint = TextMuted,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (totalCount == 0) {
                                    "No forwarded SOS reports received yet.\nConnect with Vivo Relay to receive forwarded emergency incidents."
                                } else {
                                    "No incidents match the '${selectedPriorityFilter?.label ?: "All"}' filter."
                                },
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        filteredSortedReports.forEach { (report, evaluation) ->
                            IncidentPriorityCard(
                                report = report,
                                evaluation = evaluation,
                                receivedAt = receivedAtById[report.reportId],
                                onClick = { selectedReportForDetails = report }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))
            }
        }

    // Incident Details Dialog (Requirement D)
    selectedReportForDetails?.let { report ->
        val evaluation = remember(report) { PriorityClassifier.evaluate(report) }
        IncidentDetailDialog(
            report = report,
            evaluation = evaluation,
            receivedAt = receivedAtById[report.reportId],
            context = context,
            onOpenMap = onOpenMap,
            onDismiss = { selectedReportForDetails = null }
        )
    }

    // Help / Info Dialog
    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Coordinator Dashboard Triage",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "The Emergency Coordinator operates 100% offline.\n\n" +
                            "• CRITICAL: Immediate life-threatening conditions (unconscious, cardiac arrest, severe blood loss, drowning).\n" +
                            "• HIGH: Explicitly trapped individuals, serious injuries, children in danger, or active fires.\n" +
                            "• MEDIUM: Floods, elderly assistance requests, or reported incidents requiring assistance.\n" +
                            "• UNKNOWN: Insufficient or contradictory information.\n\n" +
                            "Reports are deduplicated by report ID, persisted locally, and acknowledged with verified Coordinator ACKs over RFCOMM.",
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

/**
 * Metric summary cards at the top of the dashboard.
 */
@Composable
private fun DashboardOverviewCards(
    totalCount: Int,
    criticalCount: Int,
    highCount: Int,
    noLocationCount: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Total Unique Incidents
            MetricSummaryCard(
                title = "Total Incidents",
                value = "$totalCount",
                subtitle = "Unique received",
                accentColor = AccentGreen,
                modifier = Modifier.weight(1f)
            )

            // Critical Priority
            MetricSummaryCard(
                title = "Critical",
                value = "$criticalCount",
                subtitle = "Life-threatening",
                accentColor = Color(0xFFFF5252),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // High Priority
            MetricSummaryCard(
                title = "High Priority",
                value = "$highCount",
                subtitle = "Trapped / Urgent",
                accentColor = Color(0xFFFFB74D),
                modifier = Modifier.weight(1f)
            )

            // Location Unavailable
            MetricSummaryCard(
                title = "No Location",
                value = "$noLocationCount",
                subtitle = "GPS unavailable",
                accentColor = Color(0xFFFF7043),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricSummaryCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Filter chips allowing filtering by priority level.
 */
@Composable
private fun PriorityFilterChips(
    selectedFilter: IncidentPriority?,
    totalCount: Int,
    criticalCount: Int,
    highCount: Int,
    mediumCount: Int,
    unknownCount: Int,
    onSelectFilter: (IncidentPriority?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChipItem(
            label = "All ($totalCount)",
            isSelected = selectedFilter == null,
            onClick = { onSelectFilter(null) }
        )
        FilterChipItem(
            label = "Critical ($criticalCount)",
            isSelected = selectedFilter == IncidentPriority.CRITICAL,
            accentColor = Color(0xFFFF5252),
            onClick = { onSelectFilter(IncidentPriority.CRITICAL) }
        )
        FilterChipItem(
            label = "High ($highCount)",
            isSelected = selectedFilter == IncidentPriority.HIGH,
            accentColor = Color(0xFFFFB74D),
            onClick = { onSelectFilter(IncidentPriority.HIGH) }
        )
        FilterChipItem(
            label = "Medium ($mediumCount)",
            isSelected = selectedFilter == IncidentPriority.MEDIUM,
            accentColor = Color(0xFF4DD0E1),
            onClick = { onSelectFilter(IncidentPriority.MEDIUM) }
        )
        FilterChipItem(
            label = "Unknown ($unknownCount)",
            isSelected = selectedFilter == IncidentPriority.UNKNOWN,
            accentColor = Color(0xFF9EABA3),
            onClick = { onSelectFilter(IncidentPriority.UNKNOWN) }
        )
        Spacer(modifier = Modifier.width(6.dp))
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    accentColor: Color = AccentGreen,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else CardBackground)
            .border(
                1.dp,
                if (isSelected) accentColor else CardBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (isSelected) accentColor else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Individual Incident Card with rich details and priority badges.
 */
@Composable
private fun IncidentPriorityCard(
    report: SosReport,
    evaluation: PriorityEvaluation,
    receivedAt: Long? = null,
    onClick: () -> Unit
) {
    val createdTimeStr = remember(report.timestamp) { TimeFormatter.formatTime(report.timestamp) }
    val receivedTimeStr = remember(receivedAt) { TimeFormatter.formatTime(receivedAt) }

    val (badgeBg, badgeBorder, badgeText, badgeLabel) = when (evaluation.priority) {
        IncidentPriority.CRITICAL -> QuadColor(
            Color(0xFF3B1212),
            Color(0xFFFF3333),
            Color(0xFFFF5252),
            "[CRITICAL]"
        )
        IncidentPriority.HIGH -> QuadColor(
            Color(0xFF33200B),
            Color(0xFFFF9800),
            Color(0xFFFFB74D),
            "[HIGH]"
        )
        IncidentPriority.MEDIUM -> QuadColor(
            Color(0xFF0F262B),
            Color(0xFF00BCD4),
            Color(0xFF4DD0E1),
            "[MEDIUM]"
        )
        IncidentPriority.UNKNOWN -> QuadColor(
            Color(0xFF1E2421),
            Color(0xFF4E5D55),
            Color(0xFF9EABA3),
            "[UNKNOWN]"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBackground)
            .border(1.dp, if (evaluation.priority == IncidentPriority.CRITICAL) Color(0xFFFF3333).copy(alpha = 0.6f) else CardBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column {
            // Header Row: Priority Badge + Category + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Explicit Priority Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badgeBg)
                            .border(1.dp, badgeBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Category Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1B2620))
                            .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = report.category.uppercase(),
                            color = TextPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Created: $createdTimeStr",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (receivedAt != null && receivedAt != report.timestamp) {
                        Text(
                            text = "Coord Recv: $receivedTimeStr",
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Explainable Reason
            Text(
                text = "Reason: ${evaluation.reason}",
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Structured Triage Flags (Never claiming "No" for unknown/unspecified)
            val childrenStr = if (report.childrenInvolved) "Yes (Flagged)" else "Not reported"
            val elderlyStr = if (report.elderlyInvolved) "Yes (Flagged)" else "Not reported"
            val medicalStr = if (report.medicalEmergency) "Yes (Emergency)" else "Not reported"
            val injuryStr = if (report.category.equals("Injury", true) || report.notes.contains("injur", true)) "Yes (Reported)" else "Not reported"
            val peopleCountStr = report.peopleCount.ifBlank { "Not provided" }

            Text(
                text = "People: $peopleCountStr  |  Child: $childrenStr  |  Elderly: $elderlyStr  |  Injury: $injuryStr  |  Medical: $medicalStr",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            if (report.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Notes: \"${report.notes}\"",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
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
                                text = "Location Unavailable (${report.locationStatus})",
                                color = Color(0xFFFFB74D),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer: ID, Origin, Delivery Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${report.reportId.take(8)}... | ${report.senderDeviceName}",
                    color = TextMuted,
                    fontSize = 10.sp
                )

                Text(
                    text = "✓ Coordinator Confirmed",
                    color = AccentGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Detailed view dialog displaying complete incident parameters, full notes, and Copy Coordinates action.
 */
@Composable
private fun IncidentDetailDialog(
    report: SosReport,
    evaluation: PriorityEvaluation,
    receivedAt: Long? = null,
    context: Context,
    onOpenMap: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val formattedDateTime = remember(report.timestamp) { TimeFormatter.formatDateTime(report.timestamp) }
    val formattedReceivedDateTime = remember(receivedAt) { TimeFormatter.formatDateTime(receivedAt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Incident Details",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "[${evaluation.priority.label.uppercase()}]",
                        color = when (evaluation.priority) {
                            IncidentPriority.CRITICAL -> Color(0xFFFF5252)
                            IncidentPriority.HIGH -> Color(0xFFFFB74D)
                            IncidentPriority.MEDIUM -> Color(0xFF4DD0E1)
                            IncidentPriority.UNKNOWN -> Color(0xFF9EABA3)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "ID: ${report.reportId}",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Priority & Reason
                DetailSection(title = "Priority & Reason") {
                    Text(
                        text = evaluation.reason,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 16.sp
                    )
                    if (evaluation.explicitHazards.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Detected hazards: ${evaluation.explicitHazards.joinToString(", ")}",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Structured Fields
                DetailSection(title = "Triage Information") {
                    DetailRow(label = "Category", value = report.category)
                    DetailRow(label = "People Count", value = report.peopleCount.ifBlank { "Not provided" })
                    DetailRow(
                        label = "Children Involved",
                        value = if (report.childrenInvolved) "Yes (Flagged)" else "Not reported"
                    )
                    DetailRow(
                        label = "Elderly Involved",
                        value = if (report.elderlyInvolved) "Yes (Flagged)" else "Not reported"
                    )
                    DetailRow(
                        label = "Medical Emergency",
                        value = if (report.medicalEmergency) "Yes (Flagged)" else "Not reported"
                    )
                    val injuryStatus = if (report.category.equals("Injury", true) || report.notes.contains("injur", true)) "Yes (Reported)" else "Not reported"
                    DetailRow(label = "Injury Status", value = injuryStatus)
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Notes
                DetailSection(title = "Emergency Notes") {
                    if (report.notes.isNotBlank()) {
                        Text(
                            text = "\"${report.notes}\"",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            text = "No notes provided in report.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Location Details & Copy Action
                DetailSection(title = "Location Information") {
                    if (report.latitude != null && report.longitude != null) {
                        Text(
                            text = "Latitude: ${report.latitude}\nLongitude: ${report.longitude}\nAccuracy: ±${report.accuracyMeters?.toInt() ?: 0} meters\nStatus: ${report.locationStatus}",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Action Buttons: Copy Coordinates + View on Map
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "GPS Coordinates",
                                        "${report.latitude}, ${report.longitude}"
                                    )
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(
                                        context,
                                        "Coordinates copied: ${report.latitude}, ${report.longitude}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Text(
                                    text = "Copy Coords",
                                    color = DarkBackground,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = {
                                    onDismiss()
                                    onOpenMap()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2F26)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                            ) {
                                Text(
                                    text = "View on Map",
                                    color = AccentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Coordinates unavailable.\nStatus: ${report.locationStatus}",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Provenance & Transmission Details
                DetailSection(title = "Provenance & Delivery") {
                    DetailRow(label = "Origin Device", value = report.senderDeviceName)
                    DetailRow(label = "Original Creation Time", value = formattedDateTime)
                    if (receivedAt != null && receivedAt != report.timestamp) {
                        DetailRow(label = "Coordinator Received Time", value = formattedReceivedDateTime)
                    }
                    DetailRow(label = "Delivery State", value = "DELIVERED_TO_COORDINATOR")
                    DetailRow(label = "RFCOMM Status", value = "Coordinator ACK returned")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = AccentGreen, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = CardBackground
    )
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0F1713))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                color = AccentGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextSecondary, fontSize = 11.sp)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private data class QuadColor(
    val bg: Color,
    val border: Color,
    val text: Color,
    val label: String
)
