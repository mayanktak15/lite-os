package com.litetv.app.sdui

/**
 * Data classes representing the SDUI configuration schema.
 * These are parsed from the remote/bundled JSON config.
 * The UI is driven entirely by these structures.
 */

/** Root SDUI configuration */
data class SduiConfig(
    val schemaVersion: Int,
    val minAppVersion: String,
    val theme: ThemeConfig,
    val sections: List<Section>,
    val navigation: List<String>,
    val playlistSources: List<PlaylistSource>,
    val refreshIntervalMinutes: Int,
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val searchConfig: SearchConfig = SearchConfig(),
    val appUpdate: AppUpdateConfig? = null,
    val cacheTtlMinutes: Int = 60
)

/** Theme configuration — colors applied dynamically */
data class ThemeConfig(
    val backgroundColor: String,
    val cardColor: String,
    val accentColor: String,
    val textColor: String
)

/** A UI section on the home screen */
data class Section(
    val id: String,
    val type: String,          // hero_row, channel_row, category_grid, text_heading
    val title: String,
    val visible: Boolean,
    val order: Int,
    val category: String?,     // Filter channels by this category
    val channelIds: List<String>?  // Specific channel IDs (for featured)
)

/** A playlist source that can be selected in settings */
data class PlaylistSource(
    val id: String,
    val label: String,
    val url: String
)

/** Remote search configuration */
data class SearchConfig(
    val enabled: Boolean = true,
    val placeholder: String = "Search channels…",
    val maxResults: Int = 50
)

/**
 * Application binary update metadata delivered via SDUI configuration.
 */
data class AppUpdateConfig(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minRequiredVersionCode: Int = 1,
    val updateUrl: String,
    val apkDownloadUrl: String? = null,
    val apkSha256: String? = null,
    val releaseNotes: String = "",
    val forceUpdate: Boolean = false
)

