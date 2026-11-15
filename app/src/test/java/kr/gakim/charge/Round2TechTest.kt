package kr.gakim.charge

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.DemoDataRepository
import kr.gakim.charge.domain.*
import kr.gakim.charge.ui.state.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Round2TechTest {
    private val vehicle = FakeVehicleGateway()
    private val navi = FakeNaviGateway(DemoDataRepository.SCHOOL)
    private fun controller(n: NaviGateway = navi, v: VehicleGateway = vehicle, clock: () -> Long = { 0L }) =
        PlanController(GatewaySet(v, n, FakeSpeechGateway(), true), DemoDataRepository(), 2, 1, clock)
    private suspend fun guide(c: PlanController) {
        c.refresh()
        c.setDeadline(Deadline.At(960))
        c.openCompare()
        c.select(0)
        c.openGuide()
    }

    @Test fun subIdsDistinguishSamePoi() {
        val a = DemoDataRepository.SCHOOL.copy(poiId = "poi", poiSubId = "one")
        val b = a.copy(poiSubId = "two")
        assertFalse(PlaceMatcher.samePlace(a, b))
        assertNotEquals(PlaceMatcher.keyOf(a), PlaceMatcher.keyOf(b))
        assertTrue(PlaceMatcher.samePlace(a, a.copy(name = "renamed")))
    }
    @Test fun noIdNoCoordinatesSameNameRemainsSame() {
        val a = NaviPlace("마트", "", 0.0, 0.0)
        assertTrue(PlaceMatcher.samePlace(a, a.copy(address = "returned")))
        assertFalse(PlaceMatcher.samePlace(a.copy(name = " "), a.copy(name = "")))
    }
    @Test fun cancelledRefreshClearsLoading() = runTest {
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading = awaitCancellation()
        }
        val c = controller(v = slow)
        val job = launch { c.refresh() }
        runCurrent()
        assertTrue(c.state.value.loading)
        job.cancelAndJoin()
        assertFalse(c.state.value.loading)
    }
    @Test fun cancelledConfirmationClearsBusy() = runTest {
        var waiting = false
        val slow = object : NaviGateway by navi {
            override suspend fun readRoute(): NaviRead {
                if (waiting) awaitCancellation()
                return navi.readRoute()
            }
        }
        val c = controller(n = slow)
        guide(c)
        waiting = true
        val job = launch { c.confirmGuidance() }
        runCurrent()
        assertTrue(c.state.value.guide.busy)
        job.cancelAndJoin()
        assertFalse(c.state.value.guide.busy)
    }
    @Test fun returnConsumedOnlyOncePerConfirmation() = runTest {
        val c = controller(clock = { testScheduler.currentTime })
        guide(c)
        c.confirmGuidance()
        assertTrue(c.consumeReturnToFront())
        assertFalse(c.consumeReturnToFront())
        c.confirmGuidance()
        assertFalse(c.consumeReturnToFront()) // requery never brought navigation forward
    }
    @Test fun returnExpiresThirtySecondsAfterConfirm() = runTest {
        var clock = 0L
        val c = controller(clock = { clock })
        guide(c)
        c.confirmGuidance()
        clock = 30_001
        assertFalse(c.consumeReturnToFront())
        assertFalse(c.hasOwnNavigationHandoff())
    }
    @Test fun activeMismatchDoesNotReturnBeforeFinalResult() = runTest {
        val mismatch = object : NaviGateway by navi {
            override suspend fun addFirstWaypoint(place: NaviPlace) = NaviRequest.Sent
        }
        val c = controller(n = mismatch)
        guide(c)
        val job = launch { c.confirmGuidance() }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(VerifyResult.MISMATCH, c.state.value.guide.verify)
        assertFalse(c.consumeReturnToFront())
        job.join()
        assertTrue(c.consumeReturnToFront())
    }
    @Test fun gearReadTimestampAndMovingBlock() = runTest {
        var clock = 123L
        val c = controller(clock = { clock })
        guide(c)
        assertEquals(123L, c.state.value.parkReadAtMs)
        clock = 4000
        vehicle.parkState = ParkState.MOVING
        c.confirmGuidance()
        assertEquals(4000L, c.state.value.parkReadAtMs)
        assertEquals(0, navi.requestCount)
    }
    @Test fun namedVoiceUsesCardChargingMode() = runTest {
        val c = controller()
        c.refresh()
        c.setCharge(ChargePreference.WANT)
        c.setDeadline(Deadline.At(1020))
        c.openCompare()
        val expected = selectionIndex(c.state.value, martCards(c.state.value)[1])
        c.onSpokenSentence("비마트로 가자!")
        assertEquals(expected, c.state.value.selectedIndex)
        assertEquals(StopMode.SHOP_AND_CHARGE, c.state.value.selected?.option?.mode)
    }
    @Test fun ordinalVoiceUsesCardChargingMode() = runTest {
        val c = controller()
        c.refresh()
        c.setCharge(ChargePreference.WANT)
        c.setDeadline(Deadline.At(1020))
        c.openCompare()
        val expected = selectionIndex(c.state.value, martCards(c.state.value)[1])
        c.onSpokenSentence("오른쪽으로!")
        assertEquals(expected, c.state.value.selectedIndex)
        assertEquals(StopMode.SHOP_AND_CHARGE, c.state.value.selected?.option?.mode)
    }
    @Test fun durationAndUnsupportedDestinationCannotAutoAdvanceWrongPlan() = runTest {
        val c = controller()
        c.refresh()
        c.onSpokenSentence("장보기 삼십 분 하고 네 시까지 애 데리러")
        assertEquals(30, c.state.value.form.activityMinutes)
        c.onSpokenSentence("병원에서 애 데리러 네 시까지")
        assertNotNull(c.state.value.inputMessage)
        assertNull(c.state.value.autoAdvanceAtMs)
    }
    @Test fun batteryChangeInvalidatesComputedComparison() = runTest {
        val c = controller()
        guide(c)
        c.setDemoBatteryOverride(15)
        assertTrue(c.state.value.options.isEmpty())
        assertNull(c.state.value.arrivalSoc)
        c.openCompare()
        assertEquals(7, c.state.value.arrivalSoc)
    }
}
