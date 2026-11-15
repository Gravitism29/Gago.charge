package kr.gakim.charge

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.data.PlaceMatcher
import kr.gakim.charge.data.fake.FakeNaviGateway
import kr.gakim.charge.data.fake.FakeSpeechGateway
import kr.gakim.charge.data.fake.FakeVehicleGateway
import kr.gakim.charge.demo.DemoDataRepository
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.ChargingPrompt
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.RouteAction
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.domain.VerifyResult
import kr.gakim.charge.domain.formatClock
import kr.gakim.charge.ui.state.PlanController
import kr.gakim.charge.ui.state.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake 게이트웨이로 세 화면 흐름이 끝까지 진행되는지 확인한다. */
class PlanControllerTest {

    private val vehicle = FakeVehicleGateway()
    private val navi = FakeNaviGateway(DemoDataRepository.SCHOOL)
    private val speech = FakeSpeechGateway(stepDelayMs = 1)
    private val controller = PlanController(
        GatewaySet(vehicle, navi, speech, usingFakes = true), DemoDataRepository(), verifyAttempts = 3, verifyDelayMs = 10,
    )
    private val state get() = controller.state.value

    private fun indexOf(martName: String, mode: StopMode) =
        state.options.indexOfFirst { it.option.candidate.name.startsWith(martName) && it.option.mode == mode }

    @Test
    fun fullFlow_buttonsOnly_addsMartBeforeSchool() = runTest {
        controller.refresh()
        assertEquals(BatteryReading.Known(25), state.battery)
        assertNotNull(state.blocker) // 픽업 시각을 아직 안 넣음
        controller.setDeadline(Deadline.At(16 * 60))
        assertNull(state.blocker)

        controller.openCompare()
        assertEquals(Screen.COMPARE, state.screen)
        assertEquals(17, state.arrivalSoc) // 25% - 시연 소모 8%p
        assertEquals(ChargingPrompt.COMPARE_CHARGING, state.prompt)
        assertEquals(4, state.options.size)
        // 지시서의 시연 예시 값
        val aShop = state.options[indexOf("A마트", StopMode.SHOP_ONLY)].option
        val aCharge = state.options[indexOf("A마트", StopMode.SHOP_AND_CHARGE)].option
        val bCharge = state.options[indexOf("B마트", StopMode.SHOP_AND_CHARGE)].option
        assertEquals("3시 55분", formatClock(aShop.arrival!!))
        assertEquals("4시 10분", formatClock(aCharge.arrival!!))
        assertEquals(TimingStatus.LATE, aCharge.status)
        assertEquals("3시 52분", formatClock(bCharge.arrival!!))
        assertEquals(TimingStatus.OK, bCharge.status)

        controller.select(indexOf("B마트", StopMode.SHOP_AND_CHARGE))
        controller.openGuide()
        assertEquals(Screen.GUIDE, state.screen)
        assertEquals(0, navi.requestCount) // 확인 전에는 내비를 바꾸지 않는다

        controller.confirmGuidance()
        assertEquals(1, navi.requestCount)
        assertEquals(RouteAction.REQUEST_ONCE, state.guide.action)
        assertEquals(VerifyResult.APPLIED, state.guide.verify)
        val actual = state.guide.actual!!
        assertTrue(PlaceMatcher.samePlace(DemoDataRepository.SCHOOL, actual.destination!!)) // 목적지 유지
        assertEquals(listOf(DemoDataRepository.MART_B.place), actual.waypoints) // 마트 → 학교

        // 다시 눌러도 요청을 또 보내지 않고 재조회만 한다.
        controller.confirmGuidance()
        assertEquals(1, navi.requestCount)
        assertEquals(RouteAction.REQUERY, state.guide.action)
        assertEquals(VerifyResult.APPLIED, state.guide.verify)
    }

    @Test
    fun fullFlow_withFakeSpeech() = runTest {
        controller.refresh()
        speech.listen().collect { controller.onSpeechEvent(it) }
        controller.onSpeechStopped()
        assertEquals(Deadline.At(16 * 60), state.form.deadline)
        assertTrue(state.form.pickup)
        assertFalse(state.speech.listening)
        controller.openCompare()
        assertEquals(Screen.COMPARE, state.screen)
    }

    @Test
    fun spokenCorrection_lastValueWins() = runTest {
        controller.refresh()
        FakeSpeechGateway("장 보고 네 시, 아니 네 시 반까지 애 데리러 가야 해", 1).listen()
            .collect { controller.onSpeechEvent(it) }
        assertEquals(Deadline.At(16 * 60 + 30), state.form.deadline)
    }

    @Test
    fun shoppingOnlyDay_needsNoPickupPlaceOrTime() = runTest {
        controller.refresh()
        controller.setPickup(false)
        assertNull(state.blocker)
        controller.openCompare()
        assertEquals(Screen.COMPARE, state.screen)
        assertTrue(state.options.all { it.option.status == TimingStatus.NO_DEADLINE })
    }

    @Test
    fun anytimeDay_doesNotWaitForTime() = runTest {
        controller.refresh()
        controller.setDeadline(Deadline.Anytime)
        assertNull(state.blocker)
        controller.openCompare()
        assertEquals(Screen.COMPARE, state.screen)
    }

    @Test
    fun unknownBattery_asksUser_andZeroIsValid() = runTest {
        vehicle.soc = null
        controller.refresh()
        controller.setDeadline(Deadline.At(16 * 60))
        controller.openCompare()
        assertEquals(ChargingPrompt.ASK_USER, state.prompt)
        assertEquals(4, state.options.size)

        controller.back()
        vehicle.soc = 0
        controller.openCompare()
        assertEquals(BatteryReading.Known(0), state.battery)
        // 0%는 '미확인'이 아니라 유효한 값이다 → 충전 비교로 이어진다.
        assertEquals(0, state.arrivalSoc)
        assertEquals(ChargingPrompt.COMPARE_CHARGING, state.prompt)
    }

    @Test
    fun declineCharging_defaultsToShopButOffersOptionalCharging() = runTest {
        controller.refresh()
        controller.setDeadline(Deadline.At(16 * 60))
        controller.setCharge(ChargePreference.DECLINE)
        controller.openCompare()
        assertEquals(4, state.options.size)
        assertEquals(StopMode.SHOP_ONLY, state.selected?.option?.mode)
    }

    @Test
    fun naviUnknown_blocks() = runTest {
        navi.unavailable = true
        controller.refresh()
        controller.setDeadline(Deadline.At(16 * 60))
        assertNotNull(state.blocker)
        controller.openCompare()
        assertEquals(Screen.TODAY, state.screen)
    }

    @Test
    fun twoWaypointsAlready_doesNotAdd() = runTest {
        val p1 = NaviPlace("약국", "", 37.1, 127.1)
        val p2 = NaviPlace("세탁소", "", 37.2, 127.2)
        navi.setRoute(NaviRoute(DemoDataRepository.SCHOOL, listOf(p1, p2)))
        controller.refresh()
        controller.setDeadline(Deadline.At(16 * 60))
        assertTrue(state.blocker!!.contains("경유지"))
        controller.openCompare()
        assertEquals(Screen.TODAY, state.screen)
        assertEquals(0, navi.requestCount)
    }

    private suspend fun toGuide() {
        controller.refresh()
        controller.setDeadline(Deadline.At(16 * 60))
        controller.openCompare()
        controller.select(indexOf("A마트", StopMode.SHOP_ONLY))
        controller.openGuide()
    }

    @Test
    fun moving_holds() = runTest {
        // 주행 잠금은 1·2 화면의 입력을 막으므로, 비교·안내는 정차 상태에서 만든 뒤 주행으로 바꿔 확인한다.
        toGuide()
        vehicle.parkState = ParkState.MOVING
        controller.refresh()
        controller.confirmGuidance()
        assertEquals(RouteAction.HOLD, state.guide.action)
        assertEquals(0, navi.requestCount)
    }

    @Test
    fun unknownParkState_needsUserConfirmation() = runTest {
        vehicle.parkState = ParkState.UNKNOWN
        toGuide()
        controller.confirmGuidance()
        assertEquals(RouteAction.HOLD, state.guide.action)
        assertEquals(0, navi.requestCount)
        controller.setParkedConfirmed(true)
        controller.confirmGuidance()
        assertEquals(1, navi.requestCount)
        assertEquals(VerifyResult.APPLIED, state.guide.verify)
    }

    @Test
    fun routeChangedAfterCompare_recompares() = runTest {
        toGuide()
        navi.setRoute(NaviRoute(NaviPlace("병원", "", 37.3, 127.3), emptyList()))
        controller.confirmGuidance()
        assertEquals(RouteAction.RECOMPARE, state.guide.action)
        assertEquals(0, navi.requestCount)
    }

    @Test
    fun requestAcceptedButNotApplied_isMismatch() = runTest {
        navi.ignoreRequests = true
        toGuide()
        controller.confirmGuidance()
        assertEquals(1, navi.requestCount)
        assertEquals(VerifyResult.MISMATCH, state.guide.verify)
        assertFalse(state.guide.busy)
    }

    @Test
    fun existingWaypointIsKept_martGoesFirst() = runTest {
        val pharmacy = NaviPlace("약국", "", 37.1, 127.1)
        navi.setRoute(NaviRoute(DemoDataRepository.SCHOOL, listOf(pharmacy)))
        toGuide()
        controller.confirmGuidance()
        assertEquals(VerifyResult.APPLIED, state.guide.verify)
        assertEquals(listOf(DemoDataRepository.MART_A.place, pharmacy), state.guide.actual!!.waypoints)
    }

    @Test
    fun naviUnknownAtConfirm_doesNotSend() = runTest {
        toGuide()
        navi.unavailable = true
        controller.confirmGuidance()
        assertEquals(0, navi.requestCount)
        assertNotNull(state.guide.message)
    }
}
