package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ItineraryTest {
    private val now = LocalDateTime.of(2026,11,15,13,0)
    private val evidence = Evidence("test measured",now)
    private val a = Place("a","수령처",37.5,127.0)
    private val b = Place("b","병원",37.6,127.1)
    private fun leg(minutes:Int,km:Double) = Leg(Measured(minutes,evidence),Measured(km,evidence))
    private fun energy(range:Double=30.0) = Energy(Measured(range,evidence),Measured(5.0,evidence),Measured(25,evidence))
    private val site = ChargingSite(Availability.AVAILABLE,waitMinutes=Measured(0,evidence),effectivePowerKw=Measured(30.0,evidence),overlapVerified=true,evidence=evidence)
    private fun plan(stay:Int=20) = Itinerary(listOf(Visit(1,place=a,stayMinutes=stay)),reserveKm=10.0)

    @Test fun sameSocDifferentRouteChangesChargingDecision() {
        assertEquals(EnergyStatus.OPTIONAL,energyDecision(energy(),listOf(leg(10,8.0)),0,10.0,now).status)
        assertEquals(EnergyStatus.NEED_CHARGE,energyDecision(energy(),listOf(leg(10,25.0)),0,10.0,now).status)
    }
    @Test fun cannotReachChargerIsDifferentFromNeedingChargeAfterwards() {
        assertEquals(EnergyStatus.CANNOT_REACH,energyDecision(energy(5.0),listOf(leg(10,8.0)),0,10.0,now).status)
        assertEquals(EnergyStatus.NEED_CHARGE,energyDecision(energy(10.0),listOf(leg(10,8.0),leg(20,20.0)),0,10.0,now).status)
    }
    @Test fun socAloneNeverInventsRange() {
        assertEquals(EnergyStatus.UNKNOWN,energyDecision(Energy(socPercent=Measured(100,evidence)),listOf(leg(10,8.0)),0,10.0,now).status)
    }
    @Test fun staleRangeIsUnknown() {
        val stale = energy().copy(rangeKm=Measured(100.0,evidence.copy(observedAt=now.minusMinutes(3))))
        assertEquals(EnergyStatus.UNKNOWN,energyDecision(stale,listOf(leg(1,1.0)),0,10.0,now).status)
    }
    @Test fun missingOrStaleLegDoesNotProduceConfidentDecision() {
        assertEquals(EnergyStatus.UNKNOWN,energyDecision(energy(),listOf(Leg(null,null)),0,10.0,now).status)
        assertEquals(EnergyStatus.UNKNOWN,energyDecision(energy(),listOf(Leg(null,Measured(2.0,evidence.copy(observedAt=now.minusHours(1))))),0,10.0,now).status)
    }
    @Test fun overlapIsCountedOnceAndOverheadsAreAdded() {
        assertEquals(63,stopMinutes(55,55,site))
        assertEquals(118,stopMinutes(55,55,site.copy(overlapVerified=false)))
    }
    @Test fun queuingAddsSeparately() {
        assertEquals(73,stopMinutes(55,55,site.copy(waitMinutes=Measured(10,evidence))))
    }
    @Test fun arbitraryStayChangesCompletionAndDownstreamSlack() {
        val visits = listOf(Visit(1,place=a,stayMinutes=20),Visit(2,Activity.HOSPITAL,place=b,stayMinutes=90,deadline=now.plusHours(2)))
        fun run(stay:Int) = evaluateItinerary(Itinerary(visits.map { if(it.id==1L) it.copy(stayMinutes=stay) else it }),listOf(leg(12,8.0),leg(17,11.0)),ChargeMode.NONE,null,null,energy(100.0),now)
        assertEquals(35L,java.time.Duration.between(run(20).results[1].arrival,run(55).results[1].arrival).toMinutes())
        assertEquals(35L,run(20).results[1].slackMinutes!!-run(55).results[1].slackMinutes!!)
    }
    @Test fun appointmentMeansArrivalBeforeItsOwnStay() {
        val visits = listOf(Visit(1,Activity.COLLECT,place=a,stayMinutes=15),Visit(2,Activity.HOSPITAL,place=b,stayMinutes=90,deadline=now.withHour(14).withMinute(10)))
        val result = evaluateItinerary(Itinerary(visits,departure=now.plusMinutes(18)),listOf(leg(12,8.0),leg(17,11.0)),ChargeMode.NONE,null,null,energy(100.0),now)
        assertEquals(now.withHour(14).withMinute(2),result.results[1].arrival)
        assertEquals(8L,result.results[1].slackMinutes)
    }
    @Test fun noDeadlineProducesCompletionWithoutAskingForPickup() {
        val result=evaluateItinerary(plan(55),listOf(leg(10,7.0)),ChargeMode.NONE,null,null,energy(),now)
        assertEquals(65L,result.totalMinutes);assertNull(result.critical?.slackMinutes)
    }
    @Test fun unknownTravelLeavesArrivalUnknown() {
        val result=evaluateItinerary(plan(),listOf(Leg(null,null)),ChargeMode.NONE,null,null,energy(),now)
        assertNull(result.totalMinutes);assertNull(result.results.first().arrival)
    }
    @Test fun incompatibleAndUnknownAreDifferent() {
        val bad=evaluateItinerary(plan(),listOf(leg(10,7.0)),ChargeMode.TOP_UP,0,site.copy(availability=Availability.INCOMPATIBLE),energy(),now)
        val unknown=evaluateItinerary(plan(),listOf(leg(10,7.0)),ChargeMode.TOP_UP,0,site.copy(availability=Availability.UNKNOWN),energy(),now)
        assertFalse(bad.selectable);assertTrue(unknown.selectable);assertTrue(unknown.conditions.any { "미확인" in it })
    }
    @Test fun requiredChargeUsesDistanceEfficiencyAndEffectivePower() {
        val result=evaluateItinerary(plan(),listOf(leg(10,25.0)),ChargeMode.REQUIRED,0,site,energy(30.0),now)
        assertEquals(1.0,result.energyKwh!!,0.0001);assertEquals(2,result.chargeMinutes)
    }
    @Test fun noEfficiencyDoesNotInventRequiredChargingTime() {
        val result=evaluateItinerary(plan(),listOf(leg(10,25.0)),ChargeMode.REQUIRED,0,site,energy().copy(kmPerKwh=null),now)
        assertNull(result.chargeMinutes);assertNull(result.totalMinutes)
    }
    @Test fun shortTopUpCannotPromiseEnoughForContinuation() {
        val result=evaluateItinerary(plan(1),listOf(leg(10,25.0),),ChargeMode.TOP_UP,0,site.copy(effectivePowerKw=Measured(1.0,evidence)),energy(26.0),now)
        assertFalse(result.selectable);assertTrue(result.conditions.any { "이후 경로" in it })
    }
    @Test fun unknownQueueIsConditionalBesideArrival() {
        val result=evaluateItinerary(plan(),listOf(leg(10,7.0)),ChargeMode.TOP_UP,0,site.copy(waitMinutes=null),energy(),now)
        assertNotNull(result.totalMinutes);assertTrue(result.conditions.any { "바로 충전할 경우" in it })
    }
    @Test fun movingVisitPreservesItsOwnPlaceTimeAndStay() {
        val original=Itinerary(listOf(Visit(1,place=a,stayMinutes=55,deadline=now.plusHours(1)),Visit(2,place=b)))
        assertEquals(original.visits[0],moveVisit(original,0,1).visits[1])
    }
    @Test fun unknownEnergyNeverProducesAutomaticRecommendation() {
        val result=evaluateItinerary(plan(),listOf(leg(10,7.0)),ChargeMode.NONE,null,null,Energy(),now)
        assertNull(recommend(listOf(result)))
    }
    @Test fun routeWithEnoughRangeMayBeatAnotherRouteRequiringCharge() {
        val close=evaluateItinerary(plan(),listOf(leg(5,5.0)),ChargeMode.NONE,null,null,energy(20.0),now)
        val far=evaluateItinerary(plan(),listOf(leg(20,15.0)),ChargeMode.REQUIRED,0,site,energy(20.0),now)
        assertEquals(close.id,recommend(listOf(far,close))?.id)
    }
    // 2026-11-17 사용자 결정: 약속에 여유가 넉넉하면 '들른 김에 충전'을 기본으로 권한다.
    @Test fun topUpIsRecommendedWhenSlackIsAmple() {
        fun options(deadline:LocalDateTime?,siteNow:ChargingSite=site):List<Option> {
            val p=Itinerary(listOf(Visit(1,place=a,stayMinutes=30),Visit(2,Activity.ARRIVE,place=b,deadline=deadline)),reserveKm=10.0)
            val legs=listOf(leg(10,5.0),leg(10,5.0))
            return listOf(evaluateItinerary(p,legs,ChargeMode.NONE,null,null,energy(100.0),now),evaluateItinerary(p,legs,ChargeMode.TOP_UP,0,siteNow,energy(100.0),now))
        }
        assertEquals(ChargeMode.TOP_UP,recommend(options(now.plusHours(2)))?.mode)
        assertEquals(ChargeMode.TOP_UP,recommend(options(null),25)?.mode)
        // 충전하면 약속 여유가 15분 아래로 줄면 빠른 쪽 그대로.
        assertEquals(ChargeMode.NONE,recommend(options(now.plusMinutes(70)))?.mode)
        // 배터리가 이미 넉넉하거나, 충전기가 지금 사용 가능이 아니거나, 대기로 15분 넘게 늘면 권하지 않는다.
        assertEquals(ChargeMode.NONE,recommend(options(now.plusHours(2)),80)?.mode)
        assertEquals(ChargeMode.NONE,recommend(options(now.plusHours(2),site.copy(availability=Availability.UNKNOWN)))?.mode)
        assertEquals(ChargeMode.NONE,recommend(options(now.plusHours(2),site.copy(waitMinutes=Measured(10,evidence))))?.mode)
    }
}
