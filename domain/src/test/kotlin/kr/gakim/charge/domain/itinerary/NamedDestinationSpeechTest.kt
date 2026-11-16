package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class NamedDestinationSpeechTest {
    private val now = LocalDateTime.of(2026, 11, 16, 12, 0)
    private fun parse(text: String) = interpretItinerary(text, Itinerary(), emptyList(), now, remotePlaces = true)

    @Test fun schoolArrivalIsNeutralWithNoStay() {
        for (verb in listOf("가야 해", "도착해야 해", "가야 돼")) {
            val result = parse("은평점에서 25분 장 보고 오후 1시 20분까지 학교 $verb")
            assertNull(result.question)
            assertEquals(listOf(Activity.SHOP, Activity.ARRIVE), result.plan.visits.map { it.activity })
            assertEquals(listOf("은평점", "학교"), result.plan.visits.map { it.spokenPlace })
            assertEquals(listOf(25, 0), result.plan.visits.map { it.stayMinutes })
            assertEquals(now.withHour(13).withMinute(20), result.plan.visits.last().deadline)
            assertTrue(result.plan.visits.all { it.place == null })
        }
    }
    @Test fun pickupRequiresAnExplicitVerb() {
        for (verb in listOf("데리러", "픽업", "태우러", "받으러")) {
            val result = parse("오후 1시 20분까지 학교 $verb 가야 해")
            assertEquals(Activity.PICKUP, result.plan.visits.single().activity)
            assertEquals("학교", result.plan.visits.single().spokenPlace)
        }
    }
    @Test fun properDestinationsRetainTheirNames() {
        for (name in listOf("판교역", "분당서울대병원", "학교", "회사", "송도")) {
            val v = parse("오후 1시 20분까지 $name 가야 해").plan.visits.single()
            assertEquals(Activity.ARRIVE, v.activity)
            assertEquals(name, v.spokenPlace)
            assertEquals(0, v.stayMinutes)
        }
    }
    @Test fun destinationBeforeClockIsRetained() {
        assertEquals("판교역", parse("판교역까지 오후 1시 20분 도착해야 해").plan.visits.single().spokenPlace)
    }
    @Test fun fullMartNameAndSavedHomeArePreserved() {
        val home = Place("saved", "집", 37.4, 127.1)
        val result = interpretItinerary("이마트 성남점에서 25분 장 보고 오후 1시 20분까지 집 가야 해",
            Itinerary(), listOf(CatalogEntry(home, setOf(Activity.CUSTOM))), now, remotePlaces = true)
        assertEquals(2, result.plan.visits.size)
        assertNull(result.question)
        assertEquals(25, result.plan.visits.first().stayMinutes)
        assertEquals("이마트 성남점", result.plan.visits.first().spokenPlace)
        assertEquals(home, result.plan.visits.last().place)
        assertEquals(Activity.ARRIVE, result.plan.visits.last().activity)
    }
    @Test fun spokenReplacementClearsOldCoordinates() {
        val current = Itinerary(listOf(Visit(1, place = Place("old", "이마트 이전점", 37.4, 127.1))))
        val next = interpretItinerary("이마트 성남점에서 25분 장 보고", current, emptyList(), now, remotePlaces = true)
        assertNull(next.plan.visits.single().place)
        assertEquals("이마트 성남점", next.plan.visits.single().spokenPlace)
    }
    @Test fun deadlineStaysWithTheDestinationForTheOnScreenExample() {
        // 에뮬레이터 재현(2026-11-16): 마감이 장보기에 붙고 판교역은 '시간 제약 없음'이 됐다.
        for (clock in listOf("오후 2시" to 14 * 60, "오후 1시 20분" to 13 * 60 + 20)) {
            val result = parse("롯데마트 판교점에서 25분 장 보고 ${clock.first}까지 판교역 가야 해")
            assertEquals(listOf("롯데마트 판교점", "판교역"), result.plan.visits.map { it.spokenPlace })
            assertNull(result.plan.visits.first().deadline)
            assertEquals(now.toLocalDate().atStartOfDay().plusMinutes(clock.second.toLong()), result.plan.visits.last().deadline)
        }
    }
    @Test fun deadlineStaysWithDestinationInTheMorningToo() {
        val morning = LocalDateTime.of(2026, 11, 16, 11, 25)
        val result = interpretItinerary("롯데마트 판교점에서 25분 장 보고 오후 2시까지 판교역 가야 해", Itinerary(), emptyList(), morning, remotePlaces = true)
        assertEquals(listOf(null, morning.withHour(14).withMinute(0)), result.plan.visits.map { it.deadline })
        val place = Place("m", "롯데마트 판교점", 37.39, 127.11)
        val bound = interpretItinerary("롯데마트 판교점에서 25분 장 보고 오후 2시까지 판교역 가야 해", Itinerary(),
            listOf(CatalogEntry(place, setOf(Activity.CUSTOM), listOf(place.name))), morning, remotePlaces = true)
        assertEquals(listOf(null, morning.withHour(14).withMinute(0)), bound.plan.visits.map { it.deadline })
    }
}
