package com.litetv.app

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.litetv.app.iptv.PlaylistRepository
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings screen for playlist source management.
 * Allows selecting pre-configured sources, entering a custom URL,
 * refreshing the playlist, and viewing status.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        private val SOURCES = listOf(
            "https://iptv-org.github.io/iptv/countries/in.m3u" to "IPTV-Org India",
            "https://iptv-org.github.io/iptv/categories/news.m3u" to "IPTV-Org News",
            "https://romaxa55.github.io/world_ip_tv/output/index.m3u" to "World IPTV"
        )
    }

    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var customUrlInput: EditText
    private lateinit var lastUpdatedText: TextView
    private lateinit var channelCountText: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        playlistRepository = PlaylistRepository(this)
        customUrlInput = findViewById(R.id.customUrlInput)
        lastUpdatedText = findViewById(R.id.lastUpdated)
        channelCountText = findViewById(R.id.channelCount)

        setupSourceButtons()
        setupRefreshButton()
        updateStatusDisplay()
    }

    private fun setupSourceButtons() {
        val sourceViews = listOf(
            R.id.sourceIptvOrgIndia to 0,
            R.id.sourceIptvOrgNews to 1,
            R.id.sourceWorldIptv to 2
        )

        for ((viewId, sourceIndex) in sourceViews) {
            findViewById<View>(viewId)?.setOnClickListener {
                selectSource(SOURCES[sourceIndex].first, SOURCES[sourceIndex].second)
            }
            // Highlight active source
            updateSourceHighlight(viewId, SOURCES[sourceIndex].first)
        }

        // Custom URL source
        findViewById<View>(R.id.sourceCustom)?.setOnClickListener {
            val url = customUrlInput.text.toString().trim()
            if (url.isNotEmpty() && (url.startsWith("https://") || url.startsWith("http://"))) {
                selectSource(url, "Custom")
            } else {
                Toast.makeText(this, "Enter a valid HTTP(S) URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupRefreshButton() {
        findViewById<View>(R.id.refreshButton)?.setOnClickListener {
            refreshPlaylist()
        }
    }

    private fun selectSource(url: String, name: String) {
        scope.launch {
            Toast.makeText(this@SettingsActivity, "Switching to $name…", Toast.LENGTH_SHORT).show()
            val success = playlistRepository.switchSource(url)
            if (success) {
                Toast.makeText(this@SettingsActivity, "Loaded ${playlistRepository.channels.size} channels", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Failed to load playlist", Toast.LENGTH_SHORT).show()
            }
            updateStatusDisplay()
            updateAllSourceHighlights()
        }
    }

    private fun refreshPlaylist() {
        scope.launch {
            Toast.makeText(this@SettingsActivity, "Refreshing…", Toast.LENGTH_SHORT).show()
            val success = playlistRepository.refresh()
            if (success) {
                Toast.makeText(this@SettingsActivity, "Refreshed: ${playlistRepository.channels.size} channels", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Refresh failed", Toast.LENGTH_SHORT).show()
            }
            updateStatusDisplay()
        }
    }

    private fun updateStatusDisplay() {
        val lastRefresh = playlistRepository.lastRefreshTime
        if (lastRefresh > 0) {
            val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
            lastUpdatedText.text = getString(R.string.last_updated, dateFormat.format(Date(lastRefresh)))
        } else {
            lastUpdatedText.text = getString(R.string.last_updated, "Never")
        }

        val count = playlistRepository.channels.size
        channelCountText.text = getString(R.string.channel_count, count)
    }

    private fun updateAllSourceHighlights() {
        val sourceViews = listOf(
            R.id.sourceIptvOrgIndia to 0,
            R.id.sourceIptvOrgNews to 1,
            R.id.sourceWorldIptv to 2
        )
        for ((viewId, sourceIndex) in sourceViews) {
            updateSourceHighlight(viewId, SOURCES[sourceIndex].first)
        }
    }

    private fun updateSourceHighlight(viewId: Int, sourceUrl: String) {
        val view = findViewById<View>(viewId) ?: return
        val statusView = when (viewId) {
            R.id.sourceIptvOrgIndia -> findViewById<TextView>(R.id.sourceIptvOrgIndiaStatus)
            R.id.sourceIptvOrgNews -> findViewById<TextView>(R.id.sourceIptvOrgNewsStatus)
            R.id.sourceWorldIptv -> findViewById<TextView>(R.id.sourceWorldIptvStatus)
            else -> null
        }

        if (playlistRepository.activeSourceUrl == sourceUrl) {
            statusView?.text = "● Active"
            statusView?.setTextColor(getColor(R.color.accent))
        } else {
            statusView?.text = ""
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
