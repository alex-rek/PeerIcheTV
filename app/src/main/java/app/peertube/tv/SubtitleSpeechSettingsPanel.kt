package app.peertube.tv

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun speechStatusText(code: String): String = stringResource(when (code) {
    "off" -> R.string.speech_status_off
    "loading" -> R.string.speech_status_loading
    "ready" -> R.string.speech_status_ready
    "speaking" -> R.string.speech_status_speaking
    "translating" -> R.string.speech_status_translating
    "no_subtitles" -> R.string.speech_status_no_subtitles
    "missing_voice" -> R.string.speech_status_missing_voice
    "translation_error" -> R.string.speech_status_translation_error
    "captions_error" -> R.string.speech_status_captions_error
    "engine_error" -> R.string.speech_status_engine_error
    else -> R.string.speech_status_speech_error
})

@Composable
internal fun speechModeText(mode: String): String = stringResource(when (mode) {
    "original" -> R.string.speech_mode_original
    "translated" -> R.string.speech_mode_translated
    "both" -> R.string.speech_mode_both
    else -> R.string.speech_mode_auto
})

@Composable
internal fun speechAudioText(audio: String): String = stringResource(when (audio) {
    "unchanged" -> R.string.speech_audio_unchanged
    "mute" -> R.string.speech_audio_mute
    else -> R.string.speech_audio_duck
})

@Composable
internal fun speechStrategyText(strategy: String): String = stringResource(when (strategy) {
    "speed" -> R.string.speech_strategy_speed
    "wait" -> R.string.speech_strategy_wait
    else -> R.string.speech_strategy_skip
})

@Composable
internal fun speechFallbackText(fallback: String): String =
    stringResource(if (fallback == "skip") R.string.speech_fallback_skip else R.string.speech_fallback_original)

@Composable
internal fun speechLanguageText(language: String, subtitleTranslationLanguage: String): String =
    if (language.isBlank()) stringResource(R.string.speech_language_follow, translationLanguageName(subtitleTranslationLanguage))
    else translationLanguageName(language)

@Composable
internal fun speechVoiceText(voice: String, voices: List<SpeechVoice>): String {
    if (voice.isBlank()) return stringResource(R.string.speech_voice_auto)
    val found = voices.firstOrNull { it.name == voice } ?: return voice
    return if (found.network) stringResource(R.string.speech_voice_network, found.name) else found.name
}

internal fun speechTestPhrase(language: String): String = when (language.substringBefore('-')) {
    "ru" -> "Проверка озвучивания субтитров PeerIche."
    "en" -> "PeerIche subtitle speech test."
    "de" -> "PeerIche Test der Untertitel-Sprachausgabe."
    "fr" -> "Test de lecture vocale des sous-titres PeerIche."
    "es" -> "Prueba de voz de subtítulos de PeerIche."
    "it" -> "Prova della lettura vocale dei sottotitoli PeerIche."
    "pt" -> "Teste de leitura de legendas do PeerIche."
    else -> "PeerIche. 1, 2, 3."
}

/** Settings → Speech: the same options as the player's side panel plus engine, test and voice data. */
@Composable
internal fun SubtitleSpeechSettingsPanel(subtitleTranslationLanguage: String) {
    val context = LocalContext.current
    val settings by SubtitleSpeechPrefs.state(context).collectAsState()
    val scope = rememberCoroutineScope()
    val engine = remember(settings.engine) { TtsEngineController(context, settings.engine) }
    val voices by engine.voices.collectAsState()
    val engines by engine.engines.collectAsState()
    var engineStatus by remember(engine) { mutableStateOf("ready") }
    var testJob by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(engine) { onDispose { testJob?.cancel(); engine.close() } }
    LaunchedEffect(engine) {
        try { engine.initialize() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { engineStatus = "engine_error" }
    }
    fun change(transform: (SubtitleSpeechSettings) -> SubtitleSpeechSettings) = SubtitleSpeechPrefs.update(context, transform)
    val language = settings.language.ifBlank { subtitleTranslationLanguage }
    val languageVoices = voices.filter { it.language.substringBefore('-') == language.substringBefore('-') }
    val off = stringResource(R.string.speech_status_off)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.speech_enabled), Modifier.weight(1f))
        Switch(settings.enabled, { enabled -> change { it.copy(enabled = enabled) } })
    }
    Text(stringResource(R.string.speech_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SettingsChoice(stringResource(R.string.speech_mode), speechModeText(settings.mode),
        SubtitleSpeechSettings.modes.map { it to speechModeText(it) }) { value -> change { it.copy(mode = value) } }
    SettingsChoice(stringResource(R.string.speech_language), speechLanguageText(settings.language, subtitleTranslationLanguage),
        listOf("" to speechLanguageText("", subtitleTranslationLanguage)) + downloadableSubtitleTranslationLanguages()) { value ->
        change { it.copy(language = value, voice = "") }
    }
    SettingsChoice(stringResource(R.string.speech_engine),
        engines.firstOrNull { it.first == settings.engine }?.second ?: stringResource(R.string.speech_engine_default),
        listOf("" to stringResource(R.string.speech_engine_default)) + engines) { value -> change { it.copy(engine = value, voice = "") } }
    SettingsChoice(stringResource(R.string.speech_voice), speechVoiceText(settings.voice, voices),
        listOf("" to stringResource(R.string.speech_voice_auto)) + languageVoices.map { it.name to speechVoiceText(it.name, voices) }) { value ->
        change { it.copy(voice = value) }
    }
    SettingsChoice(stringResource(R.string.speech_rate), "${(settings.rate * 100).toInt()}%",
        (50..200 step 10).map { "$it" to "$it%" }) { value -> change { it.copy(rate = value.toInt() / 100f) } }
    SettingsChoice(stringResource(R.string.speech_pitch), "${(settings.pitch * 100).toInt()}%",
        (50..200 step 10).map { "$it" to "$it%" }) { value -> change { it.copy(pitch = value.toInt() / 100f) } }
    SettingsChoice(stringResource(R.string.speech_audio), speechAudioText(settings.audio),
        SubtitleSpeechSettings.audioModes.map { it to speechAudioText(it) }) { value -> change { it.copy(audio = value) } }
    if (settings.audio == "duck") SettingsChoice(stringResource(R.string.speech_duck_volume), "${settings.duckPercent}%",
        (0..100 step 10).map { "$it" to "$it%" }) { value -> change { it.copy(duckPercent = value.toInt()) } }
    SettingsChoice(stringResource(R.string.speech_strategy), speechStrategyText(settings.strategy),
        SubtitleSpeechSettings.strategies.map { it to speechStrategyText(it) }) { value -> change { it.copy(strategy = value) } }
    SettingsChoice(stringResource(R.string.speech_fallback), speechFallbackText(settings.fallback),
        SubtitleSpeechSettings.fallbacks.map { it to speechFallbackText(it) }) { value -> change { it.copy(fallback = value) } }
    Text("${stringResource(R.string.speech_status)}: ${if (engineStatus != "ready") speechStatusText(engineStatus) else if (settings.enabled) speechStatusText("ready") else off}",
        color = if (engineStatus in listOf("engine_error", "missing_voice", "speech_error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = {
        testJob?.cancel(); engine.stop()
        testJob = scope.launch {
            engineStatus = "speaking"
            try {
                engine.speak(SpeechPart(speechTestPhrase(language), language), settings.rate, settings.pitch, settings.voice)
                engineStatus = "ready"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SpeechUnavailable) { engineStatus = error.code }
            catch (_: Exception) { engineStatus = "engine_error" }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("${stringResource(R.string.speech_test)} · ${translationLanguageName(language)}") }
    OutlinedButton(onClick = { testJob?.cancel(); engine.stop(); engineStatus = "ready" }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.speech_test_stop))
    }
    OutlinedButton(onClick = {
        runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            if (settings.engine.isNotBlank()) setPackage(settings.engine)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) }.onFailure { engineStatus = "engine_error" }
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.speech_install_data)) }
}
