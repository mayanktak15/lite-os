package com.litetv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.litetv.app.player.PlaybackErrorInfo
import com.litetv.app.player.PlaybackState
import com.litetv.app.player.PlayerManager

/**
 * Full-screen IPTV video playback activity for Android TV.
 *
 * Designed for reliable playback on TV hardware:
 * - Direct Media3 ExoPlayer integration with memory constraints
 * - Explicit visual states (Connecting, Buffering, Reconnecting, Error)
 * - Watchdog-driven recovery preventing indefinite "Buffering..." freezes
 * - Bounded automatic failover to next channel upon permanent failure
 * - Intuitive TV D-pad remote controls (OK, Left, Right, Up, Down, Back)
 */
class PlayerActivity : AppCompatActivity(), PlayerManager.PlayerListener {

    companion object {
        private const val TAG = "PlayerActivity"
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_GROUP = "channel_group"
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        const val EXTRA_CHANNEL_URLS = "channel_urls"
        const val EXTRA_CHANNEL_NAMES = "channel_names"

        private const val OVERLAY_HIDE_DELAY_MS = 5000L
        private const val FAILOVER_COUNTDOWN_SECONDS = 3
        private const val MAX_CONSECUTIVE_FAILOVERS = 3
    }

    private lateinit var playerManager: PlayerManager
    private lateinit var playerView: PlayerView

    // Buffering / status views
    private lateinit var bufferingOverlay: View
    private lateinit var bufferingStatusText: TextView
    private lateinit var bufferingDetailText: TextView

    // Channel metadata banner
    private lateinit var channelInfoOverlay: LinearLayout
    private lateinit var channelNameText: TextView
    private lateinit var channelGroupText: TextView

    // Error overlay & actions
    private lateinit var errorOverlay: View
    private lateinit var errorTitle: TextView
    private lateinit var errorText: TextView
    private lateinit var errorDiagnostics: TextView
    private lateinit var failoverCountdownText: TextView
    private lateinit var btnRetry: Button
    private lateinit var btnNextChannel: Button
    private lateinit var btnBack: Button

    private var currentIndex = 0
    private var channelUrls: List<String> = emptyList()
    private var channelNames: List<String> = emptyList()

    // Bounded failover state tracking
    private val failedChannelIndices = mutableSetOf<Int>()
    private var consecutiveFailures = 0
    private var isAutoFailoverEnabled = true
    private var failoverSecondsRemaining = 0
    private var pendingFailoverTargetIndex = -1

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideChannelInfo() }

    private val failoverTickRunnable = object : Runnable {
        override fun run() {
            if (failoverSecondsRemaining > 0) {
                val targetName = channelNames.getOrElse(pendingFailoverTargetIndex) {
                    "Channel ${pendingFailoverTargetIndex + 1}"
                }
                failoverCountdownText.visibility = View.VISIBLE
                failoverCountdownText.text = "Switching to '$targetName' in ${failoverSecondsRemaining}s… (Press OK to cancel)"
                failoverSecondsRemaining--
                handler.postDelayed(this, 1000L)
            } else {
                executeFailover()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        bindViews()
        setupActionButtons()

        // Extract channel data from intent
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            Log.e(TAG, "No stream URL provided in intent")
            finish()
            return
        }
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Unknown"
        val channelGroup = intent.getStringExtra(EXTRA_CHANNEL_GROUP) ?: ""
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)
        channelUrls = intent.getStringArrayListExtra(EXTRA_CHANNEL_URLS) ?: listOf(streamUrl)
        channelNames = intent.getStringArrayListExtra(EXTRA_CHANNEL_NAMES) ?: listOf(channelName)

        // Initialize PlayerManager
        playerManager = PlayerManager(this)
        val player = playerManager.initialize()
        playerManager.listener = this
        playerView.player = player

        // Start initial playback
        updateChannelInfo(channelName, channelGroup)
        playerManager.play(streamUrl, channelName)

        showChannelInfo()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)

        bufferingOverlay = findViewById(R.id.bufferingOverlay)
        bufferingStatusText = findViewById(R.id.bufferingStatusText)
        bufferingDetailText = findViewById(R.id.bufferingDetailText)

        channelInfoOverlay = findViewById(R.id.channelInfoOverlay)
        channelNameText = findViewById(R.id.playerChannelName)
        channelGroupText = findViewById(R.id.playerChannelGroup)

        errorOverlay = findViewById(R.id.playerErrorOverlay)
        errorTitle = findViewById(R.id.playerErrorTitle)
        errorText = findViewById(R.id.playerErrorText)
        errorDiagnostics = findViewById(R.id.playerErrorDiagnostics)
        failoverCountdownText = findViewById(R.id.playerFailoverCountdownText)

        btnRetry = findViewById(R.id.btnRetry)
        btnNextChannel = findViewById(R.id.btnNextChannel)
        btnBack = findViewById(R.id.btnBack)
    }

    private fun setupActionButtons() {
        btnRetry.setOnClickListener {
            cancelFailoverCountdown()
            errorOverlay.visibility = View.GONE
            failedChannelIndices.remove(currentIndex)
            consecutiveFailures = 0
            playerManager.retry()
        }

        btnNextChannel.setOnClickListener {
            cancelFailoverCountdown()
            switchToNextChannel()
        }

        btnBack.setOnClickListener {
            cancelFailoverCountdown()
            finish()
        }
    }

    // --- TV D-pad Controls ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If error overlay is active and user interacts, cancel automatic failover countdown
        if (errorOverlay.visibility == View.VISIBLE) {
            cancelFailoverCountdown()
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    switchToPreviousChannel()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    switchToNextChannel()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                playerManager.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                switchToPreviousChannel()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                switchToNextChannel()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                showChannelInfo()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                hideChannelInfo()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun switchToNextChannel() {
        if (channelUrls.isEmpty()) return
        cancelFailoverCountdown()
        currentIndex = (currentIndex + 1) % channelUrls.size
        playCurrentChannel()
    }

    private fun switchToPreviousChannel() {
        if (channelUrls.isEmpty()) return
        cancelFailoverCountdown()
        currentIndex = if (currentIndex > 0) currentIndex - 1 else channelUrls.size - 1
        playCurrentChannel()
    }

    private fun playCurrentChannel() {
        val url = channelUrls.getOrNull(currentIndex) ?: return
        val name = channelNames.getOrElse(currentIndex) { "Channel ${currentIndex + 1}" }

        cancelFailoverCountdown()
        errorOverlay.visibility = View.GONE
        bufferingOverlay.visibility = View.GONE

        updateChannelInfo(name, "")
        showChannelInfo()

        Log.i(TAG, "User switched to channel index $currentIndex: '$name'")
        playerManager.switchChannel(url, name)
    }

    private fun updateChannelInfo(name: String, group: String) {
        channelNameText.text = name
        channelGroupText.text = group
        channelGroupText.visibility = if (group.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showChannelInfo() {
        channelInfoOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun hideChannelInfo() {
        channelInfoOverlay.visibility = View.GONE
        handler.removeCallbacks(hideOverlayRunnable)
    }

    // --- Automatic Failover ---

    private fun scheduleAutoFailoverIfEligible() {
        if (!isAutoFailoverEnabled || channelUrls.size <= 1) return

        failedChannelIndices.add(currentIndex)
        consecutiveFailures++

        if (consecutiveFailures > MAX_CONSECUTIVE_FAILOVERS) {
            Log.w(TAG, "Max consecutive failovers reached ($consecutiveFailures). Halting failover loop.")
            failoverCountdownText.visibility = View.GONE
            errorDiagnostics.append("\n(Stopped automatic failover after $MAX_CONSECUTIVE_FAILOVERS attempts)")
            return
        }

        if (failedChannelIndices.size >= channelUrls.size) {
            Log.w(TAG, "All ${channelUrls.size} channels in playlist have failed.")
            failoverCountdownText.visibility = View.GONE
            errorDiagnostics.append("\n(All channels in catalogue are currently unavailable)")
            return
        }

        // Find next eligible channel not yet failed
        val nextEligible = findNextEligibleChannelIndex()
        if (nextEligible != -1) {
            pendingFailoverTargetIndex = nextEligible
            failoverSecondsRemaining = FAILOVER_COUNTDOWN_SECONDS
            handler.removeCallbacks(failoverTickRunnable)
            handler.post(failoverTickRunnable)
        }
    }

    private fun findNextEligibleChannelIndex(): Int {
        for (i in 1 until channelUrls.size) {
            val candidate = (currentIndex + i) % channelUrls.size
            if (!failedChannelIndices.contains(candidate)) {
                return candidate
            }
        }
        return -1
    }

    private fun executeFailover() {
        if (pendingFailoverTargetIndex in channelUrls.indices) {
            Log.i(TAG, "Executing automatic failover to channel index $pendingFailoverTargetIndex")
            currentIndex = pendingFailoverTargetIndex
            pendingFailoverTargetIndex = -1
            playCurrentChannel()
        }
    }

    private fun cancelFailoverCountdown() {
        handler.removeCallbacks(failoverTickRunnable)
        failoverCountdownText.visibility = View.GONE
        pendingFailoverTargetIndex = -1
    }

    // --- PlayerManager.PlayerListener Callbacks ---

    override fun onStateChanged(state: PlaybackState, message: String) {
        when (state) {
            PlaybackState.CONNECTING -> {
                cancelFailoverCountdown()
                errorOverlay.visibility = View.GONE
                bufferingOverlay.visibility = View.VISIBLE
                bufferingStatusText.text = "Connecting to channel…"
                bufferingDetailText.text = message
            }
            PlaybackState.BUFFERING -> {
                cancelFailoverCountdown()
                errorOverlay.visibility = View.GONE
                bufferingOverlay.visibility = View.VISIBLE
                bufferingStatusText.text = getString(R.string.buffering)
                bufferingDetailText.text = message
            }
            PlaybackState.RECONNECTING -> {
                cancelFailoverCountdown()
                errorOverlay.visibility = View.GONE
                bufferingOverlay.visibility = View.VISIBLE
                bufferingStatusText.text = "Reconnecting…"
                bufferingDetailText.text = message
            }
            PlaybackState.PLAYING -> {
                cancelFailoverCountdown()
                bufferingOverlay.visibility = View.GONE
                errorOverlay.visibility = View.GONE
                consecutiveFailures = 0
                failedChannelIndices.remove(currentIndex)
            }
            PlaybackState.ERROR -> {
                bufferingOverlay.visibility = View.GONE
                errorOverlay.visibility = View.VISIBLE
            }
            PlaybackState.IDLE, PlaybackState.ENDED -> {
                bufferingOverlay.visibility = View.GONE
            }
        }
    }

    override fun onBuffering(isBuffering: Boolean) {
        bufferingOverlay.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    override fun onPlaying() {
        cancelFailoverCountdown()
        bufferingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.GONE
        consecutiveFailures = 0
        failedChannelIndices.remove(currentIndex)
    }

    override fun onError(errorInfo: PlaybackErrorInfo) {
        cancelFailoverCountdown()
        bufferingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE

        errorTitle.text = when (errorInfo.category) {
            PlaybackErrorInfo.Category.NETWORK -> "Network Error"
            PlaybackErrorInfo.Category.TIMEOUT -> "Connection Timed Out"
            PlaybackErrorInfo.Category.UNSUPPORTED -> "Unsupported Format"
            PlaybackErrorInfo.Category.MALFORMED -> "Invalid Stream Data"
            PlaybackErrorInfo.Category.UNKNOWN -> "Channel Unavailable"
        }

        errorText.text = errorInfo.userFriendlyMessage

        val diag = buildString {
            append("Host: ${errorInfo.sanitizedHost}")
            if (errorInfo.httpStatusCode != null) {
                append(" | HTTP ${errorInfo.httpStatusCode}")
            }
            append(" | Code: ${errorInfo.errorCode}")
            val seconds = errorInfo.bufferingDurationMs / 1000
            if (seconds > 0) {
                append(" | Buffering: ${seconds}s")
            }
            if (errorInfo.retryAttempts > 0) {
                append(" | Retries: ${errorInfo.retryAttempts}")
            }
        }
        errorDiagnostics.text = diag

        // Focus retry button for TV remote
        btnRetry.post { btnRetry.requestFocus() }

        // Start bounded auto failover countdown
        scheduleAutoFailoverIfEligible()
    }

    override fun onError(message: String) {
        bufferingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
        errorText.text = message
        btnRetry.post { btnRetry.requestFocus() }
    }

    override fun onEnded() {
        Log.i(TAG, "Stream ended. Automatically switching to next channel.")
        switchToNextChannel()
    }

    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        com.litetv.app.update.AppUpdateManager.isPlaybackActive = true
        playerManager.resume()
    }

    override fun onStop() {
        super.onStop()
        com.litetv.app.update.AppUpdateManager.isPlaybackActive = false
        cancelFailoverCountdown()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.litetv.app.update.AppUpdateManager.isPlaybackActive = false
        cancelFailoverCountdown()
        handler.removeCallbacksAndMessages(null)
        playerManager.release()
    }
}
