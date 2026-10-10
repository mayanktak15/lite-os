package com.litetv.app.sdui

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses and validates SDUI JSON configuration into [SduiConfig] data models.
 *
 * Security and reliability principles:
 * - Uses Android's built-in org.json (zero heavy external parser dependencies)
 * - Strict schema versioning (supported schema versions: 1, 2)
 * - Strict component whitelisting (only known UI component types rendered)
 * - Safe degradation: missing or unknown attributes fall back gracefully
 * - Rejects any executable code, arbitrary classes, or script constructs
 */
object SduiConfigParser {

    private const val TAG = "SduiConfigParser"
    val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2)

    /**
     * Parse a JSON string into an [SduiConfig].
     * Returns null if parsing fails, json is malformed, or schema version is unsupported.
     */
    fun parse(jsonString: String): SduiConfig? {
        return try {
            val json = JSONObject(jsonString)
            val schemaVersion = json.optInt("schemaVersion", -1)

            if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
                Log.w(TAG, "Unsupported or missing schema version: $schemaVersion (supported: $SUPPORTED_SCHEMA_VERSIONS)")
                return null
            }

            SduiConfig(
                schemaVersion = schemaVersion,
                minAppVersion = json.optString("minAppVersion", "1.0.0"),
                theme = parseTheme(json.optJSONObject("theme")),
                sections = parseSections(json.optJSONArray("sections")),
                navigation = parseStringList(json.optJSONArray("navigation")),
                playlistSources = parsePlaylistSources(json.optJSONArray("playlistSources")),
                refreshIntervalMinutes = json.optInt("refreshIntervalMinutes", 60),
                featureFlags = parseFeatureFlags(json.optJSONObject("featureFlags")),
                searchConfig = parseSearchConfig(json.optJSONObject("searchConfig") ?: json.optJSONObject("search")),
                appUpdate = parseAppUpdate(json.optJSONObject("appUpdate")),
                cacheTtlMinutes = json.optInt("cacheTtlMinutes", 60)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SDUI config", e)
            null
        }
    }

    private fun parseTheme(json: JSONObject?): ThemeConfig {
        if (json == null) return defaultTheme()
        return ThemeConfig(
            backgroundColor = json.optString("backgroundColor", "#0D0D0D"),
            cardColor = json.optString("cardColor", "#242424"),
            accentColor = json.optString("accentColor", "#00BFA5"),
            textColor = json.optString("textColor", "#FFFFFF")
        )
    }

    private fun defaultTheme() = ThemeConfig(
        backgroundColor = "#0D0D0D",
        cardColor = "#242424",
        accentColor = "#00BFA5",
        textColor = "#FFFFFF"
    )

    private fun parseSections(jsonArray: JSONArray?): List<Section> {
        if (jsonArray == null) return emptyList()
        val sections = mutableListOf<Section>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val type = obj.optString("type", "")
            // Only allow known component types (fail safe)
            if (type !in KNOWN_COMPONENT_TYPES) {
                Log.w(TAG, "Unknown section type '$type', skipping")
                continue
            }
            sections.add(
                Section(
                    id = obj.optString("id", "section_$i"),
                    type = type,
                    title = obj.optString("title", ""),
                    visible = obj.optBoolean("visible", true),
                    order = obj.optInt("order", i),
                    category = obj.optString("category", "").takeIf { it.isNotEmpty() },
                    channelIds = parseStringList(obj.optJSONArray("channelIds")).takeIf { it.isNotEmpty() }
                )
            )
        }
        return sections.sortedBy { it.order }
    }

    private fun parsePlaylistSources(jsonArray: JSONArray?): List<PlaylistSource> {
        if (jsonArray == null) return defaultPlaylistSources()
        val sources = mutableListOf<PlaylistSource>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val url = obj.optString("url", "")
            if (url.isEmpty() || (!url.startsWith("https://") && !url.startsWith("http://"))) {
                Log.w(TAG, "Skipping playlist source with invalid URL: $url")
                continue
            }
            sources.add(
                PlaylistSource(
                    id = obj.optString("id", "source_$i"),
                    label = obj.optString("label", "Source ${i + 1}"),
                    url = url
                )
            )
        }
        return sources.ifEmpty { defaultPlaylistSources() }
    }

    private fun parseStringList(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.optString(i, null)
            if (!value.isNullOrEmpty()) {
                list.add(value)
            }
        }
        return list
    }

    private fun parseFeatureFlags(json: JSONObject?): Map<String, Boolean> {
        if (json == null) return emptyMap()
        val flags = mutableMapOf<String, Boolean>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            flags[key] = json.optBoolean(key, false)
        }
        return flags
    }

    private fun parseSearchConfig(json: JSONObject?): SearchConfig {
        if (json == null) return SearchConfig()
        return SearchConfig(
            enabled = json.optBoolean("enabled", true),
            placeholder = json.optString("placeholder", "Search channels…"),
            maxResults = json.optInt("maxResults", 50).coerceIn(5, 200)
        )
    }

    private fun parseAppUpdate(json: JSONObject?): AppUpdateConfig? {
        if (json == null) return null
        val latestVersionCode = json.optInt("latestVersionCode", -1)
        val latestVersionName = json.optString("latestVersionName", "")
        val updateUrl = json.optString("updateUrl", "")

        if (latestVersionCode <= 0 || updateUrl.isEmpty()) {
            Log.w(TAG, "Incomplete appUpdate config, skipping")
            return null
        }

        return AppUpdateConfig(
            latestVersionCode = latestVersionCode,
            latestVersionName = latestVersionName,
            minRequiredVersionCode = json.optInt("minRequiredVersionCode", 1),
            updateUrl = updateUrl,
            apkDownloadUrl = json.optString("apkDownloadUrl", "").takeIf { it.startsWith("https://") },
            apkSha256 = json.optString("apkSha256", "").takeIf { it.isNotEmpty() },
            releaseNotes = json.optString("releaseNotes", ""),
            forceUpdate = json.optBoolean("forceUpdate", false)
        )
    }

    private fun defaultPlaylistSources() = listOf(
        PlaylistSource("iptv_org_india", "IPTV-Org India", "https://iptv-org.github.io/iptv/countries/in.m3u"),
        PlaylistSource("iptv_org_news", "IPTV-Org News", "https://iptv-org.github.io/iptv/categories/news.m3u"),
        PlaylistSource("world_iptv", "World IPTV", "https://romaxa55.github.io/world_ip_tv/output/index.m3u")
    )

    /** Known component types that the renderer supports */
    val KNOWN_COMPONENT_TYPES = setOf(
        "hero_row",
        "channel_row",
        "category_grid",
        "text_heading",
        "loading",
        "error",
        "empty"
    )
}

