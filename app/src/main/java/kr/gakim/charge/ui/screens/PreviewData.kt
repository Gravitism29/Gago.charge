package kr.gakim.charge.ui.screens

import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.demo.DemoDataRepository
import kr.gakim.charge.domain.ChargingPrompt
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.VerifyResult
import kr.gakim.charge.domain.compareMarts
import kr.gakim.charge.ui.state.AssistantLines
import kr.gakim.charge.ui.state.GuideUi
import kr.gakim.charge.ui.state.OptionUi
import kr.gakim.charge.ui.state.Screen
import kr.gakim.charge.ui.state.Slot
import kr.gakim.charge.ui.state.SpeechUi
import kr.gakim.charge.ui.state.TodayForm
import kr.gakim.charge.ui.state.UiState

/** @Preview 에서 레이아웃을 보기 위한 대표 상태. 실제 앱 흐름에서는 쓰이지 않는다. */
internal object PreviewData {
    enum class GuideKind { READY, VERIFYING, APPLIED, MOVING }

    private val route = NaviRoute(DemoDataRepository.SCHOOL, emptyList())

    private fun base() = UiState(
        usingFakes = false, dataLabel = "시연 데이터", nowMinutes = 15 * 60, thresholdPercent = 20,
        battery = BatteryReading.Known(100), navi = NaviRead.Known(route),
    )

    private fun UiState.decorated() = copy(assistant = AssistantLines.build(this), recommendedIndex = AssistantLines.recommendedIndex(this))

    fun today(): UiState = base().decorated()

    fun todayDeadlineMissing(): UiState = base().copy(
        form = TodayForm(deadline = Deadline.Unset),
        spokenSlots = setOf(Slot.ACTIVITY, Slot.PICKUP),
        speech = SpeechUi(heard = "장 보고 애 데리러 가야 해"),
    ).decorated()

    fun compare(selected: Int?, deadline: Int = 16 * 60): UiState {
        val marts = listOf(DemoDataRepository.MART_A, DemoDataRepository.MART_B)
        val details = marts.associateBy { it.candidate.id }
        val options = compareMarts(marts.map { it.candidate }, 15 * 60, 20, deadline, 5, ChargingPrompt.COMPARE_CHARGING)
            .map { o -> details.getValue(o.candidate.id).let { OptionUi(o, it.place, it.toMartMinutes, it.martToDestinationMinutes) } }
        return base().copy(
            screen = Screen.COMPARE, form = TodayForm(deadline = Deadline.At(deadline)),
            prompt = ChargingPrompt.COMPARE_CHARGING, arrivalSoc = 92, options = options, selectedIndex = selected,
        ).decorated()
    }

    fun guide(kind: GuideKind): UiState {
        val s = compare(selected = 2)
        val planned = NaviRoute(DemoDataRepository.SCHOOL, listOf(DemoDataRepository.MART_B.place))
        val guide = when (kind) {
            GuideKind.READY -> GuideUi(parkState = ParkState.UNKNOWN)
            GuideKind.MOVING -> GuideUi(parkState = ParkState.MOVING)
            GuideKind.VERIFYING -> GuideUi(parkState = ParkState.PARKED, busy = true, sent = true, verify = VerifyResult.PENDING)
            GuideKind.APPLIED -> GuideUi(parkState = ParkState.PARKED, sent = true, verify = VerifyResult.APPLIED, actual = planned)
        }
        return s.copy(screen = Screen.GUIDE, guide = guide).decorated()
    }
}
