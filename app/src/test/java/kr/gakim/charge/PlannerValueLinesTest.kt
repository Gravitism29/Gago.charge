package kr.gakim.charge

import java.time.LocalDateTime
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

/** 2026-11-18 PT 심사: 아끼는 시간·배터리 변화·경고 색·중간 문장 처리. */
class PlannerValueLinesTest {
    private val now=LocalDateTime.of(2026,11,18,15,0)
    private val evidence=Evidence("vehicle",now)
    private val shop=Visit(1,Activity.SHOP,place=Place("m","마트",37.5,127.0),stayMinutes=25)
    private val site=ChargingSite(Availability.AVAILABLE,overlapVerified=true,evidence=evidence,effectivePowerKw=Measured(50.0,evidence))
    private fun option(mode:ChargeMode=ChargeMode.TOP_UP,extra:Long?=8,charge:Int?=25,kwh:Double?=null,visit:Visit=shop,
                       status:EnergyStatus=EnergyStatus.OPTIONAL,deadline:LocalDateTime?=null,slack:Long?=null)=
        Option("m:$mode",listOf(visit.copy(deadline=deadline)),listOf(VisitResult(visit.copy(deadline=deadline),now.plusMinutes(10),now.plusMinutes(43),slack)),
            mode,43,5.0,extra,charge,kwh,EnergyDecision(status,0.0,""),true,emptyList(),emptyList(),Availability.AVAILABLE,1)

    @Test fun savedTimeComparesWithASeparateChargerStop() {
        // 따로 들르면 충전 25분 + 주차·도보·연결·분리 8분 = 33분. 볼일 중 충전은 8분만 더 든다.
        assertEquals(33,separateStopMinutes(option(),site))
        assertEquals(25L,savedMinutes(option(),site))
        assertEquals("따로 충전하면 +33분 → 25분 아껴요(추정)",savedTimeLine(option(),site))
        // 충전기 정보가 없으면 기본 어림 8분.
        assertEquals(33,separateStopMinutes(option(),null))
    }
    @Test fun savedTimeIsHiddenWhenSmallUnknownOrNotOverlapping() {
        assertNull(savedTimeLine(option(charge=4),site)) // 4분 아낌은 말하지 않는다
        assertNull(savedTimeLine(option(extra=null),site))
        assertNull(savedTimeLine(option(charge=null),site))
        assertNull(savedTimeLine(option(ChargeMode.NONE),site))
        assertNull(savedTimeLine(option(ChargeMode.REQUIRED),site))
        assertNull(savedTimeLine(option().copy(selectable=false),site))
        // 충전 전용 방문은 '들른 김에'가 아니다.
        assertNull(savedTimeLine(option(visit=shop.copy(activity=Activity.CHARGE)),site))
    }
    @Test fun savedTimeUsesOnlyTheEnergyActuallyAdded() {
        // 80% 상한으로 5kWh 만 넣으면 50kW 에서 6분이면 된다 → 따로 들러도 14분, 아끼는 건 6분.
        assertEquals(14,separateStopMinutes(option(kwh=5.0),site))
        assertEquals(6L,savedMinutes(option(kwh=5.0),site))
        assertNull(savedTimeLine(option(kwh=0.0),site))
    }
    @Test fun batteryBeforeAfterNeedsCapacityAndFreshSoc() {
        val energy=Energy(socPercent=Measured(25,evidence),batteryCapacityKwh=Measured(77.0,evidence))
        // 25kWh / 77kWh ≈ 32% → 57%
        assertEquals("25% → 약 57%(추정)",socChangeLine(option(kwh=25.0),energy,now))
        // 들른 김에 충전은 80%에서, 필요한 만큼은 100%에서 멈춘다.
        assertEquals("25% → 약 80%(추정)",socChangeLine(option(kwh=60.0),energy,now))
        assertEquals("25% → 약 100%(추정)",socChangeLine(option(ChargeMode.REQUIRED,kwh=70.0),energy,now))
        assertNull(socChangeLine(option(ChargeMode.NONE,kwh=25.0),energy,now))
        assertNull(socChangeLine(option(kwh=25.0),energy.copy(batteryCapacityKwh=null),now))
        assertNull(socChangeLine(option(kwh=null),energy,now))
        val staleSoc=energy.copy(socPercent=Measured(25,Evidence("vehicle",now.minusMinutes(11))))
        assertNull(socChangeLine(option(kwh=25.0),staleSoc,now))
        // 이미 80% 이상이면 들른 김에 충전으로 늘지 않는다.
        assertNull(socChangeLine(option(kwh=0.0),energy.copy(socPercent=Measured(85,evidence)),now))
    }
    @Test fun requiredButtonIsHiddenOnlyWhenNotNeeded() {
        assertEquals(listOf(ChargeMode.NONE,ChargeMode.TOP_UP),visibleChargeModes(option(ChargeMode.REQUIRED)))
        assertEquals(listOf(ChargeMode.NONE,ChargeMode.TOP_UP,ChargeMode.REQUIRED),
            visibleChargeModes(option(ChargeMode.REQUIRED,status=EnergyStatus.NEED_CHARGE)))
        assertEquals(3,visibleChargeModes(null).size)
    }
    @Test fun estimateDisclaimersAreDimAndRealRisksAreOrange() {
        val estimates=option().copy(conditions=listOf("대기 시간 미확인 · 바로 충전할 경우의 조건부 예상"))
        assertEquals(WarningSummary("추정값 포함",false),estimates.warningSummary())
        assertNull(option().warningSummary())
        val fast=option().copy(conditions=listOf(FAST_CHARGER_NOTE,"대기 시간 미확인"))
        assertEquals(WarningSummary("확인할 내용 1가지",true),fast.warningSummary())
        val tight=option(deadline=now.plusMinutes(13),slack=3)
        assertEquals(listOf("마감이 촉박해요"),tight.riskWarnings())
        assertEquals(listOf("마감에 늦어요"),option(deadline=now.plusMinutes(5),slack=-5).riskWarnings())
        assertTrue(option(deadline=now.plusMinutes(40),slack=30).riskWarnings().isEmpty())
        val closed=option().copy(closingNotices=listOf(ClosingNotice(now.toLocalDate(),false,"t")),chargingStatus=Availability.UNAVAILABLE)
        assertEquals(2,closed.riskWarnings().size)
        assertTrue(closed.warningSummary()!!.risk)
    }
    @Test fun stoppingTheMicKeepsANonBlankPartial() {
        val listening=PlannerState(now=now,listening=true,partial=" 30분 장 보고 갈게 ")
        assertEquals("30분 장 보고 갈게",listening.stopTranscript())
        assertNull(listening.copy(partial="  ").stopTranscript())
        assertNull(listening.copy(listening=false).stopTranscript())
    }
    @Test fun processingConfirmShiftAndStaleness() {
        assertEquals("일정에 맞는 곳을 찾는 중…",PlannerState(now=now,searchingPlaces=true).processingLine())
        assertNull(PlannerState(now=now,searchingPlaces=true,listening=true).processingLine())
        assertNull(PlannerState(now=now).processingLine())
        assertTrue(isInfoApplyMessage("“배고파”$confirmIgnoredSuffix"))
        assertFalse(isInfoApplyMessage("표시된 도착·충전 조건이 바뀌었어요. 다시 확인해 주세요."))
        val o=option()
        val s=PlannerState(now=now,screen=2,options=listOf(o),selectedId=o.id,shownFinish=now.plusMinutes(40))
        assertEquals("출발이 3분 늦어져 오후 3:43에 끝나요",s.departureShiftLine())
        assertNull(s.copy(shownFinish=now.plusMinutes(43)).departureShiftLine())
        assertNull(s.copy(plan=s.plan.copy(departure=now.plusMinutes(5))).departureShiftLine())
        val efficiency=Evidence("전비 추정",now.minusMinutes(20))
        val energy=Energy(kmPerKwh=Measured(6.0,efficiency))
        assertFalse(evidenceStale(efficiency,energy,now))
        assertTrue(evidenceStale(Evidence("경로",now.minusMinutes(20)),energy,now))
        assertTrue(evidenceStale(efficiency,energy,now.plusMinutes(11)))
    }
    @Test fun errandSegmentSaysItCharges() {
        assertEquals(listOf("이동 10분","장보기 25분 동안 충전","주차·연결 8분"),option().timelineSegmentLabels(listOf(10,25,8)))
        assertEquals("장보기 25분",option(ChargeMode.NONE).timelineSegmentLabels(listOf(10,25,0))[1])
    }
}
