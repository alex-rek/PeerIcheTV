package app.peertube.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLaterSyncTest {
    private fun video(uuid: String) = Video(uuid = uuid, name = uuid)

    private class FakeServer(vararg uuids: String) : WatchLaterRemote {
        var nextId = 100L
        val elements = linkedMapOf<Long, Video>().apply { uuids.forEach { put(size + 1L, Video(uuid = it, name = it)) } }
        val refused = mutableSetOf<String>()
        var offline = false
        var addCalls = 0
        var beforeAdd: () -> Unit = {}
        val uuids get() = elements.values.map { it.uuid }

        private fun checkOnline() { if (offline) throw java.io.IOException("offline") }
        override suspend fun playlistId(): Long { checkOnline(); return 7 }
        override suspend fun fetch(playlistId: Long): List<RemoteWatchLaterItem> { checkOnline(); return elements.map { RemoteWatchLaterItem(it.value, it.key) } }
        override suspend fun add(playlistId: Long, video: Video): Long? {
            checkOnline(); addCalls++; beforeAdd()
            if (video.uuid in refused) return null
            return nextId++.also { elements[it] = video }
        }
        override suspend fun remove(playlistId: Long, elementId: Long) { checkOnline(); elements.remove(elementId) }
    }

    private class Local(var list: WatchLaterList = WatchLaterList()) {
        val rejected = mutableSetOf<String>()
        val update: ((WatchLaterList) -> WatchLaterList) -> WatchLaterList = { transform -> transform(list).also { list = it } }
        val uuids get() = list.items.map { it.video.uuid }
        fun sync(server: WatchLaterRemote) = runBlocking { syncWatchLater(update, server, rejected) }
    }

    @Test
    fun firstSyncMergesBothListsWithoutDuplicates() {
        val server = FakeServer("a", "b")
        val local = Local(WatchLaterList().add(video("b")).add(video("c")))
        local.sync(server)
        assertEquals(setOf("a", "b", "c"), local.uuids.toSet())
        assertEquals(listOf("a", "b", "c"), server.uuids)
        assertEquals(1, server.addCalls)
        assertEquals(0, local.list.pendingChanges)
    }

    @Test
    fun localRemovalReachesTheServer() {
        val server = FakeServer("a", "b")
        val local = Local().apply { sync(server); list = list.remove("a"); sync(server) }
        assertEquals(listOf("b"), server.uuids)
        assertEquals(listOf("b"), local.uuids)
        assertEquals(0, local.list.pendingChanges)
    }

    @Test
    fun changesFromOtherDevicesArrive() {
        val server = FakeServer("a", "b")
        val local = Local().apply { sync(server) }
        server.elements.remove(1L)
        server.elements[50L] = video("z")
        local.sync(server)
        assertEquals(listOf("b", "z"), local.uuids)
    }

    @Test
    fun offlineChangesAreKeptAndSentLater() {
        val server = FakeServer("a")
        val local = Local().apply { sync(server) }
        server.offline = true
        local.list = local.list.add(video("new")).remove("a")
        runCatching { local.sync(server) }
        assertEquals(listOf("new"), local.uuids)
        assertEquals(2, local.list.pendingChanges)
        server.offline = false
        local.sync(server)
        assertEquals(listOf("new"), server.uuids)
        assertEquals(0, local.list.pendingChanges)
    }

    @Test
    fun removingADuplicatedVideoDeletesEveryServerCopy() {
        val server = FakeServer("a", "a")
        val local = Local().apply { sync(server) }
        assertEquals(listOf("a"), local.uuids)
        local.list = local.list.remove("a")
        local.sync(server)
        assertTrue(server.uuids.isEmpty())
    }

    @Test
    fun removalDuringAddIsNotLost() {
        val server = FakeServer()
        val local = Local(WatchLaterList().add(video("a")))
        server.beforeAdd = { local.list = local.list.remove("a").add(video("a")).remove("a") }
        local.sync(server)
        assertFalse("a" in local.list)
        local.sync(server)
        assertTrue(server.uuids.isEmpty())
    }

    @Test
    fun readdingBeforeSyncKeepsTheServerEntry() {
        val server = FakeServer("a")
        val local = Local().apply { sync(server) }
        local.list = local.list.remove("a").add(video("a"))
        assertEquals(0, local.list.pendingChanges)
        local.sync(server)
        assertEquals(listOf("a"), server.uuids)
        assertEquals(0, server.addCalls)
    }

    @Test
    fun refusedVideoStaysLocalAndIsNotRetried() {
        val server = FakeServer().apply { refused += "x" }
        val local = Local(WatchLaterList().add(video("x")))
        local.sync(server)
        local.sync(server)
        assertEquals(listOf("x"), local.uuids)
        assertEquals(1, server.addCalls)
    }
}
