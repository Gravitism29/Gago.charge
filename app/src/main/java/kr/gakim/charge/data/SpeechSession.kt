package kr.gakim.charge.data

/** Serializes SDK initialization/callbacks against close, including reentrant callbacks. */
class SpeechSession {
    private var closed = false
    private var initialized = false
    @Synchronized fun active(block: () -> Unit) { if (!closed) block() }
    @Synchronized fun initialize(block: () -> Unit) {
        if (!closed && !initialized) { initialized = true; block() }
    }
    @Synchronized fun timeout(block: () -> Unit) {
        if (!closed && !initialized) { closed = true; block() }
    }
    @Synchronized fun close(block: () -> Unit) {
        if (!closed) { closed = true; block() }
    }
}

fun normalizeSpeechRms(rms: Double): Float =
    if (!rms.isFinite()) 0f else (rms / 10.0).toFloat().coerceIn(0f, 1f)
