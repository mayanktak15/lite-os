package com.litetv.app.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Manages a single ExoPlayer instance with memory-conscious configuration.
 *
 * Key design decisions for 1 GB RAM:
 * - Single player instance (never multiple)
 * - Conservative buffer sizes (5s min, 15s max)
 * - Explicit lifecycle management
 * - Bounded retry logic (max 3 attempts)
 * - Channel switching reuses the same player instance
 */
class PlayerManager(private val context: Context) {

    companion object {
        private const val TAG = "PlayerManager"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L

        // Conservative buffer sizes for 1 GB RAM TV
        private const val MIN_BUFFER_MS = 5_000
        private const val MAX_BUFFER_MS = 15_000
        private const val BUFFER_FOR_PLAYBACK_MS = 2_000
        private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
    }

    private var player: ExoPlayer? = null
    private var retryCount = 0
    private var currentUrl: String = ""

    /** Listener for playback state changes */
    var listener: PlayerListener? = null

    interface PlayerListener {
        fun onBuffering(isBuffering: Boolean)
        fun onPlaying()
        fun onError(message: String)
        fun onEnded()
    }

    /**
     * Initialize the player. Call in Activity.onCreate().
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun initialize(): ExoPlayer {
        release() // Ensure no leaked player

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()

        val exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }

        player = exoPlayer
        Log.d(TAG, "Player initialized")
        return exoPlayer
    }

    /**
     * Play a stream URL. Reuses the existing player instance.
     */
    fun play(url: String) {
        val p = player ?: return
        currentUrl = url
        retryCount = 0

        Log.d(TAG, "Playing: ${url.take(80)}")
        val mediaItem = MediaItem.fromUri(url)
        p.setMediaItem(mediaItem)
        p.prepare()
    }

    /**
     * Switch to a different channel (reuses player).
     */
    fun switchChannel(url: String) {
        play(url)
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
     * Retry playback of the current URL.
     */
    fun retry() {
        if (currentUrl.isNotEmpty() && retryCount < MAX_RETRIES) {
            retryCount++
            Log.d(TAG, "Retry attempt $retryCount/$MAX_RETRIES")
            play(currentUrl)
        } else {
            listener?.onError("Playback failed after $MAX_RETRIES attempts")
        }
    }

    /**
     * Pause playback (e.g., when Activity goes to background).
     */
    fun pause() {
        player?.pause()
    }

    /**
     * Resume playback (e.g., when Activity returns to foreground).
     */
    fun resume() {
        player?.play()
    }

    /**
     * Release the player. Call in Activity.onDestroy().
     */
    fun release() {
        player?.let { p ->
            p.removeListener(playerListener)
            p.stop()
            p.release()
            Log.d(TAG, "Player released")
        }
        player = null
    }

    /**
     * Get the underlying ExoPlayer instance.
     */
    fun getPlayer(): ExoPlayer? = player

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    listener?.onBuffering(true)
                }
                Player.STATE_READY -> {
                    listener?.onBuffering(false)
                    listener?.onPlaying()
                    retryCount = 0  // Reset on successful playback
                }
                Player.STATE_ENDED -> {
                    listener?.onEnded()
                }
                Player.STATE_IDLE -> {
                    // No-op
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val errorMsg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                    "Network connection failed"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Connection timed out"
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "Invalid stream format"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                    "Codec not supported"
                else ->
                    "Playback error: ${error.message}"
            }
            Log.e(TAG, "Player error: $errorMsg (code: ${error.errorCode})", error)

            if (retryCount < MAX_RETRIES) {
                // Auto-retry with delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    retry()
                }, RETRY_DELAY_MS)
            } else {
                listener?.onError(errorMsg)
            }
        }
    }
}
