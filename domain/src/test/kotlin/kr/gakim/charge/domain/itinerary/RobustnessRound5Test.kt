package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** 2026-11-18 검토(P1-b·P1-c·P2-d) 회귀 시험. */
class RobustnessRound5Test {
    private val now = LocalDateTime.of(2026, 11, 18, 13, 0)
    private val evidence = Evidence("테스트 값", now)
    private val place = Place("a", "식당", 37.5, 127.0)
    private val legs = listOf(Leg(Measured(10, evidence), Measured(5.0, evidence)))
    private fun site(kw: Double = 30.0, overlap: Boolean = true) = ChargingSite(Availability.AVAILABLE, waitMinutes = Measured(0, evidence),
        effectivePowerKw = Measured(kw, evidence), overlapVerified = overlap, evidence = evidence)
    private fun plan(stay: Int = 30, activity: Activity = Activity.MEAL) = Itinerary(listOf(Visit(1, activity, place = place, stayMinutes = stay)))

    @Test fun efficiencyStaysUsableForThirtyMinutes() {
        val energy = Energy(Measured(12.0, evidence), Measured(5.0, evidence.copy(observedAt = now.minusMinutes(20))))
        val option = evaluateItinerary(plan(), legs, ChargeMode.REQUIRED, 0, site(), energy.copy(rangeKm = Measured(12.0, evidence)), now)
        assertNotNull("20분 전 전비로도 필요한 충전 시간을 낸다", option.chargeMinutes)
        val stale = Energy(Measured(12.0, evidence), Measured(5.0, evidence.copy(observedAt = now.minusMinutes(31))))
        assertNull(evaluateItinerary(plan(), legs, ChargeMode.REQUIRED, 0, site(), stale, now).chargeMinutes)
    }

    @Test fun efficiencyAndCapacityEstimatesRejectImplausibleValues() {
        assertEquals(5.0, estimateEfficiencyKmPerKwh(300.0, 60.0)!!, 1e-9)
        assertNull(estimateEfficiencyKmPerKwh(300.0, 20.0))
        assertNull(estimateEfficiencyKmPerKwh(100.0, 50.0))
        assertNull(estimateEfficiencyKmPerKwh(null, 50.0))
        assertNull(estimateEfficiencyKmPerKwh(300.0, 0.0))
        assertEquals(90.0, estimateBatteryCapacityKwh(45.0, 50)!!, 1e-9)
        assertNull("SOC 가 낮으면 오차가 커서 내지 않는다", estimateBatteryCapacityKwh(5.0, 5))
        assertNull(estimateBatteryCapacityKwh(45.0, null))
    }

    @Test fun unverifiedOverlapTopUpIsNotSelectableWithReason() {
        val option = evaluateItinerary(plan(), legs, ChargeMode.TOP_UP, 0, site(overlap = false),
            Energy(Measured(100.0, evidence), Measured(5.0, evidence)), now)
        assertFalse(option.selectable)
        assertNull(option.chargeMinutes)
        assertTrue(option.conditions.any { it.contains("동시 진행 가능 여부 미확인") && it.contains("고를 수 없어요") })
    }

    @Test fun fastChargerOverlapIsCappedAtSixtyMinutesWithNote() {
        val energy = Energy(Measured(100.0, evidence), Measured(5.0, evidence))
        val option = evaluateItinerary(plan(stay = 90), legs, ChargeMode.TOP_UP, 0, site(kw = 50.0), energy, now)
        assertEquals(60, option.chargeMinutes)
        assertEquals(50.0, option.energyKwh!!, 1e-9)
        assertTrue(option.conditions.contains(FAST_CHARGER_NOTE))
        // 체류 시간은 볼일 그대로(90분)이고, 충전만 60분으로 센다.
        assertEquals(now.plusMinutes(10 + 3 + 2 + 2 + 90 + 1), option.results.single().finish)
        // 완속은 볼일 내내 충전한다.
        val slow = evaluateItinerary(plan(stay = 90), legs, ChargeMode.TOP_UP, 0, site(kw = 7.0), energy, now)
        assertEquals(90, slow.chargeMinutes)
        assertFalse(slow.conditions.contains(FAST_CHARGER_NOTE))
    }

    @Test fun knownCapacityAndSocCapEnergyAtEightyPercent() {
        val energy = Energy(Measured(100.0, evidence), Measured(5.0, evidence), Measured(50, evidence), Measured(60.0, evidence))
        val option = evaluateItinerary(plan(stay = 90), legs, ChargeMode.TOP_UP, 0, site(kw = 50.0), energy, now)
        assertEquals(18.0, option.energyKwh!!, 1e-9)
        val full = evaluateItinerary(plan(stay = 90), legs, ChargeMode.TOP_UP, 0, site(kw = 50.0),
            energy.copy(socPercent = Measured(85, evidence)), now)
        assertEquals(0.0, full.energyKwh!!, 1e-9)
    }
}
