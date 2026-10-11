package com.sethu.emergency.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sethu.emergency.comm.coordinator.CoordinatorReportStore
import com.sethu.emergency.comm.coordinator.IncidentPriority
import com.sethu.emergency.comm.coordinator.PriorityClassifier
import com.sethu.emergency.comm.coordinator.PriorityEvaluation
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.comm.util.TimeFormatter
import com.sethu.emergency.location.CoordinateUtils
import com.sethu.emergency.location.GeoBoundingBox
import com.sethu.emergency.ui.components.MapsforgeMapView
import com.sethu.emergency.ui.components.SethuBottomNavigationBar
import com.sethu.emergency.ui.components.SethuIcons
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.CardBackground
import com.sethu.emergency.ui.theme.CardBorder
import com.sethu.emergency.ui.theme.CardSelectedBorder
import com.sethu.emergency.ui.theme.DarkBackground
import com.sethu.emergency.ui.theme.TextMuted
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary
import org.mapsforge.core.model.LatLong
import java.io.File
import kotlin.math.sqrt

/**
 * Display modes for offline spatial mapping.
 */
enum class MapDisplayMode {
    BENGALURU_MAP,
    TACTICAL_RADAR
}

/**
 * Dedicated 100% offline tactical spatial plotter and coordinate dashboard.
 * Supports both real Mapsforge 0.24.1 geographical vector map (Bengaluru)
 * and pure Jetpack Compose tactical polar radar.
 * Zero external tile libraries, zero online tile downloads, zero network calls.
 */
@Composable
fun OfflineMapScreen(
    onBack: () -> Unit,
    onTabSelected: (String) -> Unit,
    coordinatorStore: CoordinatorReportStore
) {
    val context = LocalContext.current
    val actualStore = coordinatorStore
    var showHelpDialog by remember { mutableStateOf(false) }

    // Map mode toggle and local file state
    var mapDisplayMode by remember { mutableStateOf(MapDisplayMode.BENGALURU_MAP) }
    var mapCheckTrigger by remember { mutableStateOf(0) }
    var mapLoadError by remember { mutableStateOf<String?>(null) }

    val mapsDir = remember { context.getExternalFilesDir("maps") ?: File(context.filesDir, "maps") }
    val mapFile = remember(mapsDir) { File(mapsDir, "bengaluru.map") }
    val isMapFilePresent = remember(mapFile, mapCheckTrigger) {
        mapFile.exists() && mapFile.isFile && mapFile.length() > 0L
    }

    val reports by actualStore.reportsFlow.collectAsState()

    // Separate valid-coordinate reports from location-unavailable reports
    val validReports = remember(reports) {
        reports.filter { CoordinateUtils.isValid(it.latitude, it.longitude) }
    }
    val missingReports = remember(reports) {
        reports.filter { !CoordinateUtils.isValid(it.latitude, it.longitude) }
    }

    // Evaluated valid reports with priority
    val evaluatedValidReports = remember(validReports) {
        validReports.map { report ->
            report to PriorityClassifier.evaluate(report)
        }
    }

    var selectedIncident by remember { mutableStateOf<SosReport?>(null) }
    var selectedFilter by remember { mutableStateOf<IncidentPriority?>(null) }

    // Filtered valid reports
    val displayedReports = remember(evaluatedValidReports, selectedFilter) {
        if (selectedFilter == null) {
            evaluatedValidReports
        } else {
            evaluatedValidReports.filter { it.second.priority == selectedFilter }
        }
    }

    // Pan & Zoom state for tactical canvas
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Calculate bounding box for all valid reports
    val boundingBox = remember(validReports) {
        val coords = validReports.mapNotNull {
            if (it.latitude != null && it.longitude != null) Pair(it.latitude, it.longitude) else null
        }
        CoordinateUtils.calculateBoundingBox(coords)
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
                            text = "Incident Spatial Map",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (mapDisplayMode == MapDisplayMode.BENGALURU_MAP) {
                                "Mapsforge 0.24.1 Vector Map"
                            } else {
                                "100% Offline Coordinate Radar"
                            },
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
                currentTab = "Map",
                onTabSelected = onTabSelected
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Overview Metric Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapMetricBox(
                    title = "Mapped Incidents",
                    value = "${validReports.size}",
                    accentColor = AccentGreen,
                    subtitle = "Valid GPS Coordinates",
                    modifier = Modifier.weight(1f)
                )
                MapMetricBox(
                    title = "No Location",
                    value = "${missingReports.size}",
                    accentColor = if (missingReports.isNotEmpty()) Color(0xFFFF7043) else TextMuted,
                    subtitle = "Coordinates Unavailable",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Mode Toggle: Bengaluru Vector Map vs Tactical Radar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapModeToggleChip(
                    title = "Bengaluru Map",
                    subtitle = if (isMapFilePresent) "Offline Vector" else "File Required",
                    icon = SethuIcons.MapFold,
                    isSelected = mapDisplayMode == MapDisplayMode.BENGALURU_MAP,
                    onClick = { mapDisplayMode = MapDisplayMode.BENGALURU_MAP },
                    modifier = Modifier.weight(1f)
                )
                MapModeToggleChip(
                    title = "Tactical Radar",
                    subtitle = "Coordinate Canvas",
                    icon = SethuIcons.LocationBeacon,
                    isSelected = mapDisplayMode == MapDisplayMode.TACTICAL_RADAR,
                    onClick = { mapDisplayMode = MapDisplayMode.TACTICAL_RADAR },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Map Display Area
            if (mapDisplayMode == MapDisplayMode.BENGALURU_MAP) {
                if (!isMapFilePresent || mapLoadError != null) {
                    MapFileProvisioningCard(
                        mapFile = mapFile,
                        errorMessage = mapLoadError,
                        onSwitchToRadar = { mapDisplayMode = MapDisplayMode.TACTICAL_RADAR },
                        onCheckAgain = {
                            mapLoadError = null
                            mapCheckTrigger++
                        }
                    )
                } else {
                    MapsforgeMapView(
                        mapFile = mapFile,
                        incidents = displayedReports,
                        selectedIncident = selectedIncident,
                        onSelectIncident = { selectedIncident = it },
                        initialCenter = boundingBox?.let { LatLong(it.centerLat, it.centerLon) },
                        onError = { err -> mapLoadError = err }
                    )
                }
            } else {
                // Tactical Canvas Plotter Box (Polar radar)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF070D0A))
                        .border(1.5.dp, CardSelectedBorder.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                ) {
                    if (validReports.isEmpty()) {
                        // Empty State on Canvas
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = SethuIcons.LocationBeacon,
                                contentDescription = "No locations",
                                tint = TextMuted,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No Incident Coordinates Mapped",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (missingReports.isNotEmpty()) {
                                    "${missingReports.size} incidents received, but all lack valid GPS coordinates."
                                } else {
                                    "When emergency reports with GPS coordinates are received, they will plot here on the tactical radar."
                                },
                                color = TextSecondary,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 15.sp
                            )
                        }
                    } else {
                        // Interactive Pure Compose Canvas
                        TacticalCoordinateCanvas(
                            boundingBox = boundingBox,
                            incidents = displayedReports,
                            selectedIncident = selectedIncident,
                            zoomScale = zoomScale,
                            panOffsetX = panOffsetX,
                            panOffsetY = panOffsetY,
                            onTransform = { scaleChange, panChange ->
                                zoomScale = (zoomScale * scaleChange).coerceIn(0.5f, 5.0f)
                                panOffsetX += panChange.x
                                panOffsetY += panChange.y
                            },
                            onSelectIncident = { selectedIncident = it }
                        )

                        // Top-left Canvas HUD overlay (Bounding Box / Center coordinates)
                        boundingBox?.let { box ->
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xCC0C1410))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Text(
                                    text = "Center: ${"%.4f".format(box.centerLat)}, ${"%.4f".format(box.centerLon)}\nSpan: ${CoordinateUtils.formatDistance(CoordinateUtils.haversineDistanceMeters(box.minLat, box.minLon, box.maxLat, box.maxLon))}",
                                    color = AccentGreen,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        // Top-right Compass / Reset Button
                        Row(
                            modifier = Modifier
                                .padding(8.dp)
                                .align(Alignment.TopEnd),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xCC0C1410))
                                    .clickable {
                                        zoomScale = 1f
                                        panOffsetX = 0f
                                        panOffsetY = 0f
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Reset View",
                                    color = TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Bottom-right Compass indicator
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xCC0C1410))
                                .border(1.dp, CardBorder, CircleShape)
                                .padding(6.dp)
                                .align(Alignment.BottomEnd)
                        ) {
                            Text(
                                text = "N ▲",
                                color = AccentGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Selected Incident Inspection Card (if a pin is selected)
            selectedIncident?.let { sel ->
                val selEval = remember(sel) { PriorityClassifier.evaluate(sel) }
                SelectedIncidentInspectionCard(
                    report = sel,
                    evaluation = selEval,
                    context = context,
                    onDismiss = { selectedIncident = null }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Priority Filter Chips
            if (validReports.isNotEmpty()) {
                PriorityFilterRow(
                    selectedFilter = selectedFilter,
                    totalCount = validReports.size,
                    criticalCount = evaluatedValidReports.count { it.second.priority == IncidentPriority.CRITICAL },
                    highCount = evaluatedValidReports.count { it.second.priority == IncidentPriority.HIGH },
                    mediumCount = evaluatedValidReports.count { it.second.priority == IncidentPriority.MEDIUM },
                    unknownCount = evaluatedValidReports.count { it.second.priority == IncidentPriority.UNKNOWN },
                    onSelectFilter = { selectedFilter = it }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Section Header: Mapped Incidents List
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mapped Incident Locations (${displayedReports.size})",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Tap pin or card to inspect",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // List of Mapped Incidents
            if (displayedReports.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    displayedReports.forEach { (report, evaluation) ->
                        MappedIncidentRowCard(
                            report = report,
                            evaluation = evaluation,
                            isSelected = selectedIncident?.reportId == report.reportId,
                            context = context,
                            onFocus = {
                                selectedIncident = report
                            }
                        )
                    }
                }
            } else if (validReports.isNotEmpty()) {
                Text(
                    text = "No mapped incidents match '${selectedFilter?.label ?: "All"}' filter.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            // Missing Location Section (Honest reporting of reports without valid GPS)
            if (missingReports.isNotEmpty()) {
                Spacer(modifier = Modifier.height(18.dp))
                MissingLocationSection(
                    missingReports = missingReports,
                    context = context
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = {
                Text(
                    text = "Offline Spatial Mapping",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Sethu provides dual-mode offline spatial awareness without internet, mobile data, or external cloud tile dependencies:",
                        color = TextPrimary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "1. Bengaluru Map (Vector):\n" +
                                "• Renders actual geographic roads, water bodies, and place names from local 'bengaluru.map'.\n" +
                                "• Stored in app-specific storage (requires zero broad storage permissions).\n" +
                                "• Interactive pinch-zoom, pan, and real-time SOS pin overlays.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Text(
                        text = "2. Tactical Radar (Coordinate Canvas):\n" +
                                "• Pure Compose polar grid with auto-fitting bounding box and relative distance rings.\n" +
                                "• Always available independently of external map files.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    Text(
                        text = "Map data © OpenStreetMap contributors.",
                        color = AccentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
 * Chip selector for switching between Bengaluru Vector Map and Tactical Radar modes.
 */
@Composable
private fun MapModeToggleChip(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF0F2318) else Color(0xFF0C1410),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) AccentGreen else CardBorder
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) AccentGreen else TextMuted,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = title,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    color = if (isSelected) AccentGreen else TextMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

/**
 * Information card displayed when 'bengaluru.map' is missing, unreadable, or invalid.
 * Explains how to provision the file and provides a direct fallback button to Tactical Radar.
 */
@Composable
private fun MapFileProvisioningCard(
    mapFile: File,
    errorMessage: String? = null,
    onSwitchToRadar: () -> Unit,
    onCheckAgain: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF070D0A))
            .border(
                1.5.dp,
                if (errorMessage != null) Color(0xFFFF5252).copy(alpha = 0.7f) else CardSelectedBorder.copy(alpha = 0.7f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = SethuIcons.MapFold,
                    contentDescription = "Offline Vector Map",
                    tint = if (errorMessage != null) Color(0xFFFF5252) else AccentGreen,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Bengaluru Vector Map Offline",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                color = if (errorMessage != null) Color(0x33FF5252) else Color(0x33FF9800),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (errorMessage != null) "Incompatible / Corrupt Map File" else "Map Data File Required",
                    color = if (errorMessage != null) Color(0xFFFF5252) else Color(0xFFFF9800),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (errorMessage != null) {
                    "Error loading vector file: $errorMessage\nPlease verify Mapsforge 0.24.1 format."
                } else {
                    "Geographical road, boundary, and place-name rendering requires the offline 'bengaluru.map' vector file."
                },
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Storage Path Card
            Surface(
                color = Color(0xFF0C1410),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, CardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "APP-SPECIFIC STORAGE LOCATION",
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = mapFile.absolutePath,
                        color = TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Provision via ADB:\nadb push bengaluru.map ${mapFile.absolutePath}",
                        color = AccentGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSwitchToRadar,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Use Tactical Radar",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Map Path", mapFile.absolutePath))
                        Toast.makeText(context, "Path copied to clipboard", Toast.LENGTH_SHORT).show()
                        onCheckAgain()
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Copy Path / Check",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Pure Jetpack Compose Canvas rendering a dark tactical coordinate grid,
 * distance scale rings, and priority incident pins.
 */
@Composable
private fun TacticalCoordinateCanvas(
    boundingBox: GeoBoundingBox?,
    incidents: List<Pair<SosReport, PriorityEvaluation>>,
    selectedIncident: SosReport?,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    onTransform: (Float, Offset) -> Unit,
    onSelectIncident: (SosReport) -> Unit
) {
    var canvasWidth by remember { mutableFloatStateOf(1f) }
    var canvasHeight by remember { mutableFloatStateOf(1f) }

    // Pin positions mapped to screen coords for touch detection
    val pinScreenLocations = remember(incidents, boundingBox, canvasWidth, canvasHeight, zoomScale, panOffsetX, panOffsetY) {
        if (boundingBox == null || canvasWidth <= 1f || canvasHeight <= 1f) emptyList()
        else {
            val padding = 36f
            val drawW = canvasWidth - (padding * 2)
            val drawH = canvasHeight - (padding * 2)
            val cX = canvasWidth / 2f
            val cY = canvasHeight / 2f

            incidents.mapNotNull { (report, eval) ->
                val lat = report.latitude ?: return@mapNotNull null
                val lon = report.longitude ?: return@mapNotNull null

                val normX = ((lon - boundingBox.minLon) / boundingBox.spanLon).toFloat()
                val normY = ((boundingBox.maxLat - lat) / boundingBox.spanLat).toFloat()

                val baseX = padding + (normX * drawW)
                val baseY = padding + (normY * drawH)

                val screenX = cX + (baseX - cX) * zoomScale + panOffsetX
                val screenY = cY + (baseY - cY) * zoomScale + panOffsetY

                Triple(report, eval, Offset(screenX, screenY))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan)
                }
            }
            .pointerInput(pinScreenLocations) {
                detectTapGestures { tapOffset ->
                    val clicked = pinScreenLocations.minByOrNull { (_, _, pos) ->
                        val dx = pos.x - tapOffset.x
                        val dy = pos.y - tapOffset.y
                        sqrt(dx * dx + dy * dy)
                    }
                    if (clicked != null) {
                        val dist = sqrt((clicked.third.x - tapOffset.x) * (clicked.third.x - tapOffset.x) +
                                (clicked.third.y - tapOffset.y) * (clicked.third.y - tapOffset.y))
                        if (dist < 40f) {
                            onSelectIncident(clicked.first)
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasWidth = size.width
            canvasHeight = size.height

            val cX = size.width / 2f
            val cY = size.height / 2f

            // 1. Draw Tactical Grid Background
            val gridColor = Color(0xFF112319)
            val gridSpacing = 40f * zoomScale
            val startX = (cX + panOffsetX) % gridSpacing
            val startY = (cY + panOffsetY) % gridSpacing

            var x = startX
            while (x < size.width) {
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f
                )
                x += gridSpacing
            }

            var y = startY
            while (y < size.height) {
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += gridSpacing
            }

            // 2. Center Crosshairs and Concentric Radar Rings
            val centerScreen = Offset(cX + panOffsetX, cY + panOffsetY)
            val ringColor = Color(0xFF163223)
            val ringRadii = listOf(60f, 130f, 210f)

            ringRadii.forEach { baseR ->
                drawCircle(
                    color = ringColor,
                    radius = baseR * zoomScale,
                    center = centerScreen,
                    style = Stroke(
                        width = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                )
            }

            // Center beacon axis lines
            drawLine(
                color = Color(0xFF1D4530),
                start = Offset(centerScreen.x - 30f, centerScreen.y),
                end = Offset(centerScreen.x + 30f, centerScreen.y),
                strokeWidth = 1.5f
            )
            drawLine(
                color = Color(0xFF1D4530),
                start = Offset(centerScreen.x, centerScreen.y - 30f),
                end = Offset(centerScreen.x, centerScreen.y + 30f),
                strokeWidth = 1.5f
            )

            // 3. Draw Incident Pins
            pinScreenLocations.forEach { (report, eval, pos) ->
                val isSelected = selectedIncident?.reportId == report.reportId
                val pinColor = when (eval.priority) {
                    IncidentPriority.CRITICAL -> Color(0xFFFF5252)
                    IncidentPriority.HIGH -> Color(0xFFFFB74D)
                    IncidentPriority.MEDIUM -> Color(0xFF4DD0E1)
                    IncidentPriority.UNKNOWN -> Color(0xFF9EABA3)
                }

                // If Critical, draw glowing pulse ring
                if (eval.priority == IncidentPriority.CRITICAL) {
                    drawCircle(
                        color = Color(0x44FF3333),
                        radius = 22f,
                        center = pos
                    )
                }

                // If Selected, draw highlight halo
                if (isSelected) {
                    drawCircle(
                        color = AccentGreen,
                        radius = 20f,
                        center = pos,
                        style = Stroke(width = 2.5f)
                    )
                }

                // Outer pin circle
                drawCircle(
                    color = Color(0xFF0C1410),
                    radius = 12f,
                    center = pos
                )
                // Colored pin border
                drawCircle(
                    color = pinColor,
                    radius = 12f,
                    center = pos,
                    style = Stroke(width = 2.5f)
                )
                // Center inner dot
                drawCircle(
                    color = pinColor,
                    radius = 5f,
                    center = pos
                )
            }
        }
    }
}

/**
 * Inspection card displayed when an incident pin is selected on the tactical canvas.
 * Compact layout with priority badge, category, triage reason, notes, and unclipped Copy button.
 */
@Composable
private fun SelectedIncidentInspectionCard(
    report: SosReport,
    evaluation: PriorityEvaluation,
    context: Context,
    onDismiss: () -> Unit
) {
    val coordsStr = CoordinateUtils.formatCoordinates(report.latitude, report.longitude)
    val accuracyStr = CoordinateUtils.formatAccuracy(report.accuracyMeters)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, AccentGreen, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header Row: Priority Badge + Category & People on left; Dismiss on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when (evaluation.priority) {
                                    IncidentPriority.CRITICAL -> Color(0xFF3B1212)
                                    IncidentPriority.HIGH -> Color(0xFF33200B)
                                    IncidentPriority.MEDIUM -> Color(0xFF0F262B)
                                    IncidentPriority.UNKNOWN -> Color(0xFF1E2421)
                                }
                            )
                            .border(
                                1.dp,
                                when (evaluation.priority) {
                                    IncidentPriority.CRITICAL -> Color(0xFFFF3333)
                                    IncidentPriority.HIGH -> Color(0xFFFF9800)
                                    IncidentPriority.MEDIUM -> Color(0xFF00BCD4)
                                    IncidentPriority.UNKNOWN -> Color(0xFF4E5D55)
                                },
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = evaluation.priority.label.uppercase(),
                            color = when (evaluation.priority) {
                                IncidentPriority.CRITICAL -> Color(0xFFFF5252)
                                IncidentPriority.HIGH -> Color(0xFFFFB74D)
                                IncidentPriority.MEDIUM -> Color(0xFF4DD0E1)
                                IncidentPriority.UNKNOWN -> Color(0xFF9EABA3)
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "${report.category} (${report.peopleCount} affected)",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Text(text = "✕", color = TextMuted, fontSize = 13.sp)
                }
            }

            // Reason Text
            Text(
                text = "Triage: ${evaluation.reason}",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            // Notes Text if present
            if (report.notes.isNotBlank()) {
                Text(
                    text = "\"${report.notes}\"",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Coordinates Box with Copy Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F1713))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = "GPS: $coordsStr",
                        color = AccentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Accuracy: $accuracyStr  •  Status: ${report.locationStatus}",
                        color = TextMuted,
                        fontSize = 9.sp
                    )
                }

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("GPS Coordinates", coordsStr)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Coordinates copied: $coordsStr", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(
                        text = "Copy",
                        color = DarkBackground,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Filter chips for priority selection on the map.
 * Horizontally scrollable with trailing padding so all filters are fully visible.
 */
@Composable
private fun PriorityFilterRow(
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterPill(
            label = "All ($totalCount)",
            isSelected = selectedFilter == null,
            onClick = { onSelectFilter(null) }
        )
        FilterPill(
            label = "Critical ($criticalCount)",
            isSelected = selectedFilter == IncidentPriority.CRITICAL,
            accentColor = Color(0xFFFF5252),
            onClick = { onSelectFilter(IncidentPriority.CRITICAL) }
        )
        FilterPill(
            label = "High ($highCount)",
            isSelected = selectedFilter == IncidentPriority.HIGH,
            accentColor = Color(0xFFFFB74D),
            onClick = { onSelectFilter(IncidentPriority.HIGH) }
        )
        FilterPill(
            label = "Medium ($mediumCount)",
            isSelected = selectedFilter == IncidentPriority.MEDIUM,
            accentColor = Color(0xFF4DD0E1),
            onClick = { onSelectFilter(IncidentPriority.MEDIUM) }
        )
        FilterPill(
            label = "Unknown ($unknownCount)",
            isSelected = selectedFilter == IncidentPriority.UNKNOWN,
            accentColor = Color(0xFF9EABA3),
            onClick = { onSelectFilter(IncidentPriority.UNKNOWN) }
        )
        Spacer(modifier = Modifier.width(6.dp))
    }
}

@Composable
private fun FilterPill(
    label: String,
    isSelected: Boolean,
    accentColor: Color = AccentGreen,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else CardBackground)
            .border(1.dp, if (isSelected) accentColor else CardBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
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
 * Individual row card for a mapped incident below the canvas.
 * Category, affected count, timestamp on top row, triage reason on second row,
 * dedicated GPS row, and dedicated unclipped action buttons row.
 */
@Composable
private fun MappedIncidentRowCard(
    report: SosReport,
    evaluation: PriorityEvaluation,
    isSelected: Boolean,
    context: Context,
    onFocus: () -> Unit
) {
    val coordsStr = CoordinateUtils.formatCoordinates(report.latitude, report.longitude)
    val accuracyStr = CoordinateUtils.formatAccuracy(report.accuracyMeters)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(
                1.dp,
                if (isSelected) AccentGreen else CardBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onFocus)
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Row 1: Priority dot + Category & people count on left; Timestamp on right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (evaluation.priority) {
                                    IncidentPriority.CRITICAL -> Color(0xFFFF5252)
                                    IncidentPriority.HIGH -> Color(0xFFFFB74D)
                                    IncidentPriority.MEDIUM -> Color(0xFF4DD0E1)
                                    IncidentPriority.UNKNOWN -> Color(0xFF9EABA3)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${report.category} (${report.peopleCount} affected)",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = TimeFormatter.formatTime(report.timestamp),
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            // Row 2: Triage Reason
            Text(
                text = "Triage: ${evaluation.reason}",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Row 3: Dedicated GPS Row with subtle contrast background
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF0F1713))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GPS: $coordsStr ($accuracyStr)",
                    color = AccentGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Row 4: Dedicated Action Buttons Row with adequate height and tap area
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("GPS Coordinates", coordsStr)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Coordinates copied: $coordsStr", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentGreen.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "Copy",
                        color = AccentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = onFocus,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) AccentGreen else Color(0xFF1E2F26)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isSelected) "Focused" else "Focus",
                        color = if (isSelected) DarkBackground else AccentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Honest reporting section for incidents that do NOT have valid GPS coordinates.
 */
@Composable
private fun MissingLocationSection(
    missingReports: List<SosReport>,
    context: Context
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1512))
            .border(1.dp, Color(0xFFFF7043).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = SethuIcons.AlertTriangle,
                        contentDescription = "Warning",
                        tint = Color(0xFFFF7043),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Location Unavailable (${missingReports.size} incidents)",
                        color = Color(0xFFFFB74D),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = if (expanded) "Hide ▲" else "View ▼",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }

            Text(
                text = "These emergency incidents lack valid GPS coordinates. Location cannot be plotted on the tactical map and must be identified via report notes.",
                color = TextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    missingReports.forEach { report ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CardBackground)
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${report.category} | ${report.senderDeviceName}",
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Status: ${report.locationStatus}",
                                        color = Color(0xFFFF7043),
                                        fontSize = 10.sp
                                    )
                                }
                                if (report.notes.isNotBlank()) {
                                    Text(
                                        text = "Notes: \"${report.notes}\"",
                                        color = TextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapMetricBox(
    title: String,
    value: String,
    accentColor: Color,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardBackground)
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                color = TextSecondary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = accentColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 9.sp
            )
        }
    }
}
