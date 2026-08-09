package com.nigh.aprstx

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    callsign: String,
    passcode: String,
    comment: String,
    status: String,
    interval: String,
    location: AprsLocation?,
    busy: Boolean,
    scheduling: Boolean,
    countdownSec: Int,
    onCallsign: (String) -> Unit,
    onPasscode: (String) -> Unit,
    onComment: (String) -> Unit,
    onStatus: (String) -> Unit,
    onInterval: (String) -> Unit,
    onGps: () -> Unit,
    onSend: () -> Unit,
    onStartSchedule: () -> Unit,
    onStopSchedule: () -> Unit,
    onOpenLogs: () -> Unit,
) {
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
            ) { Text(if (busy) "…" else "Send") }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = interval,
            onValueChange = onInterval,
            label = { Text("Interval (sec [>=${Aprs.MIN_INTERVAL_SEC}])") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !scheduling,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        if (!scheduling) {
            Button(
                onClick = onStartSchedule,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
        } else {
            val intervalSec = interval.toIntOrNull()?.coerceAtLeast(Aprs.MIN_INTERVAL_SEC) ?: 60
            val progress = if (intervalSec > 0) {
                (1f - countdownSec.toFloat() / intervalSec.toFloat()).coerceIn(0f, 1f)
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
                ) { Text("Stop (${countdownSec}s)") }
            }
            Text(
                text = "Sending every ${intervalSec}s — GPS acquired only at TX (background OK)",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(8.dp))
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs, key = { it.id }) { entry ->
                    Column {
                        Text(
                            text = "${fmt.format(Date(entry.timestampMs))}  ${entry.type.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (entry.type) {
                                LogType.SUCCESS -> ColorSuccess
                                LogType.ERROR -> ColorError
                                LogType.WARNING -> ColorWarning
                                LogType.INFO -> MaterialTheme.colorScheme.primary
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

private val ColorSuccess = Color(0xFF2E7D32)
private val ColorError = Color(0xFFC62828)
private val ColorWarning = Color(0xFFEF6C00)
