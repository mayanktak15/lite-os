package com.litetv.app.iptv

/**
 * Represents a single TV channel parsed from an M3U playlist.
 * Kept as a simple data class — no Room entity, no Parcelable.
 * Channels are held in-memory in a bounded list (max 5000).
 */
data class Channel(
    val id: String,            // Unique identifier (tvg-id or generated)
    val name: String,          // Display name
    val logoUrl: String,       // Channel logo URL (may be empty)
    val group: String,         // Category/group (e.g., "News", "Sports")
    val streamUrl: String      // HLS/stream URL
) {
    companion object {
        /** Maximum number of channels to keep in memory */
        const val MAX_CHANNELS = 5000
    }
}
