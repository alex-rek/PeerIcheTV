package app.peertube.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as PeerIche's HistorySyncTest, so both apps merge server history alike. */
class HistorySyncTest {
    @Test fun firstPassAddsServerHistoryBelowWithoutReordering() {
        val plan = planHistoryImport(listOf("a", "b", "c"), local = setOf("b"), previousNewest = null, pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty())
        assertEquals(listOf("a", "c"), plan.bottom)
        assertEquals("a", plan.newest)
    }

    @Test fun videosWatchedElsewhereSinceLastPassGoToTop() {
        val plan = planHistoryImport(listOf("new2", "new1", "old", "older"), local = setOf("new1", "old"), previousNewest = "old", pendingRemoval = emptySet())
        assertEquals(listOf("new2", "new1"), plan.top)
        assertEquals(listOf("older"), plan.bottom)
        assertEquals("new2", plan.newest)
    }

    @Test fun nothingNewWhenServerTopIsUnchanged() {
        val plan = planHistoryImport(listOf("old", "older"), local = setOf("old", "older"), previousNewest = "old", pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty() && plan.bottom.isEmpty())
    }

    @Test fun removalsNotYetOnServerAreNotBroughtBack() {
        val plan = planHistoryImport(listOf("removed", "kept"), local = emptySet(), previousNewest = null, pendingRemoval = setOf("removed"))
        assertEquals(listOf("kept"), plan.bottom)
        assertEquals("kept", plan.newest)
    }

    @Test fun lostMarkerDoesNotReorder() {
        val plan = planHistoryImport(listOf("x", "y"), local = setOf("y"), previousNewest = "gone", pendingRemoval = emptySet())
        assertTrue(plan.top.isEmpty())
        assertEquals(listOf("x"), plan.bottom)
    }

    @Test fun videoRemovedOnServerIsRemovedHere() {
        val plan = planHistoryImport(listOf("kept"), local = setOf("kept", "gone", "tvOnly"), previousNewest = "kept",
            pendingRemoval = emptySet(), seen = setOf("kept", "gone"), complete = true)
        assertEquals(listOf("gone"), plan.removeLocal)
        assertEquals(setOf("kept"), plan.seen)
    }

    @Test fun partialServerListNeverRemovesAnything() {
        val plan = planHistoryImport(listOf("kept"), local = setOf("kept", "older"), previousNewest = "kept",
            pendingRemoval = emptySet(), seen = setOf("kept", "older"), complete = false)
        assertTrue(plan.removeLocal.isEmpty())
        assertEquals(setOf("kept", "older"), plan.seen)
    }

    @Test fun clearedServerHistoryRemovesOnlyWhatWasThere() {
        val plan = planHistoryImport(emptyList(), local = setOf("synced", "tvOnly"), previousNewest = "synced",
            pendingRemoval = emptySet(), seen = setOf("synced"), complete = true)
        assertEquals(listOf("synced"), plan.removeLocal)
        assertEquals(null, plan.newest)
    }

    @Test fun onlyTheNewestEntriesAreAdded() {
        val plan = planHistoryImport(listOf("a", "b", "c"), local = emptySet(), previousNewest = null,
            pendingRemoval = emptySet(), complete = true, addLimit = 2)
        assertEquals(listOf("a", "b"), plan.bottom)
        assertEquals(setOf("a", "b", "c"), plan.seen)
    }

    @Test fun beforeDateIsUtcIso() {
        assertEquals("1970-01-01T00:00:01.500Z", historyBeforeDate(1_500))
    }
}
