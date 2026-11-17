package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

/** 2026-11-18 검토(P1-a·P1-b·P1-c·P3-e) 회귀 시험. */
class RobustnessRound5Test {
    private var t = LocalDateTime.of(2026, 11, 18, 13, 0)
    private val destination = NaviPlace("기존 목적지", "", 37.5, 127.0)
    private var liveCalls = 0
    private val repo = object : ItineraryRepository() {
        override suspend fun refreshLive(plan: Itinerary, position: Place?, now: LocalDateTime,
            isCurrent: () -> Boolean, onUpdate: () -> Unit) { liveCalls++; super.refreshLive(plan, position, now, isCurrent, onUpdate) }
    }
    private fun controller(vehicle: VehicleGateway = FakeVehicleGateway()) =
        ItineraryController(GatewaySet(vehicle, FakeNaviGateway(destination), FakeSpeechGateway(), true), repo, { t },
            verifyAttempts = 1, verifyDelayMs = 1)
    private fun place(id: String) = repo.catalog.first { it.place.id == id }.place

    @Test fun compareScreenRequeriesAfterFourMinutesAndKeepsExplicitSelection() = runTest {
        val c = controller()
        c.setDemoEnergy(true); c.refresh()
        c.setPlace(1, place("mart-e"))
        c.previewRoutes(); c.compare()
        val id = c.state.value.options.first { it.selectable }.id
        c.select(id)
        assertFalse(c.liveRefreshDue())
        t = t.plusSeconds(239)
        assertFalse(c.liveRefreshDue())
        t = t.plusSeconds(2)
        assertTrue("비교 화면을 4분 넘게 열어 두면 다시 조회한다", c.liveRefreshDue())
        c.refreshLive()
        assertEquals(1, liveCalls)
        assertFalse("방금 다시 읽었으면 또 읽지 않는다", c.liveRefreshDue())
        assertEquals(id, c.state.value.selectedId)
        assertTrue(c.state.value.selectionExplicit)
        c.back()
        t = t.plusMinutes(10)
        assertFalse("일정 화면에서는 자동으로 다시 조회하지 않는다", c.liveRefreshDue())
    }

    @Test fun zeroMinuteArrivalIsNotOfferedForTopUp() {
        val c = controller()
        c.setDemoEnergy(true)
        c.setPlace(1, place("mart-e"))
        c.add(Activity.ARRIVE)
        val arrive = c.state.value.plan.visits.last()
        assertEquals(0, arrive.stayMinutes)
        c.setPlace(arrive.id, place("mart-s"))
        val options = c.state.value.comparisonOptions
        assertEquals(setOf(1L), options.filter { it.mode == ChargeMode.TOP_UP }.map { it.chargeVisitId }.toSet())
        assertTrue("필요한 만큼 충전은 도착지에서도 계산한다", options.any { it.mode == ChargeMode.REQUIRED && it.chargeVisitId == arrive.id })
    }

    private fun vehicle(efficiency: Double?) = object : VehicleGateway {
        override suspend fun readBatterySoc() = BatteryReading.Known(50)
        override suspend fun readParkState() = ParkState.PARKED
        override suspend fun readRangeKm() = Measured(300.0, Evidence("Android RANGE_REMAINING", t))
        override suspend fun readEfficiencyKmPerKwh() = efficiency?.let { Measured(it, Evidence("Vehicle current EV efficiency km/kWh", t)) }
        override suspend fun readBatteryEnergyKwh() = Measured(60.0, Evidence("Android EV_BATTERY_LEVEL", t))
        override fun release() = Unit
    }

    @Test fun missingVehicleEfficiencyFallsBackToLabelledEstimate() = runTest {
        val c = controller(vehicle(null)); c.refresh()
        val energy = c.state.value.energy
        assertEquals(5.0, energy.kmPerKwh!!.value, 1e-9)
        assertTrue(energy.kmPerKwh!!.evidence.source.contains("추정"))
        assertEquals(120.0, energy.batteryCapacityKwh!!.value, 1e-9)
        assertTrue(energy.batteryCapacityKwh!!.evidence.source.contains("추정"))
        val reported = controller(vehicle(6.2)); reported.refresh()
        assertEquals("차량이 준 전비가 있으면 그것을 쓴다", 6.2, reported.state.value.energy.kmPerKwh!!.value, 1e-9)
    }

    @Test fun nearbyChargerWordingSaysParkAndWalk() {
        val now = t
        val evidence = Evidence("vehicle", now)
        val visit = Visit(1, Activity.SHOP, place = Place("a", "마트", 37.5, 127.0), stayMinutes = 30)
        val option = Option("a", listOf(visit), listOf(VisitResult(visit, now.plusMinutes(13), now.plusMinutes(51), null)),
            ChargeMode.TOP_UP, 51, 20.0, 8, 30, null, EnergyDecision(EnergyStatus.OPTIONAL, 0.0, ""), true, emptyList(), emptyList(), Availability.AVAILABLE, 1)
        val site = ChargingSite(Availability.AVAILABLE, overlapVerified = true, evidence = evidence, effectivePowerKw = Measured(50.0, evidence))
        assertTrue(benefitLine(option, Energy(), now, site.copy(sameLot = true)).startsWith("장 보는 동안 약 +"))
        assertTrue(benefitLine(option, Energy(), now, site.copy(sameLot = false)).startsWith("충전기에 세워 두고 걸어서 다녀오는 동안 약 +"))
    }
}
