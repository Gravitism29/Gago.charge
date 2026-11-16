package kr.gakim.charge

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

class PlannerVoiceSafetyTest {
    private var now=LocalDateTime.of(2026,11,16,10,0)
    private val navi=FakeNaviGateway(null)
    private fun controller(vehicle:FakeVehicleGateway=FakeVehicleGateway())=ItineraryController(GatewaySet(vehicle,navi,FakeSpeechGateway(),true),clock={now},verifyAttempts=1,verifyDelayMs=1)
    private suspend fun ready(c:ItineraryController) {
        c.setDemoEnergy(true);c.refresh();c.compare()
        // Match the first visible page; “이걸로” must not choose a card hidden on another page.
        c.select(c.state.value.candidateGroups().first().first { it.mode==ChargeMode.NONE && it.selectable }.id)
    }
    @Test fun compareProceedOnlyOpensReviewAndNamedPlaceMustMatch()=runTest {
        val c=controller();ready(c)
        c.handleVoiceCommand("없는 곳으로 가자","test")
        assertEquals(1,c.state.value.screen)
        c.handleVoiceCommand("이걸로 가자","test")
        assertEquals(2,c.state.value.screen)
        assertEquals(0,navi.requestCount)
    }
    @Test fun confirmationUsesExistingPreflightAndCompletedHandoffDoesNotInvalidate()=runTest {
        val c=controller();ready(c);c.guide()
        assertEquals(PlannerVoiceCommand.Confirm,c.handleVoiceCommand("안내 시작","test"))
        assertTrue(c.prepareGuidance());c.confirm()
        assertEquals(ApplyPhase.APPLIED,c.state.value.phase)
        val message=c.state.value.applyMessage
        val selected=c.state.value.selected
        now=now.plusMinutes(2);c.tick();c.refresh()
        assertEquals(ApplyPhase.APPLIED,c.state.value.phase)
        assertEquals(message,c.state.value.applyMessage)
        assertEquals(selected,c.state.value.selected)
        assertFalse(c.prepareGuidance())
        assertEquals(1,navi.requestCount)
    }
    @Test fun preSendTimingChangeStillRevokesApproval()=runTest {
        val c=controller();ready(c);c.guide();c.setAcknowledged(true)
        now=now.plusMinutes(1);c.tick()
        assertFalse(c.state.value.acknowledged)
        assertNull(c.state.value.approvalKey)
        c.confirm();assertEquals(0,navi.requestCount)
    }
    @Test fun modeControlHasComputedAlternativesAfterExplicitModeRequest()=runTest {
        val c=controller();ready(c);c.setCharge(ChargeMode.NONE)
        assertTrue(c.state.value.options.all { it.mode==ChargeMode.NONE })
        assertEquals(setOf(ChargeMode.NONE,ChargeMode.TOP_UP,ChargeMode.REQUIRED),c.state.value.comparisonOptions.map { it.mode }.toSet())
        val charged=c.state.value.comparisonOptions.first { it.mode==ChargeMode.TOP_UP && it.selectable }
        c.selectMode(charged.id)
        assertEquals(charged.id,c.state.value.selectedId)
        assertEquals(ChargeMode.TOP_UP,c.state.value.selected?.mode)
        assertEquals(0,navi.requestCount)
    }
    @Test fun gateRejectsWrongStepMovingAndPendingApprovals()=runTest {
        val c=controller();ready(c)
        assertFalse(c.state.value.canStartGuidance())
        c.guide()
        val s=c.state.value
        assertFalse(s.copy(park=ParkState.MOVING).canStartGuidance())
        assertFalse(s.copy(pendingImport=NaviRoute(null,emptyList())).canStartGuidance())
        assertFalse(s.copy(question=SpeechQuestion("확인")).canStartGuidance())
        assertFalse(s.copy(park=ParkState.UNKNOWN,parkedConfirmed=false).canStartGuidance())
    }
    @Test fun voiceChargingPagingAndNamedSelectionUseComputedCandidates()=runTest {
        val c=controller();ready(c)
        val place=c.state.value.selected!!.visits.first().place
        c.handleVoiceCommand("충전하고 갈게","test")
        assertEquals(ChargeMode.TOP_UP,c.state.value.selected?.mode)
        assertEquals(place,c.state.value.selected!!.visits.first().place)
        c.handleVoiceCommand("충전 안 해","test")
        assertEquals(ChargeMode.NONE,c.state.value.selected?.mode)
        c.handleVoiceCommand("다른 곳 보여줘","test")
        assertEquals(1,c.state.value.candidatePage)
        c.handleVoiceCommand("${place!!.name}으로 가자","test")
        assertEquals(2,c.state.value.screen)
        assertEquals(place,c.state.value.selected!!.visits.first().place)
        assertEquals(0,navi.requestCount)
    }
    @Test fun movingVehicleRejectsVoiceCommandsAndSharedCtaGate()=runTest {
        val vehicle=FakeVehicleGateway()
        val c=controller(vehicle);ready(c)
        val selected=c.state.value.selectedId
        vehicle.parkState=ParkState.MOVING;c.refreshPark()
        assertNull(c.handleVoiceCommand("충전하고 갈게","test"))
        assertNull(c.handleVoiceCommand("이걸로 가자","test"))
        assertEquals(selected,c.state.value.selectedId)
        assertEquals(1,c.state.value.screen)
        assertFalse(c.prepareGuidance())
        assertEquals(0,navi.requestCount)
    }
}
