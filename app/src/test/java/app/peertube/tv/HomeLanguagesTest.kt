package app.peertube.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLanguagesTest {
    private fun video(uuid: String, language: String? = null, host: String = "a.example", nsfw: Boolean = false) =
        Video(uuid = uuid, name = uuid, host = host, nsfw = nsfw, language = language?.let { VideoLanguage(it) })

    @Test
    fun interfaceLanguageAndRegionalCodesAreResolved() {
        assertEquals(setOf("ru"), resolveHomeVideoLanguages(listOf(INTERFACE_VIDEO_LANGUAGE), "ru"))
        assertEquals(setOf("pt", "pt-BR", "pt-PT"), resolveHomeVideoLanguages(listOf("pt"), "en"))
        assertTrue(resolveHomeVideoLanguages(emptyList(), "ru").isEmpty())
    }

    @Test
    fun preferredLanguagesFirstThenUnknownThenOthersKeepingOrder() {
        val list = listOf(video("1", "en"), video("2"), video("3", "ru"), video("4", "de"), video("5", "ru"))
        assertEquals(listOf("3", "5", "2", "1", "4"), list.preferVideoLanguages(setOf("ru")).map { it.uuid })
        assertEquals(list, list.preferVideoLanguages(emptySet()))
    }

    @Test
    fun languageFirstMergeDropsDuplicatesAndKeepsTheLimit() {
        val merged = mergeLanguageFirst(listOf(video("1", "ru"), video("2", "ru")), listOf(video("2", "ru"), video("3"), video("4")), limit = 3)
        assertEquals(listOf("1", "2", "3"), merged.map { it.uuid })
    }

    @Test
    fun oneServerCannotFillAGlobalList() {
        val list = (1..5).map { video("a$it", host = "busy.example") } + video("b1", host = "other.example")
        assertEquals(listOf("a1", "a2", "a3", "b1"), capPerServer(list).map { it.uuid })
    }

    @Test
    fun interestSourcesFailOnlyTogether() {
        val local = Result.success(listOf(video("1"), video("2")))
        val global = Result.failure<List<Video>>(IllegalStateException("sepia"))
        assertEquals(listOf("1", "2"), mergeInterestSources(local, global).map { it.uuid })
        assertEquals(listOf("1", "3"), mergeInterestSources(Result.success(listOf(video("1"))), Result.success(listOf(video("1"), video("3", host = "b.example")))).map { it.uuid })
        val failed = runCatching { mergeInterestSources(Result.failure(IllegalStateException("x")), global) }
        assertTrue(failed.isFailure)
    }

    @Test
    fun sensitiveVideosAreDroppedUnlessAllowed() {
        val list = listOf(video("1"), video("2", nsfw = true))
        assertEquals(listOf("1"), list.withSensitivePolicy(false).map { it.uuid })
        assertEquals(2, list.withSensitivePolicy(true).size)
        assertEquals("both", sensitiveContentFilter(true))
        assertEquals("false", sensitiveContentFilter(false))
    }
}
