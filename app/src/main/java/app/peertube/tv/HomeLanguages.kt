package app.peertube.tv

import java.util.Locale

/** Stored in the Home video languages to follow the interface language. */
internal const val INTERFACE_VIDEO_LANGUAGE = "interface"

/** PeerTube language codes offered for Home, as in PeerIche. */
internal val homeVideoLanguageCodes = listOf("en", "ru", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "nl", "pl", "cs",
    "sv", "fi", "da", "no", "hu", "ro", "el", "tr", "ar", "he", "fa", "hi", "zh", "ja", "ko", "id", "vi", "ca", "eu", "gl", "eo")

/** Regional PeerTube codes that count as the base language. */
private val regionalVideoLanguages = mapOf("pt" to listOf("pt-BR", "pt-PT"), "zh" to listOf("zh-Hans", "zh-Hant"))

internal fun resolveHomeVideoLanguages(selected: List<String>, interfaceLanguage: String): Set<String> =
    selected.map { if (it == INTERFACE_VIDEO_LANGUAGE) interfaceLanguage else it }
        .filter(String::isNotBlank)
        .flatMap { listOf(it) + regionalVideoLanguages[it].orEmpty() }
        .toSet()

/**
 * Videos in the preferred languages first, then videos without a language, then the rest.
 * The order inside each group is kept, so the chosen sort still applies.
 */
internal fun List<Video>.preferVideoLanguages(languages: Set<String>): List<Video> {
    if (languages.isEmpty()) return this
    return sortedBy { video ->
        val code = video.language?.id
        when {
            code in languages -> 0
            code.isNullOrBlank() -> 1
            else -> 2
        }
    }
}

private fun Video.identityKey(): String = "${(host ?: "").lowercase()}|${uuid.lowercase()}"

/** The language-filtered request first, filled up from the unfiltered one, without duplicates. */
internal fun mergeLanguageFirst(preferred: List<Video>, others: List<Video>, limit: Int): List<Video> =
    (preferred + others).distinctBy { it.identityKey() }.take(limit)

/** At most [max] videos from each source server, so one busy server cannot fill a global list. */
internal fun capPerServer(videos: List<Video>, max: Int = 3): List<Video> {
    val counts = hashMapOf<String, Int>()
    return videos.filter { video ->
        val server = (video.host ?: video.channel?.host ?: "").lowercase()
        val seen = counts[server] ?: 0
        counts[server] = seen + 1
        seen < max
    }
}

/** Global interest videos after the active server's own, without duplicates; fails only if both sources fail. */
internal fun mergeInterestSources(local: Result<List<Video>>, global: Result<List<Video>>): List<Video> {
    if (local.isFailure && global.isFailure) throw local.exceptionOrNull()!!
    return (local.getOrDefault(emptyList()) + global.getOrDefault(emptyList())).distinctBy { it.identityKey() }
}

/** The one rule for every feed: drops sensitive videos unless they are allowed. */
internal fun List<Video>.withSensitivePolicy(allow: Boolean): List<Video> = if (allow) this else filterNot { it.nsfw }

internal fun sensitiveContentFilter(allow: Boolean): String = if (allow) "both" else "false"

internal fun videoLanguageName(code: String): String =
    Locale.forLanguageTag(code).let { locale -> locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) } }
