package kr.gakim.charge.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kr.gakim.charge.BuildConfig
import kotlinx.coroutines.launch
import kr.gakim.charge.R
import kr.gakim.charge.data.SpeechEvent
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.pleos.GatewayFactory
import kr.gakim.charge.demo.DemoDataRepository
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.ui.state.PlanController
import kr.gakim.charge.ui.state.AssistantSpeech
import kr.gakim.charge.ui.state.UiState

/**
 * 화면과 [PlanController] 를 잇는 얇은 층. 판단·흐름은 컨트롤러와 `:domain` 에 있다.
 * 시작 모드는 리소스 R.bool.use_fakes_default(gradle.properties 의 gakim.useFakes)이고 앱 안에서 바꿀 수 있다.
 */
class GakimViewModel(application: Application) : AndroidViewModel(application) {

    private var gateways: GatewaySet = GatewayFactory.create(
        application,
        application.resources.getBoolean(R.bool.use_fakes_default),
    )
    private val controller = PlanController(gateways, DemoDataRepository())
    private var speechJob: Job? = null
    private var workJob: Job? = null
    private var speechGeneration = 0L
    private var foreground = true

    val state: StateFlow<UiState> = controller.state

    init {
        refresh()
        viewModelScope.launch {
            val output = AssistantSpeech()
            var previousScreen = state.value.screen
            state.collect { s ->
                if (s.screen != previousScreen) { stopListening(); previousScreen = s.screen }
                if (foreground) output.update(s, gateways.speechOutput)
            }
        }
        viewModelScope.launch {
            state.map { it.autoAdvanceAtMs }.distinctUntilChanged().collectLatest { due ->
                if (due != null && foreground) {
                    delay((due - System.currentTimeMillis()).coerceAtLeast(0))
                    inScope { controller.advanceIfDue() }
                }
            }
        }
    }

    fun refresh() { cancelAutoAdvance(); inScope { controller.refresh() } }

    /** 디버그 전용 시연 준비: 내비 목적지를 시연 학교로 설정한 뒤 다시 읽는다. */
    fun setDemoDestination() {
        cancelAutoAdvance()
        if (!BuildConfig.DEBUG || workJob?.isActive == true) return
        inScope { controller.setDemoDestination(DemoDataRepository.SCHOOL) }
    }

    /** 디버그 전용 시연 잔량(%). null 이면 차에서 읽은 실제 값으로 돌아간다. */
    fun setDemoBatteryOverride(percent: Int?) {
        if (!BuildConfig.DEBUG) return
        cancelAutoAdvance()
        stopListening()
        controller.setDemoBatteryOverride(percent)
    }

    /** Pleos SDK ↔ Fake 전환. */
    fun setUseFakes(useFakes: Boolean) {        if (useFakes == gateways.usingFakes) return
        stopListening()
        workJob?.cancel()
        gateways.release()
        gateways = GatewayFactory.create(getApplication(), useFakes)
        controller.replaceGateways(gateways)
        refresh()
    }

    fun setActivityMinutes(minutes: Int) { beginTouchEdit(); controller.setActivityMinutes(minutes) }
    fun setPickup(pickup: Boolean) { beginTouchEdit(); controller.setPickup(pickup) }
    fun setDeadline(deadline: Deadline) { beginTouchEdit(); controller.setDeadline(deadline) }
    fun nudgeDeadline(deltaMinutes: Int) { beginTouchEdit(); controller.nudgeDeadline(deltaMinutes) }
    fun setCharge(charge: ChargePreference) { beginTouchEdit(); controller.setCharge(charge) }

    fun beginTouchEdit() {
        stopListening()
        controller.cancelAutoAdvance(announce = false)
        if (!state.value.guide.busy) workJob?.cancel()
    }

    /** 마이크 버튼: 듣는 중이면 멈추고, 아니면 한 문장을 듣는다. */
    fun toggleListening() {
        cancelAutoAdvance()
        gateways.speechOutput.stop()
        if (speechJob?.isActive == true) {
            stopListening()
            return
        }
        val token = ++speechGeneration
        val source = gateways
        val screen = state.value.screen
        speechJob = viewModelScope.launch {
            try {
                source.speech.listen().collect {
                    if (token == speechGeneration && screen == state.value.screen) {
                        if (it is SpeechEvent.Final) {
                            val text = it.text
                            stopListening()
                            val acceptedGeneration = speechGeneration
                            if (workJob?.isActive != true) inScope {
                                if (acceptedGeneration == speechGeneration && screen == state.value.screen) controller.onSpokenSentence(text)
                            }
                        } else controller.onSpeechEvent(it)
                    }
                }
            } finally {
                if (token == speechGeneration) controller.onSpeechStopped()
            }
        }
    }

    private fun stopListening() {
        speechGeneration++
        speechJob?.cancel()
        speechJob = null
        controller.onSpeechStopped()
    }

    fun openCompare() {
        cancelAutoAdvance()
        if (workJob?.isActive == true) return
        stopListening()
        inScope { controller.openCompare() }
    }
    fun onSpokenSentence(text: String) { stopListening(); inScope { controller.onSpokenSentence(text) } }
    fun cancelAutoAdvance() = controller.cancelAutoAdvance()
    fun setTtsEnabled(enabled: Boolean) {
        controller.setTtsEnabled(enabled)
        if (!enabled) gateways.speechOutput.stop()
    }
    fun select(index: Int) { beginTouchEdit(); controller.select(index) }
    fun openGuide() { cancelAutoAdvance(); stopListening(); inScope { controller.openGuide() } }
    fun setParkedConfirmed(confirmed: Boolean) { beginTouchEdit(); controller.setParkedConfirmed(confirmed) }
    fun confirmGuidance() {
        stopListening()
        cancelAutoAdvance()
        if (workJob?.isActive == true) return
        inScope { controller.confirmGuidance() }
    }
    fun back() {
        if (state.value.guide.busy) return
        workJob?.cancel(); stopListening(); controller.back()
    }

    fun restart() {
        workJob?.cancel(); stopListening()
        controller.restart()
        refresh()
    }

    private fun inScope(block: suspend () -> Unit) {
        workJob?.cancel()
        workJob = viewModelScope.launch { block() }
    }

    fun onStart() { foreground = true }

    fun onStop() {
        foreground = false
        if (controller.hasOwnNavigationHandoff()) return
        stopListening()
        gateways.speechOutput.stop()
        controller.cancelAutoAdvance(announce = false)
        workJob?.cancel()
    }

    fun consumeReturnToFront() = controller.consumeReturnToFront()

    override fun onCleared() {
        stopListening()
        workJob?.cancel()
        gateways.release()
    }
}
