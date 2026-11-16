package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

class PlannerVisualRegressionTest {
    private val now=LocalDateTime.of(2026,11,15,13,0)
    private val destination=NaviPlace("기존 목적지","",37.5,127.0)
    private fun controller(range:Double?=100.0,soc:Int=100,stale:Boolean=false,navi:NaviGateway=FakeNaviGateway(destination)):ItineraryController {
        val car=object:VehicleGateway {
            override suspend fun readBatterySoc()=BatteryReading.Known(soc)
            override suspend fun readParkState()=ParkState.PARKED
            override suspend fun readRangeKm()=range?.let { Measured(it,Evidence("RANGE_REMAINING",if(stale) now.minusMinutes(3) else now)) }
            override fun release()=Unit
        }
        return ItineraryController(GatewaySet(car,navi,FakeSpeechGateway(),true),clock={now},verifyAttempts=1,verifyDelayMs=1)
    }
    @Test fun zeroRangeAtFullSocComparesTimeAndAllowsManualCharging()=runTest {
        val c=controller(0.0);c.refresh()
        val s=c.state.value
        assertTrue(s.rangeUnknown)
        assertEquals(0.0,s.energy.rangeKm!!.value,0.0) // original evidence is retained
        assertNull(s.selectedId) // no automatic charging judgment
        assertTrue(s.options.filter { it.mode==ChargeMode.NONE }.all { it.selectable && it.totalMinutes!=null })
        val manual=s.options.first { it.mode==ChargeMode.TOP_UP && it.selectable }
        c.select(manual.id);assertEquals(manual.id,c.state.value.selectedId)
    }
    @Test fun fivePercentBoundaryTreatsZeroAsUnknown()=runTest {
        val c=controller(0.0,5);c.refresh();assertTrue(c.state.value.rangeUnknown)
        assertTrue(c.state.value.options.first { it.mode==ChargeMode.NONE }.selectable)
    }
    @Test fun zeroAtLowSocKeepsExistingBlock()=runTest {
        val c=controller(0.0,4);c.refresh();assertFalse(c.state.value.rangeUnknown)
        assertTrue(c.state.value.options.filter { it.mode==ChargeMode.NONE }.none { it.selectable })
    }
    @Test fun genuineNonzeroInsufficientRangeRemainsBlocking()=runTest {
        val c=controller(1.0);c.refresh()
        assertFalse(c.state.value.rangeUnknown)
        assertTrue(c.state.value.options.all { !it.selectable && it.energy.status==EnergyStatus.CANNOT_REACH })
        c.select(c.state.value.options.first().id);assertNull(c.state.value.selectedId)
    }
    @Test fun staleAndMissingRangeCompareTimeWithoutRecommendation()=runTest {
        for(c in listOf(controller(null),controller(100.0,stale=true))) {
            c.refresh();assertTrue(c.state.value.rangeUnknown);assertNull(c.state.value.selectedId)
            assertTrue(c.state.value.options.filter { it.mode==ChargeMode.NONE }.all { it.selectable && it.totalMinutes!=null })
        }
    }
    private suspend fun example(index:Int):PlannerState {
        val sentence=plannerExamples(now)[index]
        val c=controller();c.refresh();c.onSpeech(SpeechEvent.Final(sentence),"예시 문장");c.compare()
        val s=c.state.value
        assertNull(s.question);assertNull(s.pendingTime);assertNull(s.inputBlocker)
        assertTrue(s.plan.visits.size in 1..3)
        assertTrue(s.options.any { it.selectable && it.totalMinutes!=null })
        val typed=controller();typed.refresh();typed.acceptTranscript(sentence)
        assertEquals(typed.state.value.plan,s.plan)
        return s
    }
    @Test fun shoppingExampleProducesSchoolDeadlineAndTwentyFiveMinuteStay()=runTest {
        val s=example(0)
        assertEquals(listOf("은평점","학교"),s.plan.visits.map { it.place?.name })
        assertEquals(25,s.plan.visits.first().stayMinutes)
        assertEquals(exampleDeadline(now),s.plan.visits.last().deadline)
        assertTrue(s.plan.visits.last().deadline!!.isAfter(now))
    }
    @Test fun hospitalCollectionExampleProducesTwoResolvedVisits()=runTest {
        assertEquals(listOf("병원","물건 수령처"),example(1).plan.visits.map { it.place?.name })
    }
    @Test fun mealChargingExampleProducesThreeComparableRestaurants()=runTest {
        val s=example(2)
        assertEquals(Activity.MEAL,s.plan.visits.single().activity)
        assertEquals(30,s.plan.visits.single().stayMinutes)
        assertEquals(ChargeMode.TOP_UP,s.plan.chargeMode)
        assertEquals(3,s.options.map { it.visits.first().place?.id }.distinct().size)
    }
    @Test fun keepDestinationMaterializesCandidateAndAppendsFinalVisitWithoutSending()=runTest {
        val navi=FakeNaviGateway(destination);val c=controller(navi=navi);c.refresh();c.compare()
        c.select(c.state.value.options.first { it.mode==ChargeMode.NONE && it.selectable }.id);c.guide()
        val first=c.state.value.selected!!.visits.first().place
        assertTrue(c.state.value.canKeepDestination());c.keepExistingDestination()
        assertEquals(first,c.state.value.plan.visits.first().place)
        assertEquals(destination,c.state.value.plan.visits.last().place?.toNavi())
        assertEquals(0,c.state.value.plan.visits.last().stayMinutes)
        assertEquals(2,c.state.value.screen);assertFalse(c.state.value.acknowledged)
        assertEquals(0,navi.requestCount)
        c.setAcknowledged(true);c.confirm()
        assertEquals(ApplyPhase.APPLIED,c.state.value.phase)
        assertEquals(destination,c.state.value.actualRoute?.destination)
    }
    @Test fun keepDestinationCannotExceedThreeVisitCapacity()=runTest {
        val c=controller();c.refresh()
        c.acceptTranscript(plannerExamples(now)[1]);c.add(Activity.PICKUP)
        c.setPlace(c.state.value.plan.visits.last().id,c.allPlaces().first { it.name=="학교" });c.compare()
        c.select(c.state.value.options.first { it.mode==ChargeMode.NONE && it.selectable }.id);c.guide()
        assertFalse(c.state.value.canKeepDestination());val before=c.state.value.plan
        c.keepExistingDestination();assertEquals(before,c.state.value.plan)
    }
}
