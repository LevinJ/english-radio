@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.levin.englishradio

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.concurrent.futures.ResolvableFuture
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RadioService : MediaSessionService() {

    companion object {
        const val ACTION_PLAY_STATION = "com.levin.englishradio.PLAY_STATION"
        const val ACTION_SET_SLEEP_TIMER = "com.levin.englishradio.SET_SLEEP_TIMER"
        const val ACTION_GET_SLEEP_TIMER = "com.levin.englishradio.GET_SLEEP_TIMER"
        const val ACTION_SET_NETWORK = "com.levin.englishradio.SET_NETWORK"
        const val EXTRA_STATION_ID = "stationId"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_END_ELAPSED = "endElapsed"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NETWORK_MODE = "networkMode"

        val CMD_PLAY = SessionCommand(ACTION_PLAY_STATION, Bundle.EMPTY)
        val CMD_SET_TIMER = SessionCommand(ACTION_SET_SLEEP_TIMER, Bundle.EMPTY)
        val CMD_GET_TIMER = SessionCommand(ACTION_GET_SLEEP_TIMER, Bundle.EMPTY)
        val CMD_SET_NETWORK = SessionCommand(ACTION_SET_NETWORK, Bundle.EMPTY)

        const val PREFS = "english_radio"
        const val PREF_AUTO_FAILOVER = "auto_failover"
        private const val BACKOFF_MIN_MS = 1_000L
        private const val BACKOFF_MAX_MS = 30_000L
        private const val HEAL_AFTER_FAILURES = 5
        private const val FADE_MS = 60_000L

        // Network watchdog tuning.
        private const val MONITOR_INTERVAL_MS = 2_000L
        private const val STALL_MS = 12_000L
        private const val STALL_WINDOW_MS = 60_000L
        private const val STALLS_TO_FAILOVER = 2
        private const val FAILOVER_COOLDOWN_MS = 90_000L
        private const val FAILOVER_MAX = 3
        private const val FAILOVER_WINDOW_MS = 10 * 60_000L
        private const val PIN_TIMEOUT_MS = 10_000

        enum class NetMode { DEFAULT, CELLULAR }
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var prefs: SharedPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var currentStationId: String = ""
    private var consecutiveFailures = 0
    private var backoffMs = BACKOFF_MIN_MS
    private var healing = false
    private val triedUrls = mutableSetOf<String>()

    @Volatile private var timerEndElapsed = 0L

    // Network failover state.
    private var netMode = NetMode.DEFAULT
    private var cellularRequest: NetworkRequest? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularNetwork: Network? = null
    private val stallEvents = ArrayDeque<Long>()
    private val failoverEvents = ArrayDeque<Long>()
    private var lastFailoverAt = 0L
    private var lastPositionMs = -1L
    private var lastProgressAt = SystemClock.elapsedRealtime()

    private val monitorRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.playWhenReady && p.mediaItemCount > 0) {
                val now = SystemClock.elapsedRealtime()
                val pos = p.currentPosition
                if (p.isPlaying || pos != lastPositionMs) {
                    lastPositionMs = pos
                    lastProgressAt = now
                } else if (now - lastProgressAt > STALL_MS) {
                    // Buffered data is not advancing although playback is requested.
                    lastProgressAt = now
                    onStallDetected(p)
                }
            }
            handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    private val retryRunnable = Runnable {
        val p = player ?: return@Runnable
        val station = stationById(currentStationId) ?: return@Runnable
        rotateToNextUrl(p, station)
        p.prepare()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val end = timerEndElapsed
            if (end == 0L) return
            val remaining = end - SystemClock.elapsedRealtime()
            when {
                remaining <= 0L -> {
                    timerEndElapsed = 0L
                    p.volume = 1f
                    p.pause()
                }
                remaining <= FADE_MS -> {
                    p.volume = (remaining.toFloat() / FADE_MS).coerceIn(0.05f, 1f)
                    handler.postDelayed(this, 1_000L)
                }
                else -> handler.postDelayed(this, 2_000L)
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { p ->
                p.addListener(playerListener)
                val station = stationById(prefs.getString("last_station", null))
                    ?: DEFAULT_STATIONS.first()
                p.setMediaItem(mediaItemFor(station))
                if (prefs.getBoolean("last_playing", false)) {
                    p.playWhenReady = true
                    p.prepare()
                }
            }
        player = exo

        mediaSession = MediaSession.Builder(this, exo)
            .setCallback(sessionCallback)
            .build()

        registerNetworkListener()
        handler.post(monitorRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkListener()
        releaseCellular(false)
        scope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player?.run {
            removeListener(playerListener)
            release()
            player = null
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- playback helpers

    private fun activeUrl(station: Station): String {
        val lastGood = prefs.getString("url_${station.id}", null)
        val healed = prefs.getString("healed_${station.id}", null)
        return lastGood ?: healed ?: station.urls.first()
    }

    private fun candidateUrls(station: Station): List<String> {
        val list = LinkedHashSet<String>()
        list.add(activeUrl(station))
        list.addAll(station.urls)
        prefs.getString("healed_${station.id}", null)?.let { list.add(it) }
        return list.toList()
    }

    private fun mediaItemFor(station: Station): MediaItem =
        MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(activeUrl(station))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(station.name)
                    .setArtist(station.subtitle)
                    .build()
            )
            .build()

    private fun switchTo(station: Station, play: Boolean) {
        val p = player ?: return
        consecutiveFailures = 0
        backoffMs = BACKOFF_MIN_MS
        healing = false
        triedUrls.clear()
        handler.removeCallbacks(retryRunnable)
        p.setMediaItem(mediaItemFor(station))
        p.prepare()
        if (play) p.playWhenReady = true
    }

    /** Cycle through the candidate URLs when consecutive failures accumulate. */
    private fun rotateToNextUrl(p: ExoPlayer, station: Station) {
        val candidates = candidateUrls(station)
        val current = p.currentMediaItem?.localConfiguration?.uri?.toString()
        val idx = candidates.indexOf(current)
        val nextIdx = ((if (idx < 0) 0 else idx + 1)).coerceAtMost(candidates.size - 1)
        if (candidates[nextIdx] != current) {
            p.setMediaItem(mediaItemFor(station).buildUpon().setUri(candidates[nextIdx]).build())
        }
    }

    private fun onPlaybackFailed() {
        val p = player ?: return
        val station = stationById(currentStationId) ?: return
        p.currentMediaItem?.localConfiguration?.uri?.toString()?.let { triedUrls.add(it) }
        consecutiveFailures++

        if (consecutiveFailures >= HEAL_AFTER_FAILURES && !healing) {
            startHealing(station)
            return
        }
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** Ask the Radio Browser directory for a replacement URL for this station. */
    private fun startHealing(station: Station) {
        healing = true
        scope.launch(Dispatchers.IO) {
            val replacement = try {
                StreamHealer.findReplacement(station, triedUrls + candidateUrls(station))
            } catch (e: Exception) {
                null
            }
            handler.post {
                healing = false
                if (replacement != null && currentStationId == station.id) {
                    prefs.edit()
                        .putString("healed_${station.id}", replacement)
                        .putString("url_${station.id}", replacement)
                        .apply()
                    consecutiveFailures = 0
                    backoffMs = BACKOFF_MIN_MS
                    val p = player ?: return@post
                    p.setMediaItem(
                        mediaItemFor(station).buildUpon().setUri(replacement).build()
                    )
                    p.prepare()
                } else {
                    // No replacement found; keep cycling existing URLs.
                    onPlaybackFailed()
                }
            }
        }
    }

    // ---------------------------------------------------------------- network monitoring

    private fun registerNetworkListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { reconnectNow("network available") }
            }

            override fun onLost(network: Network) {
                // Player will surface errors; retries handle recovery.
            }
        }
        try {
            cm.registerDefaultNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    private fun unregisterNetworkListener() {
        val cm = connectivityManager ?: return
        val cb = networkCallback ?: return
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // ignore
        }
        connectivityManager = null
        networkCallback = null
    }

    /** Retry immediately (network came back) instead of waiting out the backoff. */
    private fun reconnectNow(reason: String) {
        val p = player ?: return
        if (!p.playWhenReady) return
        if (p.isPlaying) return
        handler.removeCallbacks(retryRunnable)
        consecutiveFailures = 0
        backoffMs = BACKOFF_MIN_MS
        p.prepare()
    }

    // ---------------------------------------------------------------- sleep timer

    private fun setSleepTimer(minutes: Int?) {
        handler.removeCallbacks(timerRunnable)
        val p = player ?: return
        if (minutes == null || minutes <= 0) {
            timerEndElapsed = 0L
            p.volume = 1f
        } else {
            timerEndElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
            handler.post(timerRunnable)
        }
    }

    // ---------------------------------------------------------------- listeners

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentStationId = mediaItem?.mediaId ?: ""
            consecutiveFailures = 0
            backoffMs = BACKOFF_MIN_MS
            handler.removeCallbacks(retryRunnable)
            if (currentStationId.isNotEmpty()) {
                prefs.edit().putString("last_station", currentStationId).apply()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            prefs.edit().putBoolean("last_playing", isPlaying || p.playWhenReady).apply()
            if (isPlaying) {
                // Playback is healthy again: persist the working URL.
                p.currentMediaItem?.localConfiguration?.uri?.toString()?.let { url ->
                    if (currentStationId.isNotEmpty()) {
                        prefs.edit().putString("url_$currentStationId", url).apply()
                    }
                }
                consecutiveFailures = 0
                backoffMs = BACKOFF_MIN_MS
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            recordStallEvent()
            onPlaybackFailed()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            // Also catch "stuck buffering": no error but never reaches READY/playing.
        }
    }

    // ---------------------------------------------------------------- network failover

    private fun onStallDetected(p: ExoPlayer) {
        // First, a cheap local fix: force a re-prepare (reconnects the loaders).
        if (recordStallEvent()) return // failover triggered
        p.prepare()
    }

    /** @return true if this event triggered a network failover. */
    private fun recordStallEvent(): Boolean {
        val now = SystemClock.elapsedRealtime()
        stallEvents.addLast(now)
        while (stallEvents.isNotEmpty() && now - stallEvents.first() > STALL_WINDOW_MS) {
            stallEvents.removeFirst()
        }
        if (stallEvents.size >= STALLS_TO_FAILOVER && netMode == NetMode.DEFAULT) {
            stallEvents.clear()
            if (attemptCellularFailover(now)) return true
        }
        return false
    }

    private fun attemptCellularFailover(now: Long): Boolean {
        if (!prefs.getBoolean(PREF_AUTO_FAILOVER, true)) return false
        if (now - lastFailoverAt < FAILOVER_COOLDOWN_MS) return false
        failoverEvents.addLast(now)
        while (failoverEvents.isNotEmpty() && now - failoverEvents.first() > FAILOVER_WINDOW_MS) {
            failoverEvents.removeFirst()
        }
        if (failoverEvents.size > FAILOVER_MAX) {
            // Repeated failovers did not help: likely a stream problem, not a
            // network problem. Let the URL rotation / directory healing handle it.
            return false
        }
        val cm = connectivityManager ?: return false
        lastFailoverAt = now

        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return false // already running on cellular; switching networks is pointless
        }
        val hasCellular = cm.allNetworks.orEmpty().any { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        if (!hasCellular) return false

        requestCellularPin(cm)
        return true
    }

    private fun requestCellularPin(cm: ConnectivityManager) {
        if (cellularRequest != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { bindToCellular(cm, network) }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (cellularNetwork == network) {
                        // Cellular went away: unbind immediately so the default
                        // path (Wi-Fi) is used again; retries/heal continue.
                        switchToNetwork(NetMode.DEFAULT, reconnect = true)
                    }
                }
            }

            override fun onUnavailable() {
                handler.post { releaseCellular(false) }
            }
        }
        cellularRequest = req
        cellularCallback = cb
        try {
            cm.requestNetwork(req, cb, PIN_TIMEOUT_MS)
        } catch (e: Exception) {
            cellularRequest = null
            cellularCallback = null
        }
    }

    private fun bindToCellular(cm: ConnectivityManager, network: Network) {
        if (cellularNetwork == network && netMode == NetMode.CELLULAR) return
        cellularNetwork = network
        try {
            cm.bindProcessToNetwork(network)
        } catch (e: Exception) {
            // ignore: fall back to default routing
        }
        netMode = NetMode.CELLULAR
        stallEvents.clear()
        lastProgressAt = SystemClock.elapsedRealtime()
        forceReconnect()
    }

    /** mode 0 = default (Wi-Fi preferred), 1 = pin to cellular now. */
    fun setNetworkModeRequested(mode: Int) {
        val cm = connectivityManager
        if (mode == 1) {
            if (netMode != NetMode.CELLULAR && cm != null) {
                lastFailoverAt = SystemClock.elapsedRealtime()
                requestCellularPin(cm)
            }
        } else {
            lastFailoverAt = SystemClock.elapsedRealtime()
            switchToNetwork(NetMode.DEFAULT, reconnect = true)
        }
    }

    private fun switchToNetwork(mode: NetMode, reconnect: Boolean) {
        if (mode == NetMode.DEFAULT) releaseCellular(reconnect) else return
    }

    private fun releaseCellular(reconnect: Boolean) {
        val cm = connectivityManager
        try {
            cm?.bindProcessToNetwork(null)
        } catch (e: Exception) {
            // ignore
        }
        cellularNetwork = null
        netMode = NetMode.DEFAULT
        stallEvents.clear()
        lastProgressAt = SystemClock.elapsedRealtime()
        val cb = cellularCallback
        if (cb != null && cm != null) {
            try {
                cm.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // ignore
            }
        }
        cellularCallback = null
        cellularRequest = null
        if (reconnect) forceReconnect()
    }

    /** Rebuild the data sources so new sockets use the (possibly changed) route. */
    private fun forceReconnect() {
        val p = player ?: return
        val item = p.currentMediaItem ?: return
        consecutiveFailures = 0
        backoffMs = BACKOFF_MIN_MS
        handler.removeCallbacks(retryRunnable)
        p.setMediaItem(item)
        p.prepare()
    }

    /** 1 = traffic effectively goes over cellular (pinned, or cellular is the default route). */
    private fun effectiveNetworkMode(): Int {
        if (netMode == NetMode.CELLULAR) return 1
        val cm = connectivityManager ?: return 0
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return 0
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) 1 else 0
    }

    // ---------------------------------------------------------------- session commands

    private val sessionCallback = object : MediaSession.Callback {
        /** Advertise the custom commands so the controller may send them. */
        override fun onConnect(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(CMD_PLAY)
                    .add(CMD_SET_TIMER)
                    .add(CMD_GET_TIMER)
                    .add(CMD_SET_NETWORK)
                    .build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )

        override fun onCustomCommand(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val future = ResolvableFuture.create<SessionResult>()
            when (customCommand.customAction) {
                ACTION_PLAY_STATION -> {
                    val station = stationById(args.getString(EXTRA_STATION_ID))
                    if (station != null) switchTo(station, play = true)
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_SET_SLEEP_TIMER -> {
                    val minutes = args.getInt(EXTRA_MINUTES, -1)
                    setSleepTimer(if (minutes < 0) null else minutes)
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_GET_SLEEP_TIMER -> future.set(
                    SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(EXTRA_END_ELAPSED, timerEndElapsed)
                            putInt(EXTRA_NETWORK_MODE, effectiveNetworkMode())
                        },
                    )
                )
                ACTION_SET_NETWORK -> {
                    setNetworkModeRequested(args.getInt(EXTRA_MODE, 0))
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> future.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return future
        }
    }
}
