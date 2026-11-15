package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class UxRound3DomainTest {
    private val now = LocalDateTime.of(2026, 11, 15, 13, 0)
    private val evidence = Evidence("테스트 값", now)
    private val placeA = Place("a", "첫 방문", 37.5, 127.0)
    private val placeB = Place("b", "둘째 방문", 37.6, 127.1)
    private val visits = listOf(
        Visit(1, place = placeA, deadline = now.plusHours(1)),
        Visit(2, place = placeB, deadline = now.plusHours(2)),
    )
    private val legs = listOf(
        Leg(Measured(10, evidence), Measured(5.0, evidence)),
        Leg(Measured(20, evidence), Measured(10.0, evidence)),
    )
    private val energy = Energy(Measured(100.0, evidence), Measured(5.0, evidence))
    private val site = ChargingSite(
        Availability.AVAILABLE, waitMinutes = Measured(4, evidence),
        effectivePowerKw = Measured(30.0, evidence), overlapVerified = true, evidence = evidence,
    )

    private fun evaluate(
        plan: Itinerary = Itinerary(visits),
        mode: ChargeMode = ChargeMode.TOP_UP,
        index: Int? = 0,
        chargeSite: ChargingSite? = site,
    ) = evaluateItinerary(plan, legs, mode, index, chargeSite, energy, now)

    @Test fun defaultVisitAloneIsExplicitlyPlaceholder() {
        assertEquals(Activity.CHARGE, Activity.entries.first { it.label == "충전" })
        assertTrue(Itinerary().visits.single().placeholder)
        assertFalse(Visit(1).placeholder)
        assertFalse(Visit(2, place = placeA).placeholder)
    }

    @Test fun elapsedDepartureCannotOfferOptimisticArrivalOrSelection() {
        val option = evaluate(Itinerary(visits, departure = now.minusMinutes(1)), ChargeMode.NONE, null, null)
        assertFalse(option.selectable)
        assertTrue(option.conditions.any { it.contains("출발") && it.contains("지났") })
        assertNull(option.totalMinutes)
        option.results.forEach {
            assertNull(it.arrival)
            assertNull(it.finish)
            assertNull(it.slackMinutes)
        }
    }

    @Test fun nowDepartureRemainsValid() {
        val option = evaluate(Itinerary(visits, departure = now), ChargeMode.NONE, null, null)
        assertTrue(option.selectable)
        assertEquals(now.plusMinutes(10), option.results.first().arrival)
        assertNotNull(option.totalMinutes)
    }

    @Test fun confirmedIncompatibleAndUnavailableAreNotUnknown() {
        val incompatible = evaluate(chargeSite = site.copy(availability = Availability.INCOMPATIBLE))
        val unavailable = evaluate(chargeSite = site.copy(availability = Availability.UNAVAILABLE))
        assertEquals(Availability.INCOMPATIBLE, incompatible.chargingStatus)
        assertEquals(Availability.UNAVAILABLE, unavailable.chargingStatus)
        assertFalse(incompatible.selectable)
        assertFalse(unavailable.selectable)
    }

    @Test fun missingAndStaleChargingStatusRemainUnknown() {
        val missing = evaluate(chargeSite = null)
        val old = site.copy(availability = Availability.UNAVAILABLE, evidence = evidence.copy(observedAt = now.minusMinutes(6)))
        val stale = evaluate(chargeSite = old)
        assertEquals(Availability.UNKNOWN, missing.chargingStatus)
        assertEquals(Availability.UNKNOWN, stale.chargingStatus)
        assertTrue(missing.conditions.any { it.contains("상태 미확인") })
        assertTrue(stale.conditions.any { it.contains("상태 미확인") })
        assertTrue("오래된 사용 불가 정보만으로 현재 부적합으로 단정하지 않습니다", stale.selectable)
        assertNull("미확인 충전 상태는 자동 추천하지 않습니다", recommend(listOf(stale)))
    }

    @Test fun staleWaitIsConditionalAndNotUsedAsCurrentWait() {
        val staleWait = site.copy(waitMinutes = Measured(45, evidence.copy(observedAt = now.minusMinutes(6))))
        val option = evaluate(chargeSite = staleWait)
        assertTrue(option.conditions.any { it.contains("대기 시간 미확인") && it.contains("바로 충전") })
        // 10분 이동 + 주차 3 + 보행 2 + 연결 2 + 동시 체류 20 + 해제 1 + 다음 이동 20.
        assertEquals(now.plusMinutes(58), option.results.last().arrival)
    }

    @Test fun stalePowerCannotCreateChargeTime() {
        val oldPower = site.copy(effectivePowerKw = Measured(30.0, evidence.copy(observedAt = now.minusMinutes(6))))
        val option = evaluate(mode = ChargeMode.REQUIRED, chargeSite = oldPower)
        assertEquals(Availability.AVAILABLE, option.chargingStatus)
        assertNull(option.chargeMinutes)
        assertTrue(option.conditions.any { it.contains("산정 근거 부족") })
    }

    @Test fun chargeOnlyTopUpUsesStayAsBudgetWithoutOverlap() {
        val plan = Itinerary(listOf(Visit(1, Activity.CHARGE, place = placeA, stayMinutes = 20)))
        val option = evaluateItinerary(plan, legs.take(1), ChargeMode.TOP_UP, 0,
            site.copy(overlapVerified = false), energy, now)

        assertEquals(20, option.chargeMinutes)
        assertEquals(now.plusMinutes(42), option.results.single().finish)
        assertFalse(option.conditions.any { it.contains("동시 진행 가능 여부 미확인") })
        // 충전 전용 방문을 충전 없이 지나치는 선택은 더 이상 유효한 일정이 아니다.
        val withoutCharge = evaluateItinerary(plan, legs.take(1), ChargeMode.NONE, null, null, energy, now)
        assertFalse(withoutCharge.selectable)
        assertNull(withoutCharge.results.single().finish)
        assertTrue(withoutCharge.conditions.any { it.contains("충전하지 않는 선택") })
    }

    @Test fun chargeOnlyRequiredDoesNotAddSeparateActivityStay() {
        val plan = Itinerary(listOf(Visit(1, Activity.CHARGE, place = placeA, stayMinutes = 20)))
        val limitedEnergy = Energy(Measured(10.0, evidence), Measured(5.0, evidence))
        val option = evaluateItinerary(plan, legs.take(1), ChargeMode.REQUIRED, 0,
            site.copy(overlapVerified = false), limitedEnergy, now)

        assertEquals(6, option.chargeMinutes)
        assertEquals(now.plusMinutes(28), option.results.single().finish)
    }

    @Test fun chargeOnlyVisitCannotBeSkippedByChargingAtAnotherVisit() {
        val plan = Itinerary(listOf(
            Visit(1, Activity.CHARGE, place = placeA, deadline = now.plusHours(1)),
            Visit(2, Activity.SHOP, place = placeB, deadline = now.plusHours(2)),
        ))
        val option = evaluateItinerary(plan, legs, ChargeMode.TOP_UP, 1, site, energy, now)

        assertFalse(option.selectable)
        assertTrue(option.conditions.any { it.contains(placeA.name) && it.contains("충전하지 않는 선택") })
        assertNull(option.totalMinutes)
        option.results.forEach { assertNull(it.arrival); assertNull(it.finish); assertNull(it.slackMinutes) }
    }

    @Test fun multipleChargeOnlyVisitsAreNotCalculatedAsOneStop() {
        val plan = Itinerary(listOf(
            Visit(1, Activity.CHARGE, place = placeA),
            Visit(2, Activity.CHARGE, place = placeB),
        ))
        val option = evaluateItinerary(plan, legs, ChargeMode.TOP_UP, 0, site, energy, now)

        assertFalse(option.selectable)
        assertTrue(option.conditions.any { it.contains("한 곳") })
        assertNull(option.totalMinutes)
        option.results.forEach { assertNull(it.arrival); assertNull(it.finish); assertNull(it.slackMinutes) }
    }

    @Test fun requiredChargingOverBudgetUsesActualTimeAndExplainsExcess() {
        val plan = Itinerary(listOf(Visit(1, Activity.CHARGE, place = placeA, stayMinutes = 5)))
        val limitedEnergy = Energy(Measured(10.0, evidence), Measured(5.0, evidence))
        val option = evaluateItinerary(plan, legs.take(1), ChargeMode.REQUIRED, 0, site, limitedEnergy, now)

        assertTrue(option.selectable)
        assertEquals(6, option.chargeMinutes)
        assertEquals(now.plusMinutes(28), option.results.single().finish)
        assertTrue(option.conditions.any { it.contains("6분") && it.contains("5분") && it.contains("1분 초과") })
    }

    @Test fun zeroRangeCannotReachRouteWithoutCharging() {
        val plan = Itinerary(listOf(Visit(1, place = placeA)))
        val option = evaluateItinerary(plan, legs.take(1), ChargeMode.NONE, null, null,
            Energy(rangeKm = Measured(0.0, evidence)), now)

        assertEquals(EnergyStatus.CANNOT_REACH, option.energy.status)
        assertFalse(option.selectable)
        assertTrue(option.conditions.any { it.contains("충전 없이") && it.contains("거리 부족") })
        assertNull(recommend(listOf(option)))
    }

    @Test fun enoughRangeForRouteButNotReserveCanStillBeSelectedManually() {
        val plan = Itinerary(listOf(Visit(1, place = placeA)), reserveKm = 20.0)
        val option = evaluateItinerary(plan, legs.take(1), ChargeMode.NONE, null, null,
            Energy(rangeKm = Measured(10.0, evidence)), now)

        assertEquals(EnergyStatus.NEED_CHARGE, option.energy.status)
        assertTrue(option.selectable)
        assertTrue(option.conditions.any { it.contains("여유 거리 부족") })
        assertNull(recommend(listOf(option)))
    }

    @Test fun chargeVisitIdentifiesSameModeOptionsAndNoneHasNoChargeStatus() {
        val first = evaluate(index = 0)
        val second = evaluate(index = 1)
        assertEquals(1L, first.chargeVisitId!!)
        assertEquals(2L, second.chargeVisitId!!)
        assertNotEquals(first.id, second.id)
        assertEquals(Availability.AVAILABLE, first.chargingStatus)
        val none = evaluate(mode = ChargeMode.NONE, index = null, chargeSite = null)
        assertNull(none.chargingStatus)
        assertNull(none.chargeVisitId)
        assertTrue(none.selectable)
        assertNotNull(none.results.first().arrival)
        assertEquals(none.id, recommend(listOf(none))?.id)
    }
}
