package com.litetv.app.sdui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SduiConfigParserTest {

    private val validJson = """
        {
          "schemaVersion": 1,
          "minAppVersion": "1.0.0",
          "theme": {
            "backgroundColor": "#121212",
            "cardColor": "#1E1E1E",
            "accentColor": "#00BFA5",
            "textColor": "#FFFFFF"
          },
          "sections": [
            {
              "id": "hero_featured",
              "type": "hero_row",
              "title": "Top Picks",
              "visible": true,
              "order": 1,
              "channelIds": ["ch_1", "ch_2"]
            },
            {
              "id": "news_row",
              "type": "channel_row",
              "title": "Latest News",
              "visible": true,
              "order": 2,
              "category": "News"
            }
          ],
          "navigation": ["home", "search", "settings"],
          "playlistSources": [
            {
              "id": "source_1",
              "label": "Primary Source",
              "url": "https://example.com/playlist.m3u"
            }
          ],
          "refreshIntervalMinutes": 120,
          "featureFlags": {
            "search_enabled": true,
            "channel_preview": false
          },
          "searchConfig": {
            "enabled": true,
            "placeholder": "Find your stream…",
            "maxResults": 30
          },
          "appUpdate": {
            "latestVersionCode": 2,
            "latestVersionName": "1.1.0",
            "minRequiredVersionCode": 1,
            "updateUrl": "https://example.com/releases",
            "apkDownloadUrl": "https://example.com/app-release.apk",
            "apkSha256": "abcdef123456",
            "releaseNotes": "Performance improvements",
            "forceUpdate": false
          },
          "cacheTtlMinutes": 90
        }
    """.trimIndent()

    @Test
    fun parse_validJson_parsesCorrectly() {
        val config = SduiConfigParser.parse(validJson)
        assertNotNull(config)
        assertEquals(1, config!!.schemaVersion)
        assertEquals("1.0.0", config.minAppVersion)
        assertEquals(120, config.refreshIntervalMinutes)
        assertEquals(90, config.cacheTtlMinutes)

        // Theme
        assertEquals("#121212", config.theme.backgroundColor)
        assertEquals("#1E1E1E", config.theme.cardColor)
        assertEquals("#00BFA5", config.theme.accentColor)
        assertEquals("#FFFFFF", config.theme.textColor)

        // Sections
        assertEquals(2, config.sections.size)
        val sec1 = config.sections[0]
        assertEquals("hero_featured", sec1.id)
        assertEquals("hero_row", sec1.type)
        assertEquals("Top Picks", sec1.title)
        assertEquals(listOf("ch_1", "ch_2"), sec1.channelIds)

        val sec2 = config.sections[1]
        assertEquals("news_row", sec2.id)
        assertEquals("channel_row", sec2.type)
        assertEquals("News", sec2.category)

        // Navigation
        assertEquals(listOf("home", "search", "settings"), config.navigation)

        // Playlist sources
        assertEquals(1, config.playlistSources.size)
        assertEquals("Primary Source", config.playlistSources[0].label)
        assertEquals("https://example.com/playlist.m3u", config.playlistSources[0].url)

        // Feature flags
        assertEquals(true, config.featureFlags["search_enabled"])
        assertEquals(false, config.featureFlags["channel_preview"])

        // Search config
        assertEquals(true, config.searchConfig.enabled)
        assertEquals("Find your stream…", config.searchConfig.placeholder)
        assertEquals(30, config.searchConfig.maxResults)

        // App update
        assertNotNull(config.appUpdate)
        assertEquals(2, config.appUpdate!!.latestVersionCode)
        assertEquals("1.1.0", config.appUpdate!!.latestVersionName)
        assertEquals("https://example.com/app-release.apk", config.appUpdate!!.apkDownloadUrl)
        assertEquals("abcdef123456", config.appUpdate!!.apkSha256)
        assertEquals("Performance improvements", config.appUpdate!!.releaseNotes)
        assertFalse(config.appUpdate!!.forceUpdate)
    }

    @Test
    fun parse_schemaVersion2_supported() {
        val v2Json = """
            {
              "schemaVersion": 2,
              "sections": []
            }
        """.trimIndent()
        val config = SduiConfigParser.parse(v2Json)
        assertNotNull(config)
        assertEquals(2, config!!.schemaVersion)
    }

    @Test
    fun parse_unsupportedSchemaVersion_returnsNull() {
        val json = """{ "schemaVersion": 999 }"""
        val config = SduiConfigParser.parse(json)
        assertNull(config)
    }

    @Test
    fun parse_malformedJson_returnsNull() {
        val json = "{ bad json "
        val config = SduiConfigParser.parse(json)
        assertNull(config)
    }

    @Test
    fun parse_unknownSectionType_skipsUnknownSection() {
        val json = """
            {
              "schemaVersion": 1,
              "sections": [
                {
                  "id": "unknown_sec",
                  "type": "unsupported_widget",
                  "title": "Skip me"
                },
                {
                  "id": "valid_sec",
                  "type": "channel_row",
                  "title": "Keep me"
                }
              ]
            }
        """.trimIndent()
        val config = SduiConfigParser.parse(json)
        assertNotNull(config)
        assertEquals(1, config!!.sections.size)
        assertEquals("valid_sec", config.sections[0].id)
    }
}
