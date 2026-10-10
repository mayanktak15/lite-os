package com.litetv.app.sdui

import android.content.Context
import android.content.Intent
import android.util.Log
import com.litetv.app.PlayerActivity
import com.litetv.app.SettingsActivity
import com.litetv.app.iptv.Channel

/**
 * Handles SDUI actions — navigation, playback, settings.
 * Actions are declarative strings from the SDUI config.
 * No arbitrary code execution — only known actions are handled.
 */
class SduiActionHandler(private val context: Context) {

    companion object {
        private const val TAG = "SduiActionHandler"
    }

    /**
     * Handle a channel selection — launch player.
     */
    fun playChannel(channel: Channel, channelList: List<Channel>) {
        Log.d(TAG, "Playing channel: ${channel.name}")
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_CHANNEL_GROUP, channel.group)
            putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, channelList.indexOf(channel))
            putStringArrayListExtra(
                PlayerActivity.EXTRA_CHANNEL_URLS,
                ArrayList(channelList.map { it.streamUrl })
            )
            putStringArrayListExtra(
                PlayerActivity.EXTRA_CHANNEL_NAMES,
                ArrayList(channelList.map { it.name })
            )
        }
        context.startActivity(intent)
    }

    /**
     * Open settings screen.
     */
    fun openSettings() {
        Log.d(TAG, "Opening settings")
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }
}
