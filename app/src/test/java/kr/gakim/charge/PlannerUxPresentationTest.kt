package kr.gakim.charge

import java.time.LocalDateTime
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

class PlannerUxPresentationTest {
    private val now=LocalDateTime.of(2026,11,16,10,0)
    private val evidence=Evidence("전비",now)
    private val energy=Energy(kmPerKwh=Measured(6.0,evidence))
    private val visit=Visit(1,place=Place("mart","마트",37.5,127.0))
    private fun option(extra:Long?=8,mode:ChargeMode=ChargeMode.TOP_UP)=Option("o",listOf(visit),
        listOf(VisitResult(visit,now.plusMinutes(17),now.plusMinutes(45),null)),mode,45,3.9,extra,20,3.0,
        EnergyDecision(EnergyStatus.OPTIONAL,0.0,""),true,emptyList(),emptyList(),Availability.AVAILABLE,1)
    @Test fun clockHandlesNoonMidnightAndMinutes() {
        assertEquals("오전 12:00",clock(now.withHour(0)))
        assertEquals("오후 12:05",clock(now.withHour(12).withMinute(5)))
        assertEquals("오후 1:20",clock(now.withHour(13).withMinute(20)))
        assertEquals("내일 오후 1:20",at(now.plusDays(1).withHour(13).withMinute(20),now))
        assertEquals("시각 미확인",clock(null))
        assertEquals("오후 1:20까지 갈게",clockText("13:20까지 갈게"))
        assertEquals("오후 1:20까지 갈게",clockText("오후 1시 20분까지 갈게"))
        assertEquals("오후 1:20",clockText("오후 1:20"))
    }
    @Test fun sourceMapperPreservesStoredEvidenceAndFormatsPowerBoundaries() {
        val raw=Evidence("Android RANGE_REMAINING",now)
        assertEquals("차량 주행 가능 거리",sourceLabel(raw.source))
        assertEquals("Android RANGE_REMAINING",raw.source)
        assertEquals("차량 주행 가능 거리",sourceLabel("Pleos Vehicle SDK 주행 가능 거리"))
        assertEquals("차량 배터리 잔량",sourceLabel("Vehicle SOC"))
        assertEquals("7kW 완속",sourceLabel("명목 출력 기준 7.0 kW"))
        assertEquals("11kW 완속",powerLabel(11.0))
        assertEquals("22kW 중속",powerLabel(22.0))
        assertEquals("40kW 급속",powerLabel(40.0))
    }
    @Test fun benefitUsesComputedGainAndBaselineDifference() {
        val site=ChargingSite(Availability.AVAILABLE,overlapVerified=true,evidence=evidence)
        assertEquals("충전 +18km · 장 보는 동안 · 8분 늦게 끝나요",benefitLine(option(),energy,now,site))
        assertEquals("충전 +18km · 장 보는 동안 · 끝나는 시각 그대로",benefitLine(option(0),energy,now,site))
        assertEquals("+8분 · +18km",option().modeResult(energy,now))
        assertEquals("+0분",option(mode=ChargeMode.NONE).modeResult(energy,now))
    }
    @Test fun unknownEfficiencyDoesNotInventGainOrOverlap() {
        assertEquals("충전량 미확인 · 끝나는 시각 미확인",benefitLine(option(null),Energy(),now,null))
        assertNull(option().rangeGain(energy,now.plusMinutes(3)))
        assertFalse(benefitLine(option(),energy,now,null).contains("동안"))
    }
    @Test fun elapsedSegmentsIncludeEveryStopWithoutCountingOverlapTwice() {
        assertEquals(listOf(17L,20L,8L),option().elapsedSegments(now))
        assertEquals(45L,option().elapsedSegments(now)!!.sum())
    }
    @Test fun fallbackCannotRecommendAnUnreachableNoChargeRoute() {
        val baseline=option(mode=ChargeMode.NONE)
        assertTrue(warningFallback("대기 시간 미확인",option(),baseline).contains("충전 없이 오전 10:45에 끝나요"))
        assertFalse(warningFallback("대기 시간 미확인",option(),baseline.copy(selectable=false)).contains("충전 없이"))
    }
    @Test fun blockedChargingReportsTheProblemBeforeBenefitsAndCountsEnergyWarning() {
        val blocked=option().copy(selectable=false,energy=EnergyDecision(EnergyStatus.CANNOT_REACH,note="충전 장소까지 주행 가능 거리 부족"))
        assertEquals("이 방식으로는 갈 수 없어요 · 다른 방식을 골라 주세요",benefitLine(blocked,energy,now,null))
        assertEquals(listOf("충전 장소까지 주행 가능 거리 부족"),blocked.reviewWarnings())
    }
    @Test fun estimatedGainRoundsDownAndNeedsPowerAndTime() {
        val site=ChargingSite(Availability.AVAILABLE,evidence=Evidence("t",LocalDateTime.of(2026,11,16,12,0)),
            effectivePowerKw=Measured(50.0,Evidence("t",LocalDateTime.of(2026,11,16,12,0))))
        assertEquals(120,estimatedRangeGain(30,site))
        assertEquals(5,estimatedRangeGain(9,site.copy(effectivePowerKw=Measured(7.0,site.evidence))))
        assertNull(estimatedRangeGain(null,site)); assertNull(estimatedRangeGain(30,site.copy(effectivePowerKw=null)))
    }
}
