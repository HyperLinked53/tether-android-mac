package com.conduit.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.conduit.android.connection.ConnectionService
import com.conduit.android.state.ConduitState
import com.conduit.android.state.PairingInfo
import com.conduit.android.state.PeerConnection
import com.conduit.android.state.TransferStatus
import com.conduit.android.state.TransferUi
import androidx.compose.runtime.collectAsState
import com.conduit.android.ui.theme.ConduitTheme
import com.conduit.android.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {
    private val screenConsentRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeStore.init(this)
        if (intent?.getBooleanExtra(ConnectionService.EXTRA_REQUEST_SCREEN, false) == true) {
            screenConsentRequested.value = true
        }
        setContent { ConduitTheme { TetherApp(screenConsentRequested) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(ConnectionService.EXTRA_REQUEST_SCREEN, false)) {
            screenConsentRequested.value = true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ConnectionService.syncClipboard()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TetherApp(screenConsentRequested: MutableState<Boolean> = mutableStateOf(false)) {
    val context = LocalContext.current

    val running by ConduitState.serverRunning.collectAsStateWithLifecycle()
    val port by ConduitState.port.collectAsStateWithLifecycle()
    val peers by ConduitState.peers.collectAsStateWithLifecycle()
    val transfers by ConduitState.transfers.collectAsStateWithLifecycle()
    val pairing by ConduitState.pairing.collectAsStateWithLifecycle()
    val log by ConduitState.log.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? -> uri?.let { ConnectionService.sendFile(it) } }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ConnectionService.startScreenCapture(result.resultCode, data)
        }
    }
    fun launchScreenConsent() {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    LaunchedEffect(screenConsentRequested.value) {
        if (screenConsentRequested.value) {
            screenConsentRequested.value = false
            launchScreenConsent()
        }
    }

    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.READ_MEDIA_IMAGES)
            else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }.toTypedArray()
        permLauncher.launch(perms)
    }

    var allFilesAccess by rememberSaveable { mutableStateOf(hasAllFilesAccess()) }
    var remoteControlEnabled by rememberSaveable { mutableStateOf(isAccessibilityEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = hasAllFilesAccess()
                remoteControlEnabled = isAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Tether",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionCard(running, port, peers,
                onStart = { ConnectionService.start(context) },
                onStop = { ConnectionService.stop(context) },
                onPair = { ConnectionService.beginPairing() })

            pairing?.let { PairingCard(it) }

            val hasPeer = peers.any { it.authenticated }
            EditorialButton(
                text = "Send a file to Mac",
                enabled = hasPeer,
                onClick = { filePicker.launch("*/*") },
            )
            EditorialButton(
                text = "Remote Cursor",
                enabled = hasPeer,
                onClick = { context.startActivity(Intent(context, TouchpadActivity::class.java)) },
            )

            if (transfers.isNotEmpty()) TransfersCard(transfers)

            ScreenMirrorCard(
                enabled = hasPeer,
                onStart = { launchScreenConsent() },
                onStop = { ConnectionService.stopScreenMirroring() },
            )

            if (!remoteControlEnabled) RemoteControlCard {
                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }

            if (!allFilesAccess) AllFilesAccessCard {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))
                }
                runCatching { context.startActivity(intent) }
            }

            NotificationAccessCard {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }

            LogCard(log.map { it.text })

            AppearanceCard()

            AutoDisconnectCard()

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─── Shared primitives ─────────────────────────────────────────────────────

@Composable
private fun EditorialCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
        shape        = RoundedCornerShape(12.dp),
        color        = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun EditorialOutlinedCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
        shape        = RoundedCornerShape(12.dp),
        color        = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun EditorialButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor   = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun DimText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ─── Cards ─────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionCard(
    running: Boolean, port: Int, peers: List<PeerConnection>,
    onStart: () -> Unit, onStop: () -> Unit, onPair: () -> Unit,
) = EditorialCard {
    SectionTitle("Connection")
    BodyText(if (running) "Listening on port $port" else "Service stopped")
    if (peers.isEmpty()) {
        DimText("No Mac connected")
    } else {
        peers.forEach {
            DimText("• ${it.name} (${it.platform})${if (it.authenticated) "" else " — pairing…"}")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running) {
            OutlinedButton(
                onClick = onStop,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Stop", style = MaterialTheme.typography.labelMedium) }
        } else {
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("Start", style = MaterialTheme.typography.labelMedium) }
        }
        OutlinedButton(
            onClick = onPair,
            enabled = running,
            shape = RoundedCornerShape(10.dp),
        ) { Text("Pair a device", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun PairingCard(info: PairingInfo) = EditorialCard {
    val qr = remember(info.qrPayload) { qrImageBitmap(info.qrPayload) }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Pair with your Mac")
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.size(180.dp))
        }
        DimText("Enter this code in the Mac app:")
        SelectionCode(info.secret)
    }
}

@Composable
private fun SelectionCode(secret: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        SelectionContainer {
            Text(
                secret,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun TransfersCard(transfers: List<TransferUi>) = EditorialCard {
    SectionTitle("Transfers")
    transfers.take(8).forEach { t ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BodyText("${if (t.direction.name == "INCOMING") "↓" else "↑"} ${t.name}")
            when (t.status) {
                TransferStatus.ACTIVE, TransferStatus.OFFERED ->
                    LinearProgressIndicator(
                        progress = { t.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                else -> DimText(t.error ?: t.status.name)
            }
        }
    }
}

@Composable
private fun ScreenMirrorCard(enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) =
    EditorialCard {
        SectionTitle("Screen mirroring")
        DimText("Show this phone's screen on your Mac. You'll be asked to allow screen capture.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = enabled,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text("Start mirroring", style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = onStop,
                shape = RoundedCornerShape(10.dp),
            ) { Text("Stop", style = MaterialTheme.typography.labelMedium) }
        }
        if (!enabled) DimText("Connect your Mac first.")
    }

@Composable
private fun RemoteControlCard(onOpen: () -> Unit) = EditorialOutlinedCard {
    SectionTitle("Remote control")
    DimText("Enable Tether's accessibility service so your Mac can tap, swipe and type here while mirroring.")
    TextButton(onClick = onOpen) {
        Text("Enable remote control",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AllFilesAccessCard(onOpen: () -> Unit) = EditorialOutlinedCard {
    SectionTitle("File browsing")
    DimText("Grant \"All files access\" so your Mac can browse this phone's files.")
    TextButton(onClick = onOpen) {
        Text("Grant all files access",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NotificationAccessCard(onOpen: () -> Unit) = EditorialOutlinedCard {
    SectionTitle("Notification mirroring")
    DimText("Grant notification access to mirror alerts to your Mac.")
    TextButton(onClick = onOpen) {
        Text("Open notification access settings",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LogCard(lines: List<String>) = EditorialCard {
    SectionTitle("Activity")
    LazyColumn(Modifier.heightIn(max = 200.dp)) {
        items(lines.reversed()) {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppearanceCard() {
    val currentMode by ThemeStore.mode.collectAsState()
    val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
    EditorialCard {
        SectionTitle("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                val selected = currentMode == key
                Surface(
                    onClick = { ThemeStore.setMode(key) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                            .wrapContentWidth(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoDisconnectCard() {
    val currentOption by ThemeStore.disconnectOption.collectAsState()
    val currentCustomHours by ThemeStore.disconnectCustomHours.collectAsState()
    var customInput by remember { mutableStateOf(currentCustomHours.toString()) }

    val options = listOf(
        "never"  to "Until app quits",
        "1h"     to "1 hour",
        "5h"     to "5 hours",
        "12h"    to "12 hours",
        "custom" to "Custom",
    )

    EditorialCard {
        SectionTitle("Auto-disconnect")
        Text(
            "Automatically disconnect after the phone has been linked for this long.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (key, label) ->
            val selected = currentOption == key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { ThemeStore.setDisconnectOption(key) },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        if (currentOption == "custom") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { v ->
                        customInput = v.filter { it.isDigit() }
                        customInput.toIntOrNull()?.let { ThemeStore.setDisconnectCustomHours(it.coerceAtLeast(1)) }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Text("hours", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ─── Helpers ───────────────────────────────────────────────────────────────

private fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

private fun isAccessibilityEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val component = "${context.packageName}/${com.conduit.android.features.ConduitInputService::class.java.name}"
    return flat.split(':').any { it.equals(component, ignoreCase = true) }
}
