package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.data.pleos.rangeFromMeters
import kr.gakim.charge.data.pleos.selectRange
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVehicleEnergyTest {
    private val vehicle = FakeVehicleGateway()
    private fun controller() = ItineraryController(GatewaySet(vehicle, FakeNaviGateway(null), FakeSpeechGateway(), true))
    private fun range(km: Double) = Measured(km, Evidence("test range", LocalDateTime.now()))

    @Test fun pushedSocReachesStateWithoutRefresh() = runTest {
        val c = controller()
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushSoc(72); runCurrent()
        assertEquals(72, c.state.value.energy.socPercent?.value)
    }
    @Test fun pushedRangeRecalculatesWithoutRefresh() = runTest {
        val c = controller()
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushRange(range(320.0)); runCurrent()
        assertEquals(320.0, c.state.value.energy.rangeKm!!.value, 0.0)
        assertTrue(c.state.value.options.isNotEmpty())
        val before = c.state.value.options
        vehicle.pushRange(range(1.0)); runCurrent()
        assertNotEquals(before, c.state.value.options)
    }
    @Test fun unchangedRangeEventNewerThanScreenClockStaysUsable() = runTest {
        // 에뮬레이터 재현(2026-11-16): 같은 값 이벤트의 근거 시각이 화면 시계보다 몇 초 뒤여서 '주행 거리 ?' 로 떨어졌다.
        var at = LocalDateTime.of(2026, 11, 16, 10, 0, 0)
        val c = ItineraryController(GatewaySet(vehicle, FakeNaviGateway(null), FakeSpeechGateway(), true), clock = { at })
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushRange(Measured(58.0, Evidence("test range", at))); runCurrent()
        assertFalse(c.state.value.rangeUnknown)
        at = at.plusSeconds(5)
        vehicle.pushRange(Measured(58.0, Evidence("test range", at))); runCurrent()
        assertFalse(c.state.value.rangeUnknown)
    }
    @Test fun androidZeroFallsBackToPleosMeters() {
        val result = selectRange(rangeFromMeters(0.0, "Android RANGE_REMAINING"),
            rangeFromMeters(320000.0, "Pleos Vehicle SDK 주행 가능 거리"))!!
        assertEquals(320.0, result.value, 0.0)
        assertEquals("Pleos Vehicle SDK 주행 가능 거리", result.evidence.source)
    }
    @Test fun positiveAndroidWinsAndUnavailableIsUnknown() {
        val android = rangeFromMeters(500000.0, "Android RANGE_REMAINING")
        assertEquals(android, selectRange(android, range(320.0)))
        assertNull(selectRange(rangeFromMeters(0.0, "Android RANGE_REMAINING"), null))
        assertNull(selectRange(null, rangeFromMeters(Double.NaN, "Pleos")))
    }
    @Test fun demoIsOffInNewControllerAndOffRestoresLatestRealValues() = runTest {
        val c = controller()
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        c.setDemoEnergy(true)
        vehicle.pushSoc(81); vehicle.pushRange(range(320.0)); runCurrent()
        assertEquals(25, c.state.value.energy.socPercent?.value)
        assertFalse(controller().state.value.demoEnergy)
        c.setDemoEnergy(false)
        assertEquals(81, c.state.value.energy.socPercent?.value)
        assertEquals(320.0, c.state.value.energy.rangeKm!!.value, 0.0)
    }
    @Test fun socAndRangeChangesClearApproval() = runTest {
        val c = controller()
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushRange(range(320.0)); vehicle.pushSoc(70); runCurrent()
        c.compare(); c.setAcknowledged(true)
        assertTrue(c.state.value.acknowledged)
        vehicle.pushSoc(69); runCurrent()
        assertFalse(c.state.value.acknowledged); assertNull(c.state.value.approvalKey)
        c.setAcknowledged(true); assertTrue(c.state.value.acknowledged)
        vehicle.pushRange(range(319.0)); runCurrent()
        assertFalse(c.state.value.acknowledged); assertNull(c.state.value.approvalKey)
    }
    @Test fun refreshDoesNotOverwriteNewerPushWhileWaitingForNavigation() = runTest {
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val base = FakeNaviGateway(null)
        val navi = object : NaviGateway by base {
            override suspend fun readRoute(): NaviRead { waiting.complete(Unit); resume.await(); return base.readRoute() }
        }
        val c = ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), true))
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        val refresh = launch { c.refresh() }; waiting.await()
        vehicle.pushSoc(88); vehicle.pushRange(range(321.0)); runCurrent()
        resume.complete(Unit); refresh.join()
        assertEquals(88, c.state.value.energy.socPercent?.value)
        assertEquals(321.0, c.state.value.energy.rangeKm!!.value, 0.0)
    }
    @Test fun cancelledCollectorStopsReceivingUpdates() = runTest {
        val c = controller()
        val job = backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushSoc(70); runCurrent(); job.cancel(); runCurrent()
        vehicle.pushSoc(20); runCurrent()
        assertEquals(70, c.state.value.energy.socPercent?.value)
    }
    @Test fun unchangedEnergyAndParkKeepRevisionAndApproval() = runTest {
        val c = controller()
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        vehicle.pushSoc(70); vehicle.pushRange(range(320.0)); runCurrent()
        c.refreshPark(); c.compare(); c.setAcknowledged(true)
        val before = c.state.value
        assertTrue(before.acknowledged)
        repeat(3) { vehicle.pushSoc(70); vehicle.pushRange(range(320.0)); runCurrent(); c.refreshPark() }
        assertEquals(before.revision, c.state.value.revision)
        assertEquals(before.approvalKey, c.state.value.approvalKey)
        assertTrue(c.state.value.acknowledged)
    }
    @Test fun slowRemoteSurvivesEnergyTicksAndVehicleRefresh() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        val at = LocalDateTime.now()
        val real = Place("real", "이마트 성남점", 37.4127, 127.0947)
        val repo = object : kr.gakim.charge.demo.ItineraryRepository() {
            var ready = false
            override val sourceStatuses get() = if (ready) listOf(kr.gakim.charge.data.remote.SourceStatus("places", at, "1곳")) else emptyList()
            override fun candidates(visit: Visit) = if (ready) listOf(real) else super.candidates(visit)
            override suspend fun refreshRemote(plan: Itinerary, position: Place?, now: LocalDateTime) {
                started.complete(Unit); finish.await(); ready = true
            }
        }
        val c = ItineraryController(GatewaySet(vehicle, FakeNaviGateway(null), FakeSpeechGateway(), true), repo)
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        val fetch = launch { c.previewRoutes() }; started.await()
        repeat(3) { vehicle.pushSoc(70); vehicle.pushRange(range(320.0)); runCurrent(); c.refreshPark() }
        c.refresh()
        finish.complete(Unit); fetch.join()
        assertFalse(fetch.isCancelled)
        assertTrue(c.state.value.options.all { it.visits.first().place == real })
        assertEquals("1곳", c.state.value.sourceStatuses.single().status)
    }
}
