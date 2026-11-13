@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.levin.englishradio

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------- ViewModel

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val connected: Boolean = false,
        val stationId: String = "",
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val timerEndElapsed: Long = 0L,
        val nowElapsed: Long = 0L,
        val networkMode: Int = 0, // 0 = system default (Wi-Fi preferred), 1 = cellular
        val autoFailoverEnabled: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var controller: MediaController? = null
    private var reconnectAttempts = 0

    private val cmdPlay = SessionCommand(RadioService.ACTION_PLAY_STATION, Bundle.EMPTY)
    private val cmdSetTimer = SessionCommand(RadioService.ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)
    private val cmdGetTimer = SessionCommand(RadioService.ACTION_GET_SLEEP_TIMER, Bundle.EMPTY)
    private val cmdSetNetwork = SessionCommand(RadioService.ACTION_SET_NETWORK, Bundle.EMPTY)

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences(
            RadioService.PREFS, Context.MODE_PRIVATE,
        )
    }

    init {
        connect()
        // 1 Hz clock so the countdown chip recomposes.
        viewModelScope.launch {
            while (true) {
                val s = _state.value
                if (s.timerEndElapsed > 0L) {
                    _state.update { it.copy(nowElapsed = SystemClock.elapsedRealtime()) }
                }
                delay(1_000L)
            }
        }
    }

    private fun connect() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, RadioService::class.java))
        val future = MediaController.Builder(ctx, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    this@RadioViewModel.controller = null
                    _state.update { it.copy(connected = false) }
                }
            })
            .buildAsync()
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (c == null) {
                if (reconnectAttempts++ < 10) {
                    viewModelScope.launch {
                        delay(1_500)
                        connect()
                    }
                }
            } else {
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish()
                })
                _state.update {
                    it.copy(autoFailoverEnabled = prefs.getBoolean(RadioService.PREF_AUTO_FAILOVER, true))
                }
                fetchTimer()
                publish()
            }
        }, { command -> command.run() })
    }

    private fun publish() {
        val c = controller ?: return
        _state.update {
            it.copy(
                connected = true,
                stationId = c.currentMediaItem?.mediaId ?: it.stationId,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            )
        }
    }

    fun playStation(id: String) {
        val c = controller ?: return
        val s = _state.value
        if (s.stationId == id && s.isPlaying) {
            c.pause()
            return
        }
        if (s.stationId == id && !s.isPlaying) {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
        val args = Bundle().apply { putString(RadioService.EXTRA_STATION_ID, id) }
        c.sendCustomCommand(cmdPlay, args)
        publish()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE || c.mediaItemCount == 0) {
                c.prepare()
            }
            c.play()
        }
        publish()
    }

    fun setSleepTimer(minutes: Int?) {
        val c = controller ?: return
        val args = Bundle().apply { putInt(RadioService.EXTRA_MINUTES, minutes ?: -1) }
        c.sendCustomCommand(cmdSetTimer, args)
        _state.update {
            it.copy(
                timerEndElapsed = minutes?.let { m ->
                    SystemClock.elapsedRealtime() + m * 60_000L
                } ?: 0L
            )
        }
        fetchTimer()
    }

    private fun fetchTimer() {
        val c = controller ?: return
        val future = c.sendCustomCommand(cmdGetTimer, Bundle.EMPTY)
        future.addListener({
            try {
                val result = future.get()
                val extras = result.extras
                val end = extras?.getLong(RadioService.EXTRA_END_ELAPSED, 0L) ?: 0L
                val mode = extras?.getInt(RadioService.EXTRA_NETWORK_MODE, 0) ?: 0
                _state.update {
                    it.copy(
                        timerEndElapsed = end,
                        nowElapsed = SystemClock.elapsedRealtime(),
                        networkMode = mode,
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }, { command -> command.run() })
    }

    fun returnToWifi() {
        val c = controller ?: return
        val args = Bundle().apply { putInt(RadioService.EXTRA_MODE, 0) }
        c.sendCustomCommand(cmdSetNetwork, args)
        _state.update { it.copy(networkMode = 0) }
        fetchTimer()
    }

    fun useCellularNow() {
        val c = controller ?: return
        val args = Bundle().apply { putInt(RadioService.EXTRA_MODE, 1) }
        c.sendCustomCommand(cmdSetNetwork, args)
        fetchTimer()
    }

    fun setAutoFailoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(RadioService.PREF_AUTO_FAILOVER, enabled).apply()
        _state.update { it.copy(autoFailoverEnabled = enabled) }
    }

    fun timerRemainingMs(): Long {
        val end = _state.value.timerEndElapsed
        return if (end <= 0L) 0L else (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    /** Which dialog option the currently-running timer corresponds to (nearest preset). */
    fun activeTimerMinutes(): Int? {
        val remaining = timerRemainingMs()
        if (remaining <= 0L) return null
        val remMin = remaining / 60_000.0
        return when {
            remMin > 90.0 -> 120
            remMin > 45.0 -> 60
            else -> 30
        }
    }

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }
}

// ---------------------------------------------------------------------- Activity

class MainActivity : ComponentActivity() {

    private val viewModel: RadioViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContextHolder = applicationContext

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EnglishRadioTheme {
                MainScreen(viewModel, shouldShowBatteryHint(this))
            }
        }
    }

    private fun shouldShowBatteryHint(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_hint_done", false)) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        if (ignoring) prefs.edit().putBoolean("battery_hint_done", true).apply()
        return !ignoring
    }

    override fun onResume() {
        super.onResume()
        // Refresh state in case the service recovered while the UI was away.
    }
}

// ---------------------------------------------------------------------- Compose UI

@Composable
fun EnglishRadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4F8),
            secondary = Color(0xFF9CCCDC),
            background = Color(0xFF101418),
            surface = Color(0xFF171C22),
            onBackground = Color(0xFFE2E6EB),
            onSurface = Color(0xFFE2E6EB),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: RadioViewModel, showBatteryHint: Boolean) {
    val state by vm.state.collectAsState()
    var timerDialog by remember { mutableStateOf(false) }
    var returnWifiDialog by remember { mutableStateOf(false) }
    var networkDialog by remember { mutableStateOf(false) }
    var miuiDialog by remember { mutableStateOf(!miuiHintShown()) }
    var batteryHint by remember { mutableStateOf(showBatteryHint) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("English Radio", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp,
                ),
            ) {
                items(DEFAULT_STATIONS, key = { it.id }) { station ->
                    val selected = station.id == state.stationId
                    StationRow(
                        name = station.name,
                        subtitle = station.subtitle,
                        selected = selected,
                        isPlaying = selected && state.isPlaying,
                        isBuffering = selected && state.isBuffering,
                        onClick = { vm.playStation(station.id) },
                    )
                }
            }

            ControlBar(
                state = state,
                timerRemainingMs = vm.timerRemainingMs(),
                onPlayPause = { vm.togglePlay() },
                onTimerClick = { timerDialog = true },
                onNetworkClick = {
                    if (state.networkMode == 1) returnWifiDialog = true else networkDialog = true
                },
                onNetworkLongClick = { networkDialog = true },
            )
        }
    }

    if (timerDialog) {
        SleepTimerDialog(
            activeMinutes = vm.activeTimerMinutes(),
            onDismiss = { timerDialog = false },
            onSelect = {
                vm.setSleepTimer(it)
                timerDialog = false
            },
        )
    }

    if (returnWifiDialog) {
        HintDialog(
            title = "Return to Wi-Fi?",
            body = "Streams will use the home WLAN route again. If it is still unstable, the app " +
                "will automatically switch back to mobile data after a few stalls.",
            confirmText = "Use Wi-Fi",
            onConfirm = {
                vm.returnToWifi()
                returnWifiDialog = false
            },
            dismissText = "Stay on mobile",
            onDismiss = { returnWifiDialog = false },
        )
    }

    if (networkDialog) {
        NetworkDialog(
            networkMode = state.networkMode,
            autoFailover = state.autoFailoverEnabled,
            onAutoFailoverChange = { vm.setAutoFailoverEnabled(it) },
            onUseCellular = {
                vm.useCellularNow()
                networkDialog = false
            },
            onUseWifi = {
                vm.returnToWifi()
                networkDialog = false
            },
            onDismiss = { networkDialog = false },
        )
    }

    if (miuiDialog) {
        HintDialog(
            title = "Keep playback alive",
            body = "For uninterrupted background playback on this phone:\n\n" +
                "\u2022 Settings \u2192 Apps \u2192 English Radio \u2192 enable \"Autostart\"\n" +
                "\u2022 In the recent-apps screen, pull the app card down to lock it\n" +
                "\u2022 Keep notifications enabled for this app\n\n" +
                "The app itself handles network drops and dead stream URLs automatically.",
            confirmText = "OK",
            onConfirm = { markMiuiHintShown(); miuiDialog = false },
        )
    }

    if (batteryHint) {
        val context = androidx.compose.ui.platform.LocalContext.current
        HintDialog(
            title = "Disable battery optimization",
            body = "Android may pause playback when the screen is off. Allow English Radio to run " +
                "unrestricted in the app info screen (toggle \"Battery \u2192 Unrestricted\").",
            confirmText = "Open settings",
            onConfirm = {
                context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit().putBoolean("battery_hint_done", true).apply()
                try {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                } catch (e: Exception) {
                    // ignore
                }
                batteryHint = false
            },
            dismissText = "Skip",
            onDismiss = {
                context.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .edit().putBoolean("battery_hint_done", true).apply()
                batteryHint = false
            },
        )
    }
}

private fun miuiHintShown(): Boolean {
    val ctx = appContextHolder ?: return false
    return ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        .getBoolean("miui_hint_done", false)
}

private fun markMiuiHintShown() {
    appContextHolder?.getSharedPreferences("ui", Context.MODE_PRIVATE)
        ?.edit()?.putBoolean("miui_hint_done", true)?.apply()
}

// Tiny static context holder for prefs checks (no storage of Activity).
internal var appContextHolder: Context? = null

@Composable
fun StationRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.surfaceVariant ?: MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isBuffering && !isPlaying -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Box(
                        modifier = Modifier
                            .size(if (isPlaying) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (isPlaying) {
                NowPlayingBars(Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun NowPlayingBars(modifier: Modifier = Modifier) {
    // Simple animated equalizer drawn with Canvas (no icon deps).
    var tick by remember { mutableStateOf(0L) }
    val barColor = MaterialTheme.colorScheme.primary
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(220L)
        }
    }
    Canvas(modifier) {
        val barWidth = size.width / 5f
        val t = tick / 220.0
        for (i in 0 until 3) {
            val level = 0.35f + 0.6f * kotlin.math.abs(
                kotlin.math.sin(t * 1.7 + i * 1.3)
            ).toFloat()
            val h = size.height * level
            drawRoundRect(
                color = barColor,
                topLeft = Offset(barWidth * (1 + i), size.height - h),
                size = Size(barWidth * 0.7f, h),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlBar(
    state: RadioViewModel.UiState,
    timerRemainingMs: Long,
    onPlayPause: () -> Unit,
    onTimerClick: () -> Unit,
    onNetworkClick: () -> Unit,
    onNetworkLongClick: () -> Unit,
) {
    val station = stationById(state.stationId)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = state.connected, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPlaying) PauseGlyph(Modifier.size(26.dp))
                else PlayGlyph(Modifier.size(26.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    station?.name ?: if (state.connected) "Ready" else "Connecting\u2026",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !state.connected -> "Starting service\u2026"
                        state.isPlaying -> "Live"
                        state.isBuffering -> "Buffering / reconnecting\u2026"
                        else -> "Paused"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            NetworkChip(
                networkMode = state.networkMode,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .combinedClickable(
                        onClick = onNetworkClick,
                        onLongClick = onNetworkLongClick,
                    ),
            )
            OutlinedButton(onClick = onTimerClick, shape = RoundedCornerShape(20.dp)) {
                Text(
                    if (timerRemainingMs > 0) formatRemaining(timerRemainingMs) else "Sleep \u221E",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun NetworkChip(networkMode: Int, modifier: Modifier = Modifier) {
    val onCellular = networkMode == 1
    val color = if (onCellular) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary
    Text(
        text = if (onCellular) "Mobile data" else "Wi-Fi",
        fontSize = 12.sp,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
fun NetworkDialog(
    networkMode: Int,
    autoFailover: Boolean,
    onAutoFailoverChange: (Boolean) -> Unit,
    onUseCellular: () -> Unit,
    onUseWifi: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network") },
        text = {
            Column {
                Text(
                    "Streaming is using: ${if (networkMode == 1) "mobile data" else "Wi-Fi (default route)"}",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAutoFailoverChange(!autoFailover) }
                        .padding(vertical = 6.dp),
                ) {
                    Checkbox(checked = autoFailover, onCheckedChange = onAutoFailoverChange)
                    Text(
                        "Switch to mobile data automatically when Wi-Fi stalls",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUseWifi, enabled = networkMode != 0) {
                        Text("Use Wi-Fi", fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onUseCellular, enabled = networkMode != 1) {
                        Text("Use mobile data", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun SleepTimerDialog(
    activeMinutes: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf<Int?>(null, 30, 60, 120).forEach { m ->
                    val label = when (m) {
                        null -> "Off"
                        else -> "$m minutes"
                    }
                    val active = activeMinutes == m
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(m) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(label, fontSize = 15.sp, textAlign = TextAlign.Start)
                        if (m == 60) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "(default)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                Text(
                    "The volume fades out gently during the last minute, then playback pauses.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
fun HintDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text(title) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 21.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = if (dismissText != null && onDismiss != null) {
            { TextButton(onClick = onDismiss) { Text(dismissText) } }
        } else null,
    )
}

@Composable
fun PlayGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.12f)
            lineTo(size.width * 0.9f, size.height * 0.5f)
            lineTo(size.width * 0.22f, size.height * 0.88f)
            close()
        }
        drawPath(path, Color(0xFF101418))
    }
}

@Composable
fun PauseGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            Color(0xFF101418),
            Offset(w * 0.14f, h * 0.1f),
            Size(w * 0.26f, h * 0.8f),
            CornerRadius(3f, 3f),
        )
        drawRoundRect(
            Color(0xFF101418),
            Offset(w * 0.6f, h * 0.1f),
            Size(w * 0.26f, h * 0.8f),
            CornerRadius(3f, 3f),
        )
    }
}

fun formatRemaining(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
