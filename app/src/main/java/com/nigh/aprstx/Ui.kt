package com.nigh.aprstx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ListItem
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val GITHUB_URL = "https://github.com/Nigh/aprs-android"

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

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
) {
    var passcodeFocused by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("APRS-TX", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onOpenLogs) { Text("Logs") }
            }
            HorizontalDivider()
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Station", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { heading() })
                        if (scheduling) Text("Stop scheduled TX to edit your credentials.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = callsign, onValueChange = onCallsign,
                                label = { Text("Callsign *") }, modifier = Modifier.weight(1f),
                                enabled = !scheduling, singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                            )
                            OutlinedTextField(
                                value = passcode, onValueChange = onPasscode,
                                label = { Text("Passcode *") },
                                modifier = Modifier.weight(1f).onFocusChanged { passcodeFocused = it.isFocused },
                                enabled = !scheduling, singleLine = true,
                                visualTransformation = if (passcodeFocused) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Packet text", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() })
                        OutlinedTextField(
                            value = comment, onValueChange = onComment,
                            label = { Text("Comment (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        OutlinedTextField(
                            value = status, onValueChange = onStatus,
                            label = { Text("Status (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                    }
                }
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Location", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).semantics { heading() })
                        }
                        if (location != null) {
                            Text("%.4f°, %.4f°".format(location.latitude, location.longitude),
                                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            val accuracy = location.accuracy?.let { "Accuracy ±%.1f m".format(it) } ?: "Accuracy unavailable"
                            val speed = location.speedMps?.let { " · %.1f km/h".format(it * 3.6f) } ?: ""
                            Text(accuracy + speed, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("No location yet. Get a GPS fix or send to locate automatically.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onGps, enabled = !busy && !scheduling,
                            modifier = Modifier.fillMaxWidth()) { Text("Get GPS location") }
                    }
                }
                Surface(shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Transmission", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).semantics { heading() })
                            Text(if (scheduling) "Running" else "Stopped", style = MaterialTheme.typography.labelLarge,
                                color = if (scheduling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (smartMove) "GPS every ${minIntervalSec}s · TX after ${moveThresholdM}m, or at ${maxIntervalSec}s"
                            else "Scheduled TX every ${minIntervalSec}s",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (scheduling) {
                            Text("Next location check in ${countdownSec}s", style = MaterialTheme.typography.bodyMedium)
                            val progress = if (pollIntervalSec > 0) {
                                (1f - countdownSec.toFloat() / pollIntervalSec.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            FilledTonalButton(onClick = onStopSchedule, modifier = Modifier.fillMaxWidth()) {
                                Text("Stop scheduled TX")
                            }
                        } else {
                            if (busy) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text("Working…", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = onStartSchedule, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Start scheduled TX")
                            }
                            OutlinedButton(onClick = onSend, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text("Send once")
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SettingsSection(val title: String, val description: String) {
    TRANSMISSION("Transmission", "Choose when scheduled APRS packets are sent."),
    AUTOMATION("Automation", "Control GPS power use and WiFi start / stop rules."),
    WEBHOOK("Webhook", "Send location updates to your own HTTPS backend."),
    ZONES("Stop zones", "Manage places where APRS transmission is blocked."),
    BACKUP("Backup", "Export your configuration or restore it from a file."),
}

@Composable
private fun NavigationArrow(back: Boolean = false) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.size(24.dp)) {
        val pointsLeft = back != rtl
        fun point(x: Float, y: Float) = Offset(size.width * (if (pointsLeft) 1f - x else x), size.height * y)
        val stroke = 2.dp.toPx()
        drawLine(color, point(.4f, .25f), point(.65f, .5f), stroke, StrokeCap.Round)
        drawLine(color, point(.65f, .5f), point(.4f, .75f), stroke, StrokeCap.Round)
        if (back) drawLine(color, point(.2f, .5f), point(.65f, .5f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SettingsEntry(section: SettingsSection, summary: String, onOpen: (SettingsSection) -> Unit) {
    ListItem(
        headlineContent = { Text(section.title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { NavigationArrow() },
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open ${section.title}") { onOpen(section) },
    )
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp).semantics { heading() })
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = null, colors = settingsSwitchColors())
    }
}

@Composable
fun SettingsScreen(
    autoStartOnWifiDisconnect: Boolean,
    autoStopOnWifiConnect: Boolean,
    autoPowerSave: Boolean,
    showTxSuccessToast: Boolean,
    onShowTxSuccessToast: (Boolean) -> Unit,
    webhookEnabled: Boolean,
    webhookUrl: String,
    webhookId: String,
    onWebhookEnabled: (Boolean) -> Unit,
    onWebhookUrl: (String) -> Unit,
    onWebhookId: (String) -> Unit,
    minIntervalSec: Int,
    maxIntervalSec: Int,
    smartMove: Boolean,
    moveThresholdM: Int,
    stopZones: List<StopZone>,
    onAutoStartOnWifiDisconnect: (Boolean) -> Unit,
    onAutoStopOnWifiConnect: (Boolean) -> Unit,
    onAutoPowerSave: (Boolean) -> Unit,
    onMinInterval: (Int) -> Unit,
    onMaxInterval: (Int) -> Unit,
    onSmartMove: (Boolean) -> Unit,
    onMoveThreshold: (Int) -> Unit,
    onStopZonesChange: (List<StopZone>) -> Unit,
    onFetchGps: suspend () -> AprsLocation,
    onExportJson: () -> String,
    onImportJson: (String) -> Boolean,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewLat by remember { mutableStateOf("") }
    var previewLon by remember { mutableStateOf("") }
    var gpsBusy by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupBusy = true
            try {
                val json = onExportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream failed")
                }
                BeaconRuntime.emitToast("Settings exported", LogType.SUCCESS)
            } catch (e: Exception) {
                BeaconRuntime.emitToast("Export failed: ${e.message}", LogType.ERROR)
            } finally {
                backupBusy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupBusy = true
            try {
                val raw = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("openInputStream failed")
                }
                if (onImportJson(raw)) {
                    BeaconRuntime.emitToast("Settings imported", LogType.SUCCESS)
                } else {
                    BeaconRuntime.emitToast("Import failed: invalid file", LogType.ERROR)
                }
            } catch (e: Exception) {
                BeaconRuntime.emitToast("Import failed: ${e.message}", LogType.ERROR)
            } finally {
                backupBusy = false
            }
        }
    }

    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = section != null) {
        focusManager.clearFocus()
        section = null
    }

    val pageState = rememberSaveableStateHolder()
    val overviewScroll = rememberScrollState()
    val navigateBack = {
        focusManager.clearFocus()
        if (section == null) onBack() else section = null
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (section != null) IconButton(onClick = navigateBack,
                    modifier = Modifier.semantics { contentDescription = "Back to settings" }) {
                    NavigationArrow(back = true)
                }
                Column(Modifier.weight(1f)) {
                    if (section != null) Text("Settings",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(section?.title ?: "Settings", style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                }
            }
            HorizontalDivider()
            AnimatedContent(
                targetState = section,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState == null) -1 else 1
                    (slideInHorizontally(tween(220)) { direction * it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(180)) { -direction * it / 4 } + fadeOut(tween(180)))
                },
                label = "Settings navigation",
            ) { currentSection ->
                pageState.SaveableStateProvider(currentSection?.name ?: "overview") {
                    val scroll = if (currentSection == null) overviewScroll else rememberScrollState()
                    Column(
                        Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 24.dp)
                            .semantics { paneTitle = currentSection?.title ?: "Settings overview" },
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (currentSection != null) {
                            Text(currentSection.description, style = MaterialTheme.typography.bodyLarge)
                            Text("Valid changes are saved automatically.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        when (currentSection) {
                            null -> {
                                Text("Manage your station", style = MaterialTheme.typography.titleLarge)
                                Text("Choose a category to adjust its settings.", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SettingsGroup("Beacon & location") {
                                    SettingsEntry(SettingsSection.TRANSMISSION,
                                        if (smartMove) "${minIntervalSec}–${maxIntervalSec}s · Move ${moveThresholdM}m" else "Every ${minIntervalSec}s",
                                    ) { section = it }
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    SettingsEntry(SettingsSection.AUTOMATION,
                                        "Power saving ${if (autoPowerSave) "on" else "off"} · WiFi ${if (autoStartOnWifiDisconnect || autoStopOnWifiConnect) "on" else "off"}",
                                    ) { section = it }
                                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                                    SettingsEntry(SettingsSection.ZONES,
                                        if (stopZones.isEmpty()) "No zones configured" else "${stopZones.count { it.enabled }} enabled · ${stopZones.size} total",
                                    ) { section = it }
                                }
                                SettingsGroup("Integrations") {
                                    SettingsEntry(SettingsSection.WEBHOOK,
                                        when {
                                            !webhookEnabled -> "Off · Optional location reporting"
                                            !Webhook.validUrl(webhookUrl.trim()) || webhookId.isBlank() -> "On · Setup required"
                                            else -> "On · ${webhookId.trim()}"
                                        },
                                    ) { section = it }
                                }
                                SettingsGroup("Data & backup") {
                                    SettingsEntry(SettingsSection.BACKUP, "Export or restore settings as JSON") { section = it }
                                }
                                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                    TextButton(onClick = { uriHandler.openUri(GITHUB_URL) },
                                        modifier = Modifier.semantics { contentDescription = "Open project repository, made by BA7NTM" }) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("github.com/Nigh/aprs-android", textAlign = TextAlign.Center)
                                            Text("made by BA7NTM", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                            SettingsSection.TRANSMISSION -> {
                                SettingsToggle("Show TX success toast",
                                    "Show a short message after each successful APRS transmission. Errors and cancellation messages remain visible.",
                                    showTxSuccessToast, onShowTxSuccessToast)
                                HorizontalDivider()
                                var minText by remember(minIntervalSec) { mutableStateOf(minIntervalSec.toString()) }
                                var maxText by remember(maxIntervalSec) { mutableStateOf(maxIntervalSec.toString()) }
                                var moveText by remember(moveThresholdM) { mutableStateOf(moveThresholdM.toString()) }

                                Text("Timing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                                OutlinedTextField(
                                    value = minText,
                                    onValueChange = { raw ->
                                        minText = raw
                                        raw.toIntOrNull()
                                            ?.takeIf { it in Aprs.MIN_INTERVAL_SEC..Aprs.MAX_INTERVAL_SEC }
                                            ?.let(onMinInterval)
                                    },
                                    label = { Text(if (smartMove) "Minimum interval (seconds)" else "Send interval (seconds)") },
                                    isError = minText.toIntOrNull()?.let { it in Aprs.MIN_INTERVAL_SEC..Aprs.MAX_INTERVAL_SEC } != true,
                                    supportingText = { Text("${Aprs.MIN_INTERVAL_SEC}–${Aprs.MAX_INTERVAL_SEC} seconds") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                )

                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                SettingsToggle(
                                    "TX on location change",
                                    "Check at the minimum interval. Send after moving, or at the maximum interval.",
                                    smartMove, onSmartMove,
                                )
                                if (smartMove) {
                                    OutlinedTextField(
                                        value = maxText,
                                        onValueChange = { raw ->
                                            maxText = raw
                                            raw.toIntOrNull()
                                                ?.takeIf { it in minIntervalSec..Aprs.MAX_INTERVAL_SEC }
                                                ?.let(onMaxInterval)
                                        },
                                        label = { Text("Maximum interval (seconds)") },
                                        isError = maxText.toIntOrNull()?.let { it in minIntervalSec..Aprs.MAX_INTERVAL_SEC } != true,
                                        supportingText = { Text("${minIntervalSec}–${Aprs.MAX_INTERVAL_SEC} seconds") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )

                                    OutlinedTextField(
                                        value = moveText,
                                        onValueChange = { raw ->
                                            moveText = raw
                                            raw.toIntOrNull()
                                                ?.takeIf { it in Aprs.MIN_MOVE_M..Aprs.MAX_MOVE_M }
                                                ?.let(onMoveThreshold)
                                        },
                                        label = { Text("Move threshold (meters)") },
                                        isError = moveText.toIntOrNull()?.let { it in Aprs.MIN_MOVE_M..Aprs.MAX_MOVE_M } != true,
                                        supportingText = { Text("${Aprs.MIN_MOVE_M}–${Aprs.MAX_MOVE_M} meters") },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                }
                            }
                            SettingsSection.AUTOMATION -> {
                                SettingsToggle("Automatic power saving",
                                    "After 3 GPS timeouts, increase polling by ${GpsPowerSave.STEP_SEC}s, up to ${GpsPowerSave.MAX_INTERVAL_SEC}s. No change when the minimum interval is already ${GpsPowerSave.MAX_INTERVAL_SEC}s or more.",
                                    autoPowerSave, onAutoPowerSave)
                                HorizontalDivider()
                                Text("WiFi", style = MaterialTheme.typography.titleMedium)
                                SettingsToggle("Start after disconnecting",
                                    "Start scheduled TX after ${minIntervalSec}s without WiFi.",
                                    autoStartOnWifiDisconnect, onAutoStartOnWifiDisconnect)
                                SettingsToggle("Stop after connecting",
                                    "Stop scheduled TX when WiFi connects. If WiFi is connected at launch, first stay disconnected for 100s to arm this rule.",
                                    autoStopOnWifiConnect, onAutoStopOnWifiConnect)
                            }
                            SettingsSection.WEBHOOK -> {
                                Text("Device identity", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
                                Text("Copy the device_hash sent with every report to register this device on your server. It identifies the device; it is not a request signature.",
                                    style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = {
                                    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Device hash", Webhook.deviceHash(context)))
                                    if (android.os.Build.VERSION.SDK_INT < 33) BeaconRuntime.emitToast("Device hash copied", LogType.SUCCESS)
                                }) { Text("Copy device hash") }
                                HorizontalDivider()
                                SettingsToggle("Enable webhook",
                                    "Report fresh locations even when stop zones or other rules block APRS.",
                                    webhookEnabled, onWebhookEnabled)
                                if (webhookEnabled) {
                                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                    Text("Server configuration", style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.semantics { heading() })
                                    OutlinedTextField(
                                        value = webhookUrl,
                                        onValueChange = onWebhookUrl,
                                        label = { Text("Webhook HTTPS URL") },
                                        singleLine = true,
                                        isError = webhookEnabled && !Webhook.validUrl(webhookUrl.trim()),
                                        supportingText = { Text(if (Webhook.validUrl(webhookUrl.trim())) "Receives JSON POST requests" else "Enter a valid HTTPS URL without userinfo or a fragment") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = webhookId,
                                        onValueChange = onWebhookId,
                                        label = { Text("Webhook reporting ID") },
                                        singleLine = true,
                                        isError = webhookEnabled && webhookId.isBlank(),
                                        supportingText = { Text("Required: identifies you on your webhook server") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            SettingsSection.ZONES -> {
                                Text(
                                    "${stopZones.count { it.enabled }} enabled · ${stopZones.size}/${StopZone.MAX_ZONES} zones",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "APRS is blocked inside enabled zones. Leave by ${StopZone.CLEAR_EXTRA_M}m (${StopZone.LARGE_CLEAR_EXTRA_M}m for radii above ${StopZone.LARGE_RADIUS_THRESHOLD_M}m) to arm auto-stop.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Text("Add a stop zone", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
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
                                                    previewLat = "%.6f".format(Locale.US, loc.latitude)
                                                    previewLon = "%.6f".format(Locale.US, loc.longitude)
                                                } catch (_: Exception) {
                                                    // parent logs/toasts
                                                } finally {
                                                    gpsBusy = false
                                                }
                                            }
                                        },
                                        enabled = !gpsBusy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text(if (gpsBusy) "Locating…" else "Use GPS") }
                                    Button(
                                        onClick = {
                                            val lat = previewLat.toDoubleOrNull() ?: return@Button
                                            val lon = previewLon.toDoubleOrNull() ?: return@Button
                                            if (stopZones.size >= StopZone.MAX_ZONES) return@Button
                                            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@Button
                                            onStopZonesChange(
                                                stopZones + StopZone(lat, lon, StopZone.DEFAULT_RADIUS_M, enabled = true),
                                            )
                                            previewLat = ""
                                            previewLon = ""
                                        },
                                        enabled = stopZones.size < StopZone.MAX_ZONES &&
                                            previewLat.toDoubleOrNull()?.let { it in -90.0..90.0 } == true &&
                                            previewLon.toDoubleOrNull()?.let { it in -180.0..180.0 } == true,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Add zone") }
                                }

                                if (stopZones.isEmpty()) {
                                    Text("No stop zones yet. Use GPS or enter coordinates to add one.", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (stopZones.isNotEmpty()) Text("Your stop zones", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() })
                                stopZones.forEachIndexed { index, zone ->
                                    key(zone.id) {
                                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Text(
                                                        "Stop zone ${index + 1}\n%.4f°, %.4f°".format(zone.latitude, zone.longitude),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    Switch(
                                                        modifier = Modifier.semantics { contentDescription = "Enable ${zone.note.ifBlank { "stop zone ${index + 1}" }}" },
                                                        checked = zone.enabled,
                                                        onCheckedChange = { on ->
                                                            onStopZonesChange(stopZones.toMutableList().also {
                                                                it[index] = zone.copy(enabled = on)
                                                            })
                                                        },
                                                        colors = settingsSwitchColors(),
                                                    )
                                                }
                                                OutlinedTextField(
                                                    value = zone.note,
                                                    onValueChange = { note ->
                                                        onStopZonesChange(stopZones.toMutableList().also {
                                                            it[index] = zone.copy(note = StopZone.clampNote(note))
                                                        })
                                                    },
                                                    label = { Text("Note (optional, 64 characters)") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true,
                                                )
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    var radiusText by remember(zone.id, zone.radiusM) {
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
                                                        label = { Text("Radius (meters)") },
                                                        isError = radiusText.toIntOrNull()?.let { it in StopZone.MIN_RADIUS_M..StopZone.MAX_RADIUS_M } != true,
                                                        supportingText = { Text("${StopZone.MIN_RADIUS_M}–${StopZone.MAX_RADIUS_M} meters") },
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
                                    }
                                }
                            }
                            SettingsSection.BACKUP -> {
                                Text(
                                    "Save or restore all settings, including APRS credentials, automation, webhook, and stop zones. Keep exported files private.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { exportLauncher.launch("aprs-tx-settings.json") },
                                        enabled = !backupBusy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Export JSON") }
                                    OutlinedButton(
                                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                                        enabled = !backupBusy,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Import JSON") }
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
