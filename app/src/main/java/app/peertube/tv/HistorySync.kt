package app.peertube.tv

import android.content.Context

/** How server history (newest first) enters the local list. Keys are lower-case video UUIDs. */
internal data class HistoryImport(
    /** Watched on another device since the last pass: moved to the top, in this order. */
    val top: List<String> = emptyList(),
    /** Older server entries missing here: added below the local list, in this order. */
    val bottom: List<String> = emptyList(),
    /** The newest server entry, remembered to recognise newer ones next time. */
    val newest: String? = null,
    /** Removed on the server (or on another device) since they were last seen there: removed here too. */
    val removeLocal: List<String> = emptyList(),
    /** Server entries to remember for the next pass. */
    val seen: Set<String> = emptySet()
)

/**
 * Ported from PeerIche. PeerTube history has no view dates, only its order. Entries above the one that was
 * newest at the last pass are new and go to the top; on the first pass, or when that entry is gone, nothing is
 * reordered. Only [addLimit] newest entries are added. A video that was in server history before ([seen]) and is
 * missing now was removed there; this is decided only when [complete] is true, since a partial list would make
 * older entries look removed.
 */
internal fun planHistoryImport(remote: List<String>, local: Set<String>, previousNewest: String?, pendingRemoval: Set<String>,
    seen: Set<String> = emptySet(), complete: Boolean = false, addLimit: Int = Int.MAX_VALUE): HistoryImport {
    val server = remote.filterNot { it in pendingRemoval }.distinct()
    val recent = server.take(addLimit)
    val mark = previousNewest?.let(recent::indexOf) ?: -1
    val top = if (mark > 0) recent.take(mark) else emptyList()
    val bottom = recent.filter { it !in local && it !in top }
    val present = server.toSet()
    val removeLocal = if (complete) local.filter { it in seen && it !in present } else emptyList()
    val nextSeen = if (complete) present else seen + present
    return HistoryImport(top, bottom, server.firstOrNull() ?: previousNewest.takeIf { !complete }, removeLocal, nextSeen)
}

/**
 * Per-account bookkeeping for history sync, kept apart from settings backups: the newest server entry and the
 * entries seen there at the last pass, and removals or a clear made here that have not reached the server yet.
 */
internal object HistorySyncState {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("history_sync", Context.MODE_PRIVATE)
    private fun newestKey(account: String) = "newest:$account"
    private fun seenKey(account: String) = "seen:$account"
    private fun removalsKey(account: String) = "removals:$account"
    private fun clearKey(account: String) = "clear:$account"

    fun newest(context: Context, account: String): String? = prefs(context).getString(newestKey(account), null)
    fun seen(context: Context, account: String): Set<String> = prefs(context).getStringSet(seenKey(account), null).orEmpty().toSet()
    fun removals(context: Context, account: String): Set<String> = prefs(context).getStringSet(removalsKey(account), null).orEmpty().toSet()
    fun clearRequestedAt(context: Context, account: String): Long = prefs(context).getLong(clearKey(account), 0)

    @Synchronized fun queueRemoval(context: Context, account: String, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        prefs(context).edit().putStringSet(removalsKey(account), removals(context, account) + uuids.map { it.lowercase() }).apply()
    }
    @Synchronized fun removalSent(context: Context, account: String, uuid: String) {
        prefs(context).edit().putStringSet(removalsKey(account), removals(context, account) - uuid).apply()
    }
    /** A clear replaces pending removals and forgets the order mark. */
    @Synchronized fun queueClear(context: Context, account: String, at: Long) {
        prefs(context).edit().putLong(clearKey(account), at).remove(removalsKey(account)).remove(newestKey(account)).apply()
    }
    @Synchronized fun clearSent(context: Context, account: String, at: Long) {
        if (clearRequestedAt(context, account) == at) prefs(context).edit().remove(clearKey(account)).apply()
    }
    /** A view sent from here is the newest server entry, so it is not mistaken for another device's. */
    @Synchronized fun noteView(context: Context, account: String, uuid: String) {
        val key = uuid.lowercase()
        prefs(context).edit().putString(newestKey(account), key).putStringSet(seenKey(account), seen(context, account) + key).apply()
    }
    @Synchronized fun savePass(context: Context, account: String, newest: String?, seen: Set<String>) {
        prefs(context).edit().apply { if (newest == null) remove(newestKey(account)) else putString(newestKey(account), newest) }
            .putStringSet(seenKey(account), seen).apply()
    }
}

/** ISO 8601 UTC date for PeerTube's history "beforeDate". */
internal fun historyBeforeDate(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(millis))
