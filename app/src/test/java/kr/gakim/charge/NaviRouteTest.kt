package kr.gakim.charge

import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.PlaceMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NaviRouteTest {
    private val blank = NaviPlace(name = "", address = "", latitude = 0.0, longitude = 0.0)
    private val school = NaviPlace("학교 정문", "서울 어딘가 1", 37.5, 127.0, poiId = "p1")

    @Test
    fun blankDestination_becomesNull() {
        val route = NaviRoute.fromSdk(blank, emptyList())
        assertNull(route.destination)
        assertEquals(emptyList<NaviPlace>(), route.waypoints)
    }

    @Test
    fun blankDestinationWithCoordinates_isStillNoDestination() {
        assertNull(NaviRoute.fromSdk(blank.copy(latitude = 37.6, longitude = 127.0), emptyList()).destination)
    }

    @Test
    fun realDestination_isKept_andBlankWaypointsDropped() {
        val mart = NaviPlace("A마트", "", 37.4, 127.1)
        val route = NaviRoute.fromSdk(school, listOf(blank, mart))
        assertEquals(school, route.destination)
        assertEquals(listOf(mart), route.waypoints)
    }

    @Test
    fun waypointReturnedWithZeroCoordinates_matchesByName() {
        // 에뮬레이터 실측: 보낸 A마트(37.508,127.018)가 좌표 0,0·주소 '서초구 반포동'으로 돌아왔다.
        val sent = NaviPlace("A마트 (시연)", "시연용 주소 A", 37.508, 127.018)
        val returned = NaviPlace("A마트 (시연)", "서초구 반포동", 0.0, 0.0)
        assertEquals(true, PlaceMatcher.samePlace(sent, returned))
        assertEquals(false, PlaceMatcher.samePlace(sent, returned.copy(name = "B마트 (시연)")))
    }

    @Test
    fun bothCoordinatesKnown_stillComparedByDistance() {
        val a = NaviPlace("같은 이름", "", 37.5, 127.0)
        assertEquals(false, PlaceMatcher.samePlace(a, a.copy(latitude = 37.51)))
    }

    @Test
    fun placeWithOnlyPoiId_isNotBlank() {
        val p = blank.copy(poiId = "x")
        assertEquals(p, NaviRoute.fromSdk(p, emptyList()).destination)
    }
}
