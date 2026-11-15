package kr.gakim.charge.domain

/** 비교할 마트 후보 한 곳. 시간은 분, null 은 '알 수 없음'. */
data class MartCandidate(
    val id: String,
    val name: String,
    /** 현재 위치 → 마트 → 목적지 전체 주행 시간. */
    val driveMinutes: Int?,
    /** 주차장 진입·주차·매장까지 걷는 시간. */
    val accessMinutes: Int?,
    /** 같은 주차장에 충전 시설이 있는가. */
    val hasChargingFacility: Boolean,
    val units: List<ChargerUnit>,
    /** 충전기 대기 시간. */
    val waitMinutes: Int?,
    /** 목표량까지 충전하는 데 걸리는 시간. */
    val chargeMinutes: Int?,
)

data class MartOption(
    val candidate: MartCandidate,
    val mode: StopMode,
    /** 목적지 도착 예상 시각(분). 계산할 수 없으면 null. */
    val arrival: Int?,
    val status: TimingStatus,
    /** 이 선택지를 실제로 고를 수 있는가(충전 선택지인데 쓸 충전기가 없으면 false). */
    val selectable: Boolean,
    /** 고를 수 없을 때의 설명. */
    val note: String? = null,
)

/**
 * 후보마다 '장보기만'과 (필요하면) '함께 충전' 선택지를 만든다.
 *
 * - [prompt] 가 기본 경로면 장보기만 보여 준다.
 * - 충전 비교/사용자 선택이면 두 방식을 모두 보여 주고, 쓸 수 있는 충전기가
 *   한 대도 없으면 충전 선택지를 고를 수 없게 표시한다.
 * - [deadline] 이 null 이면 마감 없는 날이다.
 */
fun compareMarts(
    candidates: List<MartCandidate>,
    depart: Int?,
    activityMinutes: Int?,
    deadline: Int?,
    margin: Int?,
    prompt: ChargingPrompt,
): List<MartOption> = candidates.flatMap { c ->
    fun evaluate(queue: Int?, charging: Int?): Timing =
        if (deadline == null) {
            timingWithoutDeadline(depart, c.driveMinutes, c.accessMinutes, queue, activityMinutes, charging)
        } else {
            timing(depart, c.driveMinutes, c.accessMinutes, queue, activityMinutes, charging, deadline, margin)
        }

    val shopOnly = evaluate(0, 0).let { MartOption(c, StopMode.SHOP_ONLY, it.arrival, it.status, selectable = true) }
    if (prompt == ChargingPrompt.DEFAULT_ROUTE) return@flatMap listOf(shopOnly)

    val usable = c.hasChargingFacility && hasUsableUnit(c.units)
    val withCharge = if (!usable) {
        MartOption(
            c, StopMode.SHOP_AND_CHARGE, null, TimingStatus.UNKNOWN, selectable = false,
            note = MSG_SHOP_ONLY_OR_ELSEWHERE,
        )
    } else {
        evaluate(c.waitMinutes, c.chargeMinutes).let {
            MartOption(c, StopMode.SHOP_AND_CHARGE, it.arrival, it.status, selectable = true)
        }
    }
    listOf(shopOnly, withCharge)
}

/** 분 단위 시각을 "3시 55분" 형태(12시간제)로 바꾼다. */
fun formatClock(minutes: Int): String {
    val m = ((minutes % 1440) + 1440) % 1440
    val h24 = m / 60
    val h12 = if (h24 % 12 == 0) 12 else h24 % 12
    val mm = m % 60
    return if (mm == 0) "${h12}시" else "${h12}시 ${mm}분"
}
