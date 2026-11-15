package kr.gakim.charge.domain

enum class TimingStatus(val label: String) {
    UNKNOWN("시간 미확인"),
    LATE("늦음"),
    TIGHT("촉박"),
    OK("여유"),

    /** 마감이 없는 날(몇 시든 괜찮음). Python 모형에는 없던 상태. */
    NO_DEADLINE("마감 없음"),
}

/** 도착 예상 시각(분)과 판정. 시각은 모두 같은 기준(예: 자정)에서 센 분 단위다. */
data class Timing(val arrival: Int?, val status: TimingStatus)

/**
 * 도착 = 출발 + 주행 + 접근 + 대기 + max(활동, 충전).
 * 대기는 활동 전에만 생기고, 연결 후 장보기와 충전을 동시에 시작한다고 본다.
 * 입력 중 하나라도 null 이면 "시간 미확인".
 */
fun timing(
    depart: Int?,
    drive: Int?,
    access: Int?,
    queue: Int?,
    activity: Int?,
    charging: Int?,
    deadline: Int?,
    margin: Int?,
): Timing {
    if (depart == null || drive == null || access == null || queue == null ||
        activity == null || charging == null || deadline == null || margin == null
    ) {
        return Timing(null, TimingStatus.UNKNOWN)
    }
    val arrival = depart + drive + access + queue + maxOf(activity, charging)
    return when {
        arrival > deadline -> Timing(arrival, TimingStatus.LATE)
        arrival + margin > deadline -> Timing(arrival, TimingStatus.TIGHT)
        else -> Timing(arrival, TimingStatus.OK)
    }
}

/**
 * 마감이 없는 날의 계산. 늦을 일이 없으므로 판정은 항상 [TimingStatus.NO_DEADLINE]이고,
 * 도착 시각은 계산할 수 있을 때만 채운다(모르면 null = 알 수 없음).
 */
fun timingWithoutDeadline(
    depart: Int?,
    drive: Int?,
    access: Int?,
    queue: Int?,
    activity: Int?,
    charging: Int?,
): Timing {
    val arrival = if (depart == null || drive == null || access == null || queue == null ||
        activity == null || charging == null
    ) {
        null
    } else {
        depart + drive + access + queue + maxOf(activity, charging)
    }
    return Timing(arrival, TimingStatus.NO_DEADLINE)
}
