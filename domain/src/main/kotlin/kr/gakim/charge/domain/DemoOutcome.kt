package kr.gakim.charge.domain

sealed interface DemoOutcome {
    data class Stopped(val label: String) : DemoOutcome
    data class Requested(val prompt: ChargingPrompt, val verify: VerifyResult) : DemoOutcome
}

/**
 * 저장된 학교 목적지 하나와 후보 마트 하나가 있는 통제된 전체 흐름(Python `demo_outcome`).
 * [observed] 는 요청 뒤 내비를 다시 읽은 값(모의)이다.
 *
 * [deadline] 기본값은 Python 모형과 같은 60분이다. Python 의 `no_deadline=True` 는
 * "시각을 아직 안 넣음"이므로 [Deadline.Unset] 에 해당하고, 새로 추가한
 * "몇 시든 괜찮음"은 [Deadline.Anytime] 이다.
 */
fun demoOutcome(
    estimate: Int?,
    values: StopInputs,
    approved: Boolean,
    observed: Route?,
    deadline: Deadline = Deadline.At(60),
    pickup: PickupIntent = PickupIntent.Pickup("학교 정문"),
): DemoOutcome {
    val prompt = chargingPrompt(ChargePreference.AUTO, estimate, 20)
    if (prompt == ChargingPrompt.ASK_USER) return DemoOutcome.Stopped(prompt.label)
    val form = planFromButtons(
        "장보기", pickup, deadline,
        if (prompt == ChargingPrompt.COMPARE_CHARGING) StopMode.SHOP_AND_CHARGE else StopMode.SHOP_ONLY,
    )
    val choice = planAction(form, values)
    if (choice is PlanResult.Message) return DemoOutcome.Stopped(choice.label)
    val baseline = Route("학교 정문", emptyList())
    val planned = Route("학교 정문", listOf("마트"))
    val action = routeDecision(baseline, baseline, planned, parked = true, confirmed = approved, sent = false)
    if (action != RouteAction.REQUEST_ONCE) return DemoOutcome.Stopped(action.label)
    return DemoOutcome.Requested(prompt, verifyReply(observed, planned))
}
