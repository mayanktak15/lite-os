package com.litetv.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.litetv.app.iptv.PlaylistRepository
import com.litetv.app.sdui.SduiConfigRepository
import com.litetv.app.update.AppUpdateManager
import com.litetv.app.update.AppUpdateStatus
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Settings screen for playlist source management and update diagnostics.
 * Allows selecting pre-configured sources, entering a custom URL,
 * refreshing the playlist, inspecting catalogue diffs, and checking app binary updates.
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
    private lateinit var configRepository: SduiConfigRepository
    private lateinit var updateManager: AppUpdateManager

    private lateinit var customUrlInput: EditText
    private lateinit var lastUpdatedText: TextView
    private lateinit var channelCountText: TextView
    private lateinit var diffSummaryText: TextView
    private lateinit var configStatusText: TextView
    private lateinit var appVersionText: TextView
    private lateinit var updateStatusText: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        playlistRepository = PlaylistRepository(this)
        configRepository = SduiConfigRepository(this)
        updateManager = AppUpdateManager(this)

        customUrlInput = findViewById(R.id.customUrlInput)
        lastUpdatedText = findViewById(R.id.lastUpdated)
        channelCountText = findViewById(R.id.channelCount)
        diffSummaryText = findViewById(R.id.diffSummary)
        configStatusText = findViewById(R.id.configStatus)
        appVersionText = findViewById(R.id.appVersionText)
        updateStatusText = findViewById(R.id.updateStatusText)

        setupSourceButtons()
        setupRefreshButton()
        setupAppUpdateSection()
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

    private fun setupAppUpdateSection() {
        appVersionText.text = "Installed: v${updateManager.currentVersionName} (Build ${updateManager.currentVersionCode})"
        updateStatusText.text = "Status: Up to date"

        findViewById<View>(R.id.checkUpdatesButton)?.setOnClickListener {
            checkForAppUpdates()
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
            Toast.makeText(this@SettingsActivity, "Refreshing playlist…", Toast.LENGTH_SHORT).show()
            val result = playlistRepository.syncCatalogue(force = true)
            if (result.success) {
                Toast.makeText(
                    this@SettingsActivity,
                    "Catalogue updated: ${playlistRepository.channels.size} channels",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Refresh failed: ${result.message}", Toast.LENGTH_SHORT).show()
            }
            updateStatusDisplay()
        }
    }

    private fun checkForAppUpdates() {
        scope.launch {
            Toast.makeText(this@SettingsActivity, "Checking for updates…", Toast.LENGTH_SHORT).show()
            val config = withContext(Dispatchers.IO) {
                configRepository.fetchRemoteConfig() ?: configRepository.getCachedOrDefault()
            }

            val status = updateManager.checkUpdate(config.appUpdate)
            when (status) {
                is AppUpdateStatus.UpdateAvailable -> {
                    updateStatusText.text = "Status: New version available (v${status.config.latestVersionName})"
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Update Available")
                        .setMessage("Lite TV v${status.config.latestVersionName} is available.\n\n${status.config.releaseNotes}")
                        .setPositiveButton("Update Now") { _, _ ->
                            if (!status.config.apkDownloadUrl.isNullOrEmpty()) {
                                scope.launch {
                                    Toast.makeText(this@SettingsActivity, "Downloading update…", Toast.LENGTH_SHORT).show()
                                    val dlResult = updateManager.downloadAndInstallApk(this@SettingsActivity, status.config)
                                    if (dlResult.isFailure) {
                                        Toast.makeText(this@SettingsActivity, "Download failed, opening browser…", Toast.LENGTH_SHORT).show()
                                        updateManager.openUpdateChannel(this@SettingsActivity, status.config.updateUrl)
                                    }
                                }
                            } else {
                                updateManager.openUpdateChannel(this@SettingsActivity, status.config.updateUrl)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                is AppUpdateStatus.UpToDate -> {
                    updateStatusText.text = "Status: Up to date"
                    Toast.makeText(this@SettingsActivity, "Lite TV is up to date!", Toast.LENGTH_SHORT).show()
                }
                is AppUpdateStatus.Error -> {
                    updateStatusText.text = "Status: Check failed"
                    Toast.makeText(this@SettingsActivity, "Update check failed: ${status.message}", Toast.LENGTH_SHORT).show()
                }
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

        // Diff & Config Diagnostics
        diffSummaryText.text = "Last Sync Diff: ${playlistRepository.lastDiffSummary}"
        configStatusText.text = "SDUI Config: ${configRepository.lastSyncStatus}"
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
        if (findViewById<View>(viewId) == null) return
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

