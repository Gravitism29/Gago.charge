package kr.gakim.charge.domain

/** 충전기 한 대의 상태. 세 조건은 반드시 같은 충전기에서 함께 만족해야 한다. */
data class ChargerUnit(
    val compatible: Boolean,
    val available: Boolean,
    val current: Boolean,
)

/**
 * 한 충전기가 호환·사용 가능·최신 상태를 모두 만족하는지 본다.
 * 충전소 단위로 조건을 섞어 판단하지 않는다(A기는 호환, B기는 사용 가능 → 사용 불가).
 */
fun hasUsableUnit(units: List<ChargerUnit>): Boolean =
    units.any { it.compatible && it.available && it.current }

/** (충전소 ID, 충전기 ID) */
data class UnitKey(val stationId: String, val unitId: String)

/** 제공처 상태변경시각과 상태 문자열. */
data class UnitStatus(val stamp: Long, val status: String)

data class UnitUpdate(val key: UnitKey, val stamp: Long, val status: String)

const val STATUS_REQUERY = "재조회"

/**
 * 저장된 상태에 갱신분을 반영한다. 더 새로운 시각만 덮어쓰고,
 * 같은 시각에 다른 상태가 오면 어느 쪽이 맞는지 알 수 없으므로 "재조회"로 둔다.
 */
fun applyChanges(
    snapshot: Map<UnitKey, UnitStatus>,
    updates: List<UnitUpdate>,
): Map<UnitKey, UnitStatus> {
    val merged = snapshot.toMutableMap()
    for (u in updates) {
        val previous = merged[u.key]
        if (previous == null || u.stamp > previous.stamp) {
            merged[u.key] = UnitStatus(u.stamp, u.status)
        } else if (u.stamp == previous.stamp && u.status != previous.status) {
            merged[u.key] = UnitStatus(u.stamp, STATUS_REQUERY)
        }
    }
    return merged
}
