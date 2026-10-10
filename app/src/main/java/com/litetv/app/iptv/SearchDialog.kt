package com.litetv.app.iptv

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.litetv.app.R
import com.litetv.app.cache.ImageCache

/**
 * TV Remote-friendly search dialog querying [ChannelSearchIndex].
 * Live updates as the user enters characters.
 */
class SearchDialog(
    context: Context,
    private val playlistRepository: PlaylistRepository,
    private val onChannelSelected: (Channel) -> Unit
) : Dialog(context, R.style.Theme_LiteTV) {

    private lateinit var searchInput: EditText
    private lateinit var resultsCount: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var closeButton: Button
    private val imageCache = ImageCache.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_search)

        searchInput = findViewById(R.id.searchInput)
        resultsCount = findViewById(R.id.searchResultsCount)
        recyclerView = findViewById(R.id.searchResultsRecyclerView)
        closeButton = findViewById(R.id.searchCloseButton)

        closeButton.setOnClickListener { dismiss() }

        recyclerView.layoutManager = LinearLayoutManager(context)
        val adapter = SearchResultsAdapter()
        recyclerView.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    adapter.submitList(emptyList())
                    resultsCount.text = "Type a channel or category name to search"
                } else {
                    val results = playlistRepository.searchIndex.search(query)
                    adapter.submitList(results)
                    resultsCount.text = "${results.size} channels found"
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Request focus on search input
        searchInput.requestFocus()
    }

    private inner class SearchResultsAdapter : RecyclerView.Adapter<SearchResultsAdapter.ViewHolder>() {

        private var items: List<Channel> = emptyList()

        fun submitList(newItems: List<Channel>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val logo: ImageView = view.findViewById(R.id.searchResultLogo)
            val name: TextView = view.findViewById(R.id.searchResultName)
            val group: TextView = view.findViewById(R.id.searchResultGroup)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val channel = items[position]
            holder.name.text = channel.name
            holder.group.text = if (channel.group.isNotEmpty()) channel.group else "General"

            if (channel.logoUrl.isNotEmpty()) {
                imageCache.loadImage(channel.logoUrl, holder.logo)
            } else {
                holder.logo.setImageResource(R.drawable.ic_placeholder)
            }

            holder.itemView.setOnClickListener {
                dismiss()
                onChannelSelected(channel)
            }

            holder.itemView.isFocusable = true
            holder.itemView.isFocusableInTouchMode = true
            holder.itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.02f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }

        override fun getItemCount() = items.size
    }
}
