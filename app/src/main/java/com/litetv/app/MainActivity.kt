package com.litetv.app

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
import com.litetv.app.sdui.*
import kotlinx.coroutines.*

/**
 * Main entry point for Lite TV.
 *
 * Startup sequence:
 * 1. Show loading state immediately
 * 2. Load SDUI config (cache → bundled default)
 * 3. Render initial UI from config
 * 4. Fetch playlist in background
 * 5. Update UI with real channel data
 * 6. Optionally fetch remote SDUI config for updates
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var configRepository: SduiConfigRepository
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var renderer: SduiRenderer
    private lateinit var actionHandler: SduiActionHandler

    private lateinit var rootContainer: View
    private lateinit var contentContainer: LinearLayout
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var loadingView: View
    private lateinit var errorView: View

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentConfig: SduiConfig? = null
    private var backPressedTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        rootContainer = findViewById(R.id.rootContainer)
        contentContainer = findViewById(R.id.contentContainer)
        sectionsContainer = findViewById(R.id.sectionsContainer)
        loadingView = findViewById(R.id.loadingView)
        errorView = findViewById(R.id.errorView)

        // Initialize components (manual DI — no Dagger overhead)
        configRepository = SduiConfigRepository(this)
        playlistRepository = PlaylistRepository(this)
        renderer = SduiRenderer(this)
        actionHandler = SduiActionHandler(this)

        // Set up navigation
        setupNavigation()

        // Start loading
        loadContent()
    }

    private fun setupNavigation() {
        findViewById<TextView>(R.id.navSettings).setOnClickListener {
            actionHandler.openSettings()
        }

        // Retry button in error view
        errorView.findViewById<View>(R.id.retryButton)?.setOnClickListener {
            loadContent()
        }
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

                // Step 2: Show initial UI with config (may show empty sections)
                renderConfig(config, emptyList())

                // Asynchronously fetch remote SDUI config in background
                launch(Dispatchers.IO) {
                    try {
                        val remoteConfig = configRepository.fetchRemoteConfig()
                        if (remoteConfig != null && remoteConfig != currentConfig) {
                            withContext(Dispatchers.Main) {
                                currentConfig = remoteConfig
                                renderConfig(remoteConfig, playlistRepository.channels)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Background remote config update failed", e)
                    }
                }

                // Step 3: Load playlist in background
                val loaded = playlistRepository.loadChannels()
                val activeConfig = currentConfig ?: config
                if (loaded) {
                    // Step 4: Re-render with real channel data
                    renderConfig(activeConfig, playlistRepository.channels)
                    showContent()
                } else if (playlistRepository.channels.isEmpty()) {
                    // No channels from cache or remote
                    showContent()
                    renderConfig(activeConfig, emptyList())
                } else {
                    // Cache had data even if remote failed
                    renderConfig(activeConfig, playlistRepository.channels)
                    showContent()
                }

                Log.d(TAG, "Loaded ${playlistRepository.channels.size} channels")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load content", e)
                if (playlistRepository.channels.isNotEmpty()) {
                    // We have cached channels — show them
                    currentConfig?.let { renderConfig(it, playlistRepository.channels) }
                    showContent()
                } else {
                    showError(getString(R.string.error_network))
                }
            }
        }
    }

    private fun renderConfig(config: SduiConfig, channels: List<Channel>) {
        renderer.render(config, sectionsContainer, channels) { channel ->
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

    /**
     * Double-press back to exit — prevents accidental exits on TV.
     */
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
        // Refresh channels if we're coming back from settings (source may have changed)
        if (currentConfig != null && playlistRepository.channels.isNotEmpty()) {
            renderConfig(currentConfig!!, playlistRepository.channels)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
