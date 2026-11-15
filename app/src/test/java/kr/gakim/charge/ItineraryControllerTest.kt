package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ItineraryControllerTest {
    private val now=LocalDateTime.of(2026,11,15,13,0)
    private val destination=NaviPlace("기존 목적지","",37.5,127.0)
    private val repo=ItineraryRepository()
    private fun controller(navi:NaviGateway=FakeNaviGateway(destination), vehicle:VehicleGateway=FakeVehicleGateway()):ItineraryController =
        ItineraryController(GatewaySet(vehicle,navi,FakeSpeechGateway(),true),repo,{now},verifyAttempts=2,verifyDelayMs=1)
    private suspend fun ready(c:ItineraryController) {
        c.setDemoEnergy(true);c.refresh();c.compare()
        c.select(c.state.value.options.first { it.selectable }.id);c.guide()
    }
    @Test fun touchAndFinalSpeechUseSamePlanAndRecalculate() = runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh()
        c.onSpeech(SpeechEvent.Final("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해"))
        assertEquals(25,c.state.value.plan.visits[0].stayMinutes);val before=c.state.value.options.first().results.last().arrival
        c.onSpeech(SpeechEvent.Final("아니, 4시 반까지"))
        assertEquals(now.withHour(16).withMinute(30),c.state.value.plan.visits.last().deadline)
        c.setStay(c.state.value.plan.visits.first().id,55)
        assertEquals(before?.plusMinutes(30),c.state.value.options.first().results.last().arrival)
    }
    @Test fun partialTranscriptNeverChangesPlan() {
        val c=controller();val before=c.state.value.plan
        c.onSpeech(SpeechEvent.Partial("은평점에서 25분"));assertEquals(before,c.state.value.plan);assertTrue(c.state.value.listening)
    }
    @Test fun fixedVenueIsKeptRatherThanForcedAlternatives() {
        val c=controller();c.setPlace(1,repo.catalog.first().place)
        assertEquals(setOf("은평점"),c.state.value.options.map { it.visits.first().place!!.name }.toSet())
    }
    @Test fun mealWithoutDeadlineDoesNotRequireSchool() {
        val c=controller();c.setActivity(1,Activity.MEAL);c.setStay(1,55);c.compare()
        assertEquals(3,c.state.value.options.map { it.visits.first().place!!.id }.distinct().size)
        assertTrue(c.state.value.plan.visits.all { it.deadline==null });assertTrue(c.state.value.options.none { it.visits.size>1 })
    }
    @Test fun hospitalArrivalAndSlackUpdateWhenDepartureChanges() {
        val c=controller();c.setActivity(1,Activity.COLLECT);c.setPlace(1,repo.catalog.first { it.place.id=="collect" }.place);c.setStay(1,15)
        c.add(Activity.HOSPITAL);val id=c.state.value.plan.visits.last().id;c.setPlace(id,repo.catalog.first { it.place.id=="hospital" }.place)
        c.setTime(id,now.withHour(14).withMinute(10));c.setTime(null,now.plusMinutes(18))
        assertEquals(now.withHour(14).withMinute(2),c.state.value.options.first().results.last().arrival)
        assertEquals(8L,c.state.value.options.first().results.last().slackMinutes)
    }
    @Test fun pastDeadlineAsksTodayOrTomorrowWithoutSilentlyRolling() {
        val c=controller();c.setTime(1,now.minusHours(1))
        assertNotNull(c.state.value.pendingTime);assertNull(c.state.value.plan.visits.first().deadline)
        c.resolvePastTime(true);assertEquals(now.plusDays(1).minusHours(1),c.state.value.plan.visits.first().deadline)
    }
    @Test fun noWriteWithoutFinalApproval() = runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.confirm()
        assertEquals(0,navi.requestCount)
    }
    @Test fun fullRouteAppliedAndReRead() = runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.setAcknowledged(true);c.confirm()
        assertEquals(1,navi.requestCount);assertEquals(ApplyPhase.APPLIED,c.state.value.phase)
        assertTrue(ItineraryController.sameRoute((navi.readRoute() as NaviRead.Known).route,c.state.value.actualRoute!!))
    }
    @Test fun changedRouteInvalidatesApprovalBeforeWrite() = runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.setAcknowledged(true)
        navi.setRoute(NaviRoute(destination.copy(name="바뀐 목적지",latitude=38.0),emptyList()));c.confirm()
        assertEquals(0,navi.requestCount);assertFalse(c.state.value.acknowledged);assertEquals(ApplyPhase.IDLE,c.state.value.phase)
    }
    @Test fun noReceiveFailsBeforeWrite() = runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.setAcknowledged(true);navi.unavailable=true;c.confirm()
        assertEquals(0,navi.requestCount);assertEquals(ApplyPhase.FAILED,c.state.value.phase)
    }
    @Test fun sentButNotAppliedStaysUnconfirmedAndDoesNotReplay() = runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.setAcknowledged(true);navi.ignoreRequests=true;c.confirm()
        assertEquals(ApplyPhase.UNCONFIRMED,c.state.value.phase);assertEquals(1,navi.requestCount)
        c.setAcknowledged(true);c.confirm();assertEquals(1,navi.requestCount)
    }
    @Test fun movingNeverAllowsTransmissionEvenWithManualParkCheckbox() = runTest {
        val navi=FakeNaviGateway(destination);val car=FakeVehicleGateway();val c=controller(navi,car);ready(c);c.setAcknowledged(true);c.setParked(true);car.parkState=ParkState.MOVING;c.confirm()
        assertEquals(0,navi.requestCount);assertEquals(ApplyPhase.FAILED,c.state.value.phase)
    }
    @Test fun lateReadCannotOverwriteNewerTouchPlan() = runTest {
        val reply=CompletableDeferred<BatteryReading>()
        val car=object:VehicleGateway {
            override suspend fun readBatterySoc()=reply.await()
            override suspend fun readParkState()=ParkState.PARKED
            override fun release()=Unit
        }
        val c=controller(vehicle=car);val work=launch { c.refresh() };runCurrent();c.setStay(1,55);reply.complete(BatteryReading.Known(10));work.join()
        assertEquals(55,c.state.value.plan.visits.first().stayMinutes);assertFalse(c.state.value.loading)
        assertEquals(10,c.state.value.energy.socPercent?.value)
    }
    @Test fun lossOfEnergyEvidenceClearsAutomaticRecommendation()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();assertNotNull(c.state.value.selectedId)
        c.setDemoEnergy(false);assertNull(c.state.value.selectedId)
    }
    @Test fun explicitDriverChoiceMayRemainWhenEnergyIsUnknown()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh()
        val option=c.state.value.options.first { it.mode==ChargeMode.NONE };c.select(option.id)
        c.setDemoEnergy(false);assertEquals(option.id,c.state.value.selectedId);assertTrue(c.state.value.selectionExplicit)
    }
    @Test fun placeChooserPrioritizesCurrentActivityAndHidesBlankSavedRows()=runTest {
        val c=controller();c.setActivity(1,Activity.COLLECT);c.refresh()
        assertEquals("물건 수령처",c.allPlaces(c.state.value.plan.visits.first()).first().name)
        assertTrue(c.allPlaces().all { it.name.isNotBlank() && it.validCoordinates })
    }
    @Test fun zeroCoordinateReplyCanOnlyReuseCoordinatesActuallySentByThisApp()=runTest {
        var route=NaviRoute(destination,emptyList())
        val navi=object:NaviGateway {
            override suspend fun readRoute():NaviRead=NaviRead.Known(route)
            override suspend fun addFirstWaypoint(place:NaviPlace)=NaviRequest.Failed("unused")
            override suspend fun applyRoute(next:NaviRoute):NaviRequest { route=next.copy(waypoints=next.waypoints.map { it.copy(latitude=0.0,longitude=0.0) });return NaviRequest.Sent }
            override fun release()=Unit
        }
        val c=controller(navi);c.setDemoEnergy(true);c.refresh()
        c.acceptTranscript("은평점에서 25분 장 보고 4시까지 학교 가야 해");c.compare();c.guide();c.setAcknowledged(true);c.confirm()
        assertEquals(ApplyPhase.APPLIED,c.state.value.phase);c.importRoute();c.confirmImport(true)
        assertTrue(c.state.value.plan.visits.all { it.place?.validCoordinates==true })
        assertEquals(37.635,c.state.value.plan.visits.first().place!!.latitude,0.00001)
    }
    @Test fun speechCorrectionWhileComparingRecalculatesWithoutLosingScreen()=runTest {
        val c=controller();c.setDemoEnergy(true);c.refresh();c.acceptTranscript("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해");c.compare()
        val before=c.state.value.options.first().results.last().slackMinutes!!
        c.acceptTranscript("아니 4시 반까지")
        assertEquals(1,c.state.value.screen);assertEquals(before+20,c.state.value.options.first().results.last().slackMinutes)
    }
    @Test fun speechEditOnConfirmationInvalidatesApproval()=runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi);ready(c);c.setAcknowledged(true);c.acceptTranscript("55분으로");c.confirm()
        assertEquals(0,navi.requestCount);assertFalse(c.state.value.acknowledged)
    }
    @Test fun fullExistingWaypointsAreReadAndOnlyApprovedReplacementIsSent()=runTest {
        val navi=FakeNaviGateway(destination,listOf(destination.copy(name="기존1",latitude=37.51),destination.copy(name="기존2",latitude=37.52)))
        val c=controller(navi);ready(c);assertEquals(2,(c.state.value.currentRoute as NaviRead.Known).route.waypoints.size)
        c.confirm();assertEquals(0,navi.requestCount);c.setAcknowledged(true);c.confirm();assertEquals(ApplyPhase.APPLIED,c.state.value.phase)
    }
    @Test fun fourthVisitIsNotSilentlyAddedBeyondNavigationCapacity() {
        val c=controller();c.add(Activity.MEAL);c.add(Activity.HOSPITAL);c.add(Activity.COLLECT);c.add(Activity.PICKUP)
        assertEquals(3,c.state.value.plan.visits.size);assertTrue(c.state.value.message?.contains("세 곳")==true)
    }
}
