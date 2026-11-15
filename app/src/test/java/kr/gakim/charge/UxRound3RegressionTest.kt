package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.fake.FakeNaviGateway
import kr.gakim.charge.data.fake.FakeSpeechGateway
import kr.gakim.charge.data.fake.FakeVehicleGateway
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.ItineraryController
import org.junit.Assert.*
import org.junit.Test

/** 코드 회귀 재현용 기대 동작. 사용자 리서치나 실차 검증 결과가 아니다. */
class UxRound3RegressionTest {
    private val now = LocalDateTime.of(2026, 11, 15, 13, 0)
    private val repo = ItineraryRepository()
    private val shop = repo.catalog.first { it.place.id == "mart-e" }.place
    private val hospital = repo.catalog.first { it.place.id == "hospital" }.place
    private val deadlineA = now.plusHours(2)
    private val deadlineB = now.plusHours(3)

    private fun controller(): ItineraryController {
        val navi = FakeNaviGateway(NaviPlace("기존 목적지", "", 37.5, 127.0))
        return ItineraryController(
            GatewaySet(FakeVehicleGateway(), navi, FakeSpeechGateway(), true),
            repo,
            { now },
            verifyAttempts = 2,
            verifyDelayMs = 1,
        )
    }

    private fun twoDeadlines(): Itinerary = Itinerary(
        visits = listOf(
            Visit(1, Activity.SHOP, place = shop, deadline = deadlineA),
            Visit(2, Activity.HOSPITAL, place = hospital, deadline = deadlineB),
        ),
    )

    @Test fun activityEditKeepsSpecifiedPlaceStayAndDeadline() {
        val c = controller()
        c.setPlace(1, shop)
        c.setStay(1, 55)
        c.setTime(1, deadlineA)

        c.setActivity(1, Activity.MEAL)

        val visit = c.state.value.plan.visits.single()
        assertEquals(Activity.MEAL, visit.activity)
        assertEquals("활동 변경으로 지정 장소가 사라지면 안 됩니다", shop, visit.place)
        assertEquals(55, visit.stayMinutes)
        assertEquals(deadlineA, visit.deadline)
    }

    @Test fun addingHospitalBySpeechPreservesExistingSpecifiedVisit() {
        val c = controller()
        c.setPlace(1, shop)
        c.setStay(1, 55)
        c.setTime(1, deadlineA)
        val existing = c.state.value.plan.visits.single()

        c.acceptTranscript("병원도 가야 해")

        assertEquals("기존 장보기가 유지되어야 합니다", existing, c.state.value.plan.visits.firstOrNull { it.id == existing.id })
        assertTrue("병원 방문이 별도로 있어야 합니다", c.state.value.plan.visits.any { it.activity == Activity.HOSPITAL })
    }

    @Test fun removingFocusedDeadlinePreservesOtherVisitDeadline() {
        val result = interpretItinerary("시간 제약 없음", twoDeadlines(), repo.catalog, now, focusedVisitId = 1)

        assertNull(result.plan.visits.first { it.id == 1L }.deadline)
        assertEquals(deadlineB, result.plan.visits.first { it.id == 2L }.deadline)
    }

    @Test fun removingUnspecifiedDeadlineAsksAndPreservesBoth() {
        val original = twoDeadlines()
        val result = interpretItinerary("시간 제약 없음", original, repo.catalog, now)

        assertNotNull("어느 방문의 마감인지 물어야 합니다", result.question)
        assertEquals("확인 전 두 마감은 그대로여야 합니다", original.visits, result.plan.visits)
    }

    @Test fun importingCurrentRouteDoesNotReplaceExistingDraftBeforeConfirmation() = runTest {
        val c = controller()
        c.refresh()
        c.setPlace(1, shop)
        c.setStay(1, 55)
        c.setTime(1, deadlineA)
        val before = c.state.value.plan

        c.importRoute()

        assertEquals("명시적 교체 확인 전에는 작성한 일정을 보존해야 합니다", before, c.state.value.plan)
    }

    @Test fun elapsedDepartureDoesNotProduceSelectableOptimisticEta() {
        val evidence = Evidence("회귀 테스트 값", now)
        val plan = Itinerary(
            visits = listOf(Visit(1, Activity.SHOP, place = shop, deadline = deadlineA)),
            departure = now.minusMinutes(30),
        )
        val legs = listOf(Leg(Measured(10, evidence), Measured(5.0, evidence)))
        val energy = Energy(rangeKm = Measured(100.0, evidence))

        val option = evaluateItinerary(plan, legs, ChargeMode.NONE, null, null, energy, now)

        assertFalse("지난 출발 예정으로 경로를 선택할 수 없어야 합니다", option.selectable)
        assertNull("지난 출발 기준의 낙관적인 도착 시각을 표시하면 안 됩니다", option.results.single().arrival)
        assertNull(option.results.single().slackMinutes)
    }
}
