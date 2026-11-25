package app.peertube.tv

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal data class PlaylistsState(
    val ready: Boolean = false,
    val playlists: List<UserPlaylist> = emptyList(),
    val status: WatchLaterStatus = WatchLaterStatus.LOCAL
) {
    operator fun get(id: String) = playlists.firstOrNull { it.id == id }
}

/** Per-account files outside backups, like "Watch later": playlists are private and must not mix between accounts. */
private class PlaylistsStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "playlists")
    private val cache = HashMap<String, UserPlaylists>()
    private val writer = Executors.newSingleThreadExecutor()

    private fun file(scope: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }

    private fun load(scope: String): UserPlaylists = runCatching {
        val text = file(scope).takeIf { it.baseFile.exists() }?.readFully()?.toString(Charsets.UTF_8)
        text?.let { PeerTubeGson.instance.fromJson(it, UserPlaylists::class.java) } ?: UserPlaylists()
    }.onFailure { logFailure("Reading playlists", it) }.getOrDefault(UserPlaylists())

    fun read(scope: String): UserPlaylists = synchronized(cache) { cache.getOrPut(scope) { load(scope) } }

    fun update(scope: String, transform: (UserPlaylists) -> UserPlaylists): UserPlaylists = synchronized(cache) {
        val current = read(scope)
        val next = transform(current)
        if (next != current) {
            cache[scope] = next
            writer.execute { persist(scope) }
        }
        next
    }

    private fun persist(scope: String) {
        val snapshot = synchronized(cache) { cache[scope] } ?: return
        val target = file(scope)
        if (snapshot == UserPlaylists()) { target.delete(); return }
        runCatching {
            directory.mkdirs()
            val stream = target.startWrite()
            try {
                stream.write(PeerTubeGson.instance.toJson(snapshot).toByteArray(Charsets.UTF_8))
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        }.onFailure { logFailure("Saving playlists", it) }
    }
}

/**
 * Ordinary playlists of the active account, or of this device when nobody is signed in. They share the life cycle
 * of [WatchLater]: the same sync passes, sign-in hand-over and sign-out clean-up.
 */
internal object Playlists {
    private const val GUEST_SCOPE = "guest"
    private val rejectedByAccount = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutable = MutableStateFlow(PlaylistsState())
    val state = mutable.asStateFlow()

    @Volatile private var store: PlaylistsStore? = null
    @Volatile private var accounts: PeerTubeAccountManager? = null
    @Volatile private var lastSyncFailed = false

    private val activeScope get() = accounts?.state?.value?.active?.id ?: GUEST_SCOPE

    fun attach(context: Context, manager: PeerTubeAccountManager) {
        store = PlaylistsStore(context)
        accounts = manager
    }

    fun create(context: Context, name: String): String? {
        WatchLater.start(context)
        val title = name.trim().take(120)
        if (title.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        store?.update(activeScope) { it.create(id, title) } ?: return null
        changed()
        return id
    }

    fun delete(context: Context, id: String) {
        WatchLater.start(context)
        store?.update(activeScope) { it.delete(id) }
        changed()
    }

    /** Adds or removes [video]; returns whether it is in the playlist afterwards. */
    fun toggle(context: Context, id: String, video: Video): Boolean {
        WatchLater.start(context)
        val target = store ?: return false
        val updated = target.update(activeScope) { lists -> lists.updateList(id) { if (video.uuid in it) it.remove(video.uuid) else it.add(video) } }
        rejectedByAccount[activeScope]?.remove(video.uuid)
        changed()
        return updated[id]?.list?.contains(video.uuid) == true
    }

    private fun changed() {
        publish()
        WatchLater.requestSync()
    }

    /** The first sign-in takes over this device's playlists, so a later account does not inherit them too. */
    fun moveGuestTo(accountId: String) {
        val target = store ?: return
        val guest = target.read(GUEST_SCOPE)
        if (guest.playlists.isEmpty()) return
        target.update(accountId) { it.absorb(guest) }
        target.update(GUEST_SCOPE) { UserPlaylists() }
    }

    /** Signing out removes the private copy, unless it still holds changes the server has not received. */
    fun forget(accountId: String) {
        rejectedByAccount.remove(accountId)
        store?.update(accountId) { if (it.pendingChanges == 0) UserPlaylists() else it }
    }

    suspend fun sync(accountId: String) {
        val manager = accounts ?: return
        val target = store ?: return
        val rejected = rejectedByAccount.getOrPut(accountId) { java.util.Collections.synchronizedSet(HashSet()) }
        lastSyncFailed = try {
            syncPlaylists({ transform -> target.update(accountId, transform) }, AccountPlaylistsRemote(manager, accountId), rejected) { UUID.randomUUID().toString() }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("Syncing playlists", error)
            true
        }
    }

    fun publish(syncing: Boolean = false) {
        val target = store ?: return
        val accountsState = accounts?.state?.value ?: return
        val account = accountsState.active
        val lists = target.read(account?.id ?: GUEST_SCOPE)
        val status = when {
            account == null -> WatchLaterStatus.LOCAL
            account.needsLogin -> WatchLaterStatus.SIGN_IN
            syncing -> WatchLaterStatus.SYNCING
            lastSyncFailed -> WatchLaterStatus.PENDING
            lists.pendingChanges > 0 -> WatchLaterStatus.PENDING
            else -> WatchLaterStatus.SYNCED
        }
        mutable.value = PlaylistsState(accountsState.ready, lists.playlists, status)
    }
}

private class AccountPlaylistsRemote(private val manager: PeerTubeAccountManager, private val accountId: String) : PlaylistsRemote {
    override suspend fun list() = manager.regularPlaylists(accountId)
    override suspend fun create(name: String) = manager.createPlaylist(accountId, name)
    override suspend fun delete(remoteId: Long) = manager.deletePlaylist(accountId, remoteId)
    override fun videos(remoteId: Long) = object : WatchLaterRemote {
        override suspend fun playlistId() = remoteId
        override suspend fun fetch(playlistId: Long) = manager.watchLaterItems(accountId, playlistId)
        override suspend fun add(playlistId: Long, video: Video) = manager.addToPlaylist(accountId, playlistId, video)
        override suspend fun remove(playlistId: Long, elementId: Long) = manager.removeFromPlaylist(accountId, playlistId, elementId)
    }
}
