package app.peertube.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSpeechTest {
    private val en = SpeechCue(1_000, 3_000, "Hello", "en")
    private val ru = SpeechCue(1_000, 3_000, "Привет", "ru")

    @Test
    fun speechIsOffByDefault() {
        assertFalse(SubtitleSpeechSettings().enabled)
    }

    @Test
    fun automaticModeSpeaksTheTranslation() {
        assertEquals(listOf(SpeechPart("Привет", "ru")), speechParts("auto", en, ru, "ru", "original"))
    }

    @Test
    fun originalInTargetLanguageNeedsNoTranslation() {
        assertEquals(listOf(SpeechPart("Привет", "ru")), speechParts("translated", ru, null, "ru", "skip"))
    }

    @Test
    fun fallbackDecidesWhatHappensWithoutTranslation() {
        assertEquals(listOf(SpeechPart("Hello", "en")), speechParts("auto", en, null, "ru", "original"))
        assertTrue(speechParts("auto", en, null, "ru", "skip").isEmpty())
    }

    @Test
    fun bothModeSpeaksOriginalThenTranslation() {
        assertEquals(listOf(SpeechPart("Hello", "en"), SpeechPart("Привет", "ru")), speechParts("both", en, ru, "ru", "original"))
    }

    @Test
    fun currentCueJoinsCuesStartingTogether() {
        val cues = listOf(en, SpeechCue(1_000, 2_500, "world", "en"), SpeechCue(5_000, 6_000, "Later", "en"))
        val cue = currentSpeechCue(cues, 1_500)!!
        assertEquals("Hello world", cue.text)
        assertEquals(2_500L, cue.endMs)
        assertNull(currentSpeechCue(cues, 4_000))
    }

    @Test
    fun queueSpeaksEachCueOnce() {
        val queue = SpeechQueueController()
        assertTrue(queue.claim(en.key))
        assertFalse(queue.claim(en.key))
        queue.reset()
        assertTrue(queue.claim(en.key))
    }

    @Test
    fun speedStrategyFitsLongTextIntoTheCue() {
        val settings = SubtitleSpeechSettings(strategy = "speed")
        assertEquals(1f, speechRate(settings, 1f, 14, 1_000), 0.01f)
        assertEquals(2.5f, speechRate(settings, 1f, 200, 1_000), 0.01f)
        assertEquals(1f, speechRate(SubtitleSpeechSettings(), 1f, 200, 1_000), 0.01f)
    }

    @Test
    fun audioIsDuckedAndRestoredKeepingUserChanges() {
        var volume = 0.8f
        val audio = SpeechAudioController({ volume }, { volume = it })
        audio.begin(SubtitleSpeechSettings(audio = "duck", duckPercent = 25))
        assertEquals(0.2f, volume, 0.001f)
        audio.volumeChanged(0.5f)
        audio.restore()
        assertEquals(0.5f, volume, 0.001f)
    }
}
