package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.ItineraryController
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiUxRound3ControllerTest {
    private var clock=LocalDateTime.of(2026,11,15,13,0)
    private val repo=ItineraryRepository()
    private val car=FakeVehicleGateway()
    private fun controller(route:NaviRoute=NaviRoute(NaviPlace("기존 목적지","",37.5,127.0),emptyList())) =
        ItineraryController(GatewaySet(car,FakeNaviGateway(route.destination,route.waypoints),FakeSpeechGateway(),true),repo,{clock},verifyAttempts=1,verifyDelayMs=1)
    @Test fun cancelledImportPreservesAllEnteredFields()=runTest {
        val c=controller();c.refresh();c.setPlace(1,repo.catalog.first().place);c.setStay(1,55);c.setTime(1,clock.plusHours(3))
        val before=c.state.value.plan;c.importRoute();assertNotNull(c.state.value.pendingImport)
        c.confirmImport(false);assertEquals(before,c.state.value.plan);assertNull(c.state.value.pendingImport)
    }
    @Test fun confirmedImportKeepsTimeAndActivityForSamePlace()=runTest {
        val p=repo.catalog.first().place
        val c=controller(NaviRoute(NaviPlace(p.name,p.address,p.latitude,p.longitude),emptyList()))
        c.refresh();c.setPlace(1,p);c.setStay(1,55);c.setTime(1,clock.plusHours(3));c.importRoute();c.confirmImport(true)
        assertEquals(55,c.state.value.plan.visits.single().stayMinutes)
        assertEquals(clock.plusHours(3),c.state.value.plan.visits.single().deadline)
        assertEquals(Activity.SHOP,c.state.value.plan.visits.single().activity)
    }
    @Test fun elapsedDepartureInvalidatesReviewAndStopsOptimisticEta()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();c.setTime(null,clock.plusMinutes(1));c.compare();c.guide();c.setAcknowledged(true)
        assertTrue(c.state.value.acknowledged);clock=clock.plusMinutes(2);c.tick()
        assertTrue(c.state.value.departureExpired);assertFalse(c.state.value.acknowledged)
        assertTrue(c.state.value.options.all { it.results.all { r->r.arrival==null } && !it.selectable })
    }
    @Test fun unchangedReviewDoesNotInvalidateAcknowledgement()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();c.compare();c.guide();c.setAcknowledged(true);c.tick()
        assertTrue(c.state.value.acknowledged)
        clock=clock.plusMinutes(1);c.tick();assertFalse(c.state.value.acknowledged)
    }
    @Test fun eachEligibleVisitGetsItsOwnChargingOptionAndNoneOnlyOnce()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();c.setPlace(1,repo.catalog.first {it.place.id=="mart-e"}.place)
        c.add(Activity.MEAL);val id=c.state.value.plan.visits.last().id;c.setPlace(id,repo.catalog.first {it.place.id=="meal-a"}.place)
        val options=c.state.value.options
        assertEquals(1,options.count {it.mode==ChargeMode.NONE})
        assertEquals(setOf(1L,id),options.filter {it.mode==ChargeMode.REQUIRED}.mapNotNull {it.chargeVisitId}.toSet())
        assertEquals(options.size,options.map {it.id}.distinct().size)
        assertEquals(id,c.state.value.selected?.chargeVisitId)
    }
    @Test fun chargeFirstUsesExistingFlowAndKnownCandidates() {
        val c=controller();c.setActivity(1,Activity.CHARGE)
        assertEquals(ChargeMode.TOP_UP,c.state.value.plan.chargeMode)
        assertTrue(c.state.value.options.isNotEmpty())
        assertTrue(c.state.value.options.none {it.chargingStatus==Availability.INCOMPATIBLE})
    }
    @Test fun refreshedMovingStateStopsEditingAndRevokesApproval()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();c.compare();c.guide();c.setAcknowledged(true)
        car.parkState=ParkState.MOVING;c.refreshPark();assertFalse(c.state.value.acknowledged)
        val before=c.state.value.plan;c.setStay(1,55);assertEquals(before,c.state.value.plan)
    }
    @Test fun clearedUnavailablePreviewDoesNotRestoreOldSelectionOnTick()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();assertNotNull(c.state.value.selectedId)
        c.clearSelection();c.tick();assertNull(c.state.value.selectedId);assertFalse(c.state.value.acknowledged)
    }
    @Test fun chargeAdditionReusesUntouchedPlaceholder() {
        val c=controller();c.add(Activity.CHARGE)
        assertEquals(1,c.state.value.plan.visits.size);assertEquals(Activity.CHARGE,c.state.value.plan.visits.single().activity)
        assertFalse(c.state.value.plan.visits.single().placeholder)
    }
    @Test fun lateFullReadDoesNotOverwriteNewerMovingPoll()=runTest {
        val routeReply=CompletableDeferred<NaviRead>()
        val navi=object:NaviGateway {
            override suspend fun readRoute()=routeReply.await()
            override suspend fun addFirstWaypoint(place:NaviPlace)=NaviRequest.Failed("unused")
            override fun release()=Unit
        }
        val c=ItineraryController(GatewaySet(car,navi,FakeSpeechGateway(),true),repo,{clock})
        val old=launch {c.refresh()};runCurrent()
        car.parkState=ParkState.MOVING;c.refreshPark();assertEquals(ParkState.MOVING,c.state.value.park)
        routeReply.complete(NaviRead.Known(NaviRoute(null,emptyList())));old.join()
        assertEquals(ParkState.MOVING,c.state.value.park)
    }
    @Test fun identicalNamesWithoutReturnedCoordinatesCannotProveWaypointOrder() {
        val x=NaviPlace("동명점","",37.5,127.0);val y=x.copy(latitude=37.6)
        val expected=NaviRoute(NaviPlace("목적지","",37.7,127.0),listOf(x,y))
        val reply=expected.copy(waypoints=listOf(x.copy(latitude=0.0,longitude=0.0),y.copy(latitude=0.0,longitude=0.0)))
        assertFalse(ItineraryController.sameRoute(reply,expected));assertTrue(ItineraryController.ambiguousRead(reply))
    }
    @Test fun uniqueWaypointNamesKeepObservedSdkFallbackWorking() {
        val x=NaviPlace("수령처","",37.5,127.0);val expected=NaviRoute(NaviPlace("병원","",37.7,127.0),listOf(x))
        assertTrue(ItineraryController.sameRoute(expected.copy(waypoints=listOf(x.copy(latitude=0.0,longitude=0.0))),expected))
    }
    @Test fun ambiguousExistingRouteIsNotReplacedWithoutIdentifiableBaseline()=runTest {
        val c=controller(NaviRoute(NaviPlace("목적지","",37.7,127.0),listOf(NaviPlace("동명점","",0.0,0.0),NaviPlace("동명점","",0.0,0.0))))
        c.setDemoEnergy(true);c.refresh();c.compare();c.guide();c.setAcknowledged(true);c.confirm()
        assertEquals(kr.gakim.charge.ui.planner.ApplyPhase.FAILED,c.state.value.phase)
        assertTrue(c.state.value.applyMessage.contains("동명이"))
    }
    @Test fun manualParkCannotSurviveMovingThenUnknown()=runTest {
        car.parkState=ParkState.UNKNOWN;val c=controller();c.refresh();c.setParked(true);assertTrue(c.state.value.parkedConfirmed)
        car.parkState=ParkState.MOVING;c.refreshPark();car.parkState=ParkState.UNKNOWN;c.refreshPark()
        assertFalse(c.state.value.parkedConfirmed);assertNull(c.state.value.parkedConfirmedAt)
    }
    @Test fun manualParkExpiresInsteadOfBeingReusedForever()=runTest {
        car.parkState=ParkState.UNKNOWN;val c=controller();c.refresh();c.setParked(true);clock=clock.plusSeconds(31);c.refreshPark()
        assertFalse(c.state.value.parkedConfirmed)
    }
    /** Hold a full refresh before its park read while the older confirmation reply is delayed. */
    private class RacingParkVehicle(private val delayedCall: Int) : VehicleGateway {
        var parkCalls = 0
        var holdEfficiency = false
        val resumeEfficiency = CompletableDeferred<Unit>()
        val delayedPark = CompletableDeferred<ParkState>()
        override suspend fun readBatterySoc(): BatteryReading = BatteryReading.Known(50)
        override suspend fun readEfficiencyKmPerKwh(): Measured<Double>? {
            if (holdEfficiency) resumeEfficiency.await()
            return null
        }
        override suspend fun readParkState(): ParkState {
            val call = ++parkCalls
            if (call == delayedCall) return delayedPark.await()
            // This is the full refresh read which starts after the delayed confirmation read.
            return if (call == delayedCall + 1) ParkState.MOVING else ParkState.PARKED
        }
        override fun release() = Unit
    }

    private class CountingNavi : NaviGateway {
        val route = NaviRoute(NaviPlace("기존 목적지", "", 37.5, 127.0), emptyList())
        var applyCalls = 0
        override suspend fun readRoute(): NaviRead = NaviRead.Known(route)
        override suspend fun addFirstWaypoint(place: NaviPlace): NaviRequest = NaviRequest.Failed("unused")
        override suspend fun applyRoute(route: NaviRoute): NaviRequest {
            applyCalls++
            return NaviRequest.Failed("unexpected request")
        }
        override fun release() = Unit
    }

    @Test fun newerMovingReadInvalidatesDelayedFinalConfirmationParkRead() = runTest {
        val vehicle = RacingParkVehicle(delayedCall = 3)
        val navi = CountingNavi()
        val c = ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), true), repo, { clock })
        c.setDemoEnergy(true); c.refresh(); c.compare(); c.guide(); c.setAcknowledged(true)
        assertTrue(c.state.value.acknowledged)
        vehicle.holdEfficiency = true
        val refresh = launch { c.refresh() }; runCurrent()
        val confirmation = launch { c.confirm() }; runCurrent()
        assertEquals(3, vehicle.parkCalls) // setup, initial confirmation, then delayed final confirmation
        vehicle.resumeEfficiency.complete(Unit); refresh.join()
        assertEquals(ParkState.MOVING, c.state.value.park)
        vehicle.delayedPark.complete(ParkState.PARKED); confirmation.join()
        assertEquals(0, navi.applyCalls)
        assertFalse(c.state.value.acknowledged)
        assertNull(c.state.value.approvalKey)
        assertEquals(kr.gakim.charge.ui.planner.ApplyPhase.FAILED, c.state.value.phase)
        assertEquals(ParkState.MOVING, c.state.value.park)
    }

    @Test fun newerMovingReadInvalidatesDelayedInitialConfirmationParkRead() = runTest {
        val vehicle = RacingParkVehicle(delayedCall = 2)
        val navi = CountingNavi()
        val c = ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), true), repo, { clock })
        c.setDemoEnergy(true); c.refresh(); c.compare(); c.guide(); c.setAcknowledged(true)
        assertTrue(c.state.value.acknowledged)
        vehicle.holdEfficiency = true
        val refresh = launch { c.refresh() }; runCurrent()
        val confirmation = launch { c.confirm() }; runCurrent()
        assertEquals(2, vehicle.parkCalls) // setup and delayed initial confirmation
        vehicle.resumeEfficiency.complete(Unit); refresh.join()
        assertEquals(ParkState.MOVING, c.state.value.park)
        vehicle.delayedPark.complete(ParkState.PARKED); confirmation.join()
        assertEquals(0, navi.applyCalls)
        assertFalse(c.state.value.acknowledged)
        assertNull(c.state.value.approvalKey)
        assertEquals(kr.gakim.charge.ui.planner.ApplyPhase.FAILED, c.state.value.phase)
        assertEquals(ParkState.MOVING, c.state.value.park)
    }

    private class SequenceParkVehicle(vararg states: ParkState) : VehicleGateway {
        private val replies = ArrayDeque(states.toList())
        var parkCalls = 0
        override suspend fun readBatterySoc(): BatteryReading = BatteryReading.Known(50)
        override suspend fun readParkState(): ParkState {
            parkCalls++
            return if (replies.isEmpty()) ParkState.PARKED else replies.removeFirst()
        }
        override fun release() = Unit
    }

    @Test fun initialMovingOrUnknownRevokesApprovalEvenWhenParkedAgain() = runTest {
        for (unsafe in listOf(ParkState.MOVING, ParkState.UNKNOWN)) {
            val vehicle = SequenceParkVehicle(ParkState.PARKED, unsafe, ParkState.PARKED)
            val navi = CountingNavi()
            val c = ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), true), repo, { clock })
            c.setDemoEnergy(true); c.refresh(); c.compare(); c.guide(); c.setAcknowledged(true)
            assertTrue(c.state.value.acknowledged)
            c.confirm()
            assertEquals(kr.gakim.charge.ui.planner.ApplyPhase.FAILED, c.state.value.phase)
            assertFalse(c.state.value.acknowledged); assertNull(c.state.value.approvalKey)
            assertEquals(0, navi.applyCalls)
            c.refreshPark()
            assertEquals(ParkState.PARKED, c.state.value.park)
            c.confirm() // The previous approval must not be reusable after the change back to P.
            assertEquals(3, vehicle.parkCalls)
            assertEquals(0, navi.applyCalls)
        }
    }

    @Test fun finalMovingRevokesApprovalAndDoesNotSend() = runTest {
        val vehicle = SequenceParkVehicle(ParkState.PARKED, ParkState.PARKED, ParkState.MOVING)
        val navi = CountingNavi()
        val c = ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), true), repo, { clock })
        c.setDemoEnergy(true); c.refresh(); c.compare(); c.guide(); c.setAcknowledged(true)
        assertTrue(c.state.value.acknowledged)
        c.confirm()
        assertEquals(3, vehicle.parkCalls)
        assertEquals(ParkState.MOVING, c.state.value.park)
        assertEquals(kr.gakim.charge.ui.planner.ApplyPhase.FAILED, c.state.value.phase)
        assertFalse(c.state.value.acknowledged); assertNull(c.state.value.approvalKey)
        assertEquals(0, navi.applyCalls)
    }

}
