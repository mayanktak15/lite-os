package com.litetv.app.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class M3uParserTest {

    @Test
    fun parse_validM3u_extractsAllChannelsWithMetadata() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="NDTVIndia" tvg-name="NDTV India" tvg-logo="https://example.com/ndtv.png" group-title="News",NDTV India
            https://example.com/stream/ndtv.m3u8
            #EXTINF:-1 tvg-id="StarSports" tvg-name="Star Sports 1" tvg-logo="https://example.com/sports.png" group-title="Sports",Star Sports 1
            https://example.com/stream/sports.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray()))

        assertEquals(2, channels.size)

        val ch1 = channels[0]
        assertEquals("NDTVIndia", ch1.id)
        assertEquals("NDTV India", ch1.name)
        assertEquals("https://example.com/ndtv.png", ch1.logoUrl)
        assertEquals("News", ch1.group)
        assertEquals("https://example.com/stream/ndtv.m3u8", ch1.streamUrl)

        val ch2 = channels[1]
        assertEquals("StarSports", ch2.id)
        assertEquals("Star Sports 1", ch2.name)
        assertEquals("https://example.com/sports.png", ch2.logoUrl)
        assertEquals("Sports", ch2.group)
        assertEquals("https://example.com/stream/sports.m3u8", ch2.streamUrl)
    }

    @Test
    fun parse_missingMetadata_fallbacksGracefully() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1,Nameless Channel
            https://example.com/stream/nameless.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray()))

        assertEquals(1, channels.size)
        val ch = channels[0]
        assertEquals("Nameless Channel", ch.name)
        assertEquals("https://example.com/stream/nameless.m3u8", ch.streamUrl)
        assertEquals("", ch.logoUrl)
        assertEquals("", ch.group)
    }

    @Test
    fun parse_duplicatesByUrl_keepsFirst() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="dup1",Duplicate Channel 1
            https://example.com/stream/duplicate.m3u8
            #EXTINF:-1 tvg-id="dup2",Duplicate Channel 2
            https://example.com/stream/duplicate.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray()))

        assertEquals(1, channels.size)
        assertEquals("Duplicate Channel 1", channels[0].name)
    }

    @Test
    fun parse_skipsNonHttpUrls() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1,Invalid Scheme
            rtmp://example.com/live/stream
            #EXTINF:-1,Valid HTTPS
            https://example.com/stream/valid.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray()))

        assertEquals(1, channels.size)
        assertEquals("Valid HTTPS", channels[0].name)
    }

    @Test
    fun parse_emptyInput_returnsEmptyList() {
        val channels = M3uParser.parse(ByteArrayInputStream(ByteArray(0)))
        assertTrue(channels.isEmpty())
    }
}
