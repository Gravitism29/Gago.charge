package kr.gakim.charge.data.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kr.gakim.charge.data.SpeechOutputGateway
import java.util.Locale

/** All calls verified with javap on TextToSpeech-3.3.1/classes.jar. No server registration. */
class PleosSpeechOutputGateway(context: Context) : SpeechOutputGateway {
    private val lock = Any()
    private var closed = false
    private var ready = false
    private var pending: String? = null
    private var tts: TextToSpeech? = null
    private val listener = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) { Log.w(TAG, "TTS: $errorType") }
    }

    init {
        safely {
            val created = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            tts = created
            created.initialize(object : OnInitializeListener {
                override fun onSuccess() = synchronized(lock) {
                    if (!closed) safely {
                        created.setLocale(Locale.KOREA)
                        created.addEventListener(listener)
                        ready = true
                        pending?.let { created.speak(it, true) }
                        pending = null
                    }
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS initialization: $errorType")
                }
            })
        }
    }
    override fun speak(text: String) = synchronized(lock) {
        if (!closed) {
            pending = text
            if (ready) safely { tts?.speak(text, true); pending = null }
        }
    }
    override fun stop() = synchronized(lock) {
        pending = null
        safely { tts?.stop() }
    }
    override fun release() = synchronized(lock) {
        closed = true
        stop()
        safely { tts?.removeEventListener(listener) }
        safely { tts?.release() }
        tts = null
    }
    private fun safely(block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "TTS unavailable", t)
        }
    }
    private companion object { const val TAG = "PleosSpeechOutput" }
}
