package kr.gakim.charge

import kr.gakim.charge.data.fake.FakeSpeechOutputGateway
import kr.gakim.charge.ui.state.*
import org.junit.Assert.*
import org.junit.Test

class AssistantSpeechTest {
    private val state = UiState(usingFakes = true, dataLabel = "", nowMinutes = 900, thresholdPercent = 20)
    @Test fun speaksChangedLinesOnly() {
        val driver = AssistantSpeech(); val output = FakeSpeechOutputGateway()
        driver.update(state, output); driver.update(state.copy(loading = true), output)
        driver.update(state.copy(assistant = AssistantUi("다음")), output)
        assertEquals(listOf(state.assistant.line, "다음"), output.spoken)
    }
    @Test fun listeningStopsWithoutSpeaking() {
        val driver = AssistantSpeech(); val output = FakeSpeechOutputGateway()
        driver.update(state, output)
        driver.update(state.copy(speech = SpeechUi(listening = true), assistant = AssistantUi("듣고 있어요…", speak = false)), output)
        assertEquals(1, output.stopCount); assertEquals(1, output.spoken.size)
    }
    @Test fun muteStopsAndSuppressesLines() {
        val driver = AssistantSpeech(); val output = FakeSpeechOutputGateway()
        driver.update(state.copy(ttsEnabled = false), output)
        driver.update(state.copy(ttsEnabled = false, assistant = AssistantUi("다음")), output)
        assertTrue(output.spoken.isEmpty()); assertEquals(1, output.stopCount) // 음소거로 바뀌는 순간 한 번만 멈춘다
    }
    @Test fun speakFlagSuppressesLine() {
        val driver = AssistantSpeech(); val output = FakeSpeechOutputGateway()
        driver.update(state.copy(assistant = AssistantUi("조용히", speak = false)), output)
        assertTrue(output.spoken.isEmpty())
    }
}
