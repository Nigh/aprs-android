package com.nigh.aprstx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GITHUB_URL = "https://github.com/Nigh/aprs-android"

@Composable
fun MainScreen(
    callsign: String,
    passcode: String,
    comment: String,
    status: String,
    location: AprsLocation?,
    busy: Boolean,
    scheduling: Boolean,
    countdownSec: Int,
    pollIntervalSec: Int,
    minIntervalSec: Int,
    maxIntervalSec: Int,
    smartMove: Boolean,
    moveThresholdM: Int,
    onCallsign: (String) -> Unit,
    onPasscode: (String) -> Unit,
    onComment: (String) -> Unit,
    onStatus: (String) -> Unit,
    onGps: () -> Unit,
    onSend: () -> Unit,
    onStartSchedule: () -> Unit,
    onStopSchedule: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("APRS-TX", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onOpenLogs) { Text("Logs") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = callsign,
                    onValueChange = onCallsign,
                    label = { Text("Callsign *") },
                    modifier = Modifier.weight(1f),
                    enabled = !scheduling,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(
                    value = passcode,
                    onValueChange = onPasscode,
                    label = { Text("Passcode *") },
                    modifier = Modifier.weight(1f),
                    enabled = !scheduling,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }

            HorizontalDivider()

            OutlinedTextField(
                value = comment,
                onValueChange = onComment,
                label = { Text("Comment (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = status,
                onValueChange = onStatus,
                label = { Text("Status (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            if (location != null) {
                val speedText = location.speedMps?.let { " • %.1f km/h".format(it * 3.6f) } ?: ""
                val accText = location.accuracy?.let { "±%.1fm".format(it) } ?: "±N/A"
                Text(
                    text = "%.4f°, %.4f°\n%s%s".format(
                        location.latitude,
                        location.longitude,
                        accText,
                        speedText,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onGps,
                    enabled = !busy && !scheduling,
                    modifier = Modifier.weight(1f),
                ) { Text(if (busy) "…" else "GPS") }
                Button(
                    onClick = onSend,
                    enabled = !busy && !scheduling,
                    modifier = Modifier.weight(2f),
                ) { Text(if (busy) "…" else "Send once") }
            }

            if (!scheduling) {
                Button(
                    onClick = onStartSchedule,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start scheduled TX") }
            } else {
                val progress = if (pollIntervalSec > 0) {
                    (1f - countdownSec.toFloat() / pollIntervalSec.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                Box(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .align(Alignment.Center),
                    )
                    FilledTonalButton(
                        onClick = onStopSchedule,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop scheduled TX (${countdownSec}s)") }
                }
                Text(
                    text = if (smartMove) {
                        "GPS every ${minIntervalSec}s — TX if moved ≥${moveThresholdM}m, else every ${maxIntervalSec}s"
                    } else {
                        "TX every ${minIntervalSec}s — GPS at each TX (background OK)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // room for floating Settings button
            Spacer(Modifier.height(56.dp))
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Text("Settings") }
    }
}

@Composable
fun SettingsScreen(
    autoStartOnWifiDisconnect: Boolean,
    autoStopOnWifiConnect: Boolean,
    minIntervalSec: Int,
    maxIntervalSec: Int,
    smartMove: Boolean,
    moveThresholdM: Int,
    stopZones: List<StopZone>,
    onAutoStartOnWifiDisconnect: (Boolean) -> Unit,
    onAutoStopOnWifiConnect: (Boolean) -> Unit,
    onMinInterval: (Int) -> Unit,
    onMaxInterval: (Int) -> Unit,
    onSmartMove: (Boolean) -> Unit,
    onMoveThreshold: (Int) -> Unit,
    onStopZonesChange: (List<StopZone>) -> Unit,
    onFetchGps: suspend () -> AprsLocation,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var previewLat by remember { mutableStateOf("") }
    var previewLon by remember { mutableStateOf("") }
    var gpsBusy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onBack) { Text("Back") }
            }

            var minText by remember(minIntervalSec) { mutableStateOf(minIntervalSec.toString()) }
            var maxText by remember(maxIntervalSec) { mutableStateOf(maxIntervalSec.toString()) }
            var moveText by remember(moveThresholdM) { mutableStateOf(moveThresholdM.toString()) }

            OutlinedTextField(
                value = minText,
                onValueChange = { raw ->
                    minText = raw
                    raw.toIntOrNull()
                        ?.takeIf { it in Aprs.MIN_INTERVAL_SEC..Aprs.MAX_INTERVAL_SEC }
                        ?.let(onMinInterval)
                },
                label = { Text("Min interval (sec [${Aprs.MIN_INTERVAL_SEC}–${Aprs.MAX_INTERVAL_SEC}])") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = if (smartMove) maxText else minText,
                onValueChange = { raw ->
                    maxText = raw
                    raw.toIntOrNull()
                        ?.takeIf { it in minIntervalSec..Aprs.MAX_INTERVAL_SEC }
                        ?.let(onMaxInterval)
                },
                label = { Text("Max interval (sec [${minIntervalSec}–${Aprs.MAX_INTERVAL_SEC}])") },
                modifier = Modifier.fillMaxWidth(),
                enabled = smartMove,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("TX on location change", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "GPS every min interval; TX if moved enough, else at max interval",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = smartMove, onCheckedChange = onSmartMove)
            }
            if (smartMove) {
                OutlinedTextField(
                    value = moveText,
                    onValueChange = { raw ->
                        moveText = raw
                        raw.toIntOrNull()
                            ?.takeIf { it in Aprs.MIN_MOVE_M..Aprs.MAX_MOVE_M }
                            ?.let(onMoveThreshold)
                    },
                    label = { Text("Move threshold m (${Aprs.MIN_MOVE_M}–${Aprs.MAX_MOVE_M})") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Auto-start on WiFi disconnect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                "Starts after one min interval (${minIntervalSec}s) disconnected; WiFi stop arms after 100s disconnected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoStartOnWifiDisconnect,
                    onCheckedChange = onAutoStartOnWifiDisconnect,
                )
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Auto-stop on WiFi connect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Stops the schedule when WiFi connects",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoStopOnWifiConnect,
                    onCheckedChange = onAutoStopOnWifiConnect,
                )
            }

            HorizontalDivider()

            Text(
                "Stop zones (${stopZones.size}/${StopZone.MAX_ZONES})",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Auto-stop when entering an enabled zone (after leaving all +${StopZone.CLEAR_EXTRA_M}m if started inside)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = previewLat,
                    onValueChange = { previewLat = it },
                    label = { Text("Latitude") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = previewLon,
                    onValueChange = { previewLon = it },
                    label = { Text("Longitude") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            gpsBusy = true
                            try {
                                val loc = onFetchGps()
                                previewLat = "%.6f".format(loc.latitude)
                                previewLon = "%.6f".format(loc.longitude)
                            } catch (_: Exception) {
                                // parent logs/toasts
                            } finally {
                                gpsBusy = false
                            }
                        }
                    },
                    enabled = !gpsBusy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (gpsBusy) "…" else "GPS") }
                Button(
                    onClick = {
                        val lat = previewLat.toDoubleOrNull() ?: return@Button
                        val lon = previewLon.toDoubleOrNull() ?: return@Button
                        if (stopZones.size >= StopZone.MAX_ZONES) return@Button
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@Button
                        onStopZonesChange(
                            stopZones + StopZone(lat, lon, StopZone.DEFAULT_RADIUS_M, enabled = true),
                        )
                    },
                    enabled = stopZones.size < StopZone.MAX_ZONES &&
                        previewLat.toDoubleOrNull() != null &&
                        previewLon.toDoubleOrNull() != null,
                    modifier = Modifier.weight(1f),
                ) { Text("Add") }
            }

            stopZones.forEachIndexed { index, zone ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%.4f°, %.4f°".format(zone.latitude, zone.longitude),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = zone.enabled,
                        onCheckedChange = { on ->
                            onStopZonesChange(stopZones.toMutableList().also {
                                it[index] = zone.copy(enabled = on)
                            })
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var radiusText by remember(zone.latitude, zone.longitude, zone.radiusM) {
                        mutableStateOf(zone.radiusM.toString())
                    }
                    OutlinedTextField(
                        value = radiusText,
                        onValueChange = { raw ->
                            radiusText = raw
                            raw.toIntOrNull()
                                ?.takeIf { it in StopZone.MIN_RADIUS_M..StopZone.MAX_RADIUS_M }
                                ?.let { n ->
                                    onStopZonesChange(stopZones.toMutableList().also {
                                        it[index] = zone.copy(radiusM = n)
                                    })
                                }
                        },
                        label = { Text("Radius m (${StopZone.MIN_RADIUS_M}–${StopZone.MAX_RADIUS_M})") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    TextButton(
                        onClick = {
                            onStopZonesChange(stopZones.toMutableList().also { it.removeAt(index) })
                        },
                    ) { Text("Remove") }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = { uriHandler.openUri(GITHUB_URL) }) {
                Text("github.com/Nigh/aprs-android")
            }
            Text(
                text = "made by BA7NTM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun LogsScreen(
    logs: List<LogEntry>,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Operation Logs", style = MaterialTheme.typography.headlineSmall)
            Row {
                if (logs.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text("No logs yet. Operations will appear here.")
        } else {
            val dark = isSystemInDarkTheme()
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs, key = { it.id }) { entry ->
                    Column {
                        Text(
                            text = "${fmt.format(Date(entry.timestampMs))}  ${entry.type.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (entry.type) {
                                LogType.SUCCESS -> if (dark) XianiiSuccess else XianiiSuccessLight
                                LogType.ERROR -> if (dark) XianiiError else XianiiErrorLight
                                LogType.WARNING -> if (dark) XianiiWarning else XianiiWarningLight
                                LogType.INFO -> if (dark) XianiiInfo else XianiiInfoLight
                            },
                        )
                        Text(
                            text = entry.message,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
