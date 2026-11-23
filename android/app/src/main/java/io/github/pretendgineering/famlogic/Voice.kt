package io.github.pretendgineering.famlogic

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Voice: talk to send a message, and hear messages read aloud.
 *
 * Two engines for each: the phone's own (Android's speech recognition and text to
 * speech) or the family's server (Whisper and Piper, built into the server image).
 * "Automatic" uses the phone's own on the Google edition when the phone has them
 * (usually Google's, which is quick and accurate), and the server everywhere else:
 * on the FOSS edition, and on any phone without working speech services. Anyone can
 * choose in Customize → Voice.
 */
object Voice {
    enum class Engine { Phone, Server }

    private fun google() = BuildConfig.FLAVOR == "google"

    fun phoneCanListen(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    /** Which engine turns speech into text here, or null if neither can. */
    fun listener(context: Context, features: Set<String>): Engine? {
        val phone = phoneCanListen(context)
        val server = "voice-stt" in features
        return pick(Prefs(context).voiceEngine, phone, server)
    }

    /** Which engine reads aloud here, or null if neither can. */
    fun speaker(context: Context, features: Set<String>): Engine? {
        val phone = tts?.ready == true
        val server = "voice-tts" in features
        return pick(Prefs(context).voiceEngine, phone, server)
    }

    private fun pick(choice: String, phone: Boolean, server: Boolean): Engine? = when (choice) {
        "phone" -> if (phone) Engine.Phone else if (server) Engine.Server else null
        "server" -> if (server) Engine.Server else if (phone) Engine.Phone else null
        else -> when {
            google() && phone -> Engine.Phone
            server -> Engine.Server
            phone -> Engine.Phone
            else -> null
        }
    }

    // ---- reading aloud

    private class Tts(context: Context) {
        var ready = false
        val engine: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = engineLanguage()
                ready = lang >= TextToSpeech.LANG_AVAILABLE
            }
        }
        private fun engineLanguage(): Int {
            val want = Locale.getDefault()
            val ok = engine.isLanguageAvailable(want)
            if (ok >= TextToSpeech.LANG_AVAILABLE) engine.language = want
            return ok
        }
    }

    @Volatile private var tts: Tts? = null
    private var player: MediaPlayer? = null

    /** Starts the phone's text to speech early, so it's ready when needed. */
    fun warmUp(context: Context) {
        if (tts == null) tts = runCatching { Tts(context) }.getOrNull()
    }

    /** Reads text aloud with the chosen engine. Returns false if it couldn't. */
    suspend fun speak(context: Context, prefs: Prefs, text: String): Boolean {
        warmUp(context)
        return when (speaker(context, prefs.serverFeatures)) {
            Engine.Phone -> {
                tts?.engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "famlogic-${text.hashCode()}")
                true
            }
            Engine.Server -> try {
                val wav = Api(prefs.serverUrl, prefs.token).speak(text)
                val f = File(context.cacheDir, "say.wav").apply { writeBytes(wav) }
                withContext(Dispatchers.Main) { play { MediaPlayer().apply { setDataSource(f.path); prepare() } } }
                true
            } catch (_: Exception) {
                false
            }
            null -> false
        }
    }

    /** "Oops, try again!" (a recording in the app, so it works even offline). */
    fun oops(context: Context) = play { MediaPlayer.create(context, R.raw.oops) }

    private fun play(make: () -> MediaPlayer?) {
        player?.release()
        player = make()?.apply {
            setOnCompletionListener { it.release(); if (player === it) player = null }
            start()
        }
    }

    fun stop() {
        tts?.engine?.stop()
        player?.release()
        player = null
    }

    // ---- listening

    /** One push-to-talk: start when the button goes down, finish when it comes up. */
    interface Take {
        /** The words heard ("" if nothing was understood). Throws if something went wrong. */
        suspend fun finish(): String
        fun cancel()
    }

    fun start(context: Context, prefs: Prefs): Take? = when (listener(context, prefs.serverFeatures)) {
        Engine.Phone -> PhoneTake(context)
        Engine.Server -> ServerTake(prefs).takeIf { it.started }
        null -> null
    }

    /** Android's own recogniser: it listens while held. */
    private class PhoneTake(context: Context) : Take {
        private val result = CompletableDeferred<String>()
        private val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    result.complete(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) {
                    // No speech or nothing matched: "" (so the child hears "Oops, try again!").
                    result.complete("")
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Children pause mid-sentence: wait a little longer before deciding they've finished.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            })
        }

        override suspend fun finish(): String {
            recognizer.stopListening()
            return try {
                kotlinx.coroutines.withTimeout(15_000) { result.await() }
            } finally {
                recognizer.destroy()
            }
        }

        override fun cancel() {
            recognizer.cancel()
            recognizer.destroy()
        }
    }

    /** Records 16 kHz mono audio while held, then the family's server turns it into text. */
    private class ServerTake(private val prefs: Prefs) : Take {
        private val rate = 16_000
        private val pcm = ByteArrayOutputStream()
        @Volatile private var running = true
        private var recorder: AudioRecord? = null
        private var thread: Thread? = null
        var started = false

        init {
            start()
        }

        @SuppressLint("MissingPermission") // the button asks for the microphone first
        private fun start() {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return
            val r = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
            }.getOrNull() ?: return
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return }
            recorder = r
            r.startRecording()
            started = true
            thread = Thread {
                val buf = ByteArray(min)
                while (running && pcm.size() < MAX_BYTES) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
                }
            }.apply { start() }
        }

        private fun stopRecording(): ByteArray {
            running = false
            thread?.join(1_000)
            recorder?.run { runCatching { stop() }; release() }
            recorder = null
            return synchronized(pcm) { pcm.toByteArray() }
        }

        override suspend fun finish(): String {
            val audio = withContext(Dispatchers.IO) { stopRecording() }
            if (audio.size < rate * 2 / 3) return "" // under a third of a second: nothing said
            return Api(prefs.serverUrl, prefs.token).transcribe(wav(audio, rate))
        }

        override fun cancel() {
            stopRecording()
        }

        companion object {
            const val MAX_BYTES = 16_000 * 2 * 55 // 55 seconds
        }
    }

    /** 16-bit mono PCM → a WAV file. */
    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }
}
