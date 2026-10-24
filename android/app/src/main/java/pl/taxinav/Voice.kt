package pl.taxinav

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Polski lektor (systemowy TTS — Google Speech Services ma polski głos). */
class Voice(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val pending = ArrayList<String>()
    private val tts = TextToSpeech(context.applicationContext, this)
    var muted = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale.forLanguageTag("pl-PL")
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
        pending.forEach { say(it) }
        pending.clear()
    }

    fun say(text: String) {
        if (muted || text.isBlank()) return
        if (!ready) { pending += text; return }
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() = tts.stop()
    fun shutdown() = tts.shutdown()
}
