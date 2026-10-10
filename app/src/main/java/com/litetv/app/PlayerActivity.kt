package com.litetv.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.litetv.app.player.PlayerManager

/**
 * Full-screen video playback activity.
 *
 * D-pad controls:
 * - OK → toggle play/pause
 * - Back → return to channel list
 * - Left → previous channel
 * - Right → next channel
 * - Up → show channel info overlay
 * - Down → hide channel info overlay
 *
 * Player lifecycle:
 * - Created on channel selection
 * - Paused in onStop, resumed in onStart
 * - Released in onDestroy
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
        private const val OVERLAY_HIDE_DELAY = 5000L
    }

    private lateinit var playerManager: PlayerManager
    private lateinit var playerView: PlayerView
    private lateinit var bufferingOverlay: View
    private lateinit var channelInfoOverlay: LinearLayout
    private lateinit var channelNameText: TextView
    private lateinit var channelGroupText: TextView
    private lateinit var errorOverlay: View
    private lateinit var errorText: TextView

    private var currentIndex = 0
    private var channelUrls: List<String> = emptyList()
    private var channelNames: List<String> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideChannelInfo() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        // Initialize views
        playerView = findViewById(R.id.playerView)
        bufferingOverlay = findViewById(R.id.bufferingOverlay)
        channelInfoOverlay = findViewById(R.id.channelInfoOverlay)
        channelNameText = findViewById(R.id.playerChannelName)
        channelGroupText = findViewById(R.id.playerChannelGroup)
        errorOverlay = findViewById(R.id.playerErrorOverlay)
        errorText = findViewById(R.id.playerErrorText)

        // Get channel data from intent
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            Log.e(TAG, "No stream URL provided")
            finish()
            return
        }
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Unknown"
        val channelGroup = intent.getStringExtra(EXTRA_CHANNEL_GROUP) ?: ""
        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)
        channelUrls = intent.getStringArrayListExtra(EXTRA_CHANNEL_URLS) ?: listOf(streamUrl)
        channelNames = intent.getStringArrayListExtra(EXTRA_CHANNEL_NAMES) ?: listOf(channelName)

        // Initialize player
        playerManager = PlayerManager(this)
        val player = playerManager.initialize()
        playerManager.listener = this
        playerView.player = player

        // Start playback
        updateChannelInfo(channelName, channelGroup)
        playerManager.play(streamUrl)

        // Show channel info briefly on start
        showChannelInfo()
    }

    // --- D-pad controls ---

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        currentIndex = (currentIndex + 1) % channelUrls.size
        playCurrentChannel()
    }

    private fun switchToPreviousChannel() {
        if (channelUrls.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else channelUrls.size - 1
        playCurrentChannel()
    }

    private fun playCurrentChannel() {
        val url = channelUrls.getOrNull(currentIndex) ?: return
        val name = channelNames.getOrElse(currentIndex) { "Channel ${currentIndex + 1}" }

        errorOverlay.visibility = View.GONE
        updateChannelInfo(name, "")
        showChannelInfo()
        playerManager.switchChannel(url)

        Log.d(TAG, "Switched to channel $currentIndex: $name")
    }

    private fun updateChannelInfo(name: String, group: String) {
        channelNameText.text = name
        channelGroupText.text = group
        channelGroupText.visibility = if (group.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showChannelInfo() {
        channelInfoOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY)
    }

    private fun hideChannelInfo() {
        channelInfoOverlay.visibility = View.GONE
        handler.removeCallbacks(hideOverlayRunnable)
    }

    // --- PlayerManager.PlayerListener ---

    override fun onBuffering(isBuffering: Boolean) {
        bufferingOverlay.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    override fun onPlaying() {
        errorOverlay.visibility = View.GONE
    }

    override fun onError(message: String) {
        errorOverlay.visibility = View.VISIBLE
        errorText.text = message
    }

    override fun onEnded() {
        // Stream ended — could auto-switch to next channel
        switchToNextChannel()
    }

    // --- Lifecycle ---

    override fun onStart() {
        super.onStart()
        playerManager.resume()
    }

    override fun onStop() {
        super.onStop()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        playerManager.release()
    }
}
