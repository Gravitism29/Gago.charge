package kr.gakim.charge

import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.GatewaySet
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.data.fake.FakeNaviGateway
import kr.gakim.charge.data.fake.FakeSpeechGateway
import kr.gakim.charge.data.fake.FakeVehicleGateway
import kr.gakim.charge.demo.DemoDataRepository
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.ChargingPrompt
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.ui.state.PlanController
import kr.gakim.charge.ui.state.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시연 잔량 덮어쓰기와 주행 잠금(1·2 화면) 규칙. */
class DemoBatteryAndDrivingLockTest {

    private val vehicle = FakeVehicleGateway()
    private val navi = FakeNaviGateway(DemoDataRepository.SCHOOL)
    private fun controller() = PlanController(
        GatewaySet(vehicle, navi, FakeSpeechGateway(), usingFakes = true),
        DemoDataRepository(), verifyAttempts = 3, verifyDelayMs = 1,
    )

    @Test
    fun demoOverride15_withAuto_comparesCharging() = runTest {
        val c = controller()
        c.refresh()
        assertEquals(BatteryReading.Known(25), c.state.value.battery)

        c.setDemoBatteryOverride(15)
        assertEquals(BatteryReading.Known(15), c.state.value.battery)
        assertEquals(15, c.state.value.demoBatteryOverride)

        c.setDeadline(Deadline.At(16 * 60))
        c.openCompare()

        val s = c.state.value
        assertEquals(Screen.COMPARE, s.screen)
        assertEquals(ChargingPrompt.COMPARE_CHARGING, s.prompt)
        assertEquals(7, s.arrivalSoc) // 15% - 시연 소모 8%p
        assertTrue(s.options.any { it.option.mode == StopMode.SHOP_AND_CHARGE })
    }

    @Test
    fun demoOverride15_withAuto_beatsRealFullBattery() = runTest {
        vehicle.soc = 100
        val c = controller()
        c.refresh()
        c.setCharge(ChargePreference.AUTO)
        c.setDemoBatteryOverride(15)
        c.setDeadline(Deadline.At(16 * 60))
        c.openCompare()
        assertEquals(ChargingPrompt.COMPARE_CHARGING, c.state.value.prompt)
        assertTrue(c.state.value.options.any { it.option.mode == StopMode.SHOP_AND_CHARGE })
    }

    @Test
    fun clearingOverride_returnsRealReading() = runTest {
        val c = controller()
        c.refresh()
        c.setDemoBatteryOverride(15)
        c.setDemoBatteryOverride(null)
        assertNull(c.state.value.demoBatteryOverride)
        assertEquals(BatteryReading.Known(25), c.state.value.battery)
    }

    @Test
    fun refreshStoresMovingParkState() = runTest {
        vehicle.parkState = ParkState.MOVING
        val c = controller()
        c.refresh()
        assertEquals(ParkState.MOVING, c.state.value.parkState)
    }

    @Test
    fun defaultParkStateIsUnknown() = runTest {
        val c = controller()
        assertEquals(ParkState.UNKNOWN, c.state.value.parkState)
    }

    @Test
    fun moving_blocksOpenCompareAndSelection() = runTest {
        val c = controller()
        c.refresh()
        c.setDeadline(Deadline.At(16 * 60))
        c.openCompare()
        assertEquals(Screen.COMPARE, c.state.value.screen)

        vehicle.parkState = ParkState.MOVING
        c.refresh()
        assertEquals(ParkState.MOVING, c.state.value.parkState)

        val previousSelection = c.state.value.selectedIndex
        c.select(0)
        assertEquals(previousSelection, c.state.value.selectedIndex)

        c.onSpokenSentence("추천대로")
        assertEquals(previousSelection, c.state.value.selectedIndex)
        assertEquals(Screen.COMPARE, c.state.value.screen)

        c.back()
        c.openCompare()
        assertEquals(Screen.TODAY, c.state.value.screen) // 주행 중에는 비교로 넘어가지 않는다
    }

    @Test
    fun movingOnToday_ignoresSpokenPlan() = runTest {
        vehicle.parkState = ParkState.MOVING
        val c = controller()
        c.refresh()
        c.onSpokenSentence("장 보고 네 시까지 애 데리러 가야 해")
        assertEquals(Deadline.Unset, c.state.value.form.deadline)
        assertTrue(c.state.value.spokenSlots.isEmpty())
    }

    @Test
    fun parkedAgain_restoresEditing() = runTest {
        vehicle.parkState = ParkState.MOVING
        val c = controller()
        c.refresh()
        c.setDeadline(Deadline.At(16 * 60))
        c.openCompare()
        assertEquals(Screen.TODAY, c.state.value.screen)

        vehicle.parkState = ParkState.PARKED
        c.refresh()
        c.setDeadline(Deadline.At(16 * 60))
        c.openCompare()
        assertEquals(Screen.COMPARE, c.state.value.screen)
        assertFalse(c.state.value.options.isEmpty())
    }
}
