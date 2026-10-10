package com.litetv.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.net.URI
import kotlin.random.Random

/**
 * Robust playback manager for Android TV IPTV streams.
 *
 * Engineered for low-resource hardware (1 GB RAM TV) and unreliable network streams:
 * - Single ExoPlayer instance with strict lifecycle management
 * - Configurable startup and rebuffering watchdog timeouts to prevent infinite "Buffering..." hangs
 * - Cross-protocol redirect handling (HTTP <-> HTTPS) and custom User-Agent
 * - Explicit stream format / MIME-type detection (HLS, MPEG-TS, DASH)
 * - Bounded exponential backoff retry with jitter
 * - Fast non-recoverable error detection (HTTP 401/403/404, unsupported codecs)
 * - Stale callback and retry cancellation across channel changes (generation token guarded)
 * - Structured diagnostic logging with sensitive URL sanitization
 */
@OptIn(UnstableApi::class)
class PlayerManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "PlayerManager"

        /** Maximum retry attempts before declaring permanent failure */
        const val MAX_RETRIES = 3

        /** Initial delay for exponential backoff retry */
        const val INITIAL_RETRY_DELAY_MS = 1500L

        /** Maximum delay cap for retry */
        const val MAX_RETRY_DELAY_MS = 5000L

        /** Watchdog timeout for initial manifest/segment connection (prevents indefinite startup buffering) */
        const val STARTUP_TIMEOUT_MS = 15_000L

        /** Watchdog timeout for mid-playback rebuffering stall */
        const val RECOVERY_TIMEOUT_MS = 20_000L

        // Conservative buffer sizes tuned for 1 GB RAM Smart TV
        private const val MIN_BUFFER_MS = 5_000
        private const val MAX_BUFFER_MS = 15_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000

        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 20_000
        private const val DEFAULT_USER_AGENT = "LiteTV/1.0 (Android TV; ExoPlayer)"

        /**
         * Sanitize stream URL for logs and diagnostics (strips tokens, query params, auth credentials).
         */
        fun sanitizeStreamUrl(url: String): String {
            if (url.isEmpty()) return ""
            return try {
                val uri = URI(url)
                val host = uri.host ?: "unknown-host"
                val path = uri.path ?: ""
                val lastSegment = path.substringAfterLast('/', "")
                if (lastSegment.isNotEmpty()) "$host/.../$lastSegment" else host
            } catch (_: Exception) {
                url.take(30) + "..."
            }
        }

        fun getHostFromUrl(url: String): String {
            if (url.isEmpty()) return "unknown"
            return try {
                URI(url).host ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }
        }

        fun detectMimeType(url: String): String? {
            val lower = url.lowercase()
            return when {
                lower.contains(".m3u8") || lower.contains("/hls") || lower.contains("m3u8") ->
                    MimeTypes.APPLICATION_M3U8
                lower.contains(".ts") || lower.contains("mpegts") ->
                    MimeTypes.VIDEO_MP2T
                lower.contains(".mpd") ->
                    MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }

    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var currentState: PlaybackState = PlaybackState.IDLE
        private set

    @Volatile
    var retryCount: Int = 0
        private set

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var currentChannelName: String = ""
        private set

    /** Generation token incremented on every channel switch to invalidate stale callbacks/retries */
    private var playbackGeneration: Long = 0L

    /** Timestamp when buffering started (for diagnostics) */
    private var bufferingStartTime: Long = 0L

    /** Watchdog runnable checking for stuck buffering */
    private val bufferingWatchdogRunnable = Runnable {
        handleBufferingTimeout()
    }

    /** Delayed retry runnable */
    private var pendingRetryRunnable: Runnable? = null

    /** Listener for playback state changes */
    var listener: PlayerListener? = null

    interface PlayerListener {
        fun onStateChanged(state: PlaybackState, message: String) {}
        fun onBuffering(isBuffering: Boolean) {}
        fun onPlaying() {}
        fun onError(errorInfo: PlaybackErrorInfo) {}
        fun onError(message: String) {}
        fun onEnded() {}
    }

    /**
     * Initialize the player with memory-tuned LoadControl and robust DataSource factories.
     */
    fun initialize(): ExoPlayer {
        release() // Guarantee clean state

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Configure resilient HTTP DataSource with cross-protocol redirect support
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT)
            .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val ctx = context ?: throw IllegalStateException("Context required to initialize ExoPlayer")
        val dataSourceFactory = DefaultDataSource.Factory(ctx, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(ctx)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }

        player = exoPlayer
        Log.i(TAG, "ExoPlayer initialized with resilient MediaSourceFactory and LoadControl")
        return exoPlayer
    }

    /**
     * Play a stream URL with channel name. Reuses the active player instance.
     */
    fun play(url: String, channelName: String = "") {
        val p = player ?: initialize()

        // Invalidate previous timeouts and retries
        cancelPendingOperations()
        playbackGeneration++
        val generation = playbackGeneration

        currentUrl = url
        currentChannelName = channelName.ifEmpty { "Channel" }
        retryCount = 0
        bufferingStartTime = System.currentTimeMillis()

        val sanitized = sanitizeStreamUrl(url)
        Log.i(TAG, "Starting playback [Gen #$generation]: '$currentChannelName' at $sanitized")

        updateState(PlaybackState.CONNECTING, "Connecting to $currentChannelName…")

        // Start initial startup watchdog timer
        scheduleBufferingWatchdog(STARTUP_TIMEOUT_MS)

        val mediaItem = buildMediaItem(url)
        p.stop()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()
    }

    /**
     * Switch to a different channel (convenience alias).
     */
    fun switchChannel(url: String, channelName: String = "") {
        play(url, channelName)
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
    }

    /**
     * Trigger manual or automated retry of the current channel.
     */
    fun retry() {
        if (currentUrl.isEmpty()) return
        Log.i(TAG, "Manual retry requested for $currentChannelName")
        cancelPendingOperations()
        executeRetry(currentUrl, currentChannelName, isManual = true)
    }

    /**
     * Pause playback (e.g., when Activity enters background).
     */
    fun pause() {
        cancelPendingOperations()
        player?.pause()
    }

    /**
     * Resume playback (e.g., when Activity returns to foreground).
     */
    fun resume() {
        val p = player ?: return
        if (currentUrl.isNotEmpty() && !p.isPlaying && currentState != PlaybackState.ERROR) {
            p.play()
        }
    }

    /**
     * Release player resources and cancel all pending timers and handlers.
     */
    fun release() {
        cancelPendingOperations()
        playbackGeneration++
        player?.let { p ->
            p.removeListener(playerListener)
            p.stop()
            p.release()
            Log.d(TAG, "Player released and listeners cleared")
        }
        player = null
        updateState(PlaybackState.IDLE, "Player released")
    }

    /**
     * Get the underlying ExoPlayer instance.
     */
    fun getPlayer(): ExoPlayer? = player

    // --- Private Recovery & State Methods ---

    /**
     * Build MediaItem with smart MIME type detection for IPTV stream formats.
     */
    internal fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        detectMimeType(url)?.let { builder.setMimeType(it) }
        return builder.build()
    }

    private fun scheduleBufferingWatchdog(timeoutMs: Long) {
        mainHandler.removeCallbacks(bufferingWatchdogRunnable)
        mainHandler.postDelayed(bufferingWatchdogRunnable, timeoutMs)
    }

    private fun cancelPendingOperations() {
        mainHandler.removeCallbacks(bufferingWatchdogRunnable)
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    /**
     * Triggered when playback remains in CONNECTING or BUFFERING state longer than allowed timeout.
     */
    private fun handleBufferingTimeout() {
        val p = player ?: return
        val currentGen = playbackGeneration
        val elapsed = System.currentTimeMillis() - bufferingStartTime

        Log.w(
            TAG,
            "Buffering watchdog timeout reached (${elapsed}ms) for '$currentChannelName' [Gen #$currentGen]. " +
                    "Player state: ${p.playbackState}, State: $currentState, Retries: $retryCount/$MAX_RETRIES"
        )

        if (retryCount < MAX_RETRIES) {
            retryCount++
            scheduleAutomatedRetry(
                reason = "Buffering timed out after ${elapsed / 1000}s"
            )
        } else {
            // Retries exhausted
            val errorInfo = PlaybackErrorInfo(
                title = "Channel Unavailable",
                message = "Stream connection timed out after $MAX_RETRIES attempts.",
                category = PlaybackErrorInfo.Category.TIMEOUT,
                media3ErrorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                isRecoverable = false,
                sanitizedHost = getHostFromUrl(currentUrl),
                sanitizedUrl = sanitizeStreamUrl(currentUrl),
                bufferingDurationMs = elapsed,
                retryAttempts = retryCount,
                diagnostics = "Buffering stall: ${elapsed}ms elapsed in $currentState state"
            )
            reportTerminalError(errorInfo)
        }
    }

    /**
     * Schedule an automated retry with exponential backoff and jitter.
     */
    private fun scheduleAutomatedRetry(reason: String) {
        val currentGen = playbackGeneration
        val backoffBase = (INITIAL_RETRY_DELAY_MS * (1 shl (retryCount - 1))).coerceAtMost(MAX_RETRY_DELAY_MS)
        val jitter = Random.nextLong(0, 300)
        val delayMs = backoffBase + jitter

        Log.i(TAG, "Scheduling retry #$retryCount in ${delayMs}ms ($reason)")
        updateState(PlaybackState.RECONNECTING, "Reconnecting (attempt $retryCount of $MAX_RETRIES)…")

        val runnable = Runnable {
            if (playbackGeneration == currentGen) {
                executeRetry(currentUrl, currentChannelName, isManual = false)
            } else {
                Log.d(TAG, "Ignoring stale retry runnable from previous generation")
            }
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun executeRetry(url: String, channelName: String, isManual: Boolean) {
        val p = player ?: return
        if (isManual) {
            retryCount = 0
        }
        bufferingStartTime = System.currentTimeMillis()
        val generation = playbackGeneration

        Log.i(TAG, "Executing retry attempt #$retryCount [Gen #$generation] for '$channelName'")
        updateState(PlaybackState.CONNECTING, "Reconnecting to $channelName…")

        scheduleBufferingWatchdog(STARTUP_TIMEOUT_MS)

        val mediaItem = buildMediaItem(url)
        p.stop()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()
    }

    private fun reportTerminalError(errorInfo: PlaybackErrorInfo) {
        cancelPendingOperations()
        player?.stop()
        updateState(PlaybackState.ERROR, errorInfo.message)

        Log.e(
            TAG,
            "Terminal playback failure: ${errorInfo.title} - ${errorInfo.message} " +
                    "(HTTP: ${errorInfo.httpStatusCode}, Media3: ${errorInfo.media3ErrorCode}, Host: ${errorInfo.sanitizedUrl})"
        )

        listener?.onError(errorInfo)
        listener?.onError(errorInfo.message)
    }

    private fun updateState(newState: PlaybackState, message: String) {
        currentState = newState
        val isBuffering = (newState == PlaybackState.CONNECTING || newState == PlaybackState.BUFFERING || newState == PlaybackState.RECONNECTING)
        listener?.onBuffering(isBuffering)
        listener?.onStateChanged(newState, message)
    }

    // --- ExoPlayer Event Listener ---

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    if (currentState == PlaybackState.PLAYING) {
                        // Mid-playback rebuffering
                        bufferingStartTime = System.currentTimeMillis()
                        updateState(PlaybackState.BUFFERING, "Buffering stream…")
                        scheduleBufferingWatchdog(RECOVERY_TIMEOUT_MS)
                    }
                }
                Player.STATE_READY -> {
                    cancelPendingOperations()
                    val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
                    retryCount = 0

                    Log.i(TAG, "Playback ready in ${bufferingDuration}ms for '$currentChannelName'")
                    updateState(PlaybackState.PLAYING, "Playing")
                    listener?.onPlaying()
                }
                Player.STATE_ENDED -> {
                    cancelPendingOperations()
                    Log.i(TAG, "Playback ended for '$currentChannelName'")
                    updateState(PlaybackState.ENDED, "Stream ended")
                    listener?.onEnded()
                }
                Player.STATE_IDLE -> {
                    // Handled internally
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            cancelPendingOperations()
            val sanitized = sanitizeStreamUrl(currentUrl)

            // Inspect underlying HTTP response code when available
            val httpStatusCode: Int? = findHttpErrorCode(error)
            val isRecoverable = isErrorRecoverable(error, httpStatusCode)

            val (errorTitle, errorMessage) = categorizeError(error, httpStatusCode)

            Log.e(
                TAG,
                "PlaybackException [Gen #$playbackGeneration]: $errorTitle - $errorMessage | " +
                        "ErrorCode: ${error.errorCode}, HTTP: $httpStatusCode, Host: $sanitized, Recoverable: $isRecoverable",
                error
            )

            if (isRecoverable && retryCount < MAX_RETRIES) {
                retryCount++
                scheduleAutomatedRetry(reason = errorMessage)
            } else {
                val category = determineCategory(error, httpStatusCode)
                val errorInfo = PlaybackErrorInfo(
                    title = errorTitle,
                    message = errorMessage,
                    category = category,
                    httpStatusCode = httpStatusCode,
                    media3ErrorCode = error.errorCode,
                    isRecoverable = isRecoverable,
                    sanitizedHost = getHostFromUrl(currentUrl),
                    sanitizedUrl = sanitized,
                    bufferingDurationMs = System.currentTimeMillis() - bufferingStartTime,
                    retryAttempts = retryCount,
                    diagnostics = "Error code ${error.errorCode}${if (httpStatusCode != null) ", HTTP $httpStatusCode" else ""}"
                )
                reportTerminalError(errorInfo)
            }
        }
    }

    internal fun findHttpErrorCode(error: PlaybackException): Int? {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.responseCode
            }
            cause = cause.cause
        }
        return null
    }

    internal fun isErrorRecoverable(error: PlaybackException, httpStatusCode: Int?): Boolean {
        // Permanent HTTP status codes — fail fast, do not retry
        if (httpStatusCode != null) {
            if (httpStatusCode in listOf(400, 401, 403, 404, 410, 451)) {
                return false
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> false
            else -> true
        }
    }

    internal fun determineCategory(error: PlaybackException, httpStatusCode: Int?): PlaybackErrorInfo.Category {
        if (httpStatusCode != null) {
            return when (httpStatusCode) {
                401, 403 -> PlaybackErrorInfo.Category.NETWORK
                404, 410 -> PlaybackErrorInfo.Category.NETWORK
                500, 502, 503, 504 -> PlaybackErrorInfo.Category.NETWORK
                else -> PlaybackErrorInfo.Category.NETWORK
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                PlaybackErrorInfo.Category.TIMEOUT
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                PlaybackErrorInfo.Category.NETWORK
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                PlaybackErrorInfo.Category.MALFORMED
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
                PlaybackErrorInfo.Category.UNSUPPORTED
            else ->
                PlaybackErrorInfo.Category.UNKNOWN
        }
    }

    internal fun categorizeError(error: PlaybackException, httpStatusCode: Int?): Pair<String, String> {
        if (httpStatusCode != null) {
            return when (httpStatusCode) {
                401, 403 -> Pair("Stream Unauthorized", "This channel is restricted or requires authentication (HTTP $httpStatusCode).")
                404, 410 -> Pair("Stream Not Found", "The broadcast feed is offline or the stream URL has expired (HTTP $httpStatusCode).")
                500, 502, 503, 504 -> Pair("Server Error", "Broadcaster server is temporarily unavailable (HTTP $httpStatusCode).")
                else -> Pair("Network Error", "HTTP response status $httpStatusCode received.")
            }
        }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                Pair("Connection Timed Out", "Connection to the broadcast server timed out.")
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                Pair("Network Error", "Could not reach the channel server. Please check internet connectivity.")
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                Pair("Unsupported Stream Format", "The stream playlist or video segments are malformed.")
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                Pair("Codec Unsupported", "Your television hardware does not support this stream's video/audio format.")
            else ->
                Pair("Playback Error", error.message?.take(80) ?: "An unexpected playback error occurred.")
        }
    }
}
