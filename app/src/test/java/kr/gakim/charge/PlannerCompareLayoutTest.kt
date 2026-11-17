package kr.gakim.charge

import java.time.LocalDateTime
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

/** 비교 카드의 축 라벨·일정 줄은 계산된 값만 쓴다. */
class PlannerCompareLayoutTest {
    private val now=LocalDateTime.of(2026,11,15,13,0)
    private val mart=Place("mart-e","은평점",37.5,127.0)
    private val school=Place("school","학교",37.6,127.1)
    private fun visit(id:Long,activity:Activity,place:Place,stay:Int,deadline:LocalDateTime?=null)=
        Visit(id,activity,place=place,stayMinutes=stay,deadline=deadline)
    private fun option(mode:ChargeMode=ChargeMode.NONE,chargeVisitId:Long?=null,chargeMinutes:Int?=null):Option {
        val visits=listOf(visit(1,Activity.SHOP,mart,20),visit(2,Activity.PICKUP,school,10,now.plusMinutes(160)))
        val results=listOf(
            VisitResult(visits[0],now.plusMinutes(12),now.plusMinutes(32),null),
            VisitResult(visits[1],now.plusMinutes(50),now.plusMinutes(60),110L),
        )
        return Option("o",visits,results,mode,60L,21.0,null,chargeMinutes,null,
            EnergyDecision(EnergyStatus.OPTIONAL,0.0,""),true,emptyList(),emptyList(),null,chargeVisitId)
    }
    @Test fun labelsSitUnderTheirOwnSegmentsOnTheSameAxis() {
        val widths=List(3){60f}
        val placed=axisLabelLayout(listOf(0f..0.2f,0.2f..0.6f,0.6f..1f),listOf("a","b","c"),widths,1000f,8f)
        assertEquals(3,placed.size)
        assertEquals(listOf(0,0,0),placed.map { it.row })
        assertEquals(100f,placed[0].left+placed[0].width/2f,0.5f) // 20% 지점의 가운데
        assertEquals(400f,placed[1].left+placed[1].width/2f,0.5f)
        assertEquals(800f,placed[2].left+placed[2].width/2f,0.5f)
        assertTrue("막대 밖으로 나가면 안 됩니다",placed.all { it.left>=0f && it.left+it.width<=1000f+0.01f })
    }
    @Test fun narrowSegmentsStaggerInsteadOfOverlapping() {
        // 가운데 구간이 아주 좁아 라벨이 이웃과 겹치는 경우.
        val placed=axisLabelLayout(listOf(0f..0.45f,0.45f..0.55f,0.55f..1f),listOf("가","나","다"),List(3){400f},1000f,8f)
        assertEquals(3,placed.size)
        val byRow=placed.groupBy { it.row }
        byRow.values.forEach { row -> row.sortedBy { it.left }.zipWithNext().forEach { (a,b) ->
            assertTrue("같은 줄에서 겹칩니다",a.left+a.width<=b.left)
        } }
        assertTrue("좁은 구간은 둘째 줄로 밀립니다",placed.maxOf { it.row }>=1)
        assertTrue(placed.all { it.left>=0f && it.left+it.width<=1000f+0.01f })
    }
    @Test fun timelineWordingNamesThePlaces() {
        assertEquals(listOf("은평점까지 12분","머무는 시간 20분","학교까지 18분"),option().timelineLabelTexts(now))
    }
    @Test fun singleVisitHasNoInventedNextPlace() {
        val only=listOf(visit(1,Activity.SHOP,mart,20))
        val single=Option("s",only,listOf(VisitResult(only[0],now.plusMinutes(12),now.plusMinutes(32),null)),
            ChargeMode.NONE,32L,8.0,null,null,null,EnergyDecision(EnergyStatus.OPTIONAL,0.0,""),true,emptyList(),emptyList())
        assertEquals(listOf("은평점까지 12분","머무는 시간 20분"),single.timelineLabelTexts(now))
        assertEquals(1,single.scheduleRows().size)
    }
    @Test fun scheduleRowsComeOnlyFromComputedResults() {
        // UX pass intentionally standardises schedule clocks on 12-hour labels.
        val rows=option().scheduleRows()
        assertEquals(listOf("오후 1:12","오후 1:50"),rows.map { it.arrival })
        assertEquals(listOf("은평점","학교"),rows.map { it.place })
        assertEquals(listOf("장보기 20분","픽업 10분"),rows.map { it.detail })
        assertEquals(listOf(null,110L),rows.map { it.slackMinutes })
    }
    @Test fun chargingRowOnlyWhenTheChosenModeCharges() {
        assertNull(option().chargingRow())
        // 2026-11-17: 볼일과 겹치는 충전은 카드의 '주차·연결'과 같은 말로 보여 준다(상세 창만 "충전 30분"이라 엇갈렸다).
        assertEquals("장보기 20분 동안 충전(추정)",option(ChargeMode.TOP_UP,1,22).chargingRow())
        assertEquals("충전 시간 미확인 · 은평점",option(ChargeMode.TOP_UP,1,null).chargingRow())
    }
    @Test fun slackChipUsesOnlyTheComputedSlack() {
        assertEquals(kr.gakim.charge.domain.TimingStatus.LATE,timingStatus(-1))
        assertEquals(kr.gakim.charge.domain.TimingStatus.TIGHT,timingStatus(4))
        assertEquals(kr.gakim.charge.domain.TimingStatus.OK,timingStatus(5))
    }
}
