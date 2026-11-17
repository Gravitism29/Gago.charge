package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

/** Code-level regressions for round-4 voice phrases (rules only); these are not user studies. */
class VoiceRound4Test {
    private val now = LocalDateTime.of(2026, 11, 15, 13, 0)
    private val mart = Place("mart", "은평점", 37.5, 127.0)
    private val hospital = Place("hospital", "병원", 37.6, 127.1)
    private val school = Place("school", "학교", 37.7, 127.2)
    private val catalog = listOf(CatalogEntry(mart, setOf(Activity.SHOP)),
        CatalogEntry(hospital, setOf(Activity.HOSPITAL)), CatalogEntry(school, setOf(Activity.PICKUP)))
    private val martOnly = Itinerary(listOf(Visit(1, Activity.SHOP, place = mart)))

    // 1
    @Test fun compareScreenProceedSynonyms() {
        for (text in listOf("그걸로 할게", "이걸로 할게요", "이걸로 해", "출발", "안내 시작", "안내해 줘", "네 그걸로"))
            assertEquals(text, PlannerVoiceCommand.Proceed(), plannerVoiceCommand(text, 1))
    }

    @Test fun placeNameAcceptsWiderEndings() {
        for (text in listOf("백경한우로 할게", "백경한우로 해줘", "백경한우로 해", "백경한우로 가자"))
            assertEquals(text, PlannerVoiceCommand.Proceed("백경한우"), plannerVoiceCommand(text, 1))
    }

    // 11
    @Test fun screenSynonymsAndQuestionMark() {
        for (text in listOf("비교해 봐", "보여 줘", "찾아 줘")) assertEquals(text, PlannerVoiceCommand.Compare, plannerVoiceCommand(text, 0))
        for (text in listOf("다른 거", "딴 데")) assertEquals(text, PlannerVoiceCommand.OtherPlaces, plannerVoiceCommand(text, 1))
        for (text in listOf("이전", "이전으로")) assertEquals(text, PlannerVoiceCommand.Back, plannerVoiceCommand(text, 1))
        for (text in listOf("40분으로", "세 시로", "삼십 분으로", "한 시간으로")) assertNull(text, plannerVoiceCommand(text, 1))
        for (text in listOf("네?", "출발?")) assertNull(text, plannerVoiceCommand(text, 2))
        assertEquals(PlannerVoiceCommand.Confirm, plannerVoiceCommand("네", 2))
    }

    // 2
    @Test fun chargeModePhrases() {
        for (text in listOf("충전 꼭 해야 돼", "충전은 해야 해", "충전 안 하면 안 돼"))
            assertEquals(text, ChargeMode.REQUIRED, interpretItinerary(text, martOnly, catalog, now).plan.chargeMode)
        assertEquals(ChargeMode.NONE, interpretItinerary("충전은 필요 없어", martOnly, catalog, now).plan.chargeMode)
        for (text in listOf("조금 늦어도 돼", "마트 조금만 있다가"))
            assertEquals(text, ChargeMode.AUTO, interpretItinerary(text, martOnly, catalog, now).plan.chargeMode)
        assertNotEquals(ChargeMode.NONE, interpretItinerary("충전하고 안경점 들러", martOnly, catalog, now).plan.chargeMode)
        assertEquals(ChargeMode.TOP_UP, interpretItinerary("조금만 충전할게", martOnly, catalog, now).plan.chargeMode)
    }

    // 3
    @Test fun shopAndMealVocabulary() {
        for (text in listOf("홈플러스 들렀다 갈게", "코스트코 갈 거야", "트레이더스 들를게", "다이소 들렀다 가자"))
            assertEquals(text, Activity.SHOP, interpretItinerary(text, Itinerary(), catalog, now).plan.visits.first().activity)
        for (text in listOf("점심 먹고 갈게", "저녁 식사하고 갈게", "식당 들렀다 가자"))
            assertEquals(text, Activity.MEAL, interpretItinerary(text, Itinerary(), catalog, now).plan.visits.first().activity)
        val evening = interpretItinerary("저녁 7시까지 집 가야 해", Itinerary(), catalog, now)
        assertTrue(evening.plan.visits.none { it.activity == Activity.MEAL })
    }

    // 4
    @Test fun deadlineAttachesToHomeDestination() {
        for (remote in listOf(false, true)) {
            val r = interpretItinerary("마트 들렀다가 세 시까지 집", Itinerary(), catalog, now, remotePlaces = remote)
            val home = r.plan.visits.single { it.activity == Activity.ARRIVE }
            assertEquals("remote=$remote", now.withHour(15), home.deadline)
            assertNull(r.plan.visits.single { it.activity == Activity.SHOP }.deadline)
        }
        val arrive = interpretItinerary("세 시까지 집 도착", Itinerary(), catalog, now)
        assertEquals(now.withHour(15), arrive.plan.visits.single { it.activity == Activity.ARRIVE }.deadline)
        val going = interpretItinerary("집으로 갈게", Itinerary(), catalog, now, remotePlaces = true)
        assertEquals("집", going.plan.visits.single { it.activity == Activity.ARRIVE }.spokenPlace)
    }

    // 5
    @Test fun withinHoursIsDeadlineNotStay() {
        val r = interpretItinerary("2시간 안에 집 가야 해", Itinerary(), catalog, now, remotePlaces = true)
        assertTrue(r.plan.visits.none { it.stayMinutes == 120 })
        val home = r.plan.visits.single { it.activity == Activity.ARRIVE }
        assertEquals(now.plusHours(2), home.deadline)
    }

    // 6
    @Test fun hangulDurations() {
        val cases = mapOf("삼십 분 있을게" to 30, "십오 분 있을게" to 15, "한 시간 있을게" to 60, "한 시간 반 있을게" to 90,
            "반 시간 있을게" to 30, "두 시간 있을게" to 120, "장 보는 데 삼십 분" to 30)
        for ((text, minutes) in cases) {
            val r = interpretItinerary(text, martOnly, catalog, now)
            assertEquals(text, minutes, r.plan.visits.single().stayMinutes)
        }
        val focused = interpretItinerary("삼십 분으로", martOnly, catalog, now, focusedVisitId = 1)
        assertEquals(30, focused.plan.visits.single().stayMinutes)
        assertEquals(mart, focused.plan.visits.single().place)
        val clock = interpretItinerary("세 시 삼십 분까지", martOnly, catalog, now)
        assertEquals(20, clock.plan.visits.single().stayMinutes)
        assertEquals(now.withHour(15).withMinute(30), clock.plan.visits.single().deadline)
    }

    // 7
    @Test fun durationCorrectionKeepsLast() {
        val plan = Itinerary(listOf(Visit(1, Activity.SHOP, place = mart), Visit(2, Activity.HOSPITAL, place = hospital)))
        val r = interpretItinerary("마트 30분, 아니 40분", plan, catalog, now)
        assertNull(r.question)
        assertEquals(40, r.plan.visits[0].stayMinutes)
        assertEquals(plan.visits[1], r.plan.visits[1])
    }

    // 8
    @Test fun negatedShopIsNotAdded() {
        val plan = Itinerary(listOf(Visit(1, Activity.HOSPITAL, place = hospital)))
        for (text in listOf("마트는 안 들러도 돼", "마트 빼 줘", "은평점은 안 가도 돼")) {
            val r = interpretItinerary(text, plan, catalog, now)
            assertTrue(text, r.plan.visits.none { it.activity == Activity.SHOP })
        }
    }

    // 9
    @Test fun movementVerbsAreNotPlaceNames() {
        assertEquals(listOf("집"), spokenPlaceMentions("집에 들어가야 해").map { it.text })
        assertEquals(listOf("집"), spokenPlaceMentions("집으로 돌아가야 돼").map { it.text })
        val back = interpretItinerary("돌아가야 돼", Itinerary(), catalog, now, remotePlaces = true)
        assertTrue(back.plan.visits.none { it.spokenPlace == "돌아" || it.spokenPlace == "들어" })
    }

    // 10
    @Test fun beforeClockIsDeadline() {
        for (text in listOf("오후 세 시 전에", "세 시 전까지")) {
            val r = interpretItinerary(text, martOnly, catalog, now)
            assertNull(text, r.question)
            assertEquals(text, now.withHour(15), r.plan.visits.single().deadline)
        }
    }
}
