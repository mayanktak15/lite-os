package com.litetv.app

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.litetv.app.iptv.Channel
import com.litetv.app.iptv.PlaylistRepository
import com.litetv.app.iptv.SearchDialog
import com.litetv.app.sdui.*
import com.litetv.app.update.AppUpdateManager
import com.litetv.app.update.AppUpdateStatus
import com.litetv.app.update.UpdateSyncScheduler
import kotlinx.coroutines.*

/**
 * Main entry point for Lite TV.
 *
 * Startup sequence:
 * 1. Show loading state immediately
 * 2. Load SDUI config (instant cache → bundled default)
 * 3. Render initial UI from config
 * 4. Load & conditionally synchronize playlist in background
 * 5. Update UI with live channel data & re-index search
 * 6. Check for app binary updates and schedule periodic sync
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var configRepository: SduiConfigRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var renderer: SduiRenderer
    private lateinit var actionHandler: SduiActionHandler
    private lateinit var updateManager: AppUpdateManager

    private lateinit var rootContainer: View
    private lateinit var contentContainer: LinearLayout
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var navSearch: TextView
    private lateinit var navSettings: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentConfig: SduiConfig? = null
    private var backPressedTime: Long = 0
    private var updateDialog: AlertDialog? = null
    private var lastSelectedChannelStreamUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        rootContainer = findViewById(R.id.rootContainer)
        contentContainer = findViewById(R.id.contentContainer)
        sectionsContainer = findViewById(R.id.sectionsContainer)
        loadingView = findViewById(R.id.loadingView)
        errorView = findViewById(R.id.errorView)
        navSearch = findViewById(R.id.navSearch)
        navSettings = findViewById(R.id.navSettings)

        // Initialize components (manual DI — no Dagger overhead)
        configRepository = SduiConfigRepository(this)
        playlistRepository = PlaylistRepository(this)
        renderer = SduiRenderer(this)
        actionHandler = SduiActionHandler(this)
        updateManager = AppUpdateManager(this)

        // Register catalogue change listener so SDUI and Search react immediately
        playlistRepository.addListener { updatedChannels, diff ->
            runOnUiThread {
                Log.d(TAG, "Catalogue updated callback: ${diff.summary}")
                currentConfig?.let { config ->
                    renderConfig(config, updatedChannels)
                }
            }
        }

        // Set up navigation
        setupNavigation()

        // Start loading
        loadContent()
    }

    private fun setupNavigation() {
        navSettings.setOnClickListener {
            actionHandler.openSettings()
        }

        navSearch.setOnClickListener {
            openSearchDialog()
        }

        // Retry button in error view
        errorView.findViewById<View>(R.id.retryButton)?.setOnClickListener {
            loadContent()
        }
    }

    private fun openSearchDialog() {
        val dialog = SearchDialog(this, playlistRepository) { channel ->
            actionHandler.playChannel(channel, playlistRepository.channels)
        }
        dialog.show()
    }

    private fun loadContent() {
        showLoading()

        scope.launch {
            try {
                // Step 1: Load SDUI config (instant — from cache or bundled)
                val config = withContext(Dispatchers.IO) {
                    configRepository.getCachedOrDefault()
                }
                currentConfig = config

                // Schedule background periodic sync according to remote config
                UpdateSyncScheduler.schedulePeriodicSync(this@MainActivity, config.refreshIntervalMinutes)

                // Step 2: Show initial UI with config
                renderConfig(config, emptyList())

                // Check for application binary update
                checkAppUpdate(config)

                // Step 3: Asynchronously fetch remote SDUI config in background
                launch(Dispatchers.IO) {
                    try {
                        val remoteConfig = configRepository.fetchRemoteConfig()
                        if (remoteConfig != null && remoteConfig != currentConfig) {
                            withContext(Dispatchers.Main) {
                                currentConfig = remoteConfig
                                renderConfig(remoteConfig, playlistRepository.channels)
                                checkAppUpdate(remoteConfig)
                                UpdateSyncScheduler.schedulePeriodicSync(this@MainActivity, remoteConfig.refreshIntervalMinutes)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Background remote config update failed", e)
                    }
                }

                // Step 4: Load & synchronize playlist in background
                val loaded = playlistRepository.loadChannels()
                val activeConfig = currentConfig ?: config
                if (loaded) {
                    renderConfig(activeConfig, playlistRepository.channels)
                    showContent()
                } else if (playlistRepository.channels.isEmpty()) {
                    showContent()
                    renderConfig(activeConfig, emptyList())
                } else {
                    renderConfig(activeConfig, playlistRepository.channels)
                    showContent()
                }

                Log.d(TAG, "Loaded ${playlistRepository.channels.size} channels")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load content", e)
                if (playlistRepository.channels.isNotEmpty()) {
                    currentConfig?.let { renderConfig(it, playlistRepository.channels) }
                    showContent()
                } else {
                    showError(getString(R.string.error_network))
                }
            }
        }
    }

    private fun checkAppUpdate(config: SduiConfig) {
        val status = updateManager.checkUpdate(config.appUpdate)
        if (status is AppUpdateStatus.UpdateAvailable) {
            showUpdateDialog(status)
        }
    }

    private fun showUpdateDialog(update: AppUpdateStatus.UpdateAvailable) {
        if (isFinishing || isDestroyed) return
        if (updateDialog?.isShowing == true) return

        val builder = AlertDialog.Builder(this)
            .setTitle(if (update.isMandatory) "Mandatory App Update" else "New Version Available")
            .setMessage(
                "Lite TV ${update.config.latestVersionName} is available.\n\n" +
                if (update.config.releaseNotes.isNotEmpty()) "What's new:\n${update.config.releaseNotes}\n\n" else "" +
                if (update.isMandatory) "This update is required to continue using the application."
                else "Would you like to install the update now?"
            )
            .setCancelable(!update.isMandatory)
            .setPositiveButton("Update Now") { _, _ ->
                if (!update.config.apkDownloadUrl.isNullOrEmpty()) {
                    scope.launch {
                        Toast.makeText(this@MainActivity, "Downloading update…", Toast.LENGTH_SHORT).show()
                        val result = updateManager.downloadAndInstallApk(this@MainActivity, update.config) { progress ->
                            Log.d(TAG, "Download progress: $progress%")
                        }
                        if (result.isFailure) {
                            Toast.makeText(this@MainActivity, "Direct install failed: ${result.exceptionOrNull()?.message}. Opening update page…", Toast.LENGTH_LONG).show()
                            updateManager.openUpdateChannel(this@MainActivity, update.config.updateUrl)
                        }
                    }
                } else {
                    updateManager.openUpdateChannel(this@MainActivity, update.config.updateUrl)
                }
            }

        if (update.isMandatory) {
            builder.setNegativeButton("Exit") { _, _ -> finish() }
        } else {
            builder.setNegativeButton("Later", null)
        }

        updateDialog = builder.create().apply { show() }
    }

    private fun renderConfig(config: SduiConfig, channels: List<Channel>) {
        renderer.render(config, sectionsContainer, channels) { channel ->
            lastSelectedChannelStreamUrl = channel.streamUrl
            actionHandler.playChannel(channel, channels)
        }
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        contentContainer.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showContent() {
        loadingView.visibility = View.GONE
        contentContainer.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        contentContainer.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.findViewById<TextView>(R.id.errorMessage)?.text = message
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                handleBackPress()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun handleBackPress() {
        if (System.currentTimeMillis() - backPressedTime < 2000) {
            finish()
        } else {
            backPressedTime = System.currentTimeMillis()
            Toast.makeText(this, R.string.press_back_to_exit, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (sectionsContainer.childCount == 0 && currentConfig != null && playlistRepository.channels.isNotEmpty()) {
            renderConfig(currentConfig!!, playlistRepository.channels)
        }
        restoreFocusToSelectedChannel()
    }

    private fun restoreFocusToSelectedChannel() {
        val targetUrl = lastSelectedChannelStreamUrl ?: return
        sectionsContainer.post {
            val foundView = findViewWithTagRecursively(sectionsContainer, targetUrl)
            if (foundView != null) {
                foundView.requestFocus()
            }
        }
    }

    private fun findViewWithTagRecursively(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                val found = findViewWithTagRecursively(child, tag)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        updateDialog?.dismiss()
        scope.cancel()
    }
}

