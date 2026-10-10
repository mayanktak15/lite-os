package com.litetv.app.sdui

import android.content.ContextWrapper
import com.litetv.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SduiConfigRepositoryTest {

    @Test
    fun defaultRemoteUrl_matchesExpectedDefaultOrBuildConfig() {
        assertEquals("https://mayanktak15.github.io/lite-os/config.json", SduiConfigRepository.FALLBACK_REMOTE_URL)
        assertEquals(BuildConfig.LITE_TV_REMOTE_CONFIG_URL, SduiConfigRepository.DEFAULT_REMOTE_URL)
    }

    @Test
    fun remoteConfigUrl_usesDefaultWhenNullOrBlank() {
        val dummyContext = ContextWrapper(null)
        val repoDefault = SduiConfigRepository(dummyContext)
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoDefault.remoteConfigUrl)

        val repoEmpty = SduiConfigRepository(dummyContext, "")
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoEmpty.remoteConfigUrl)

        val repoBlank = SduiConfigRepository(dummyContext, "   ")
        assertEquals(SduiConfigRepository.DEFAULT_REMOTE_URL, repoBlank.remoteConfigUrl)
    }

    @Test
    fun remoteConfigUrl_usesCustomUrlWhenProvided() {
        val dummyContext = ContextWrapper(null)
        val customUrl = "https://custom.example.com/tv/config.json"
        val repo = SduiConfigRepository(dummyContext, customUrl)
        assertEquals(customUrl, repo.remoteConfigUrl)
    }
}
