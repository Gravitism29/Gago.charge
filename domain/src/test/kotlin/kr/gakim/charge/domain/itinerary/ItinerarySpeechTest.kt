package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ItinerarySpeechTest {
    private val now=LocalDateTime.of(2026,11,15,13,0)
    private val mart=Place("e","은평점",37.5,127.0)
    private val school=Place("s","학교",37.6,127.1)
    private val catalog=listOf(CatalogEntry(mart,setOf(Activity.SHOP)),CatalogEntry(school,setOf(Activity.PICKUP)))
    @Test fun fullSentenceFillsActivityVenueStayDestinationDeadline() {
        val result=interpretItinerary("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해",Itinerary(),catalog,now)
        assertEquals(2,result.plan.visits.size);assertEquals(mart,result.plan.visits[0].place)
        assertEquals(25,result.plan.visits[0].stayMinutes);assertEquals(school,result.plan.visits[1].place)
        assertEquals(now.withHour(16).withMinute(10),result.plan.visits[1].deadline)
    }
    @Test fun correctionChangesOnlyDeadline() {
        val first=interpretItinerary("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해",Itinerary(),catalog,now).plan
        val next=interpretItinerary("아니, 4시 반까지",first,catalog,now).plan
        assertEquals(first.visits[0],next.visits[0]);assertEquals(first.visits[1].copy(deadline=now.withHour(16).withMinute(30)),next.visits[1])
    }
    @Test fun bareTimeAsksOnlyTimeRole() {
        val result=interpretItinerary("네 시",Itinerary(),catalog,now)
        assertTrue(result.question?.timeRole==true);assertEquals(Itinerary(),result.plan)
    }
    @Test fun ambiguousPlaceDoesNotGuess() {
        val catalog2=catalog+CatalogEntry(school.copy(id="s2",name="다른 학교",latitude=38.0),setOf(Activity.PICKUP),listOf("학교"))
        val result=interpretItinerary("학교에 네 시까지",Itinerary(),catalog2,now)
        assertEquals(2,result.question?.places?.size)
    }
    @Test fun durationWithoutActivityUpdatesExistingInput() {
        assertEquals(55,interpretItinerary("55분으로",Itinerary(),catalog,now).plan.visits[0].stayMinutes)
        assertEquals(90,interpretItinerary("1시간 30분",Itinerary(),catalog,now).plan.visits[0].stayMinutes)
    }
    @Test fun minutesInClockAreNotStayDuration() {
        assertEquals(20,interpretItinerary("4시 10분까지",Itinerary(),catalog,now).plan.visits[0].stayMinutes)
    }
    @Test fun noDeadlineAndNoPickupAreOrdinaryPlan() {
        val result=interpretItinerary("시간 제약 없이 식사할 곳 찾아줘",Itinerary(),catalog,now)
        assertEquals(1,result.plan.visits.size);assertEquals(Activity.MEAL,result.plan.visits[0].activity);assertNull(result.plan.visits[0].deadline)
    }
    @Test fun explicitTomorrowIsPreserved() {
        val result=interpretItinerary("내일 오후 2시 10분까지 학교",Itinerary(),catalog,now)
        assertEquals(now.plusDays(1).withHour(14).withMinute(10),result.plan.visits.last().deadline)
    }
    @Test fun unsupportedDurationIsNotClampedSilently() {
        val result=interpretItinerary("장보기 999분",Itinerary(),catalog,now)
        assertEquals(20,result.plan.visits.first().stayMinutes);assertNotNull(result.question)
    }
    @Test fun unknownNamedVenueDoesNotReusePreviousCoordinates() {
        val current=Itinerary(listOf(Visit(1,place=mart)))
        val result=interpretItinerary("없는점에서 25분 장 보고",current,catalog,now)
        assertEquals(mart,result.plan.visits.first().place);assertNotNull(result.question)
        assertEquals(25,result.plan.visits.first().stayMinutes)
    }
    @Test fun negatedShoppingIsNotPreferredOverMeal() {
        val result=interpretItinerary("장 보지 말고 식사할 곳 찾아줘",Itinerary(),catalog,now)
        assertEquals(Activity.MEAL,result.plan.visits.first().activity)
        assertEquals(1,result.plan.visits.size)
    }
    @Test fun observedExtraClockAfterCorrectionAsksOnlyDeadline() {
        val first=interpretItinerary("은평점에서 25분 장 보고 4시 10분까지 학교 가야 해",Itinerary(),catalog,now).plan
        val result=interpretItinerary("아니 네 시 반까지 4시",first,catalog,now)
        assertEquals(first,result.plan)
        assertEquals(listOf(now.withHour(16).withMinute(30),now.withHour(16)),result.question?.timeChoices)
    }
}
