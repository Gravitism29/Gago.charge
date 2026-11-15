package kr.gakim.charge.ui.state

import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.*

/** Pure presentation contract shared by the screen and speech output. */
object AssistantLines {
    /** 충전이 필요한 날(잔량 부족 또는 '꼭 하기')에는 제시간에 닿는 충전 선택지를 먼저 권한다. */
    fun recommendedIndex(s: UiState): Int? {
        val usable = s.options.indices.filter {
            val o = s.options[it].option
            o.selectable && o.status != TimingStatus.LATE && o.arrival != null
        }
        val wantsCharge = s.prompt == kr.gakim.charge.domain.ChargingPrompt.COMPARE_CHARGING ||
            s.form.charge == kr.gakim.charge.domain.ChargePreference.WANT
        val charging = usable.filter { s.options[it].option.mode == kr.gakim.charge.domain.StopMode.SHOP_AND_CHARGE }
        val pool = if (wantsCharge && charging.isNotEmpty()) charging else usable
        return pool.minByOrNull { s.options[it].option.arrival!! }
    }

    fun build(s: UiState): AssistantUi {
        if (s.speech.listening) return AssistantUi("듣고 있어요…", if (s.speech.heard.isBlank()) "말씀해 주세요" else "\"${s.speech.heard}\"", speak = false)
        val destination = shortName(s.destination?.name.orEmpty())
        return when (s.screen) {
            Screen.TODAY -> when {
                s.inputMessage != null -> AssistantUi(s.inputMessage)
                !s.speech.available -> AssistantUi("마이크를 쓸 수 없어요. 아래를 눌러서 정할 수 있어요.")
                s.navi == null -> AssistantUi("내비 상태를 읽는 중이에요.")
                s.navi is NaviRead.Unknown -> AssistantUi("내비 상태를 알 수 없어요.", "다시 확인해 주세요.")
                s.destination == null -> AssistantUi("내비에 목적지가 없어요.", "먼저 내비에서 가실 곳을 정해 주세요.")
                s.speech.heard.isNotBlank() && s.spokenSlots.isEmpty() ->
                    AssistantUi("잘 못 들었어요. 다시 말해 주시겠어요?", s.speech.heard)
                s.spokenSlots.isNotEmpty() && s.form.pickup && s.form.deadline == Deadline.Unset ->
                    AssistantUi("몇 시까지 ${destination}에 도착해야 하나요?", "\"네 시\", \"네 시 반까지\"처럼 말하거나 시각을 눌러 주세요.")
                (s.spokenSlots.isNotEmpty() || s.touchedForm) && s.blocker == null -> AssistantUi(when {
                    !s.form.pickup -> "장만 보고 가는 길이군요."
                    s.form.deadline == Deadline.Anytime -> "시간은 여유 있게, ${withRo(destination)} 가는 길이군요."
                    else -> (if (s.spokenSlots.isNotEmpty()) "장 보고 " else "") +
                        "${formatClock((s.form.deadline as Deadline.At).minutes)}까지 ${withIgunyo(destination)}."
                }, if (s.spokenSlots.isNotEmpty()) "두 곳을 비교할게요." else "비교하려면 아래 버튼을 눌러 주세요.")
                else -> AssistantUi("장 보고 가실 건가요?", "지금 내비 목적지는 ${withIeyo(destination)}. 말하거나 아래 예시를 눌러 보세요." +
                    if (s.battery is BatteryReading.Unknown) " 배터리 잔량을 알 수 없어요." else "")
            }
            Screen.COMPARE -> compare(s, destination)
            Screen.GUIDE -> {
                val mart = shortName(s.selected?.place?.name.orEmpty())
                val actual = s.guide.actual?.let { (it.waypoints.map { p -> p.name } + listOfNotNull(it.destination?.name)).joinToString(" → ") }
                when {
                    s.guide.verify == VerifyResult.APPLIED -> AssistantUi("${withReul(mart)} 경유지로 넣었어요. ${withNeun(destination)} 그대로예요.", actual)
                    s.guide.busy -> AssistantUi(if (s.guide.sent) "내비가 경로 계산 중이에요." else "요청을 준비하고 있어요.")
                    s.guide.message != null && s.guide.action == null -> AssistantUi(s.guide.message, "다시 시도해 주세요.")
                    s.guide.verify == VerifyResult.MISMATCH -> AssistantUi("내비 경로가 계획과 달라요. 내비 화면을 확인해 주세요.", actual)
                    s.guide.verify == VerifyResult.PENDING -> AssistantUi("아직 확인하지 못했어요.", "다시 확인해 주세요.")
                    s.guide.parkState == ParkState.MOVING -> AssistantUi("주행 중에는 바꿀 수 없어요. 정차 후 다시 시도해 주세요.")
                    s.guide.message != null -> AssistantUi(s.guide.message, "다시 시도해 주세요.")
                    else -> AssistantUi("${mart}에 들렀다가 ${withRo(destination)} 갈게요. 이대로 안내할까요?", "확인하기 전에는 내비를 바꾸지 않아요.")
                }
            }
        }
    }

    private fun compare(s: UiState, destination: String): AssistantUi {
        val decorated = s.copy(recommendedIndex = recommendedIndex(s))
        val options = martCards(decorated).mapNotNull { card ->
            val index = s.selectedIndex?.takeIf { it == card.shopOnly || it == card.charge }
                ?: s.cardModeIndices[card.martId]?.takeIf { it == card.shopOnly || it == card.charge }
                ?: defaultIndex(decorated, card)
            index?.let { s.options[it].option }?.takeIf { it.selectable && it.arrival != null }
        }
        val perMart = options
        val deadline = (s.form.deadline as? Deadline.At)?.minutes
        val line = when {
            options.isEmpty() -> "도착 시간을 알 수 없어요."
            options.all { it.status == TimingStatus.LATE } && deadline != null -> "두 곳 모두 ${formatClock(deadline)}까지는 어려워요. 그래도 들를까요?"
            perMart.size >= 2 && perMart[0].arrival != perMart[1].arrival -> {
                val fast = perMart.minBy { it.arrival!! }
                val diff = perMart.maxOf { it.arrival!! } - fast.arrival!!
                "${withGa(shortName(fast.candidate.name))} ${diff}분 더 빨라요. ${withRo(shortName(fast.candidate.name))} 갈까요?"
            }
            else -> "두 곳 다 제시간이에요."
        }
        val due = deadline?.let { "${formatClock(it)}까지" } ?: "몇 시든"
        val fastest = options.minByOrNull { it.arrival!! }
        val sub = if (fastest != null && deadline != null && options.all { it.status == TimingStatus.LATE })
            "가장 빠른 ${shortName(fastest.candidate.name)}도 ${fastest.arrival!! - deadline}분 늦어요."
        else "$destination $due · " + (s.arrivalSoc?.let { "도착 때 잔량 ${it}%" } ?: "배터리 잔량 미확인")
        return AssistantUi(line, sub)
    }

}
