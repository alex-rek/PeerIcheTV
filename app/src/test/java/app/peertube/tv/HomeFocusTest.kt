package app.peertube.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeFocusTest {
    private fun videos(vararg ids: String) = ids.map { Video(uuid = it, name = it) }

    @Test fun shownVideoKeepsFocus() {
        assertEquals("b", nearestShownVideo(videos("a", "b", "c"), videos("a", "b", "c"), "b"))
    }

    @Test fun hiddenVideoPassesFocusToTheNextOne() {
        assertEquals("c", nearestShownVideo(videos("a", "b", "c"), videos("a", "c"), "b"))
    }

    @Test fun lastHiddenVideoPassesFocusBack() {
        assertEquals("b", nearestShownVideo(videos("a", "b", "c"), videos("a", "b"), "c"))
    }

    @Test fun unknownVideoHasNoReplacement() {
        assertNull(nearestShownVideo(videos("a"), videos("a"), "x"))
        assertNull(nearestShownVideo(videos("a"), emptyList(), "a"))
    }
}
