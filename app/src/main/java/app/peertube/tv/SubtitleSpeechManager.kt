package app.peertube.tv

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Speaks the current subtitle cue through the system TTS engine. Owned by the player screen.
 * Nothing is downloaded, translated or bound to TTS while speech is off.
 */
internal class SubtitleSpeechManager(private val context: Context, private val player: Player) : Player.Listener {
    private data class Source(val server: String, val captions: List<VideoCaption>, val selectedIndex: Int, val defaultTarget: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settings = SubtitleSpeechSettings()
    private var source: Source? = null
    private var engine: TtsEngineController? = null
    private val queue = SpeechQueueController()
    private val audio = SpeechAudioController({ player.volume }, { player.volume = it })
    private var loadJob: Job? = null
    private var speechJob: Job? = null
    private var tickJob: Job? = null
    private var generation = 0
    private var loadGeneration = 0
    private var originals = emptyList<SpeechCue>()
    private var ownTranslation = emptyList<SpeechCue>()
    private var playerTranslation: List<SpeechCue>? = null
    private var playerTranslationLanguage: String? = null
    private var holding = false
    private var closed = false

    val status = MutableStateFlow("off")
    val voices = MutableStateFlow<List<SpeechVoice>>(emptyList())

    init { player.addListener(this) }

    private val target get() = settings.language.ifBlank { source?.defaultTarget.orEmpty() }
    private val translated get() = playerTranslation?.takeIf { playerTranslationLanguage == target.substringBefore('-') } ?: ownTranslation

    fun configure(value: SubtitleSpeechSettings) {
        if (closed || value == settings && (engine != null || !value.enabled)) return
        val old = settings
        settings = value
        stopSpeech()
        if (!value.enabled) { shutdown(); return }
        if (engine == null || old.engine != value.engine) startEngine()
        if (tickJob == null) tickJob = scope.launch { while (isActive) { tick(); delay(120) } }
        if (old.enabled != value.enabled || old.mode != value.mode || old.language != value.language || originals.isEmpty()) load()
    }

    fun setSource(server: String, captions: List<VideoCaption>, selectedIndex: Int, defaultTarget: String) {
        val next = Source(server, captions, selectedIndex, defaultTarget)
        if (next == source) return
        source = next
        if (settings.enabled && !closed) load()
    }

    /** Reuses the player's own subtitle translation instead of translating the same file twice. */
    fun setPlayerTranslation(active: Boolean, language: String, cues: List<TranslatedCue>) {
        val code = language.substringBefore('-')
        val wasUsed = playerTranslationLanguage != null
        playerTranslation = if (active) cues.map { SpeechCue(it.startMs, it.endMs, it.text, code) } else null
        playerTranslationLanguage = if (active) code else null
        // The player stopped translating: translate on our own if speech still needs it.
        if (wasUsed && !active && settings.enabled && !closed && ownTranslation.isEmpty()) load()
    }

    private fun startEngine() {
        engine?.close()
        val created = TtsEngineController(context, settings.engine)
        engine = created
        scope.launch {
            try { created.initialize(); voices.value = created.voices.value }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (engine === created) status.value = "engine_error" }
        }
    }

    private fun shutdown() {
        loadJob?.cancel(); loadJob = null
        tickJob?.cancel(); tickJob = null
        engine?.close(); engine = null
        originals = emptyList(); ownTranslation = emptyList()
        queue.reset()
        status.value = "off"
    }

    private fun load() {
        loadJob?.cancel()
        val loadToken = ++loadGeneration
        stopSpeech()
        queue.reset()
        originals = emptyList(); ownTranslation = emptyList()
        val current = source ?: return
        val goal = target.substringBefore('-')
        val captions = current.captions
        if (captions.isEmpty()) { status.value = "no_subtitles"; return }
        val native = captions.firstOrNull { it.language?.id?.substringBefore('-') == goal }
        val chosen = (if (settings.mode in listOf("auto", "translated")) native else null)
            ?: captions.getOrNull(current.selectedIndex) ?: captions.first()
        val sourceLanguage = chosen.language?.id?.substringBefore('-').orEmpty()
        status.value = "loading"
        loadJob = scope.launch {
            val cues = try {
                withContext(Dispatchers.IO) { downloadSubtitleCues(current.server, chosen) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.value = "captions_error"; return@launch }
            originals = cues.map { SpeechCue(it.startMs, it.endMs, it.text, sourceLanguage) }
            status.value = "ready"
            val needsTranslation = settings.mode != "original" && sourceLanguage.isNotBlank() && goal.isNotBlank() && sourceLanguage != goal
            if (!needsTranslation || playerTranslationLanguage == goal) return@launch
            status.value = "translating"
            try {
                // Batches become speakable as soon as they are ready, so speech starts before the whole file is done.
                withContext(Dispatchers.IO) {
                    translateSubtitleCues(cues, sourceLanguage, goal, onBatchTranslated = { batch ->
                        val mapped = batch.map { SpeechCue(it.startMs, it.endMs, it.text, goal) }
                        scope.launch { if (loadToken == loadGeneration) ownTranslation = ownTranslation + mapped }
                    })
                }
                status.value = "ready"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.value = "translation_error" }
        }
    }

    private fun tick() {
        val tts = engine ?: return
        if (closed || !settings.enabled || !player.isPlaying || player.playbackState != Player.STATE_READY) return
        val position = player.currentPosition
        val original = currentSpeechCue(originals, position)
        val translation = currentSpeechCue(translated, position)
        val cue = original ?: translation
        if (cue == null) { if (speechJob?.isActive == true && !holding) stopSpeech(); return }
        val parts = speechParts(settings.mode, original, translation, target, settings.fallback)
        if (parts.isEmpty() || !queue.claim(cue.key)) return
        stopSpeech()
        val token = generation
        val options = settings
        speechJob = scope.launch {
            try {
                val remaining = (cue.endMs - player.currentPosition).coerceAtLeast(1)
                val rate = speechRate(options, player.playbackParameters.speed, parts.sumOf { it.text.length }, remaining)
                tts.prepare(parts.first().language, options.voice)
                if (token != generation || !player.isPlaying) return@launch
                audio.begin(options)
                if (options.strategy == "wait") { holding = true; player.pause() }
                status.value = "speaking"
                val deadline = if (options.strategy == "wait") 30_000L else (remaining / player.playbackParameters.speed).toLong().coerceIn(1, 30_000)
                withTimeout(deadline) { parts.forEach { tts.speak(it, rate, options.pitch, options.voice) } }
                status.value = "ready"
            } catch (_: TimeoutCancellationException) { status.value = "ready" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: SpeechUnavailable) { status.value = error.code }
            catch (_: Exception) { status.value = "speech_error" }
            finally { if (generation == token) { tts.stop(); audio.restore(); resumeHeld() } }
        }
    }

    private fun resumeHeld() {
        val resume = holding && !closed && player.playbackState != Player.STATE_ENDED
        holding = false
        if (resume) player.play()
    }

    private fun stopSpeech(resume: Boolean = true) {
        generation++
        speechJob?.cancel(); speechJob = null
        engine?.stop()
        audio.restore()
        if (status.value == "speaking") status.value = "ready"
        if (resume) resumeHeld() else holding = false
    }

    override fun onVolumeChanged(volume: Float) { audio.volumeChanged(volume) }
    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying && !holding) { stopSpeech(false); queue.reset() } }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { stopSpeech(); queue.reset() }
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { stopSpeech(); queue.reset() }
    override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) stopSpeech(false) }
    override fun onPlayerError(error: PlaybackException) { stopSpeech(false) }

    fun close() {
        if (closed) return
        stopSpeech(false)
        closed = true
        player.removeListener(this)
        shutdown()
        scope.cancel()
    }
}
