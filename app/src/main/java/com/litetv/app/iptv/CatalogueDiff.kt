package com.litetv.app.iptv

/**
 * Details of a channel rename event.
 */
data class RenamedChannel(
    val oldName: String,
    val newName: String,
    val channel: Channel
)

/**
 * Details of a channel category / group migration.
 */
data class CategoryChangedChannel(
    val oldCategory: String,
    val newCategory: String,
    val channel: Channel
)

/**
 * Details of a channel metadata update (e.g., logo URL change).
 */
data class MetadataUpdatedChannel(
    val oldLogoUrl: String,
    val newLogoUrl: String,
    val channel: Channel
)

/**
 * Represents the granular difference between two channel catalogues.
 */
data class CatalogueDiff(
    val added: List<Channel> = emptyList(),
    val removed: List<Channel> = emptyList(),
    val renamed: List<RenamedChannel> = emptyList(),
    val categoryChanged: List<CategoryChangedChannel> = emptyList(),
    val metadataUpdated: List<MetadataUpdatedChannel> = emptyList()
) {
    /** True if any channel was added, removed, renamed, recategorized, or updated */
    val hasChanges: Boolean
        get() = added.isNotEmpty() ||
                removed.isNotEmpty() ||
                renamed.isNotEmpty() ||
                categoryChanged.isNotEmpty() ||
                metadataUpdated.isNotEmpty()

    /** Human-readable summary for diagnostics */
    val summary: String
        get() = buildString {
            append("+${added.size} added, -${removed.size} removed, ")
            append("${renamed.size} renamed, ${categoryChanged.size} recategorized, ")
            append("${metadataUpdated.size} updated")
        }

    companion object {
        /**
         * Compute the diff between [oldList] and [newList].
         * Channels are matched primarily by streamUrl (canonical stream identity) or id.
         */
        fun compute(oldList: List<Channel>, newList: List<Channel>): CatalogueDiff {
            if (oldList.isEmpty() && newList.isEmpty()) {
                return CatalogueDiff()
            }
            if (oldList.isEmpty()) {
                return CatalogueDiff(added = newList)
            }
            if (newList.isEmpty()) {
                return CatalogueDiff(removed = oldList)
            }

            val oldByUrl = oldList.associateBy { it.streamUrl }
            val newByUrl = newList.associateBy { it.streamUrl }

            val added = mutableListOf<Channel>()
            val removed = mutableListOf<Channel>()
            val renamed = mutableListOf<RenamedChannel>()
            val categoryChanged = mutableListOf<CategoryChangedChannel>()
            val metadataUpdated = mutableListOf<MetadataUpdatedChannel>()

            for (newCh in newList) {
                val oldCh = oldByUrl[newCh.streamUrl]
                if (oldCh == null) {
                    added.add(newCh)
                } else {
                    if (oldCh.name != newCh.name) {
                        renamed.add(RenamedChannel(oldCh.name, newCh.name, newCh))
                    }
                    if (oldCh.group != newCh.group) {
                        categoryChanged.add(CategoryChangedChannel(oldCh.group, newCh.group, newCh))
                    }
                    if (oldCh.logoUrl != newCh.logoUrl) {
                        metadataUpdated.add(MetadataUpdatedChannel(oldCh.logoUrl, newCh.logoUrl, newCh))
                    }
                }
            }

            for (oldCh in oldList) {
                if (!newByUrl.containsKey(oldCh.streamUrl)) {
                    removed.add(oldCh)
                }
            }

            return CatalogueDiff(
                added = added,
                removed = removed,
                renamed = renamed,
                categoryChanged = categoryChanged,
                metadataUpdated = metadataUpdated
            )
        }
    }
}
