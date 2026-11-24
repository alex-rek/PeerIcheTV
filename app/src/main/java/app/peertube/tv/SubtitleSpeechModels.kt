package app.peertube.tv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Subtitle speech options; speech is off until the user enables it. */
data class SubtitleSpeechSettings(
    val enabled: Boolean = false, val mode: String = "auto",
    val language: String = "", val engine: String = "", val voice: String = "",
    val rate: Float = 1f, val pitch: Float = 1f, val audio: String = "duck", val duckPercent: Int = 25,
    val strategy: String = "skip", val fallback: String = "original"
) {
    companion object {
        val modes = listOf("auto", "original", "translated", "both")
        val audioModes = listOf("unchanged", "duck", "mute")
        val strategies = listOf("skip", "speed", "wait")
        val fallbacks = listOf("original", "skip")
    }
}

/** Shared by Settings and the player; the backup restore writes the same preferences. */
internal object SubtitleSpeechPrefs {
    private const val PREFIX = "speech_"
    private val state = MutableStateFlow(SubtitleSpeechSettings())
    private var preferences: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key.startsWith(PREFIX)) state.value = read(prefs)
    }

    @Synchronized
    fun state(context: Context): StateFlow<SubtitleSpeechSettings> {
        if (preferences == null) {
            val prefs = context.applicationContext.getSharedPreferences("peertube", Context.MODE_PRIVATE)
            preferences = prefs
            state.value = read(prefs)
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        return state
    }

    fun update(context: Context, transform: (SubtitleSpeechSettings) -> SubtitleSpeechSettings) {
        val next = transform(state(context).value)
        state.value = next
        preferences?.edit()?.apply {
            putBoolean("${PREFIX}enabled", next.enabled); putString("${PREFIX}mode", next.mode)
            putString("${PREFIX}language", next.language); putString("${PREFIX}engine", next.engine)
            putString("${PREFIX}voice", next.voice); putFloat("${PREFIX}rate", next.rate); putFloat("${PREFIX}pitch", next.pitch)
            putString("${PREFIX}audio", next.audio); putInt("${PREFIX}duckPercent", next.duckPercent)
            putString("${PREFIX}strategy", next.strategy); putString("${PREFIX}fallback", next.fallback)
        }?.apply()
    }

    private fun read(prefs: SharedPreferences): SubtitleSpeechSettings {
        val d = SubtitleSpeechSettings()
        fun choice(key: String, allowed: List<String>, default: String) = prefs.getString(PREFIX + key, default)?.takeIf { it in allowed } ?: default
        return SubtitleSpeechSettings(
            enabled = prefs.getBoolean("${PREFIX}enabled", d.enabled),
            mode = choice("mode", SubtitleSpeechSettings.modes, d.mode),
            language = prefs.getString("${PREFIX}language", d.language).orEmpty(),
            engine = prefs.getString("${PREFIX}engine", d.engine).orEmpty(),
            voice = prefs.getString("${PREFIX}voice", d.voice).orEmpty(),
            rate = prefs.getFloat("${PREFIX}rate", d.rate).coerceIn(.5f, 2f),
            pitch = prefs.getFloat("${PREFIX}pitch", d.pitch).coerceIn(.5f, 2f),
            audio = choice("audio", SubtitleSpeechSettings.audioModes, d.audio),
            duckPercent = prefs.getInt("${PREFIX}duckPercent", d.duckPercent).coerceIn(0, 100),
            strategy = choice("strategy", SubtitleSpeechSettings.strategies, d.strategy),
            fallback = choice("fallback", SubtitleSpeechSettings.fallbacks, d.fallback)
        )
    }
}

internal data class SpeechCue(val startMs: Long, val endMs: Long, val text: String, val language: String) {
    val key: String get() = "$startMs:$endMs:$language:$text"
}
internal data class SpeechPart(val text: String, val language: String)

/** Speaks each cue once until playback jumps. */
internal class SpeechQueueController {
    private var lastKey: String? = null
    fun claim(key: String): Boolean = if (key == lastKey) false else { lastKey = key; true }
    fun reset() { lastKey = null }
}

internal fun currentSpeechCue(cues: List<SpeechCue>, position: Long): SpeechCue? {
    val matches = cues.filter { position >= it.startMs && position < it.endMs }
    val newest = matches.maxOfOrNull { it.startMs } ?: return null
    val active = matches.filter { it.startMs == newest }
    return SpeechCue(newest, active.minOf { it.endMs }, active.map { it.text }.distinct().joinToString(" "), active.first().language)
}

internal fun speechParts(mode: String, original: SpeechCue?, translated: SpeechCue?, target: String, fallback: String): List<SpeechPart> {
    val direct = original?.takeIf { it.language.substringBefore('-') == target.substringBefore('-') }
    val targetCue = translated ?: direct
    val chosen = when (mode) {
        "original" -> listOfNotNull(original)
        "both" -> listOfNotNull(original, targetCue)
        "translated", "auto" -> listOfNotNull(targetCue ?: original?.takeIf { fallback == "original" })
        else -> emptyList()
    }
    return chosen.map { SpeechPart(it.text, it.language) }.filter { it.text.isNotBlank() }.distinct()
}

internal fun speechRate(settings: SubtitleSpeechSettings, videoSpeed: Float, textLength: Int, remainingMs: Long): Float {
    val base = settings.rate.coerceIn(.5f, 2f) * videoSpeed.coerceIn(.25f, 3f)
    val fitted = if (settings.strategy == "speed" && remainingMs > 0) textLength / 14f / (remainingMs / 1000f) else base
    return maxOf(base, fitted).coerceIn(.5f, 2.5f)
}

internal fun speechVolume(base: Float, settings: SubtitleSpeechSettings, speaking: Boolean): Float =
    if (!speaking) base else when (settings.audio) {
        "mute" -> 0f
        "duck" -> base * settings.duckPercent.coerceIn(0, 100) / 100f
        else -> base
    }

/** Changes only the player volume and keeps volume changes made while speaking. */
internal class SpeechAudioController(private val read: () -> Float, private val write: (Float) -> Unit) {
    private var original: Float? = null
    private var applied = 1f
    private var settings = SubtitleSpeechSettings()
    fun begin(value: SubtitleSpeechSettings) {
        settings = value
        if (original == null) original = read()
        apply()
    }
    private fun apply() {
        applied = speechVolume(original ?: return, settings, true)
        write(applied)
    }
    fun volumeChanged(value: Float) {
        if (original != null && kotlin.math.abs(value - applied) > .001f) { original = value; apply() }
    }
    fun restore() {
        val value = original ?: return
        original = null
        write(value)
    }
}
