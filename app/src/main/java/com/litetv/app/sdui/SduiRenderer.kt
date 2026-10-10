package com.litetv.app.sdui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litetv.app.R
import com.litetv.app.iptv.Channel

/**
 * Renders SDUI sections into native Android Views.
 * Maps section types to concrete View hierarchies using [SduiComponentFactory].
 * All rendering uses pre-registered component types — no dynamic code execution.
 */
class SduiRenderer(private val context: Context) {

    private val componentFactory = SduiComponentFactory(context)

    /**
     * Render all visible sections from the config into the given container.
     * Clears existing children before rendering.
     */
    fun render(
        config: SduiConfig,
        container: ViewGroup,
        channels: List<Channel>,
        onChannelClick: (Channel) -> Unit
    ) {
        container.removeAllViews()

        val visibleSections = config.sections
            .filter { it.visible }
            .sortedBy { it.order }

        if (visibleSections.isEmpty() && channels.isEmpty()) {
            container.addView(componentFactory.createEmptyView())
            return
        }

        for (section in visibleSections) {
            val sectionChannels = filterChannelsForSection(section, channels)
            val view = componentFactory.createSection(section, sectionChannels, onChannelClick)
            if (view != null) {
                container.addView(view)
            }
        }
    }

    /**
     * Show a loading state in the container.
     */
    fun renderLoading(container: ViewGroup) {
        container.removeAllViews()
        container.addView(componentFactory.createLoadingView())
    }

    /**
     * Show an error state with retry callback.
     */
    fun renderError(container: ViewGroup, message: String, onRetry: () -> Unit) {
        container.removeAllViews()
        container.addView(componentFactory.createErrorView(message, onRetry))
    }

    /**
     * Filter channels based on section configuration.
     */
    private fun filterChannelsForSection(section: Section, allChannels: List<Channel>): List<Channel> {
        // If section specifies channel IDs, use those
        val channelIds = section.channelIds
        if (channelIds != null && channelIds.isNotEmpty()) {
            val idSet = channelIds.toSet()
            return allChannels.filter { it.id in idSet }
        }

        // If section specifies a category, filter by it
        val category = section.category
        if (category != null && category.isNotEmpty()) {
            return allChannels.filter {
                it.group.equals(category, ignoreCase = true)
            }
        }

        // No filter — return all channels
        return allChannels
    }
}
