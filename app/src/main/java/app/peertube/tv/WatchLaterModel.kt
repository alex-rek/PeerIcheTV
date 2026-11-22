package app.peertube.tv

/** A video in "Watch later". No [elementIds] means the server does not have it yet. */
@androidx.annotation.Keep
internal data class WatchLaterItem(val video: Video, val elementIds: List<Long> = emptyList()) {
    val isPendingAdd get() = elementIds.isEmpty()
}

/** A local removal that the server has not confirmed yet. */
@androidx.annotation.Keep
internal data class WatchLaterRemoval(val uuid: String, val elementIds: List<Long>)

internal data class RemoteWatchLaterItem(val video: Video, val elementId: Long)

/**
 * One account's list plus its unsent changes. Every operation is pure, so a change made while a sync
 * is on the network is merged with the server's answer instead of being overwritten by it.
 */
@androidx.annotation.Keep
internal data class WatchLaterList(
    val items: List<WatchLaterItem> = emptyList(),
    val removals: List<WatchLaterRemoval> = emptyList()
) {
    val pendingChanges get() = items.count { it.isPendingAdd } + removals.size

    operator fun contains(uuid: String) = items.any { it.video.uuid == uuid }

    fun add(video: Video): WatchLaterList {
        if (video.uuid in this) return this
        val removed = removals.firstOrNull { it.uuid == video.uuid }
        return copy(
            items = items + WatchLaterItem(video.compactForStorage(), removed?.elementIds.orEmpty()),
            removals = removals.filterNot { it.uuid == video.uuid }
        )
    }

    fun remove(uuid: String): WatchLaterList {
        val item = items.firstOrNull { it.video.uuid == uuid } ?: return this
        return copy(
            items = items - item,
            removals = if (item.isPendingAdd) removals else removals + WatchLaterRemoval(uuid, item.elementIds)
        )
    }

    /** Merges the server's full list: unsent local changes win, everything else follows the server. */
    fun mergeRemote(remote: List<RemoteWatchLaterItem>): WatchLaterList {
        val remoteById = remote.groupBy { it.video.uuid }
        val removedIds = removals.mapTo(HashSet()) { it.uuid }
        val fromServer = remoteById.filterKeys { it !in removedIds }.values.map { copies ->
            WatchLaterItem(copies.first().video.compactForStorage(), copies.map { it.elementId })
        }
        val unsent = items.filter { it.isPendingAdd && it.video.uuid !in remoteById }
        val stillOnServer = removals.mapNotNull { removal ->
            remoteById[removal.uuid]?.let { copies -> removal.copy(elementIds = copies.map { it.elementId }) }
        }
        return WatchLaterList(fromServer + unsent, stillOnServer)
    }

    /** Records the server's id for an added video, or queues its removal if the user removed it meanwhile. */
    fun confirmAdded(uuid: String, elementId: Long): WatchLaterList =
        if (uuid in this) copy(items = items.map { if (it.video.uuid == uuid) it.copy(elementIds = it.elementIds + elementId) else it })
        else copy(removals = removals.filterNot { it.uuid == uuid } + WatchLaterRemoval(uuid, removals.firstOrNull { it.uuid == uuid }?.elementIds.orEmpty() + elementId))

    fun confirmRemoved(uuid: String, elementId: Long): WatchLaterList = copy(
        removals = removals.mapNotNull { removal ->
            if (removal.uuid != uuid) removal
            else removal.copy(elementIds = removal.elementIds - elementId).takeIf { it.elementIds.isNotEmpty() }
        },
        // The video may have been added back while its old server entry was being deleted.
        items = items.map { if (it.video.uuid == uuid) it.copy(elementIds = it.elementIds - elementId) else it }
    )
}

internal interface WatchLaterRemote {
    suspend fun playlistId(): Long
    suspend fun fetch(playlistId: Long): List<RemoteWatchLaterItem>
    /** Returns the new element id, or null when the server refuses this video for good. */
    suspend fun add(playlistId: Long, video: Video): Long?
    suspend fun remove(playlistId: Long, elementId: Long)
}

/**
 * Pull, merge, then push. A network failure aborts the run and leaves the unsent changes queued.
 * Videos the server refused are remembered in [rejected] so they are not retried on every run.
 */
internal suspend fun syncWatchLater(
    update: ((WatchLaterList) -> WatchLaterList) -> WatchLaterList,
    remote: WatchLaterRemote,
    rejected: MutableSet<String>
) {
    val playlistId = remote.playlistId()
    val fetched = remote.fetch(playlistId)
    val merged = update { it.mergeRemote(fetched) }
    merged.removals.forEach { removal ->
        removal.elementIds.forEach { elementId ->
            remote.remove(playlistId, elementId)
            update { it.confirmRemoved(removal.uuid, elementId) }
        }
    }
    merged.items.filter { it.isPendingAdd && it.video.uuid !in rejected }.forEach { item ->
        val current = update { it }.items.firstOrNull { it.video.uuid == item.video.uuid }
        if (current?.isPendingAdd == true) {
            val elementId = remote.add(playlistId, item.video)
            if (elementId == null) rejected += item.video.uuid
            else update { it.confirmAdded(item.video.uuid, elementId) }
        }
    }
}
