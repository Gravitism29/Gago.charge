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
class RedesignStateTest {
    private val vehicle = FakeVehicleGateway()
    private val navi = FakeNaviGateway(DemoDataRepository.SCHOOL)
    private fun gateways(v: VehicleGateway = vehicle, n: NaviGateway = navi) = GatewaySet(v, n, FakeSpeechGateway(), true)
    private fun controller(g: GatewaySet = gateways(), clock: () -> Long = { 0L }) =
        PlanController(g, DemoDataRepository(), verifyAttempts = 3, verifyDelayMs = 1, nowMs = clock)
    private suspend fun compare(c: PlanController) { c.refresh(); c.setDeadline(Deadline.At(960)); c.openCompare() }
    private suspend fun guide(c: PlanController) { compare(c); c.select(0); c.openGuide() }

    @Test fun mutedSpokenPlanSchedulesExactly2500ms() = runTest {
        val c = controller(clock = { testScheduler.currentTime }); c.refresh()
        c.setTtsEnabled(false)
        c.onSpokenSentence("장 보고 네 시까지 애 데리러")
        assertEquals(setOf(Slot.ACTIVITY, Slot.PICKUP, Slot.DEADLINE), c.state.value.spokenSlots)
        assertEquals(2500L, c.state.value.autoAdvanceAtMs)
        advanceTimeBy(2499); c.advanceIfDue(); assertEquals(Screen.TODAY, c.state.value.screen)
        advanceTimeBy(1); c.advanceIfDue(); assertEquals(Screen.COMPARE, c.state.value.screen)
    }
    @Test fun touchNeverSchedulesAndCancelsPending() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); assertNull(c.state.value.autoAdvanceAtMs)
        c.onSpokenSentence("네 시까지"); assertNotNull(c.state.value.autoAdvanceAtMs)
        c.setActivityMinutes(30); assertNull(c.state.value.autoAdvanceAtMs)
    }
    @Test fun explicitCancelPreventsAdvance() = runTest {
        var clock = 0L; val c = controller(clock = { clock }); c.refresh(); c.onSpokenSentence("장만 보고 갈게")
        c.cancelAutoAdvance(); clock = 2000; c.advanceIfDue(); assertEquals(Screen.TODAY, c.state.value.screen)
    }
    @Test fun incompleteAndUnrecognizedDoNotAdvance() = runTest {
        val c = controller(); c.refresh(); c.onSpokenSentence("애 데리러")
        assertNull(c.state.value.autoAdvanceAtMs)
        assertTrue(c.state.value.assistant.line.startsWith("몇 시까지"))
        c.onSpokenSentence("음"); assertEquals("잘 못 들었어요. 다시 말해 주시겠어요?", c.state.value.assistant.line)
    }
    @Test fun commandNamesAndOrdinalsChooseMartNotFlatTile() = runTest {
        val c = controller(); compare(c); c.onSpokenSentence("두 번째")
        assertEquals(Screen.GUIDE, c.state.value.screen); assertEquals(DemoDataRepository.MART_B.place, c.state.value.selected?.place)
        c.back(); c.onSpokenSentence("에이마트")
        assertEquals(DemoDataRepository.MART_A.place, c.state.value.selected?.place)
    }
    @Test fun recommendedAndFastestOpenGuide() = runTest {
        val c = controller(); compare(c); val recommended = c.state.value.recommendedIndex
        c.onSpokenSentence("추천대로"); assertEquals(recommended, c.state.value.selectedIndex)
        assertEquals(Screen.GUIDE, c.state.value.screen)
        c.back(); c.onSpokenSentence("빠른 곳")
        // 잔량이 부족한 날 추천은 충전 선택지를 먼저 권하므로, '빠른 곳'은 도착 시각이 같은 가장 이른 선택지면 된다.
        assertEquals(c.state.value.options[recommended!!].option.arrival, c.state.value.selected?.option?.arrival)
    }
    @Test fun chargingCommandSelectsChargingOption() = runTest {
        val c = controller(); compare(c); c.onSpokenSentence("충전하는 걸로")
        assertEquals(StopMode.SHOP_AND_CHARGE, c.state.value.selected?.option?.mode)
        c.back(); c.onSpokenSentence("충전 없이")
        assertEquals(StopMode.SHOP_ONLY, c.state.value.selected?.option?.mode)
    }
    @Test fun compareAndGuideNeverApplyPlanIntents() = runTest {
        val c = controller(); compare(c); val before = c.state.value
        c.onSpokenSentence("다섯 시까지 충전하지 마")
        assertEquals(before.form, c.state.value.form); assertEquals(before.options, c.state.value.options)
        c.select(0); c.openGuide(); c.onSpokenSentence("장만 보고 갈게")
        assertEquals(before.form, c.state.value.form)
    }
    @Test fun returnThenSpokenPlanInvalidatesResults() = runTest {
        val c = controller(); compare(c); c.back(); c.onSpokenSentence("다섯 시까지")
        assertTrue(c.state.value.options.isEmpty()); assertNull(c.state.value.selected)
    }
    @Test fun voiceConfirmRequiresParkConfirmation() = runTest {
        vehicle.parkState = ParkState.UNKNOWN
        val c = controller(); guide(c); c.onSpokenSentence("안내해 줘"); assertEquals(0, navi.requestCount)
        c.onSpokenSentence("주차 중이야"); c.onSpokenSentence("네"); assertEquals(1, navi.requestCount)
    }
    @Test fun negatedConfirmNeverSends() = runTest {
        val c = controller(); guide(c); c.onSpokenSentence("아니 안내하지 마"); assertEquals(0, navi.requestCount)
    }
    @Test fun freshMovingStateBlocksPreviouslyParked() = runTest {
        val c = controller(); guide(c); vehicle.parkState = ParkState.MOVING; c.confirmGuidance()
        assertEquals(0, navi.requestCount); assertEquals(RouteAction.HOLD, c.state.value.guide.action)
    }
    @Test fun freshParkReadOccursAfterNaviRead() = runTest {
        var moveOnRead = false
        val wrapper = object : NaviGateway by navi {
            override suspend fun readRoute(): NaviRead {
                if (moveOnRead) vehicle.parkState = ParkState.MOVING
                return navi.readRoute()
            }
        }
        val c = controller(gateways(n = wrapper)); guide(c); moveOnRead = true; c.confirmGuidance()
        assertEquals(0, navi.requestCount)
    }
    @Test fun unknownAfterMismatchPreservesEvidence() = runTest {
        var reads = 0; var verifying = false
        val wrapper = object : NaviGateway by navi {
            override suspend fun readRoute(): NaviRead {
                if (verifying && ++reads > 1) return NaviRead.Unknown("late failure")
                return navi.readRoute()
            }
            override suspend fun addFirstWaypoint(place: NaviPlace): NaviRequest { verifying = true; return NaviRequest.Sent }
        }
        val c = controller(gateways(n = wrapper)); guide(c); c.confirmGuidance()
        assertEquals(VerifyResult.MISMATCH, c.state.value.guide.verify)
        assertNotNull(c.state.value.guide.actual)
    }
    @Test fun delayedRefreshCannotOverwriteGatewayReplacement() = runTest {
        val wait = CompletableDeferred<Unit>()
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading { wait.await(); return BatteryReading.Known(1) }
        }
        val c = controller(gateways(v = slow)); val job = launch { c.refresh() }; runCurrent()
        c.replaceGateways(gateways(v = FakeVehicleGateway(soc = 88))); c.refresh()
        wait.complete(Unit); job.join(); assertEquals(BatteryReading.Known(88), c.state.value.battery)
    }
    @Test fun laterRefreshWins() = runTest {
        val wait = CompletableDeferred<Unit>(); var reads = 0
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading {
                if (++reads == 1) { wait.await(); return BatteryReading.Known(1) }
                return BatteryReading.Known(77)
            }
        }
        val c = controller(gateways(v = slow)); val job = launch { c.refresh() }; runCurrent()
        c.refresh(); wait.complete(Unit); job.join(); assertEquals(BatteryReading.Known(77), c.state.value.battery)
    }
    @Test fun restartInvalidatesDelayedCompare() = runTest {
        val wait = CompletableDeferred<Unit>(); var delayRead = false
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading { if (delayRead) wait.await(); return vehicle.readBatterySoc() }
        }
        val c = controller(gateways(v = slow)); c.refresh(); c.setDeadline(Deadline.At(960)); delayRead = true
        val job = launch { c.openCompare() }; runCurrent(); c.restart(); wait.complete(Unit); job.join()
        assertEquals(Screen.TODAY, c.state.value.screen); assertTrue(c.state.value.options.isEmpty())
    }
    @Test fun compareDoubleTapDoesOneRead() = runTest {
        val wait = CompletableDeferred<Unit>(); var reads = 0
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading { if (++reads > 1) wait.await(); return vehicle.readBatterySoc() }
        }
        val c = controller(gateways(v = slow)); c.refresh(); c.setDeadline(Deadline.At(960))
        val first = launch { c.openCompare() }; runCurrent(); c.openCompare(); assertEquals(2, reads)
        wait.complete(Unit); first.join()
    }
    @Test fun cancelledReadDoesNotPublish() = runTest {
        val slow = object : VehicleGateway by vehicle {
            override suspend fun readBatterySoc(): BatteryReading { awaitCancellation() }
        }
        val c = controller(gateways(v = slow)); val job = launch { c.refresh() }; runCurrent(); job.cancelAndJoin()
        assertNull(c.state.value.battery)
    }
    @Test fun demoMovingAndRepeatTapsAreBlocked() = runTest {
        var requests = 0; val wait = CompletableDeferred<Unit>()
        val wrapper = object : NaviGateway by navi {
            override suspend fun setDestinationForDemo(place: NaviPlace): NaviRequest { requests++; wait.await(); return NaviRequest.Sent }
        }
        val c = controller(gateways(n = wrapper)); vehicle.parkState = ParkState.MOVING
        c.setDemoDestination(DemoDataRepository.SCHOOL); assertEquals(0, requests)
        vehicle.parkState = ParkState.PARKED
        val job = launch { c.setDemoDestination(DemoDataRepository.SCHOOL) }; runCurrent()
        c.setDemoDestination(DemoDataRepository.SCHOOL); assertEquals(1, requests)
        wait.complete(Unit); job.join()
    }
    @Test fun levelClampedAndStoppedReset() = runTest {
        val c = controller(); c.onSpeechEvent(SpeechEvent.Listening); c.onSpeechEvent(SpeechEvent.Level(3f))
        assertEquals(1f, c.state.value.speech.level); c.onSpeechStopped(); assertEquals(0f, c.state.value.speech.level)
        c.onSpeechEvent(SpeechEvent.Error("denied")); assertFalse(c.state.value.speech.available)
    }
    @Test fun distinctMissingCoordinatesHaveDistinctKeys() {
        val a = NaviPlace(" A ", "", 0.0, 0.0); val b = a.copy(name = "B")
        assertEquals("name:A", PlaceMatcher.keyOf(a)); assertNotEquals(PlaceMatcher.keyOf(a), PlaceMatcher.keyOf(b))
    }
}
