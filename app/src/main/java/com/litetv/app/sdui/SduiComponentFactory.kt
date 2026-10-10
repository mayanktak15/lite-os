package com.litetv.app.sdui

import android.content.Context
import android.util.Log
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
import com.litetv.app.cache.ImageCache
import com.litetv.app.iptv.Channel

/**
 * Factory that creates native Android View components for known SDUI types.
 * Uses a registry pattern — only whitelisted component types are supported.
 * Unknown types return null (fail safe).
 */
class SduiComponentFactory(private val context: Context) {

    companion object {
        private const val TAG = "SduiComponentFactory"
    }

    private val inflater = LayoutInflater.from(context)
    private val imageCache = ImageCache.getInstance()

    /**
     * Create a section View from a [Section] definition.
     * Returns null for unknown types.
     */
    fun createSection(
        section: Section,
        channels: List<Channel>,
        onChannelClick: (Channel) -> Unit
    ): View? {
        return when (section.type) {
            "hero_row" -> createChannelRow(section.title, channels, onChannelClick, isHero = true)
            "channel_row" -> createChannelRow(section.title, channels, onChannelClick, isHero = false)
            "category_grid" -> createChannelRow(section.title, channels, onChannelClick, isHero = false)
            "text_heading" -> createTextHeading(section.title)
            "loading" -> createLoadingView()
            "error" -> createErrorView(section.title, {})
            "empty" -> createEmptyView()
            else -> {
                Log.w(TAG, "Unknown component type: ${section.type}")
                null
            }
        }
    }

    /**
     * Create a horizontal row of channel cards with a title.
     */
    fun createChannelRow(
        title: String,
        channels: List<Channel>,
        onChannelClick: (Channel) -> Unit,
        isHero: Boolean = false
    ): View {
        val view = inflater.inflate(R.layout.item_category_row, null, false)
        val titleView = view.findViewById<TextView>(R.id.categoryTitle)
        val recyclerView = view.findViewById<RecyclerView>(R.id.channelRow)

        titleView.text = title

        recyclerView.layoutManager = LinearLayoutManager(
            context, LinearLayoutManager.HORIZONTAL, false
        )
        recyclerView.setItemViewCacheSize(2) // Memory-conscious cache size
        recyclerView.setHasFixedSize(false)
        recyclerView.adapter = ChannelRowAdapter(channels, onChannelClick, isHero)

        return view
    }

    /**
     * Create a text heading section.
     */
    fun createTextHeading(title: String): View {
        val textView = TextView(context).apply {
            text = title
            setTextAppearance(R.style.TextStyle_Heading)
            val padding = context.resources.getDimensionPixelSize(R.dimen.screen_padding_horizontal)
            setPadding(padding, padding / 2, padding, 0)
        }
        return textView
    }

    /**
     * Create a loading indicator view.
     */
    fun createLoadingView(): View {
        return inflater.inflate(R.layout.view_loading, null, false)
    }

    /**
     * Create an error view with retry callback.
     */
    fun createErrorView(message: String, onRetry: () -> Unit): View {
        val view = inflater.inflate(R.layout.view_error, null, false)
        view.findViewById<TextView>(R.id.errorMessage).text = message
        view.findViewById<View>(R.id.retryButton).setOnClickListener { onRetry() }
        return view
    }

    /**
     * Create an empty state view.
     */
    fun createEmptyView(): View {
        return inflater.inflate(R.layout.view_empty, null, false)
    }

    /**
     * RecyclerView adapter for a horizontal row of channel cards.
     * Memory-efficient: uses ViewHolder pattern, bounded view cache.
     */
    private inner class ChannelRowAdapter(
        private val channels: List<Channel>,
        private val onChannelClick: (Channel) -> Unit,
        private val isHero: Boolean
    ) : RecyclerView.Adapter<ChannelRowAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val logo: ImageView = view.findViewById(R.id.channelLogo)
            val name: TextView = view.findViewById(R.id.channelName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = inflater.inflate(R.layout.item_channel_card, parent, false)
            if (isHero) {
                val params = view.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.width = context.resources.getDimensionPixelSize(R.dimen.hero_card_width)
                params.height = context.resources.getDimensionPixelSize(R.dimen.hero_card_height)
                view.layoutParams = params
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val channel = channels[position]
            holder.name.text = channel.name

            // Load logo asynchronously with bounded cache
            if (channel.logoUrl.isNotEmpty()) {
                imageCache.loadImage(channel.logoUrl, holder.logo)
            } else {
                holder.logo.setImageResource(R.drawable.ic_placeholder)
            }

            holder.itemView.setOnClickListener { onChannelClick(channel) }
            holder.itemView.isFocusable = true
            holder.itemView.isFocusableInTouchMode = true

            // Scale animation on focus for TV feel
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            }
        }

        override fun getItemCount() = channels.size
    }
}
