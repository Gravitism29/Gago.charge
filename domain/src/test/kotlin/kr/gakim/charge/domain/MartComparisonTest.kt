package kr.gakim.charge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 기획 문서 1절의 시연 예시 값(가상)이 규칙에서 그대로 나오는지 확인한다. */
class MartComparisonTest {

    private val usable = listOf(ChargerUnit(true, true, true))
    private val martA = MartCandidate("A", "A마트", 30, 5, true, usable, waitMinutes = 10, chargeMinutes = 25)
    private val martB = MartCandidate("B", "B마트", 27, 5, true, usable, waitMinutes = 0, chargeMinutes = 20)
    private val at15 = 15 * 60
    private val at16 = 16 * 60

    private fun options(prompt: ChargingPrompt, deadline: Int? = at16) =
        compareMarts(listOf(martA, martB), at15, 20, deadline, 5, prompt)

    @Test
    fun demoValues() {
        val o = options(ChargingPrompt.COMPARE_CHARGING)
        assertEquals(4, o.size)
        // A마트 장보기만 → 3시 55분, 여유
        assertEquals("3시 55분", formatClock(o[0].arrival!!))
        assertEquals(TimingStatus.OK, o[0].status)
        // A마트에서 목표량까지 충전 → 4시 10분, 늦음
        assertEquals("4시 10분", formatClock(o[1].arrival!!))
        assertEquals(TimingStatus.LATE, o[1].status)
        // B마트 대기 없이 충전 → 3시 52분, 여유
        assertEquals("3시 52분", formatClock(o[3].arrival!!))
        assertEquals(TimingStatus.OK, o[3].status)
    }

    @Test
    fun defaultRouteShowsShopOnly() {
        val o = options(ChargingPrompt.DEFAULT_ROUTE)
        assertEquals(listOf(StopMode.SHOP_ONLY, StopMode.SHOP_ONLY), o.map { it.mode })
    }

    @Test
    fun askUserShowsBothModes() {
        assertEquals(4, options(ChargingPrompt.ASK_USER).size)
    }

    @Test
    fun mixedUnitsAcrossStationAreNotUsable() {
        val mixed = martA.copy(units = listOf(ChargerUnit(true, false, true), ChargerUnit(false, true, true)))
        val o = compareMarts(listOf(mixed), at15, 20, at16, 5, ChargingPrompt.COMPARE_CHARGING)
        assertFalse(o[1].selectable)
        assertNull(o[1].arrival)
        assertEquals("장보기만 또는 다른 시설", o[1].note)
    }

    @Test
    fun unknownWaitIsUnknownNotZero() {
        val o = compareMarts(listOf(martA.copy(waitMinutes = null)), at15, 20, at16, 5, ChargingPrompt.COMPARE_CHARGING)
        assertEquals(TimingStatus.UNKNOWN, o[1].status)
        assertNull(o[1].arrival)
    }

    @Test
    fun noDeadlineNeverLate() {
        val o = options(ChargingPrompt.COMPARE_CHARGING, deadline = null)
        assertEquals(setOf(TimingStatus.NO_DEADLINE), o.map { it.status }.toSet())
        assertEquals("4시 10분", formatClock(o[1].arrival!!))
    }

    @Test
    fun clockFormat() {
        assertEquals("4시", formatClock(16 * 60))
        assertEquals("12시 5분", formatClock(12 * 60 + 5))
        assertEquals("12시 30분", formatClock(30))
    }
}
