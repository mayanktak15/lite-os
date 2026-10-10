package com.litetv.app.sdui

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses SDUI JSON configuration into [SduiConfig] data classes.
 * Uses built-in org.json — no Gson/Moshi dependency.
 * Validates structure and fails safely on malformed data.
 */
object SduiConfigParser {

    private const val TAG = "SduiConfigParser"
    private const val SUPPORTED_SCHEMA_VERSION = 1

    /**
     * Parse a JSON string into an [SduiConfig].
     * Returns null if parsing fails or schema is unsupported.
     */
    fun parse(jsonString: String): SduiConfig? {
        return try {
            val json = JSONObject(jsonString)
            val schemaVersion = json.optInt("schemaVersion", -1)

            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                Log.w(TAG, "Unsupported schema version: $schemaVersion")
                return null
            }

            SduiConfig(
                schemaVersion = schemaVersion,
                minAppVersion = json.optString("minAppVersion", "1.0.0"),
                theme = parseTheme(json.optJSONObject("theme")),
                sections = parseSections(json.optJSONArray("sections")),
                navigation = parseStringList(json.optJSONArray("navigation")),
                playlistSources = parsePlaylistSources(json.optJSONArray("playlistSources")),
                refreshIntervalMinutes = json.optInt("refreshIntervalMinutes", 60)
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
            if (url.isEmpty() || !url.startsWith("https://")) {
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

    private fun defaultPlaylistSources() = listOf(
        PlaylistSource("iptv_org_india", "IPTV-Org India", "https://iptv-org.github.io/iptv/countries/in.m3u"),
        PlaylistSource("iptv_org_news", "IPTV-Org News", "https://iptv-org.github.io/iptv/categories/news.m3u"),
        PlaylistSource("world_iptv", "World IPTV", "https://romaxa55.github.io/world_ip_tv/output/index.m3u")
    )

    /** Known component types that the renderer supports */
    private val KNOWN_COMPONENT_TYPES = setOf(
        "hero_row",
        "channel_row",
        "category_grid",
        "text_heading",
        "loading",
        "error",
        "empty"
    )
}
