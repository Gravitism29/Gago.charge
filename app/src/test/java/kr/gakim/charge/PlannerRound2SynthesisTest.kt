package kr.gakim.charge

import java.time.LocalDateTime
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.data.remote.SourceStatus
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlannerRound2SynthesisTest {
    private var now=LocalDateTime.of(2026,11,16,15,0)
    private val evidence=Evidence("vehicle",now)
    private val low=Energy(socPercent=Measured(50,evidence),rangeKm=Measured(50.0,evidence))
    private val visit=Visit(1,Activity.MEAL,place=Place("a","식당",37.5,127.0),stayMinutes=30)
    private val site=ChargingSite(Availability.AVAILABLE,overlapVerified=true,evidence=evidence,
        effectivePowerKw=Measured(50.0,evidence))
    private fun option(mode:ChargeMode=ChargeMode.TOP_UP,status:EnergyStatus=EnergyStatus.OPTIONAL,extra:Long=8)=
        Option("a:$mode",listOf(visit),listOf(VisitResult(visit,now.plusMinutes(13),now.plusMinutes(51),null)),
            mode,51,20.0,extra,30,null,EnergyDecision(status,0.0,""),true,emptyList(),emptyList(),Availability.AVAILABLE,1)
    @Test fun ampleSocUsesNoChargeConclusionWithAndWithoutExtraTime() {
        val energy=low.copy(socPercent=Measured(80,evidence))
        // 2026-11-17 평가 반영: '오늘은 충전 안 해도 돼요'는 이번 일정만 본 판단을 넓힌 말이고 충전으로 얻는 양을 가렸다.
        assertEquals("이번 일정은 충전 없이 갈 수 있어요.",benefitLine(option(ChargeMode.NONE),energy,now,site))
        assertEquals("지금도 충분하지만, 식사하는 동안 약 +125km(추정) 충전돼요. 8분 더 걸려요.",benefitLine(option(),energy,now,site))
        assertEquals("지금도 충분하지만, 식사하는 동안 약 +125km(추정) 충전돼요. 충전해도 끝나는 시각은 같아요.",benefitLine(option(extra=0),energy,now,site))
        assertEquals("이번 일정은 충전 없이 갈 수 있어요. 배터리가 25%라 들른 김에 채워 두면 좋아요.",
            benefitLine(option(ChargeMode.NONE),low.copy(socPercent=Measured(25,evidence)),now,site))
    }
    @Test fun tripleRangeAlsoCountsAsAmpleAndStaleReadingsDoNot() {
        // 주행 가능 거리가 넉넉해도 충전으로 얻는 양은 숨기지 않는다. 오래된 잔량은 '충분'의 근거로 쓰지 않는다.
        assertTrue(benefitLine(option(),low.copy(rangeKm=Measured(60.0,evidence)),now,site).startsWith("식사하는 동안 약 +125km(추정)"))
        val stale=low.copy(socPercent=Measured(100,evidence.copy(observedAt=now.minusMinutes(3))))
        assertFalse(benefitLine(option(),stale,now,site).startsWith("지금도 충분"))
    }
    @Test fun optionalLowerBatteryUsesVisitActivityAndEstimatedGain() {
        for((activity,word) in listOf(Activity.SHOP to "장 보는 동안",Activity.MEAL to "식사하는 동안",Activity.HOSPITAL to "진료받는 동안",Activity.CUSTOM to "볼일 보는 동안")) {
            val v=visit.copy(activity=activity)
            assertEquals("$word 약 +125km(추정) 충전돼요. 8분 더 걸려요.",benefitLine(option().copy(visits=listOf(v)),low,now,site))
        }
    }
    @Test fun neededChargingHasClearNoneAndChargingConclusions() {
        assertEquals("충전 없이는 도착 후 여유가 부족해요",benefitLine(option(ChargeMode.NONE,EnergyStatus.NEED_CHARGE),low,now,site))
        assertEquals("충전하면 여유 있게 갈 수 있어요. 12분 더 걸려요.",benefitLine(option(status=EnergyStatus.NEED_CHARGE,extra=12),low,now,site))
        assertEquals("충전 없이는 도착 후 여유가 부족해요",benefitLine(option(ChargeMode.NONE,EnergyStatus.CANNOT_REACH).copy(selectable=false),low,now,site))
    }
    @Test fun controlsShowResultTimeAndRejectUnknownOrUnneededModes() {
        assertEquals("오후 3:51에 끝 · +8분",chargeControlLine(option()))
        assertEquals("오후 3:51에 끝",chargeControlLine(option(ChargeMode.NONE,extra=0)))
        val unknown=option().copy(results=listOf(VisitResult(visit,null,null,null)))
        assertEquals("시간 계산 불가",chargeControlLine(unknown));assertFalse(unknown.chargeControlEnabled())
        val unneeded=option(ChargeMode.REQUIRED)
        assertEquals("지금은 필요 없어요",chargeControlLine(unneeded));assertFalse(unneeded.chargeControlEnabled())
        assertTrue(option(ChargeMode.REQUIRED,EnergyStatus.NEED_CHARGE).chargeControlEnabled())
    }
    private fun other(finishDelta:Long=0,arrivalDelta:Long=0,distance:Double=20.0):Option {
        val v=visit.copy(place=visit.place!!.copy(id="b",name="다른 식당"))
        return option(ChargeMode.NONE).copy(id="b",visits=listOf(v),totalDistanceKm=distance,totalMinutes=51+finishDelta,
            results=listOf(VisitResult(v,now.plusMinutes(13+arrivalDelta),now.plusMinutes(51+finishDelta),null)))
    }
    private fun label(other:Option):String {
        val chosen=option(ChargeMode.NONE)
        return PlannerState(now=now,options=listOf(chosen,other)).recommendationLabel()
    }
    @Test fun recommendationOnlyClaimsStrictlyEarliestFinish() {
        assertEquals("추천 · 가장 빨리 끝나요",label(other(finishDelta=1)))
        assertEquals("추천",label(other()))
        assertEquals("추천 · 더 가까워요",label(other(arrivalDelta=1)))
        assertEquals("추천 · 더 가까워요",label(other(distance=21.0)))
        assertEquals("추천",label(other(arrivalDelta=-1,distance=19.0)))
        assertEquals("추천",PlannerState(now=now,options=listOf(option(ChargeMode.NONE))).recommendationLabel())
        val unknown=other().let { it.copy(totalMinutes=null,results=listOf(VisitResult(it.visits.single(),null,null,null))) }
        assertEquals("추천",label(unknown))
        val state=PlannerState(now=now,options=listOf(option(ChargeMode.NONE),other(finishDelta=1)))
        val slowerDisplayed=option().copy(results=listOf(VisitResult(visit,now.plusMinutes(13),now.plusMinutes(59),null)))
        assertEquals("추천",state.recommendationLabel(slowerDisplayed))
    }
    @Test fun warningsHaveOneSeparateFallbackAndNoConditionalJargon() {
        val o=option().copy(conditions=listOf("대기 시간 미확인 · 바로 충전할 경우의 조건부 예상"),chargingNote="속도 추정")
        assertEquals("지금은 충전기를 쓸 수 있어요. 자리는 도착해서 확인해요.",o.detailsConclusion())
        assertEquals("충전기 자리는 도착해야 알 수 있어요",o.copy(chargingStatus=Availability.UNKNOWN).detailsConclusion())
        assertEquals("대기 줄이 있을 수 있어요",warningFallback(o.conditions.single(),o,null))
        assertEquals("충전 속도와 주차·연결 시간은 추정이에요",warningFallback(o.chargingNote,o,null))
        assertFalse(sourceLabel(o.conditions.single()).contains("조건부 예상"))
    }
    @Test fun presentationCoversSpeechEmptyNetworkAndChargerStates() {
        assertEquals(speechNotUnderstood,PlannerState(message=speechNotUnderstood).presentationIssue()?.line)
        assertEquals(noNearbyPlace,PlannerState(screen=1).presentationIssue()?.line)
        for(source in listOf("카카오 장소","카카오 경로")) {
            val issue=PlannerState(sourceStatuses=listOf(SourceStatus(source,now,"네트워크 연결 실패"))).presentationIssue()!!
            assertEquals("인터넷 연결을 확인해 주세요",issue.line);assertTrue(issue.retry)
        }
        assertEquals(chargerDataMissing,PlannerState(screen=1,options=listOf(option(ChargeMode.NONE))).presentationIssue()?.line)
        assertNull(PlannerState(searchingPlaces=true,screen=1).presentationIssue())
    }
    @Test fun voiceReplacementChoiceNeverSendsAndMovingStillLocksIt()=runTest {
        val navi=FakeNaviGateway(NaviPlace("기존 목적지","",37.6,127.1))
        val vehicle=FakeVehicleGateway()
        val c=ItineraryController(GatewaySet(vehicle,navi,FakeSpeechGateway(),true),clock={now},verifyAttempts=1,verifyDelayMs=1)
        c.setDemoEnergy(true);c.refresh();c.compare();c.guide()
        assertFalse(c.prepareGuidance())
        c.handleVoiceCommand("거기만","test")
        assertTrue(c.state.value.acknowledged);assertEquals(0,navi.requestCount)
        now=now.plusMinutes(1);c.tick();assertFalse(c.state.value.acknowledged)
        vehicle.parkState=ParkState.MOVING;c.refreshPark()
        assertNull(c.handleVoiceCommand("그것만","test"));assertFalse(c.prepareGuidance())
        assertEquals(0,navi.requestCount)
    }
    @Test fun voiceKeepAddsExistingDestinationWithoutSending()=runTest {
        val destination=NaviPlace("기존 목적지","",37.6,127.1)
        val navi=FakeNaviGateway(destination)
        val c=ItineraryController(GatewaySet(FakeVehicleGateway(),navi,FakeSpeechGateway(),true),clock={now})
        c.setDemoEnergy(true);c.refresh();c.compare();c.guide()
        c.handleVoiceCommand("들를게","test")
        assertEquals(destination,c.state.value.selected?.visits?.last()?.place?.toNavi())
        assertEquals(destination.name,c.state.value.keptDestinationName)
        assertEquals(0,navi.requestCount);assertFalse(c.state.value.acknowledged)
    }
}
