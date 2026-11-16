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
        for(text in listOf("안내 시작","응","그래","가자")) {
            assertEquals(PlannerVoiceCommand.Confirm,plannerVoiceCommand(text,2))
            assertNull(plannerVoiceCommand(text,1))
            assertNull(plannerVoiceCommand(text,0))
        }
        for(text in listOf("아니","다른 곳으로")) assertEquals(PlannerVoiceCommand.Back,plannerVoiceCommand(text,2))
    }
    @Test fun unrecognisedEditsNegationsAndQuotedCommandsNeverConfirm() {
        for(text in listOf("안내 시작하지 마","안내 시작이라고 했어","응 아니","“안내 시작”","30분 장 보고 갈래"))
            assertNull(plannerVoiceCommand(text,2))
        assertNull(plannerVoiceCommand("아니 여기로 가자",1))
        assertNull(plannerVoiceCommand("충전하고 갈게",0))
    }
}
