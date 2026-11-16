package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class PlannerRound2SpeechTest {
    @Test fun choicesOnlyApplyOnConfirmationAndNeverMeanConfirm() {
        for(text in listOf("들를게","경유","추가")) {
            assertEquals(PlannerVoiceCommand.KeepDestination,plannerVoiceCommand(text,2))
            assertNull(plannerVoiceCommand(text,0));assertNull(plannerVoiceCommand(text,1))
        }
        for(text in listOf("빼고","그것만","거기만")) {
            assertEquals(PlannerVoiceCommand.ReplaceDestination,plannerVoiceCommand(text,2))
            assertNull(plannerVoiceCommand(text,0));assertNull(plannerVoiceCommand(text,1))
        }
        for(text in listOf("추가하지 마","거기만 가지 마","들를게 아니","“그것만”")) assertNull(plannerVoiceCommand(text,2))
    }
    @Test fun editorHintsUpdateOnlyFocusedVisit() {
        val now=LocalDateTime.of(2026,11,16,13,0)
        val plan=Itinerary(listOf(Visit(1,Activity.MEAL),Visit(2,Activity.SHOP)))
        val mart=Place("mart","이마트",37.5,127.0)
        val catalog=listOf(CatalogEntry(mart,setOf(Activity.SHOP)))
        val duration=interpretItinerary("30분으로",plan,catalog,now,focusedVisitId=1)
        assertEquals(30,duration.plan.visits[0].stayMinutes);assertEquals(plan.visits[1],duration.plan.visits[1])
        val namedDuration=interpretItinerary("식사 30분으로",plan,catalog,now,focusedVisitId=1,remotePlaces=true)
        assertNull(namedDuration.question);assertEquals(30,namedDuration.plan.visits[0].stayMinutes)
        val charging=interpretItinerary("충분히 충전으로",plan,catalog,now,focusedVisitId=1,remotePlaces=true)
        assertEquals(ChargeMode.REQUIRED,charging.plan.chargeMode)
        val deadline=interpretItinerary("오후 3시까지",plan,catalog,now,focusedVisitId=1)
        assertEquals(now.withHour(15),deadline.plan.visits[0].deadline)
        val place=interpretItinerary("이마트로",plan,catalog,now,focusedVisitId=1)
        assertEquals(mart,place.plan.visits[0].place);assertEquals(Activity.MEAL,place.plan.visits[0].activity)
        assertEquals(plan.visits[1],place.plan.visits[1])
        val remote=interpretItinerary("이마트로",plan,emptyList(),now,focusedVisitId=1,remotePlaces=true)
        assertEquals("이마트",remote.plan.visits[0].spokenPlace);assertNull(remote.plan.visits[0].place)
    }
}
