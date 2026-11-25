package app.peertube.tv

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSyncTest {
    private fun video(uuid: String) = Video(uuid = uuid, name = uuid)

    @Test fun newLocalPlaylistIsCreatedUnlessTheServerHasOneWithTheSameName() {
        val local = UserPlaylists().create("a", "Music").create("b", "Films")
        val plan = planPlaylistSync(local, listOf(RemotePlaylist(7, "music ")))
        assertEquals(mapOf("a" to 7L), plan.link)
        assertEquals(listOf("b"), plan.create)
        assertTrue(plan.download.isEmpty())
    }

    @Test fun serverPlaylistsAppearHereAndRenamesFollowTheServer() {
        val local = UserPlaylists(listOf(UserPlaylist("a", "Old", remoteId = 1)))
        val plan = planPlaylistSync(local, listOf(RemotePlaylist(1, "New"), RemotePlaylist(2, "Other")))
        assertEquals(mapOf("a" to "New"), plan.rename)
        assertEquals(listOf(RemotePlaylist(2, "Other")), plan.download)
    }

    @Test fun playlistDeletedOnTheServerIsDeletedHere() {
        val local = UserPlaylists(listOf(UserPlaylist("a", "Gone", remoteId = 1), UserPlaylist("b", "New")))
        val plan = planPlaylistSync(local, emptyList())
        assertEquals(listOf("a"), plan.deleteLocal)
        assertEquals(listOf("b"), plan.create)
    }

    @Test fun deletionHereReachesTheServerAndIsNotDownloadedBack() {
        val local = UserPlaylists(listOf(UserPlaylist("a", "X", remoteId = 1))).delete("a")
        assertEquals(listOf(1L), local.deletedRemote)
        val plan = planPlaylistSync(local, listOf(RemotePlaylist(1, "X")))
        assertEquals(listOf(1L), plan.deleteRemote)
        assertTrue(plan.download.isEmpty())
        assertEquals(listOf(1L), planPlaylistSync(local, emptyList()).dropDeleted)
    }

    @Test fun deviceListJoinsTheAccountMatchingByName() {
        val device = UserPlaylists().create("d", "Music").updateList("d") { it.add(video("v1")) }.create("e", "Kids")
        val account = UserPlaylists(listOf(UserPlaylist("a", "music", remoteId = 3)))
        val merged = account.absorb(device)
        assertEquals(listOf("a", "e"), merged.playlists.map { it.id })
        assertTrue("v1" in merged["a"]!!.list)
        assertNull(merged["e"]!!.remoteId)
    }

    private class FakeServer : PlaylistsRemote {
        var nextId = 100L
        val playlists = linkedMapOf<Long, String>()
        val videos = hashMapOf<Long, LinkedHashMap<Long, Video>>()
        override suspend fun list() = playlists.map { RemotePlaylist(it.key, it.value) }
        override suspend fun create(name: String): Long = nextId++.also { playlists[it] = name; videos[it] = linkedMapOf() }
        override suspend fun delete(remoteId: Long) { playlists.remove(remoteId); videos.remove(remoteId) }
        override fun videos(remoteId: Long) = object : WatchLaterRemote {
            override suspend fun playlistId() = remoteId
            override suspend fun fetch(playlistId: Long) = videos.getValue(playlistId).map { RemoteWatchLaterItem(it.value, it.key) }
            override suspend fun add(playlistId: Long, video: Video) = nextId++.also { videos.getValue(playlistId)[it] = video }
            override suspend fun remove(playlistId: Long, elementId: Long) { videos[playlistId]?.remove(elementId) }
        }
    }

    @Test fun fullPassCreatesPlaylistsAndSendsTheirVideos() = runBlocking {
        val server = FakeServer().apply { create("From site").also { videos.getValue(it)[1] = video("s1") } }
        var local = UserPlaylists().create("a", "Mine").updateList("a") { it.add(video("v1")) }
        var n = 0
        syncPlaylists({ t -> t(local).also { local = it } }, server, mutableSetOf()) { "dl${n++}" }
        assertEquals(setOf("From site", "Mine"), server.playlists.values.toSet())
        val mine = local.playlists.first { it.name == "Mine" }
        assertEquals(listOf("v1"), server.videos.getValue(mine.remoteId!!).values.map { it.uuid })
        assertEquals(0, mine.list.pendingChanges)
        val fromSite = local.playlists.first { it.name == "From site" }
        assertEquals(listOf("s1"), fromSite.list.items.map { it.video.uuid })
        assertEquals(0, local.pendingChanges)
    }

    @Test fun deletingHereDeletesOnTheServerOnTheNextPass() = runBlocking {
        val server = FakeServer()
        var local = UserPlaylists().create("a", "Temp")
        syncPlaylists({ t -> t(local).also { local = it } }, server, mutableSetOf()) { "x" }
        assertEquals(1, server.playlists.size)
        local = local.delete("a")
        syncPlaylists({ t -> t(local).also { local = it } }, server, mutableSetOf()) { "x" }
        assertTrue(server.playlists.isEmpty())
        assertTrue(local.playlists.isEmpty() && local.deletedRemote.isEmpty())
    }
}
