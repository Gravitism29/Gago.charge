package kr.gakim.charge.domain.itinerary

import org.junit.Assert.*
import org.junit.Test

class PlannerVoiceCommandTest {
    @Test fun compareProceedAndNamedPlaces() {
        for(text in listOf("이걸로 가자","그걸로 가자","여기로 가자"))
            assertEquals(PlannerVoiceCommand.Proceed(),plannerVoiceCommand(text,1))
        assertEquals(PlannerVoiceCommand.Proceed("롯데마트판교점"),plannerVoiceCommand("롯데마트 판교점으로 가자",1))
        assertEquals(PlannerVoiceCommand.Proceed("학교"),plannerVoiceCommand("학교로 가자",1))
    }
    @Test fun compareChargingAndPaging() {
        assertEquals(PlannerVoiceCommand.Charge(ChargeMode.TOP_UP),plannerVoiceCommand("충전하고 갈게",1))
        assertEquals(PlannerVoiceCommand.Charge(ChargeMode.NONE),plannerVoiceCommand("충전 안 해",1))
        assertEquals(PlannerVoiceCommand.Charge(ChargeMode.REQUIRED),plannerVoiceCommand("충분히 충전",1))
        assertEquals(PlannerVoiceCommand.OtherPlaces,plannerVoiceCommand("다른 곳 보여줘",1))
    }
    @Test fun affirmationOnlyOnConfirmationScreen() {
        // 2026-11-17 음성 평가 반영: "응"은 그 화면이 묻는 말에 대한 대답이다. 내비로 보내는 Confirm 은 여전히 확인 화면(2)에서만 나온다.
        for(text in listOf("안내 시작","응","그래","가자","네","좋아요","출발")) {
            assertEquals(PlannerVoiceCommand.Confirm,plannerVoiceCommand(text,2))
            assertTrue(plannerVoiceCommand(text,1) !is PlannerVoiceCommand.Confirm)
            assertTrue(plannerVoiceCommand(text,0) !is PlannerVoiceCommand.Confirm)
        }
        for(text in listOf("응","네","좋아요","비교해 줘")) assertEquals(PlannerVoiceCommand.Compare,plannerVoiceCommand(text,0))
        for(text in listOf("응","이걸로","여기로 갈게")) assertEquals(PlannerVoiceCommand.Proceed(),plannerVoiceCommand(text,1))
        assertEquals(PlannerVoiceCommand.Pick(0),plannerVoiceCommand("첫 번째",1))
        assertEquals(PlannerVoiceCommand.Pick(1),plannerVoiceCommand("두 번째 걸로",1))
        assertEquals(PlannerVoiceCommand.PickRecommended,plannerVoiceCommand("추천대로",1))
        assertEquals(PlannerVoiceCommand.Proceed("풍경한식"),plannerVoiceCommand("풍경한식으로",1))
        assertEquals(PlannerVoiceCommand.Back,plannerVoiceCommand("뒤로",1))
        for(text in listOf("뒤로","취소","아니요")) assertEquals(PlannerVoiceCommand.Back,plannerVoiceCommand(text,2))
        assertEquals(PlannerVoiceCommand.KeepDestination,plannerVoiceCommand("들를게요",2))
        for(text in listOf("아니","다른 곳으로")) assertEquals(PlannerVoiceCommand.Back,plannerVoiceCommand(text,2))
    }
    @Test fun unrecognisedEditsNegationsAndQuotedCommandsNeverConfirm() {
        for(text in listOf("안내 시작하지 마","안내 시작이라고 했어","응 아니","“안내 시작”","30분 장 보고 갈래"))
            assertNull(plannerVoiceCommand(text,2))
        assertNull(plannerVoiceCommand("아니 여기로 가자",1))
        assertNull(plannerVoiceCommand("충전하고 갈게",0))
    }
}
