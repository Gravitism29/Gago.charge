package kr.gakim.charge.ui.state

import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.ChargingPrompt
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.MAX_WAYPOINTS
import kr.gakim.charge.domain.MSG_PICKUP_TIME_UNSET
import kr.gakim.charge.domain.MSG_PICK_PICKUP_PLACE
import kr.gakim.charge.domain.MartOption
import kr.gakim.charge.domain.PickupIntent
import kr.gakim.charge.domain.RouteAction
import kr.gakim.charge.domain.VerifyResult
import kr.gakim.charge.domain.missingInput

/* 화면 상태. Android 에 의존하지 않는다. */

enum class Screen { TODAY, COMPARE, GUIDE }
enum class Slot { ACTIVITY, PICKUP, DEADLINE, CHARGE }
data class AssistantUi(val line: String, val sub: String? = null, val speak: Boolean = true)

/** 첫 화면 '오늘 할 일'의 입력. 전부 버튼으로 채울 수 있다. */
data class TodayForm(
    val activityMinutes: Int = 20,
    /** true=목적지에서 픽업이 있음, false=장만 보는 날. */
    val pickup: Boolean = true,
    val deadline: Deadline = Deadline.Unset,
    val charge: ChargePreference = ChargePreference.AUTO,
)

data class SpeechUi(
    val listening: Boolean = false,
    val heard: String = "",
    val note: String? = null,
    val level: Float = 0f,
    val available: Boolean = true,
)

/** 비교 화면의 선택지 하나와, 고르면 내비에 넘길 장소. */
data class OptionUi(val option: MartOption, val place: NaviPlace,
    val toMartMinutes: Int? = null, val martToDestinationMinutes: Int? = null)

data class GuideUi(
    val parkState: ParkState = ParkState.UNKNOWN,
    /** 주차 상태를 읽을 수 없을 때 사용자가 직접 확인했는가. */
    val parkedConfirmed: Boolean = false,
    val busy: Boolean = false,
    val confirmedAtMs: Long? = null,
    val requestStarted: Boolean = false,
    val returnConsumed: Boolean = false,
    /** 경유지 추가 요청을 이미 보냈는가(다시 보내지 않는다). */
    val sent: Boolean = false,
    val action: RouteAction? = null,
    val verify: VerifyResult? = null,
    val message: String? = null,
    /** 마지막으로 내비에서 다시 읽은 경로. */
    val actual: NaviRoute? = null,
)

data class UiState(
    val screen: Screen = Screen.TODAY,
    val usingFakes: Boolean,
    val dataLabel: String,
    val nowMinutes: Int,
    val thresholdPercent: Int,
    val loading: Boolean = false,
    /** null=아직 읽지 않음. */
    val battery: BatteryReading? = null,
    /** refresh 때 읽은 주차 상태. MOVING 이면 1·2 화면은 잠금 카드만 보여 준다. */
    val parkState: ParkState = ParkState.UNKNOWN,
    val parkReadAtMs: Long? = null,
    /** 디버그 시연용 잔량(%). null 이면 차에서 읽은 실제 값을 쓴다. */
    val demoBatteryOverride: Int? = null,
    val navi: NaviRead? = null,
    val form: TodayForm = TodayForm(),
    val speech: SpeechUi = SpeechUi(),
    val prompt: ChargingPrompt? = null,
    val arrivalSoc: Int? = null,
    val options: List<OptionUi> = emptyList(),
    val selectedIndex: Int? = null,
    val cardModeIndices: Map<String, Int> = emptyMap(),
    val guide: GuideUi = GuideUi(),
    val assistant: AssistantUi = AssistantUi("어디 들렀다 가세요?"),
    val spokenSlots: Set<Slot> = emptySet(),
    val autoAdvanceAtMs: Long? = null,
    val autoAdvanceDurationMs: Long = 2_500,
    val inputMessage: String? = null,
    val touchedForm: Boolean = false,
    val recommendedIndex: Int? = null,
    val ttsEnabled: Boolean = true,
) {
    val route: NaviRoute? get() = (navi as? NaviRead.Known)?.route
    val destination: NaviPlace? get() = route?.destination
    val selected: OptionUi? get() = selectedIndex?.let { options.getOrNull(it) }

    val pickupIntent: PickupIntent
        get() = if (form.pickup) PickupIntent.Pickup(destination?.name) else PickupIntent.NoPickup

    /** 비교로 넘어갈 수 없는 이유. 넘어갈 수 있으면 null. */
    val blocker: String?
        get() {
            if (form.deadline is Deadline.At && form.deadline.minutes < nowMinutes + 10) return deadlineMessage(nowMinutes)
            val read = navi ?: return "내비 상태를 읽는 중입니다."
            if (read is NaviRead.Unknown) {
                return "내비 상태를 알 수 없습니다. '다시 읽기'를 눌러 주세요. (${read.reason})"
            }
            val r = route
            if (r?.destination == null) {
                return "내비에 목적지가 없습니다. 먼저 내비에서 목적지를 설정해 주세요."
            }
            if (r.waypoints.size >= MAX_WAYPOINTS) {
                return "경유지가 이미 ${MAX_WAYPOINTS}곳이라 더 추가할 수 없습니다."
            }
            return when (missingInput(pickupIntent, form.deadline)) {
                null -> null
                MSG_PICK_PICKUP_PLACE -> "픽업 장소를 알 수 없습니다. 내비 목적지를 확인해 주세요."
                MSG_PICKUP_TIME_UNSET -> "픽업 시각을 고르거나 '몇 시든 괜찮음'을 눌러 주세요."
                else -> "입력을 확인해 주세요."
            }
        }
}

/** 현재 시각 뒤의 30분 단위 시각 네 개(빠른 선택 버튼용). */
fun deadlineChoices(nowMinutes: Int): List<Int> {
    val first = ((nowMinutes + 10 + 29) / 30) * 30
    return List(4) { first + it * 30 }
}

fun deadlineMessage(nowMinutes: Int): String =
    "지금은 ${withIeyo(kr.gakim.charge.domain.formatClock(nowMinutes))}. 그보다 뒤로 정해 주세요."
