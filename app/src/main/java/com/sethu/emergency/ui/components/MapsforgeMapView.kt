package com.sethu.emergency.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import android.view.MotionEvent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import com.sethu.emergency.comm.coordinator.IncidentPriority
import com.sethu.emergency.comm.coordinator.PriorityEvaluation
import com.sethu.emergency.comm.model.SosReport
import com.sethu.emergency.ui.theme.AccentGreen
import com.sethu.emergency.ui.theme.CardBorder
import com.sethu.emergency.ui.theme.CardSelectedBorder
import com.sethu.emergency.ui.theme.TextMuted
import com.sethu.emergency.ui.theme.TextPrimary
import com.sethu.emergency.ui.theme.TextSecondary
import org.mapsforge.core.graphics.Bitmap as MapsforgeBitmap
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File

private const val TAG = "MapsforgeMapView"

/**
 * Custom incident marker layer that links a Mapsforge [Marker] to an SOS report.
 * Provides hit-testing via [contains] to invoke [onSelect].
 */
private class IncidentMarker(
    latLong: LatLong,
    bitmap: MapsforgeBitmap,
    horizontalOffset: Int,
    verticalOffset: Int,
    val report: SosReport,
    private val mapView: MapView,
    private val onSelect: (SosReport) -> Unit
) : Marker(latLong, bitmap, horizontalOffset, verticalOffset) {

    override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
        if (contains(layerXY, tapXY, mapView)) {
            onSelect(report)
            return true
        }
        return false
    }

    override fun onDestroy() {
        // Cached bitmap reference: do not decrement ref count on individual marker teardown
    }
}

/**
 * In-memory cache for Mapsforge marker pin bitmaps, keyed by (Priority, isSelected).
 * Avoids repeated allocations and bitmap recycling issues during recompositions.
 */
internal object MapsforgeMarkerCache {
    private val cache = mutableMapOf<Pair<IncidentPriority, Boolean>, MapsforgeBitmap>()

    @Synchronized
    fun getMarkerBitmap(
        context: Context,
        priority: IncidentPriority,
        isSelected: Boolean
    ): MapsforgeBitmap {
        val key = Pair(priority, isSelected)
        return cache.getOrPut(key) {
            val density = context.resources.displayMetrics.density
            val sizeDp = if (isSelected) 34 else 26
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(24)

            val androidBitmap = AndroidBitmap.createBitmap(
                sizePx,
                sizePx,
                AndroidBitmap.Config.ARGB_8888
            )
            val canvas = AndroidCanvas(androidBitmap)
            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

            val colorInt = when (priority) {
                IncidentPriority.CRITICAL -> 0xFFFF3333.toInt()
                IncidentPriority.HIGH -> 0xFFFF9800.toInt()
                IncidentPriority.MEDIUM -> 0xFF00BCD4.toInt()
                IncidentPriority.UNKNOWN -> 0xFF9EABA3.toInt()
            }

            val cx = sizePx / 2f
            val cy = sizePx / 2f
            val radius = (sizePx / 2f) - (2.5f * density)

            // Outer highlight halo if selected
            if (isSelected) {
                paint.color = 0xFF00DF81.toInt() // AccentGreen
                paint.style = AndroidPaint.Style.STROKE
                paint.strokeWidth = 3f * density
                canvas.drawCircle(cx, cy, radius, paint)
            }

            // Priority circular badge
            paint.color = colorInt
            paint.style = AndroidPaint.Style.FILL
            val innerRadius = if (isSelected) radius - (2.5f * density) else radius
            canvas.drawCircle(cx, cy, innerRadius, paint)

            // Crisp white inner border
            paint.color = AndroidColor.WHITE
            paint.style = AndroidPaint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, innerRadius, paint)

            // Inner center beacon core
            paint.color = AndroidColor.WHITE
            paint.style = AndroidPaint.Style.FILL
            canvas.drawCircle(cx, cy, innerRadius * 0.35f, paint)

            val drawable = BitmapDrawable(context.resources, androidBitmap)
            AndroidGraphicFactory.convertToBitmap(drawable)
        }
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

/**
 * Thread-safe singleton initializer for Mapsforge [AndroidGraphicFactory].
 * In Mapsforge 0.24.1, [AndroidGraphicFactory.INSTANCE] is pre-allocated with a null Context.
 * Calling [ensureInitialized] ensures the factory instance is bound to the application context
 * exactly once, preventing NullPointerExceptions in DatabaseRenderer worker threads.
 */
object MapsforgeInitializer {
    @Volatile
    private var initialized = false
    private val lock = Any()

    fun ensureInitialized(context: Context) {
        if (!initialized) {
            synchronized(lock) {
                if (!initialized) {
                    AndroidGraphicFactory.createInstance(context.applicationContext)
                    initialized = true
                }
            }
        }
    }

    fun isInitialized(): Boolean = initialized
}

/**
 * Dedicated Jetpack Compose wrapper for Mapsforge 0.24.1 offline vector map.
 * Loads the local vector map file, renders geographical roads and place names,
 * overlays SOS incident pins, and provides interactive panning and zooming.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapsforgeMapView(
    mapFile: File,
    incidents: List<Pair<SosReport, PriorityEvaluation>>,
    selectedIncident: SosReport?,
    onSelectIncident: (SosReport) -> Unit,
    modifier: Modifier = Modifier,
    initialCenter: LatLong? = null,
    initialZoom: Byte = 12,
    onError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current

    // Ensure Mapsforge GraphicFactory singleton is initialized
    remember {
        MapsforgeInitializer.ensureInitialized(context)
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapDataStoreRef by remember { mutableStateOf<MapFile?>(null) }
    var centerText by remember { mutableStateOf("Bengaluru: 12.9716, 77.5946") }

    // Center on selected incident when selection changes
    LaunchedEffect(selectedIncident) {
        selectedIncident?.let { sel ->
            val lat = sel.latitude
            val lon = sel.longitude
            if (lat != null && lon != null) {
                mapViewRef?.let { mv ->
                    mv.model.mapViewPosition.animateTo(LatLong(lat, lon))
                    centerText = "Lat: ${"%.4f".format(lat)}, Lon: ${"%.4f".format(lon)}"
                }
            }
        }
    }

    // Cleanup resources when composable leaves composition
    DisposableEffect(mapFile) {
        onDispose {
            try {
                mapViewRef?.destroyAll()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying MapView", e)
            }
            try {
                mapDataStoreRef?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing map file", e)
            }
            MapsforgeMarkerCache.clear()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF070D0A))
            .border(1.5.dp, CardSelectedBorder.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    val mapView = MapView(ctx).apply {
                        isClickable = true
                        setBuiltInZoomControls(false)
                        setOnTouchListener { v, event ->
                            when (event.actionMasked) {
                                MotionEvent.ACTION_DOWN,
                                MotionEvent.ACTION_MOVE,
                                MotionEvent.ACTION_POINTER_DOWN -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                                MotionEvent.ACTION_UP,
                                MotionEvent.ACTION_CANCEL -> {
                                    v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            false
                        }
                    }

                    // Open offline vector map file
                    val mapDataStore = MapFile(mapFile)
                    mapDataStoreRef = mapDataStore

                    // Create tile cache using app-specific cache directory
                    val tileCache: TileCache = AndroidUtil.createTileCache(
                        ctx,
                        "mapcache",
                        mapView.model.displayModel.tileSize,
                        1.0f,
                        mapView.model.frameBufferModel.overdrawFactor
                    )

                    // Create tile renderer layer with default internal render theme
                    val tileRendererLayer: TileRendererLayer = AndroidUtil.createTileRendererLayer(
                        tileCache,
                        mapView.model.mapViewPosition,
                        mapDataStore,
                        MapsforgeThemes.DEFAULT,
                        false,
                        true,
                        false
                    )

                    mapView.layerManager.layers.add(tileRendererLayer)

                    // Default coordinates (Bengaluru Center: 12.9716° N, 77.5946° E)
                    val bengaluruCenter = LatLong(12.9716, 77.5946)
                    val targetCenter = initialCenter ?: bengaluruCenter
                    mapView.setCenter(targetCenter)
                    mapView.setZoomLevel(initialZoom)
                    mapView.setZoomLevelMin(9.toByte())
                    mapView.setZoomLevelMax(18.toByte())

                    centerText = "Center: ${"%.4f".format(targetCenter.latitude)}, ${"%.4f".format(targetCenter.longitude)}"
                    mapViewRef = mapView
                    mapView
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Mapsforge MapView", e)
                    onError?.invoke(e.localizedMessage ?: "Failed to open map file")
                    MapView(ctx)
                }
            },
            update = { mv ->
                try {
                    // Update and synchronize incident markers on layer manager
                    val layers = mv.layerManager.layers
                    val oldMarkers = layers.filterIsInstance<IncidentMarker>()
                    layers.removeAll(oldMarkers)

                    for ((report, eval) in incidents) {
                        val lat = report.latitude
                        val lon = report.longitude
                        if (lat != null && lon != null) {
                            val isSelected = report.reportId == selectedIncident?.reportId
                            val markerBitmap = MapsforgeMarkerCache.getMarkerBitmap(context, eval.priority, isSelected)
                            val marker = IncidentMarker(
                                latLong = LatLong(lat, lon),
                                bitmap = markerBitmap,
                                horizontalOffset = 0,
                                verticalOffset = 0,
                                report = report,
                                mapView = mv,
                                onSelect = onSelectIncident
                            )
                            layers.add(marker)
                        }
                    }
                    mv.repaint()
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating markers on MapView", e)
                }
            }
        )

        // Top-left HUD overlay (Coordinates & Active Markers)
        Box(
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC0C1410))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .align(Alignment.TopStart)
        ) {
            Text(
                text = "$centerText\nMarkers: ${incidents.size} active",
                color = AccentGreen,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp
            )
        }

        // Top-right Map Controls (Zoom In, Zoom Out, Center)
        Row(
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Zoom In (+)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0C1410))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable {
                        mapViewRef?.model?.mapViewPosition?.zoomIn()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Zoom Out (-)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0C1410))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable {
                        mapViewRef?.model?.mapViewPosition?.zoomOut()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "−",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Recenter
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0C1410))
                    .border(1.dp, CardBorder, RoundedCornerShape(6.dp))
                    .clickable {
                        val target = initialCenter ?: LatLong(12.9716, 77.5946)
                        mapViewRef?.setCenter(target)
                        mapViewRef?.setZoomLevel(12.toByte())
                        centerText = "Center: ${"%.4f".format(target.latitude)}, ${"%.4f".format(target.longitude)}"
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Center",
                    color = TextPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Bottom-left OpenStreetMap Attribution
        Box(
            modifier = Modifier
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCC0C1410))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .align(Alignment.BottomStart)
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                color = TextSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Bottom-right Compass Indicator
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
