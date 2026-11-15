package kr.gakim.charge.domain

/** 픽업 의도. Python 모형의 `pickup: str | None` 을 두 경우로 나눴다. */
sealed interface PickupIntent {
    /** 장만 보는 날. 픽업 장소를 요구하지 않는다(반례 1). */
    data object NoPickup : PickupIntent

    /** 픽업이 있는 날. [place] 가 비어 있으면 아직 고르지 않은 것이다. */
    data class Pickup(val place: String?) : PickupIntent
}

/** 마감 시각. Python 모형의 `when: int | None` 을 세 경우로 나눴다. */
sealed interface Deadline {
    /** 아직 입력하지 않음. */
    data object Unset : Deadline

    /** 몇 시든 괜찮은 날. 시각 입력을 기다리지 않는다(반례 2). */
    data object Anytime : Deadline

    data class At(val minutes: Int) : Deadline
}

/** 정차 방식. Python 모형의 "함께 충전" / "선택 안 함". */
enum class StopMode(val label: String) {
    SHOP_AND_CHARGE("함께 충전"),
    SHOP_ONLY("선택 안 함"),
}

data class PlanInput(
    val activity: String,
    val pickup: PickupIntent,
    val deadline: Deadline,
    val charge: StopMode,
)

/** 음성 변환·가족 계정·네트워크와 독립인 진입점(버튼 입력). */
fun planFromButtons(activity: String, pickup: PickupIntent, deadline: Deadline, charge: StopMode) =
    PlanInput(activity, pickup, deadline, charge)

/** 후보 한 곳에 대한 입력값. null 은 '알 수 없음'이다. */
data class StopInputs(
    val depart: Int?,
    val access: Int?,
    val facility: Boolean,
    val units: List<ChargerUnit>,
    val wait: Int?,
    val chargeMinutes: Int?,
    val drive: Int? = 25,
    val activityMinutes: Int? = 20,
    val margin: Int? = 5,
)

sealed interface PlanResult {
    /** 진행할 수 없거나 사용자에게 알려야 하는 상태. */
    data class Message(val label: String) : PlanResult

    /** 장보기만 하고 간다. [pickup] 은 픽업 없는 날이면 null. */
    data class ShopOnly(val activity: String, val pickup: String?, val arrival: Int?) : PlanResult

    /** 장보기와 충전을 같은 정차에서 한다("함께 정차 예상"). [arrival] 은 계산할 수 없으면 null. */
    data class StopTogether(val arrival: Int?) : PlanResult
}

const val MSG_PICK_PICKUP_PLACE = "픽업 장소 선택"
const val MSG_PICKUP_TIME_UNSET = "픽업 시각 미정"
const val MSG_SHOP_ONLY_OR_ELSEWHERE = "장보기만 또는 다른 시설"
const val LABEL_STOP_TOGETHER = "함께 정차 예상"

/**
 * 비교를 시작하기 전에 더 받아야 할 입력이 있으면 그 안내 문구를, 없으면 null 을 돌려준다.
 *
 * - 픽업이 있는 날인데 장소가 비어 있으면 "픽업 장소 선택"
 * - 픽업이 있는 날인데 시각을 아직 안 넣었으면 "픽업 시각 미정"
 * - 장만 보는 날([PickupIntent.NoPickup])은 장소도 시각도 요구하지 않는다.
 * - 몇 시든 괜찮은 날([Deadline.Anytime])은 시각을 기다리지 않는다.
 */
fun missingInput(pickup: PickupIntent, deadline: Deadline): String? {
    if (pickup is PickupIntent.Pickup) {
        if (pickup.place.isNullOrEmpty()) return MSG_PICK_PICKUP_PLACE
        if (deadline == Deadline.Unset) return MSG_PICKUP_TIME_UNSET
    }
    return null
}

/**
 * 입력이 충분한지, 시간 안에 되는지 판단한다.
 *
 * Python 모형과 다른 점(반례 수정):
 * 1. [PickupIntent.NoPickup] 이면 픽업 장소를 요구하지 않고, 마감도 요구하지 않는다.
 * 2. [Deadline.Anytime] 이면 시각 입력을 기다리지 않고 진행한다.
 */
fun planAction(form: PlanInput, inputs: StopInputs): PlanResult {
    missingInput(form.pickup, form.deadline)?.let { return PlanResult.Message(it) }
    val pickupPlace: String? = (form.pickup as? PickupIntent.Pickup)?.place
    // Unset 은 여기까지 왔다면 픽업 없는 날이므로 마감 없는 날로 본다.
    val deadlineMinutes: Int? = (form.deadline as? Deadline.At)?.minutes

    fun evaluate(queue: Int?, charging: Int?): Timing =
        if (deadlineMinutes == null) {
            timingWithoutDeadline(inputs.depart, inputs.drive, inputs.access, queue, inputs.activityMinutes, charging)
        } else {
            timing(
                inputs.depart, inputs.drive, inputs.access, queue, inputs.activityMinutes, charging,
                deadlineMinutes, inputs.margin,
            )
        }

    fun proceeds(t: Timing) = t.status == TimingStatus.OK || t.status == TimingStatus.NO_DEADLINE

    if (form.charge == StopMode.SHOP_ONLY) {
        val t = evaluate(queue = 0, charging = 0)
        return if (proceeds(t)) PlanResult.ShopOnly(form.activity, pickupPlace, t.arrival)
        else PlanResult.Message(t.status.label)
    }
    if (!inputs.facility || !hasUsableUnit(inputs.units)) {
        return PlanResult.Message(MSG_SHOP_ONLY_OR_ELSEWHERE)
    }
    val t = evaluate(queue = inputs.wait, charging = inputs.chargeMinutes)
    return if (proceeds(t)) PlanResult.StopTogether(t.arrival) else PlanResult.Message(t.status.label)
}
