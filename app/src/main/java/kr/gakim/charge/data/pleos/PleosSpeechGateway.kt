package kr.gakim.charge.data.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kr.gakim.charge.data.*
import java.util.Locale

class PleosSpeechGateway(context: Context) : SpeechGateway {
    private val appContext = context.applicationContext
    private val sessions = mutableSetOf<() -> Unit>()
    private var released = false

    override fun listen(): Flow<SpeechEvent> = callbackFlow {
        val session = SpeechSession()
        var stt: SpeechToText? = null
        fun finish() = session.close { close() }
        fun error(reason: String) = session.active {
            trySend(SpeechEvent.Error(reason)); finish()
        }
        val end: () -> Unit = { finish() }
        synchronized(sessions) { if (released) finish() else sessions += end }
        val timeout = launch {
            delay(5_000)
            session.timeout { trySend(SpeechEvent.Error("STT initialization timeout")); close() }
        }
        val results = SpeechResultWindow(this, { trySend(it); Unit }, { finish() })
        val listener = object : ResultListener {
            override fun onReady() = session.active { trySend(SpeechEvent.Listening); Unit }
            override fun onStartedRecognition() = Unit
            override fun onUpdated(text: String, completed: Boolean) = session.active {
                results.result(text, completed)
            }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) = session.active { trySend(SpeechEvent.Level(normalizeSpeechRms(rms))); Unit }
            override fun onEndedRecognition() = session.active { results.ended() }
            override fun onError(errorType: ErrorType) = session.active { results.error(errorType.name) }
        }
        try {
            session.active {
                val created = SpeechToText(appContext, Mode.ON_DEVICE)
                stt = created
                created.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        if (!isActive) { finish(); return }
                        session.initialize {
                        timeout.cancel()
                        try {
                            created.setLocale(Locale.KOREA)
                            created.addListener(listener)
                            created.request()
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            Log.w(TAG, "STT start failed", t)
                            error(t.message ?: "STT start failed")
                        }
                        }
                    }
                    override fun onFailure(errorType: ErrorType) = error(errorType.name)
                })
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "STT initialization failed", t)
            error(t.message ?: "STT initialization failed")
        }
        awaitClose {
            results.cancel()
            session.close { }
            timeout.cancel()
            synchronized(sessions) { sessions -= end }
            listOf<() -> Unit>({ stt?.stop() }, { stt?.removeListener(listener) }, { stt?.release() }).forEach {
                try { it() } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "STT release failed", t)
                }
            }
        }
    }
    override fun release() {
        val closing = synchronized(sessions) { released = true; sessions.toList().also { sessions.clear() } }
        closing.forEach { it() }
    }
    private companion object { const val TAG = "PleosSpeechGateway" }
}
