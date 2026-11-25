package app.peertube.tv

import java.util.Locale

/**
 * Ordinary playlists, synced with the signed-in PeerTube account the same way as "Watch later" (ported from PeerIche):
 * each local playlist is linked to one server playlist, and the videos of each pair are merged with [syncWatchLater].
 * Playlists made here go to the server as private playlists, so nothing is published.
 */
@androidx.annotation.Keep
internal data class UserPlaylist(
    val id: String,
    val name: String,
    /** The linked server playlist; null until it exists there. */
    val remoteId: Long? = null,
    val list: WatchLaterList = WatchLaterList()
)

@androidx.annotation.Keep
internal data class UserPlaylists(
    val playlists: List<UserPlaylist> = emptyList(),
    /** Server playlists deleted here that the server has not deleted yet. */
    val deletedRemote: List<Long> = emptyList()
) {
    operator fun get(id: String) = playlists.firstOrNull { it.id == id }
    val pendingChanges get() = deletedRemote.size + playlists.sumOf { (if (it.remoteId == null) 1 else 0) + it.list.pendingChanges }

    fun create(id: String, name: String): UserPlaylists = copy(playlists = playlists + UserPlaylist(id, name.trim()))

    /** Deleting a linked playlist queues its deletion on the server. */
    fun delete(id: String): UserPlaylists {
        val playlist = get(id) ?: return this
        return copy(playlists = playlists - playlist, deletedRemote = deletedRemote + listOfNotNull(playlist.remoteId))
    }

    fun updateList(id: String, transform: (WatchLaterList) -> WatchLaterList): UserPlaylists =
        copy(playlists = playlists.map { if (it.id == id) it.copy(list = transform(it.list)) else it })

    fun link(id: String, remoteId: Long): UserPlaylists =
        if (get(id) == null) copy(deletedRemote = deletedRemote + remoteId)
        else copy(playlists = playlists.map { if (it.id == id) it.copy(remoteId = remoteId) else it })

    /** Another list (this device's, before sign-in) joins this one; playlists with the same name share their videos. */
    fun absorb(other: UserPlaylists): UserPlaylists = other.playlists.fold(this) { merged, incoming ->
        val match = merged.playlists.firstOrNull { playlistName(it.name) == playlistName(incoming.name) }
        if (match == null) merged.copy(playlists = merged.playlists + incoming.copy(remoteId = null, list = WatchLaterList().let { list -> incoming.list.items.fold(list) { l, item -> l.add(item.video) } }))
        else incoming.list.items.fold(merged) { l, item -> l.updateList(match.id) { it.add(item.video) } }
    }
}

internal data class RemotePlaylist(val id: Long, val name: String)

internal data class PlaylistPlan(
    /** Local playlists to create on the server. */
    val create: List<String> = emptyList(),
    /** Local playlists that match an unlinked server playlist by name. */
    val link: Map<String, Long> = emptyMap(),
    /** Server playlists to create here. */
    val download: List<RemotePlaylist> = emptyList(),
    /** Linked playlists renamed on the server: the server name wins. */
    val rename: Map<String, String> = emptyMap(),
    /** Linked playlists deleted on the server. */
    val deleteLocal: List<String> = emptyList(),
    /** Playlists deleted here that still exist on the server. */
    val deleteRemote: List<Long> = emptyList(),
    /** Pending deletions the server has already done. */
    val dropDeleted: List<Long> = emptyList()
)

private fun playlistName(value: String) = value.trim().lowercase(Locale.ROOT)

/** [remote] must be every regular playlist of the account, or missing ones would look deleted. */
internal fun planPlaylistSync(local: UserPlaylists, remote: List<RemotePlaylist>): PlaylistPlan {
    val remoteById = remote.associateBy { it.id }
    val deleteRemote = local.deletedRemote.filter { it in remoteById }
    val dropDeleted = local.deletedRemote.filterNot { it in remoteById }
    val claimed = (local.playlists.mapNotNull { it.remoteId } + local.deletedRemote).toMutableSet()
    val create = mutableListOf<String>()
    val link = linkedMapOf<String, Long>()
    val rename = linkedMapOf<String, String>()
    val deleteLocal = mutableListOf<String>()
    for (playlist in local.playlists) {
        val remoteId = playlist.remoteId
        if (remoteId != null) {
            val server = remoteById[remoteId]
            when {
                server == null -> deleteLocal += playlist.id
                server.name != playlist.name -> rename[playlist.id] = server.name
            }
        } else {
            val match = remote.firstOrNull { it.id !in claimed && playlistName(it.name) == playlistName(playlist.name) }
            if (match != null) { link[playlist.id] = match.id; claimed += match.id } else create += playlist.id
        }
    }
    val download = remote.filter { it.id !in claimed }
    return PlaylistPlan(create, link, download, rename, deleteLocal, deleteRemote, dropDeleted)
}

internal interface PlaylistsRemote {
    suspend fun list(): List<RemotePlaylist>
    suspend fun create(name: String): Long
    suspend fun delete(remoteId: Long)
    /** The videos of one server playlist, as for "Watch later". */
    fun videos(remoteId: Long): WatchLaterRemote
}

/**
 * Brings playlists in step with the account, then merges each linked pair's videos. [newId] names playlists
 * downloaded from the server. A failure aborts the pass and leaves unsent changes queued.
 */
internal suspend fun syncPlaylists(
    update: ((UserPlaylists) -> UserPlaylists) -> UserPlaylists,
    remote: PlaylistsRemote,
    rejected: MutableSet<String>,
    newId: () -> String
) {
    val plan = planPlaylistSync(update { it }, remote.list())
    plan.deleteRemote.forEach { remoteId ->
        remote.delete(remoteId)
        update { it.copy(deletedRemote = it.deletedRemote - remoteId) }
    }
    update { current ->
        var next = current.copy(deletedRemote = current.deletedRemote - plan.dropDeleted.toSet())
        next = next.copy(playlists = next.playlists.filterNot { it.id in plan.deleteLocal && it.remoteId != null }
            .map { playlist -> plan.rename[playlist.id]?.let { playlist.copy(name = it) } ?: playlist })
        plan.link.forEach { (id, remoteId) -> next = next.link(id, remoteId) }
        plan.download.forEach { server -> next = next.copy(playlists = next.playlists + UserPlaylist(newId(), server.name, server.id)) }
        next
    }
    plan.create.forEach { id ->
        val playlist = update { it }[id] ?: return@forEach
        if (playlist.remoteId != null) return@forEach
        val remoteId = remote.create(playlist.name)
        update { it.link(id, remoteId) }
    }
    update { it }.playlists.filter { it.remoteId != null }.forEach { playlist ->
        val remoteId = playlist.remoteId ?: return@forEach
        syncWatchLater(
            { transform -> update { it.updateList(playlist.id, transform) }[playlist.id]?.list ?: WatchLaterList() },
            remote.videos(remoteId), rejected
        )
    }
}
