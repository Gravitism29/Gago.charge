package kr.gakim.charge.data

import kr.gakim.charge.domain.Route
import java.util.Locale
import kotlin.math.abs

/**
 * 내비에서 읽은 장소를 판단 규칙용 [Route] 키로 바꾼다.
 *
 * 내비가 돌려주는 값이 우리가 보낸 값과 글자 그대로 같다는 보장이 없으므로(POI ID 부여, 좌표 보정)
 * 먼저 '이미 알고 있는 장소'와 같은 곳인지 보고, 같으면 그 장소의 키를 쓴다.
 * 같은 곳 판단: 둘 다 POI ID 가 있으면 ID 비교, 둘 다 좌표가 있으면 약 50m 안,
 * 한쪽 좌표가 비어 있으면(0,0) 이름 비교.
 * 2026-11-14 에뮬레이터(내비 서비스 3.0.4) 확인: 경유지는 좌표 0,0과 내비가 바꾼 주소로 돌아오고
 * 이름은 보낸 그대로 온다. 목적지는 좌표가 그대로, 주소는 바뀌어 온다.
 */
object PlaceMatcher {
    private const val COORD_TOLERANCE = 0.0005

    private fun NaviPlace.hasCoordinates() = latitude != 0.0 || longitude != 0.0

    fun samePlace(a: NaviPlace, b: NaviPlace): Boolean {
        if (a.poiId.isNotBlank() && b.poiId.isNotBlank()) return a.poiId == b.poiId && a.poiSubId == b.poiSubId
        if (a.hasCoordinates() && b.hasCoordinates()) {
            return abs(a.latitude - b.latitude) <= COORD_TOLERANCE &&
                abs(a.longitude - b.longitude) <= COORD_TOLERANCE
        }
        return a.name.isNotBlank() && a.name.trim() == b.name.trim()
    }

    fun keyOf(place: NaviPlace): String =
        if (place.poiId.isNotBlank()) "poi:${place.poiId}:${place.poiSubId}"
        else if (!place.hasCoordinates()) "name:${place.name.trim()}"
        else String.format(Locale.US, "geo:%.5f,%.5f", place.latitude, place.longitude)

    fun keyOf(place: NaviPlace, known: List<NaviPlace>): String =
        keyOf(known.firstOrNull { samePlace(it, place) } ?: place)

    /** 목적지가 없으면 빈 문자열(규칙에서 "목적지 확인"으로 이어진다). */
    fun toRoute(route: NaviRoute, known: List<NaviPlace> = emptyList()): Route = Route(
        destination = route.destination?.let { keyOf(it, known) } ?: "",
        stops = route.waypoints.map { keyOf(it, known) },
    )
}
