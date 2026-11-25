package app.peertube.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class WatchLaterStatus { LOCAL, SYNCING, SYNCED, PENDING, PARTIAL, SIGN_IN }

internal data class WatchLaterState(
    val ready: Boolean = false,
    val videos: List<Video> = emptyList(),
    val status: WatchLaterStatus = WatchLaterStatus.LOCAL
) {
    operator fun contains(uuid: String?) = uuid != null && videos.any { it.uuid == uuid }
}

/** Per-account files outside backups: the list is private and must not mix between accounts. */
private class WatchLaterStore(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "watch-later")
    private val cache = HashMap<String, WatchLaterList>()
    private val writer = Executors.newSingleThreadExecutor()

    private fun file(scope: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }

    private fun load(scope: String): WatchLaterList = runCatching {
        val text = file(scope).takeIf { it.baseFile.exists() }?.readFully()?.toString(Charsets.UTF_8)
        text?.let { PeerTubeGson.instance.fromJson(it, WatchLaterList::class.java) } ?: WatchLaterList()
    }.onFailure { logFailure("Reading watch later list", it) }.getOrDefault(WatchLaterList())

    fun read(scope: String): WatchLaterList = synchronized(cache) { cache.getOrPut(scope) { load(scope) } }

    fun update(scope: String, transform: (WatchLaterList) -> WatchLaterList): WatchLaterList = synchronized(cache) {
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
        if (snapshot == WatchLaterList()) { target.delete(); return }
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
        }.onFailure { logFailure("Saving watch later list", it) }
    }
}

/**
 * "Watch later" for the active account, or for this device when nobody is signed in. Changes apply
 * locally at once and reach the server on the next sync: after a change, on app resume, periodically
 * while the home screen is open, and when the network comes back.
 */
internal object WatchLater {
    private const val GUEST_SCOPE = "guest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private val rejectedByAccount = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutable = MutableStateFlow(WatchLaterState())
    val state = mutable.asStateFlow()

    @Volatile private var store: WatchLaterStore? = null
    @Volatile private var accounts: PeerTubeAccountManager? = null
    @Volatile private var lastSyncFailed = false

    private val activeAccount get() = accounts?.state?.value?.active
    private val activeScope get() = activeAccount?.id ?: GUEST_SCOPE

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        store = WatchLaterStore(appContext)
        val manager = PeerTubeAccounts.get(appContext)
        accounts = manager
        Playlists.attach(appContext, manager)
        scope.launch { for (ignored in syncRequests) { delay(800); runSync() } }
        scope.launch {
            runCatching { manager.initialize() }
            var knownIds: Set<String>? = null
            manager.state.collect { accountsState ->
                if (!accountsState.ready) return@collect
                val ids = accountsState.accounts.mapTo(HashSet()) { it.id }
                knownIds?.minus(ids)?.forEach(::forgetSignedOutAccount)
                knownIds = ids
                accountsState.active?.takeUnless { it.needsLogin }?.let { moveGuestListTo(it.id); Playlists.moveGuestTo(it.id) }
                lastSyncFailed = false
                publish()
                requestSync()
            }
        }
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = requestSync()
                }
            )
        }.onFailure { logFailure("Watching network state", it) }
    }

    fun requestSync() { syncRequests.trySend(Unit) }

    /** Adds or removes [video] for the current account; returns whether it is in the list afterwards. */
    fun toggle(context: Context, video: Video): Boolean {
        start(context)
        val target = store ?: return false
        if (!state.value.ready) return video.uuid in state.value
        val updated = target.update(activeScope) { if (video.uuid in it) it.remove(video.uuid) else it.add(video) }
        rejectedByAccount[activeScope]?.remove(video.uuid)
        publish()
        requestSync()
        return video.uuid in updated
    }

    /** The first sign-in takes over this device's list, so a later account does not inherit it too. */
    private fun moveGuestListTo(accountId: String) {
        val target = store ?: return
        val guest = target.read(GUEST_SCOPE)
        if (guest.items.isEmpty()) return
        target.update(accountId) { list -> guest.items.fold(list) { merged, item -> merged.add(item.video) } }
        target.update(GUEST_SCOPE) { WatchLaterList() }
    }

    /** Signing out removes the private copy, unless it still holds changes the server has not received. */
    private fun forgetSignedOutAccount(accountId: String) {
        rejectedByAccount.remove(accountId)
        store?.update(accountId) { if (it.pendingChanges == 0) WatchLaterList() else it }
        Playlists.forget(accountId)
    }

    private suspend fun runSync() {
        val manager = accounts ?: return
        val target = store ?: return
        val account = activeAccount?.takeUnless { it.needsLogin } ?: return publish()
        publish(syncing = true)
        val rejected = rejectedByAccount.getOrPut(account.id) { java.util.Collections.synchronizedSet(HashSet()) }
        lastSyncFailed = try {
            syncWatchLater({ transform -> target.update(account.id, transform) }, AccountWatchLaterRemote(manager, account.id), rejected)
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logFailure("Syncing watch later", error)
            true
        }
        publish()
        // Ordinary playlists follow in the same pass, so they share every trigger of "Watch later".
        Playlists.publish(syncing = true)
        Playlists.sync(account.id)
        Playlists.publish()
    }

    private fun publish(syncing: Boolean = false) {
        Playlists.publish()
        val target = store ?: return
        val accountsState = accounts?.state?.value ?: return
        val account = accountsState.active
        val list = target.read(account?.id ?: GUEST_SCOPE)
        val rejected = account?.let { rejectedByAccount[it.id] }.orEmpty()
        val status = when {
            account == null -> WatchLaterStatus.LOCAL
            account.needsLogin -> WatchLaterStatus.SIGN_IN
            syncing -> WatchLaterStatus.SYNCING
            lastSyncFailed -> WatchLaterStatus.PENDING
            list.items.any { it.isPendingAdd && it.video.uuid in rejected } -> WatchLaterStatus.PARTIAL
            list.pendingChanges > 0 -> WatchLaterStatus.PENDING
            else -> WatchLaterStatus.SYNCED
        }
        mutable.value = WatchLaterState(accountsState.ready, list.items.map { it.video }, status)
    }
}

private class AccountWatchLaterRemote(private val manager: PeerTubeAccountManager, private val accountId: String) : WatchLaterRemote {
    override suspend fun playlistId() = manager.watchLaterPlaylistId(accountId)
    override suspend fun fetch(playlistId: Long) = manager.watchLaterItems(accountId, playlistId)
    override suspend fun add(playlistId: Long, video: Video) = manager.addToPlaylist(accountId, playlistId, video)
    override suspend fun remove(playlistId: Long, elementId: Long) = manager.removeFromPlaylist(accountId, playlistId, elementId)
}
