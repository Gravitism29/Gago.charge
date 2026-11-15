package kr.gakim.charge.domain

/** 내비 경로를 비교용으로 단순화한 값. 문자열은 장소를 구분하는 키다. */
data class Route(val destination: String, val stops: List<String> = emptyList())

/** 내비 경유지 한도. */
const val MAX_WAYPOINTS = 2

enum class RouteAction(val label: String) {
    REQUERY("현재 경로 재조회"),
    HOLD("보류"),
    RECOMPARE("계획 다시 비교"),
    CHECK_DESTINATION("목적지 확인"),
    ALREADY_APPLIED("이미 반영"),
    WAYPOINT_LIMIT("경유지 한도"),
    CHECK_EXISTING_STOPS("기존 일정 확인"),
    REQUEST_ONCE("요청 한 번"),
}

/**
 * 내비에 경유지 추가를 요청해도 되는지 판단한다.
 *
 * @param baseline 비교 화면을 만들 때 읽은 경로
 * @param current 요청 직전에 다시 읽은 경로
 * @param planned 반영되기를 기대하는 경로
 * @param parked 주차 상태인가(주행 중이면 보류)
 * @param confirmed 사용자가 확인했는가
 * @param sent 이미 요청을 보냈는가(다시 보내지 않고 재조회)
 */
fun routeDecision(
    baseline: Route,
    current: Route,
    planned: Route,
    parked: Boolean,
    confirmed: Boolean,
    sent: Boolean,
): RouteAction {
    if (sent) return RouteAction.REQUERY
    if (!parked || !confirmed) return RouteAction.HOLD
    if (current != baseline) return RouteAction.RECOMPARE
    if (planned.destination.isEmpty() || current.destination != planned.destination) {
        return RouteAction.CHECK_DESTINATION
    }
    if (current == planned) return RouteAction.ALREADY_APPLIED
    if (planned.stops.size > MAX_WAYPOINTS) return RouteAction.WAYPOINT_LIMIT
    // 기존 경유지가 계획 안에 같은 순서로 모두 남아 있어야 한다(부분수열).
    if (!isSubsequence(current.stops, planned.stops)) return RouteAction.CHECK_EXISTING_STOPS
    return RouteAction.REQUEST_ONCE
}

private fun isSubsequence(part: List<String>, whole: List<String>): Boolean {
    val rest = whole.iterator()
    return part.all { stop ->
        var found = false
        while (!found && rest.hasNext()) found = rest.next() == stop
        found
    }
}

enum class VerifyResult(val label: String) {
    PENDING("반영 확인 중"),
    APPLIED("반영 확인"),
    MISMATCH("경로 불일치"),
}

/** 요청 뒤 내비를 다시 읽은 값과 계획을 비교한다. 아직 못 읽었으면(null) 확인 중. */
fun verifyReply(actual: Route?, planned: Route): VerifyResult = when {
    actual == null -> VerifyResult.PENDING
    actual == planned -> VerifyResult.APPLIED
    else -> VerifyResult.MISMATCH
}
