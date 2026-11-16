package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

/** 예시 문장은 화면을 연 시계로 만들어야 하므로, 하루의 어느 시각에도 지난 마감이 나오면 안 된다. */
class PlannerExampleClockTest {
    private val repo=ItineraryRepository()
    private fun controller(at:LocalDateTime):ItineraryController {
        val car=object:VehicleGateway {
            override suspend fun readBatterySoc()=BatteryReading.Known(100)
            override suspend fun readParkState()=ParkState.PARKED
            override suspend fun readRangeKm()=Measured(100.0,Evidence("RANGE_REMAINING",at))
            override suspend fun readEfficiencyKmPerKwh()=Measured(5.5,Evidence("EFFICIENCY",at))
            override fun release()=Unit
        }
        return ItineraryController(GatewaySet(car,FakeNaviGateway(NaviPlace("기존 목적지","",37.5,127.0)),FakeSpeechGateway(),true),
            repo,{at},verifyAttempts=1,verifyDelayMs=1)
    }
    private fun clocks()=listOf(
        LocalDateTime.of(2026,11,15,13,0),
        LocalDateTime.of(2026,11,15,21,50),
        LocalDateTime.of(2026,11,15,23,40),
        LocalDateTime.of(2026,11,16,0,20),
    )
    @Test fun deadlineIsAboutTwoAndAHalfHoursAheadRoundedToTenMinutes() {
        for(now in clocks()) {
            val deadline=exampleDeadline(now)
            val ahead=java.time.Duration.between(now,deadline).toMinutes()
            assertTrue("$now → ${ahead}분", ahead in 150..159)
            assertEquals(0,deadline.minute%10)
            assertTrue(deadline.isAfter(now))
        }
        assertEquals(LocalDateTime.of(2026,11,15,15,30),exampleDeadline(clocks()[0]))
    }
    // UX pass intentionally uses the same 오전/오후 h:mm clock on every surface.
    @Test fun sentenceSpeaksTwelveHourClockAndSaysTomorrowAfterMidnight() {
        assertEquals("은평점에서 25분 장 보고 오후 3:30까지 학교 가야 해",plannerExamples(clocks()[0])[0])
        assertTrue(plannerExamples(clocks()[2])[0].contains("내일 오전 2:10까지"))
        assertTrue(plannerExamples(clocks()[1])[0].contains("내일 오전 12:20까지"))
        assertTrue(plannerExamples(clocks()[3])[0].contains("오전 2:50까지"))
    }
    @Test fun everyGeneratedSentenceParsesToTwoVisitsWithFutureDeadline()=runTest {
        for(now in clocks()) {
            val sentence=plannerExamples(now)[0]
            val result=interpretItinerary(sentence,Itinerary(),repo.catalog,now)
            assertNull("$now: $sentence → ${result.question?.message}",result.question)
            assertEquals(listOf("은평점","학교"),result.plan.visits.map { it.place?.name })
            val deadline=result.plan.visits.last().deadline!!
            assertEquals(exampleDeadline(now),deadline)
            assertTrue("$now: $sentence",deadline.isAfter(now))
            // 같은 문장이 지난 시각으로 읽히면 컨트롤러가 확인 창을 연다.
            val c=controller(now);c.refresh();c.acceptTranscript(sentence)
            assertNull(c.state.value.pendingTime)
            assertNull(c.state.value.question)
            assertEquals(deadline,c.state.value.plan.visits.last().deadline)
        }
    }
    @Test fun otherTwoExamplesStillCompareAtEveryClock()=runTest {
        for(now in clocks()) {
            plannerExamples(now).drop(1).forEach { sentence ->
                val c=controller(now);c.refresh();c.acceptTranscript(sentence);c.compare()
                val s=c.state.value
                assertNull("$now: $sentence → ${s.question?.message}",s.question)
                assertNull(s.pendingTime)
                assertTrue("$now: $sentence",s.options.any { it.selectable && it.totalMinutes!=null })
            }
        }
    }
}
