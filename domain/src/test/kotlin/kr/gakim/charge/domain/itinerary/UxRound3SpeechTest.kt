package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** Code-level regressions for preserving visits; these are not user studies. */
class UxRound3SpeechTest {
    private val now = LocalDateTime.of(2026, 11, 15, 13, 0)
    private val mart = Place("mart", "은평점", 37.5, 127.0)
    private val hospital = Place("hospital", "병원", 37.6, 127.1)
    private val school = Place("school", "학교", 37.7, 127.2)
    private val catalog = listOf(CatalogEntry(mart, setOf(Activity.SHOP)),
        CatalogEntry(hospital, setOf(Activity.HOSPITAL)), CatalogEntry(school, setOf(Activity.PICKUP)))
    private fun twoDeadlines() = Itinerary(listOf(
        Visit(1, Activity.HOSPITAL, place = hospital, stayMinutes = 55, deadline = now.plusHours(1)),
        Visit(2, Activity.PICKUP, place = school, stayMinutes = 30, deadline = now.plusHours(3))))

    @Test fun newActivityFillsOnlyInitialPlaceholder() {
        val result = interpretItinerary("식사할 곳 찾아줘", Itinerary(), catalog, now)
        assertEquals(1, result.plan.visits.size)
        assertEquals(Activity.MEAL, result.plan.visits.single().activity)
        assertFalse(result.plan.visits.single().placeholder)
    }

    @Test fun newActivityAppendsWithoutErasingExplicitVisit() {
        val original = Visit(1, Activity.SHOP, place = mart, stayMinutes = 55, deadline = now.plusHours(2))
        val result = interpretItinerary("병원도 가야 해", Itinerary(listOf(original)), catalog, now)
        assertEquals(original, result.plan.visits.first())
        assertEquals(Activity.HOSPITAL, result.plan.visits.last().activity)
        assertEquals(2, result.plan.visits.size)
    }

    @Test fun fullPlanRefusesNewActivityWithoutErasingVisits() {
        val current = Itinerary(twoDeadlines().visits + Visit(3, Activity.SHOP, place = mart))
        val result = interpretItinerary("식사할 곳 찾아줘", current, catalog, now)
        assertEquals(current, result.plan)
        assertNotNull(result.question)
    }

    @Test fun deadlineRemovalWithoutTargetLeavesMultipleDeadlines() {
        val current = twoDeadlines()
        val result = interpretItinerary("시간 제약 없어", current, catalog, now)
        assertEquals(current, result.plan)
        assertNotNull(result.question)
    }

    @Test fun focusedDeadlineRemovalPreservesOtherVisit() {
        val current = twoDeadlines()
        val result = interpretItinerary("시간 제약 없어", current, catalog, now, focusedVisitId = 1)
        assertEquals(current.visits[0].copy(deadline = null), result.plan.visits[0])
        assertEquals(current.visits[1], result.plan.visits[1])
    }

    @Test fun namedDeadlineRemovalChangesOnlyNamedVisit() {
        val current = twoDeadlines()
        val result = interpretItinerary("병원은 시간 제약 없어", current, catalog, now)
        assertEquals(current.visits[0].copy(deadline = null), result.plan.visits[0])
        assertEquals(current.visits[1], result.plan.visits[1])
    }

    @Test fun onlyDeadlineCanBeClearedWithoutNamingVisit() {
        val current = twoDeadlines().let { it.copy(visits = listOf(it.visits[0], it.visits[1].copy(deadline = null))) }
        val result = interpretItinerary("시간 제약 없어", current, catalog, now)
        assertNull(result.plan.visits[0].deadline)
        assertEquals(current.visits[1], result.plan.visits[1])
    }

    @Test fun explicitAllDeadlineRemovalPreservesOtherFields() {
        val current = twoDeadlines()
        val result = interpretItinerary("모든 방문 시간 제약 없어", current, catalog, now)
        assertEquals(current.visits.map { it.copy(deadline = null) }, result.plan.visits)
    }

    @Test fun chargeOnlyIsVisitWhileChargePreferencePreservesExistingVisits() {
        val first = interpretItinerary("충전소만 찾아줘", Itinerary(), catalog, now)
        assertEquals(Activity.CHARGE, first.plan.visits.single().activity)
        val current = twoDeadlines()
        val preference = interpretItinerary("충전은 빼줘", current, catalog, now)
        assertEquals(current.visits, preference.plan.visits)
        assertEquals(ChargeMode.NONE, preference.plan.chargeMode)
    }

    @Test fun chargeOnlyDefaultsToTopUpWithoutOverridingExplicitPreference() {
        val initial = interpretItinerary("충전소만 찾아줘", Itinerary(), catalog, now)
        assertEquals(ChargeMode.TOP_UP, initial.plan.chargeMode)
        for (mode in listOf(ChargeMode.NONE, ChargeMode.REQUIRED)) {
            val explicit = interpretItinerary("충전소만 찾아줘", Itinerary(chargeMode = mode), catalog, now)
            assertEquals(mode, explicit.plan.chargeMode)
        }
        val current = twoDeadlines()
        val topUp = interpretItinerary("충전도", current, catalog, now)
        assertEquals(current.visits, topUp.plan.visits)
        assertEquals(ChargeMode.TOP_UP, topUp.plan.chargeMode)
    }

    @Test fun durationBindsToNearbySecondActivity() {
        val result = interpretItinerary("병원에 가고 45분 장 봐", Itinerary(), catalog, now)
        assertEquals(20, result.plan.visits.single { it.activity == Activity.HOSPITAL }.stayMinutes)
        assertEquals(45, result.plan.visits.single { it.activity == Activity.SHOP }.stayMinutes)
    }

    @Test fun multipleDurationsAskWithoutChangingExistingStayTimes() {
        val current = twoDeadlines()
        val result = interpretItinerary("병원 20분 학교 45분", current, catalog, now)
        assertEquals(current.visits.map { it.stayMinutes }, result.plan.visits.map { it.stayMinutes })
        assertNotNull(result.question)
    }

    @Test fun conflictingDepartureTimesAskForDepartureAndPreservePriorValues() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 4시에 출발", current, catalog, now)
        assertEquals(current, result.plan)
        assertNull(result.question?.visitId)
        assertTrue(result.question?.message?.contains("출발") == true)
        assertEquals(2, result.question?.timeChoices?.size)
    }

    @Test fun conflictingArrivalTimesKeepVisitTargetAndPreserveValues() {
        val current = Itinerary(listOf(twoDeadlines().visits[0]), departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 4시까지 병원", current, catalog, now)
        assertEquals(current, result.plan)
        assertEquals(current.visits[0].id, result.question?.visitId)
        assertTrue(result.question?.message?.contains("도착") == true)
        assertEquals(2, result.question?.timeChoices?.size)
    }

    @Test fun mixedDepartureAndArrivalTimesAskWithoutSingleRoleChoices() {
        val current = Itinerary(listOf(twoDeadlines().visits[0]), departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 출발해서 병원 4시까지", current, catalog, now)
        assertTrue(result.question?.message?.contains("출발 시각과 도착 마감") == true)
        assertTrue(result.question?.timeChoices?.isEmpty() == true)
        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits[0].deadline, result.plan.visits[0].deadline)
    }

    @Test fun mixedTimesLeaveBothExistingDeadlinesAndDepartureIntact() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 출발해서 4시까지 도착", current, catalog, now)
        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNotNull(result.question)
        assertTrue(result.question?.timeChoices?.isEmpty() == true)
    }

    @Test fun timeAfterDepartureWithUnclearRoleIsNotASecondDepartureChoice() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("3시에 출발해서 병원에 4시에 가", current, catalog, now)

        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNotNull(result.question)
        assertTrue("역할 미확인 시각을 출발 선택지로 묶지 않습니다", result.question?.timeChoices?.isEmpty() == true)
    }

    @Test fun departurePrefixWithHospitalTimeAsksWithoutReplacingExistingTimes() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("출발은 3시, 병원 4시", current, catalog, now)

        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNotNull(result.question)
        assertTrue(result.question?.timeChoices?.isEmpty() == true)
    }

    @Test fun departureWithUnrecognizedPlaceWordAlsoAsksWithoutChoosingOneRole() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("출발 3시 카페 4시", current, catalog, now)

        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNotNull(result.question)
        assertTrue(result.question?.timeChoices?.isEmpty() == true)
    }

    @Test fun correctionWordDoesNotTurnUnclearLaterTimeIntoDeparture() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 출발 아니 4시에 병원 가", current, catalog, now)

        assertEquals(current.departure, result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNotNull(result.question)
        assertTrue(result.question?.timeChoices?.isEmpty() == true)
    }

    @Test fun explicitDepartureCorrectionStillUsesCorrectedTime() {
        val current = twoDeadlines().copy(departure = now.plusMinutes(15))
        val result = interpretItinerary("3시 출발 아니 4시 출발", current, catalog, now)

        assertEquals(now.withHour(16), result.plan.departure)
        assertEquals(current.visits.map { it.deadline }, result.plan.visits.map { it.deadline })
        assertNull(result.question)
    }

    @Test fun unknownNamedPlaceKeepsExistingPlaceAndAsks() {
        val original = Visit(1, Activity.SHOP, place = mart, stayMinutes = 25)
        val result = interpretItinerary("없는점에서 장 보고", Itinerary(listOf(original)), catalog, now)
        assertEquals(original, result.plan.visits.single())
        assertNotNull(result.question)
    }
}
