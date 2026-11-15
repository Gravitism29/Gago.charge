package kr.gakim.charge.ui.state

import kr.gakim.charge.data.SpeechOutputGateway

/** Speak each changed assistant line once; listening and mute always stop output. */
class AssistantSpeech {
    private var previousLine: String? = null
    private var wasSilent = false
    fun update(state: UiState, output: SpeechOutputGateway) {
        // 듣기 시작·음소거로 '바뀌는 순간'에만 멈춘다(상태가 바뀔 때마다 stop 을 부르면 음량 이벤트마다 호출된다).
        val silent = !state.ttsEnabled || state.speech.listening
        if (silent && !wasSilent) output.stop()
        wasSilent = silent
        if (previousLine != state.assistant.line) {
            previousLine = state.assistant.line
            if (state.ttsEnabled && state.assistant.speak && !state.speech.listening) output.speak(state.assistant.line)
        }
    }
}
