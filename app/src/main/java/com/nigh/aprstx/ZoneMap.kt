package com.nigh.aprstx

import android.graphics.Paint
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

private const val EARTH_RADIUS_M = 6_378_137.0
private const val MAP_GPS_POLL_MS = 5_000L
private const val INITIAL_LOCATION_MAX_AGE_MS = 5 * 60_000L
private const val DEFAULT_RADIUS_M = 10_000.0
private const val OFFSCREEN_ZONE_RANGE_M = 20_000.0
private const val DARK_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val MAPLIBRE_TILE_SIZE_PX = 512.0

internal fun mapLibreBearing(canvasBearingDeg: Float): Double = -canvasBearingDeg.toDouble()

private data class MapViewport(val lat: Double, val lon: Double, val metersPerPx: Double, val bearingDeg: Float = 0f)

@Composable
private fun DarkOsmMap(viewport: MapViewport?) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var mapReady by remember { mutableStateOf<MapLibreMap?>(null) }

    AndroidView(
        factory = {
            MapLibre.getInstance(context)
            MapView(context).also { view ->
                mapView = view
                view.onCreate(null)
                view.onStart()
                view.onResume()
                view.getMapAsync { map ->
                    map.uiSettings.setAllGesturesEnabled(false)
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isAttributionEnabled = false
                    map.setStyle(Style.Builder().fromUri(DARK_MAP_STYLE))
                    mapReady = map
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )

    LaunchedEffect(viewport, mapReady, density) {
        val v = viewport ?: return@LaunchedEffect
        val map = mapReady ?: return@LaunchedEffect
        val groundMetersPerDp = v.metersPerPx * density
        val zoom = log2(
            cos(Math.toRadians(v.lat)).coerceAtLeast(0.1) *
                (2 * Math.PI * EARTH_RADIUS_M) / (MAPLIBRE_TILE_SIZE_PX * groundMetersPerDp),
        ).coerceIn(0.0, 20.0)
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(v.lat, v.lon))
                    .zoom(zoom)
                    .bearing(mapLibreBearing(v.bearingDeg))
                    .build(),
            ),
        )
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onStop()
            mapView?.onDestroy()
            mapView = null
            mapReady = null
        }
    }
}

@Composable
fun ZoneMapScreen(
    zones: List<StopZone>,
    eventStates: Map<String, BeaconRuntime.ZoneVisualState>,
    initialLocation: AprsLocation?,
    txTrack: List<AprsLocation>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val recentInitial = remember(initialLocation) {
        initialLocation?.takeIf { System.currentTimeMillis() - it.timestampMs in 0..INITIAL_LOCATION_MAX_AGE_MS }
    }
    var viewport by remember { mutableStateOf<MapViewport?>(null) }
    var currentLocation by remember { mutableStateOf(recentInitial) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var followLocation by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var gpsAttemptKey by remember { mutableStateOf(0) }
    var gpsError by remember { mutableStateOf<String?>(null) }
    val locationPulse by rememberInfiniteTransition(label = "locationPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_500, easing = LinearEasing), RepeatMode.Restart),
        label = "locationPulseRadius",
    )

    LaunchedEffect(mapSize, recentInitial) {
        if (viewport == null && recentInitial != null && mapSize != IntSize.Zero) {
            viewport = defaultViewport(recentInitial, mapSize)
        }
    }
    LaunchedEffect(gpsAttemptKey) {
        while (true) {
            val result = runCatching { LocationHelper.getLocation(context, currentLocation) }
            val loc = result.getOrNull()
            if (loc == null) {
                if (currentLocation == null) {
                    gpsError = result.exceptionOrNull()?.message ?: "Unable to acquire GPS location"
                    break
                }
            } else {
                gpsError = null
                currentLocation = loc
                viewport = when {
                    viewport == null && mapSize != IntSize.Zero -> defaultViewport(loc, mapSize)
                    followLocation -> viewport?.copy(lat = loc.latitude, lon = loc.longitude)
                    else -> viewport
                }
            }
            delay(MAP_GPS_POLL_MS)
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xff11151b))) {
        DarkOsmMap(viewport)
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged { mapSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        val v = viewport ?: return@detectTransformGestures
                        followLocation = false
                        val scale = (v.metersPerPx / zoom).coerceIn(1.0, 200.0)
                        val bearingRad = Math.toRadians(v.bearingDeg.toDouble())
                        val groundX = pan.x * cos(bearingRad) + pan.y * sin(bearingRad)
                        val groundY = -pan.x * sin(bearingRad) + pan.y * cos(bearingRad)
                        val correction = cos(Math.toRadians(v.lat)).coerceAtLeast(0.1)
                        viewport = MapViewport(
                            inverseMercatorLat(mercatorY(v.lat) + groundY * scale / correction),
                            inverseMercatorLon(mercatorX(v.lon) - groundX * scale / correction),
                            scale,
                            v.bearingDeg + rotation,
                        )
                    }
                }
                .pointerInput(zones) {
                    detectTapGestures { point ->
                        val v = viewport ?: return@detectTapGestures
                        selectedId = zones.minByOrNull { zone ->
                            (project(zone.latitude, zone.longitude, v, size.width.toFloat(), size.height.toFloat()) - point).getDistance()
                        }?.takeIf { zone ->
                            (project(zone.latitude, zone.longitude, v, size.width.toFloat(), size.height.toFloat()) - point).getDistance() <= 36f
                        }?.id
                    }
                },
        ) {
            val v = viewport ?: return@Canvas
            drawGrid(v)
            drawTxTrack(v, txTrack)
            zones.forEach { zone ->
                val center = project(zone.latitude, zone.longitude, v, size.width, size.height)
                val radius = (zone.radiusM / v.metersPerPx).toFloat()
                val color = zoneColor(zone, eventStates[zone.id])
                drawCircle(color.copy(alpha = 0.18f), radius, center)
                drawCircle(color, radius, center, style = Stroke(2f))
                drawCircle(color, 5f, center)
                if (currentLocation?.let { Aprs.haversineMeters(it.latitude, it.longitude, zone.latitude, zone.longitude) <= zone.radiusM } == true) {
                    drawCircle(Color.White, radius + 4f, center, style = Stroke(3f))
                }
            }
            currentLocation?.let {
                val point = project(it.latitude, it.longitude, v, size.width, size.height)
                drawCircle(Color(0xff4fc3f7).copy(alpha = 0.55f * (1f - locationPulse)), 8f + locationPulse * 22f, point, style = Stroke(2f))
                drawCircle(Color(0xff0d47a1), 9f, point)
                drawCircle(Color(0xff4fc3f7), 6f, point)
                drawCircle(Color.White, 2f, point)
            }
            drawOffscreenZoneEdges(v, zones)
            drawScale(v)
        }

        viewport?.let { v ->
            TextButton(
                onClick = { viewport = v.copy(bearingDeg = 0f) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).graphicsLayer { rotationZ = v.bearingDeg },
            ) { Text("N\n↑", color = Color.White) }
        }
        TextButton(
            enabled = currentLocation != null && mapSize != IntSize.Zero,
            onClick = {
                currentLocation?.let { viewport = defaultViewport(it, mapSize, viewport?.bearingDeg ?: 0f) }
                followLocation = true
            },
            modifier = Modifier.align(Alignment.TopStart),
        ) { Text("Me · 10 km") }
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("Back") }
        Text(
            "© OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        if (viewport == null && gpsError == null) {
            Text("Acquiring GPS location…", color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        gpsError?.let { message ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("GPS unavailable") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { gpsError = null; gpsAttemptKey++ }) { Text("Retry") }
                },
                dismissButton = { TextButton(onClick = onBack) { Text("Exit Zone map") } },
            )
        }
        val selected = zones.firstOrNull { it.id == selectedId }
        if (selected != null) Text(
            text = "${selected.note.ifBlank { "Zone ${zones.indexOf(selected) + 1}" }} · ${selected.radiusM} m\n${"%.5f, %.5f".format(selected.latitude, selected.longitude)}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
        )
    }
}

private fun defaultViewport(location: AprsLocation, size: IntSize, bearingDeg: Float = 0f): MapViewport {
    val halfLongSide = max(size.width, size.height).coerceAtLeast(1) / 2.0
    return MapViewport(location.latitude, location.longitude, (DEFAULT_RADIUS_M / halfLongSide).coerceIn(1.0, 200.0), bearingDeg)
}

private fun project(lat: Double, lon: Double, v: MapViewport, width: Float, height: Float): Offset =
    projectGround(mercatorX(lon), mercatorY(lat), v, width, height)

private fun projectGround(worldX: Double, worldY: Double, v: MapViewport, width: Float, height: Float): Offset {
    val correction = cos(Math.toRadians(v.lat)).coerceAtLeast(0.1)
    val east = (worldX - mercatorX(v.lon)) * correction
    val north = (worldY - mercatorY(v.lat)) * correction
    val radians = Math.toRadians(v.bearingDeg.toDouble())
    val x = east / v.metersPerPx
    val y = -north / v.metersPerPx
    return Offset(
        (width / 2 + x * cos(radians) - y * sin(radians)).toFloat(),
        (height / 2 + x * sin(radians) + y * cos(radians)).toFloat(),
    )
}

private fun mercatorX(lon: Double): Double = EARTH_RADIUS_M * Math.toRadians(lon)
private fun mercatorY(lat: Double): Double {
    val safeLat = lat.coerceIn(-85.0, 85.0)
    return EARTH_RADIUS_M * ln(tan(Math.PI / 4 + Math.toRadians(safeLat) / 2))
}
private fun inverseMercatorLon(x: Double): Double = Math.toDegrees(x / EARTH_RADIUS_M)
private fun inverseMercatorLat(y: Double): Double =
    Math.toDegrees(2 * atan(exp(y / EARTH_RADIUS_M)) - Math.PI / 2).coerceIn(-85.0, 85.0)

private fun zoneColor(zone: StopZone, state: BeaconRuntime.ZoneVisualState?): Color = when {
    !zone.enabled -> Color(0xff7b8794)
    state == BeaconRuntime.ZoneVisualState.NEED_CLEAR -> Color(0xffffc107)
    state == BeaconRuntime.ZoneVisualState.STOPPED -> Color(0xffef5350)
    state == BeaconRuntime.ZoneVisualState.ARMED -> Color(0xff66bb6a)
    else -> Color(0xff90a4ae)
}

private fun DrawScope.drawGrid(v: MapViewport) {
    val correction = cos(Math.toRadians(v.lat)).coerceAtLeast(0.1)
    val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0, 100_000.0)
        .firstOrNull { it * correction / v.metersPerPx >= 56 } ?: 200_000.0
    val extent = max(size.width, size.height) * v.metersPerPx / correction * 0.9
    val centerX = mercatorX(v.lon); val centerY = mercatorY(v.lat)
    val minX = centerX - extent; val maxX = centerX + extent
    val minY = centerY - extent; val maxY = centerY + extent
    var x = floor(minX / step) * step
    while (x <= maxX) {
        drawLine(Color(0xff27313d), projectGround(x, minY, v, size.width, size.height), projectGround(x, maxY, v, size.width, size.height))
        x += step
    }
    var y = floor(minY / step) * step
    while (y <= maxY) {
        drawLine(Color(0xff27313d), projectGround(minX, y, v, size.width, size.height), projectGround(maxX, y, v, size.width, size.height))
        y += step
    }
}

private fun DrawScope.drawTxTrack(v: MapViewport, track: List<AprsLocation>) {
    val points = track.map { project(it.latitude, it.longitude, v, size.width, size.height) }
    points.zipWithNext().forEach { (a, b) -> drawLine(Color(0xffffb74d).copy(alpha = 0.75f), a, b, 2f) }
    points.forEach { point ->
        drawCircle(Color(0xff4e342e), 5f, point)
        drawCircle(Color(0xffffb74d), 3f, point)
    }
}

private fun DrawScope.drawOffscreenZoneEdges(v: MapViewport, zones: List<StopZone>) {
    if (zones.isEmpty()) return
    data class Callout(val zone: StopZone, val edge: Offset, val unit: Offset, val side: Int, val distance: Double)

    val projected = zones.associateWith { project(it.latitude, it.longitude, v, size.width, size.height) }
    val offscreen = zones.filter { zone ->
        val point = projected.getValue(zone)
        val radius = zone.radiusM / v.metersPerPx
        point.x !in -radius..size.width + radius || point.y !in -radius..size.height + radius
    }
    if (offscreen.isEmpty()) return
    val distances = offscreen.associateWith { Aprs.haversineMeters(v.lat, v.lon, it.latitude, it.longitude) }
    val nearby = offscreen.filter { distances.getValue(it) <= OFFSCREEN_ZONE_RANGE_M }
    val indicated = if (nearby.isNotEmpty()) nearby else listOf(offscreen.minBy { distances.getValue(it) })
    val center = Offset(size.width / 2, size.height / 2)
    val halfW = size.width / 2 - 28f
    val halfH = size.height / 2 - 28f
    val callouts = indicated.mapNotNull { zone ->
        val delta = projected.getValue(zone) - center
        if (delta.getDistance() < 0.01f) return@mapNotNull null
        val xFactor = halfW / abs(delta.x).coerceAtLeast(0.01f)
        val yFactor = halfH / abs(delta.y).coerceAtLeast(0.01f)
        val factor = min(xFactor, yFactor)
        val side = if (xFactor < yFactor) {
            if (delta.x < 0) 0 else 1 // left/right
        } else {
            if (delta.y < 0) 2 else 3 // top/bottom
        }
        Callout(zone, center + delta * factor, delta / delta.getDistance(), side, distances.getValue(zone))
    }

    val labelWidth = min(132f, size.width - 16f).coerceAtLeast(72f)
    val labelHeight = 54f
    val gap = 6f
    val margin = 8f
    val namePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 23f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    val distancePaint = Paint().apply {
        color = android.graphics.Color.LTGRAY
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    val placedRects = mutableListOf<Rect>()
    callouts.groupBy { it.side }.forEach { (side, group) ->
        val horizontal = side >= 2
        val axisLimit = if (horizontal) size.width else size.height
        val itemLength = if (horizontal) labelWidth else labelHeight
        val laneEnds = mutableListOf<Float>()
        group.sortedBy { if (horizontal) it.edge.x else it.edge.y }.forEach { callout ->
            val desired = ((if (horizontal) callout.edge.x else callout.edge.y) - itemLength / 2)
                .coerceIn(margin, (axisLimit - margin - itemLength).coerceAtLeast(margin))
            var lane = 0
            var axisStart: Float
            var labelCenter: Offset
            var labelRect: Rect
            while (true) {
                if (lane == laneEnds.size) laneEnds += margin - gap
                axisStart = max(desired, laneEnds[lane] + gap)
                if (axisStart + itemLength > axisLimit - margin) {
                    lane++
                    continue
                }
                labelCenter = when (side) {
                    0 -> Offset(margin + labelWidth / 2 + lane * (labelWidth + gap), axisStart + labelHeight / 2)
                    1 -> Offset(size.width - margin - labelWidth / 2 - lane * (labelWidth + gap), axisStart + labelHeight / 2)
                    2 -> Offset(axisStart + labelWidth / 2, margin + labelHeight / 2 + lane * (labelHeight + gap))
                    else -> Offset(axisStart + labelWidth / 2, size.height - margin - labelHeight / 2 - lane * (labelHeight + gap))
                }
                labelRect = Rect(labelCenter.x - labelWidth / 2, labelCenter.y - labelHeight / 2, labelCenter.x + labelWidth / 2, labelCenter.y + labelHeight / 2)
                if (placedRects.any { it.overlaps(labelRect) }) {
                    lane++
                    continue
                }
                break
            }
            laneEnds[lane] = axisStart + itemLength
            placedRects += labelRect
            val topLeft = labelRect.topLeft
            drawLine(Color(0xffffd54f).copy(alpha = 0.75f), callout.edge, labelCenter, 1.5f)
            val perpendicular = Offset(-callout.unit.y, callout.unit.x)
            val arrow = Path().apply {
                moveTo(callout.edge.x, callout.edge.y)
                lineTo(callout.edge.x - callout.unit.x * 16f + perpendicular.x * 7f, callout.edge.y - callout.unit.y * 16f + perpendicular.y * 7f)
                lineTo(callout.edge.x - callout.unit.x * 16f - perpendicular.x * 7f, callout.edge.y - callout.unit.y * 16f - perpendicular.y * 7f)
                close()
            }
            drawPath(arrow, Color(0xffffd54f))
            drawRoundRect(Color(0xdd18212b), topLeft, Size(labelWidth, labelHeight), CornerRadius(8f, 8f))
            drawRoundRect(Color(0xffffd54f), topLeft, Size(labelWidth, labelHeight), CornerRadius(8f, 8f), style = Stroke(1.5f))
            val distance = if (callout.distance >= 1_000) "%.1f km".format(callout.distance / 1_000) else "${callout.distance.toInt()} m"
            val name = "Zone ${zones.indexOf(callout.zone) + 1}"
            drawContext.canvas.nativeCanvas.drawText(name, labelCenter.x, labelCenter.y - 5f, namePaint)
            drawContext.canvas.nativeCanvas.drawText(distance, labelCenter.x, labelCenter.y + 19f, distancePaint)
        }
    }
}

private fun DrawScope.drawScale(v: MapViewport) {
    val desired = v.metersPerPx * 110
    val meters = listOf(100.0, 200.0, 500.0, 1_000.0, 2_000.0, 5_000.0, 10_000.0, 20_000.0, 50_000.0).lastOrNull { it <= desired } ?: 100.0
    val width = (meters / v.metersPerPx).toFloat(); val start = Offset(18f, size.height - 28f)
    drawLine(Color.White, start, start + Offset(width, 0f), 3f)
    drawLine(Color.White, start + Offset(0f, -5f), start + Offset(0f, 5f), 2f)
    drawLine(Color.White, start + Offset(width, -5f), start + Offset(width, 5f), 2f)
    val label = if (meters >= 1_000) "${(meters / 1_000).toInt()} km" else "${meters.toInt()} m"
    drawContext.canvas.nativeCanvas.drawText(label, start.x, start.y - 10f, Paint().apply {
        color = android.graphics.Color.WHITE; textSize = 28f; isAntiAlias = true
    })
}
