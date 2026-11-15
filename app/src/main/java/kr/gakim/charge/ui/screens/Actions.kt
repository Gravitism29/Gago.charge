package kr.gakim.charge.ui.screens

import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.ui.GakimViewModel

/** 화면이 부르는 동작 모음. 화면 코드가 ViewModel 에 직접 묶이지 않게 하고, 미리보기에서는 [None] 을 쓴다. */
class Actions(
    val cancelAutoAdvance: () -> Unit = {},
    val beginTouchEdit: () -> Unit = {},
    val toggleListening: () -> Unit = {},
    val onSpokenSentence: (String) -> Unit = {},
    val setActivityMinutes: (Int) -> Unit = {},
    val setPickup: (Boolean) -> Unit = {},
    val setDeadline: (Deadline) -> Unit = {},
    val nudgeDeadline: (Int) -> Unit = {},
    val setCharge: (ChargePreference) -> Unit = {},
    val openCompare: () -> Unit = {},
    val select: (Int) -> Unit = {},
    val openGuide: () -> Unit = {},
    val setParkedConfirmed: (Boolean) -> Unit = {},
    val confirmGuidance: () -> Unit = {},
    val back: () -> Unit = {},
    val restart: () -> Unit = {},
    val refresh: () -> Unit = {},
    val setTtsEnabled: (Boolean) -> Unit = {},
    val setUseFakes: (Boolean) -> Unit = {},
    val setDemoDestination: () -> Unit = {},
    /** 디버그 전용 시연 잔량(%). null 이면 실제 값. */
    val setDemoBatteryOverride: (Int?) -> Unit = {},
) {
    companion object { val None = Actions() }
}

fun GakimViewModel.toActions() = Actions(
    cancelAutoAdvance = ::cancelAutoAdvance,
    beginTouchEdit = ::beginTouchEdit,
    toggleListening = ::toggleListening,
    onSpokenSentence = ::onSpokenSentence,
    setActivityMinutes = ::setActivityMinutes,
    setPickup = ::setPickup,
    setDeadline = ::setDeadline,
    nudgeDeadline = ::nudgeDeadline,
    setCharge = ::setCharge,
    openCompare = ::openCompare,
    select = ::select,
    openGuide = ::openGuide,
    setParkedConfirmed = ::setParkedConfirmed,
    confirmGuidance = ::confirmGuidance,
    back = ::back,
    restart = ::restart,
    refresh = ::refresh,
    setTtsEnabled = ::setTtsEnabled,
    setUseFakes = ::setUseFakes,
    setDemoDestination = ::setDemoDestination,
    setDemoBatteryOverride = ::setDemoBatteryOverride,
)
