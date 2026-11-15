package kr.gakim.charge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Python 모형에 반영되지 않았던 두 반례(FABLE_BRIEF 5절)에 대한 테스트.
 * 1. 장만 보는 날에는 픽업 장소를 요구하지 않는다.
 * 2. 몇 시든 괜찮은 날에는 시각 입력을 기다리지 않는다.
 */
class CounterexampleTest {

    private val standard = StopInputs(
        depart = 0, access = 5, facility = true, units = listOf(ChargerUnit(true, true, true)),
        wait = 0, chargeMinutes = 20,
    )

    // ---- 반례 1: 장만 보는 날 ----

    @Test
    fun shoppingOnlyDay_doesNotAskForPickupPlace() {
        val form = planFromButtons("장보기", PickupIntent.NoPickup, Deadline.Unset, StopMode.SHOP_ONLY)
        assertEquals(PlanResult.ShopOnly("장보기", null, 50), planAction(form, standard))
    }

    @Test
    fun shoppingOnlyDay_withCharging_doesNotAskForPickupPlaceOrTime() {
        val form = planFromButtons("장보기", PickupIntent.NoPickup, Deadline.Unset, StopMode.SHOP_AND_CHARGE)
        assertEquals(PlanResult.StopTogether(50), planAction(form, standard))
    }

    @Test
    fun shoppingOnlyDay_stillHonoursADeadlineIfUserSetOne() {
        val form = planFromButtons("장보기", PickupIntent.NoPickup, Deadline.At(60), StopMode.SHOP_AND_CHARGE)
        assertEquals(PlanResult.Message("늦음"), planAction(form, standard.copy(chargeMinutes = 35)))
    }

    @Test
    fun pickupDay_stillAsksForPlace() {
        val form = planFromButtons("장보기", PickupIntent.Pickup(""), Deadline.At(60), StopMode.SHOP_ONLY)
        assertEquals(PlanResult.Message("픽업 장소 선택"), planAction(form, standard))
    }

    @Test
    fun shoppingOnlyDay_fullFlowReachesNavi() {
        val outcome = demoOutcome(
            70, standard, approved = true, observed = Route("학교 정문", listOf("마트")),
            deadline = Deadline.Unset, pickup = PickupIntent.NoPickup,
        )
        assertEquals(DemoOutcome.Requested(ChargingPrompt.DEFAULT_ROUTE, VerifyResult.APPLIED), outcome)
    }

    // ---- 반례 2: 몇 시든 괜찮은 날 ----

    @Test
    fun anytimeDay_doesNotWaitForTime_shopOnly() {
        val form = planFromButtons("장보기", PickupIntent.Pickup("학교 정문"), Deadline.Anytime, StopMode.SHOP_ONLY)
        assertEquals(PlanResult.ShopOnly("장보기", "학교 정문", 50), planAction(form, standard))
    }

    @Test
    fun anytimeDay_doesNotWaitForTime_withCharging() {
        val form = planFromButtons("장보기", PickupIntent.Pickup("학교 정문"), Deadline.Anytime, StopMode.SHOP_AND_CHARGE)
        // 마감이 있었다면 "늦음"이었을 35분 충전도 진행한다. 도착 = 0+25+5+0+35.
        assertEquals(PlanResult.StopTogether(65), planAction(form, standard.copy(chargeMinutes = 35)))
    }

    @Test
    fun anytimeDay_unknownWaitIsNotBlocking_butArrivalStaysUnknown() {
        val form = planFromButtons("장보기", PickupIntent.Pickup("학교 정문"), Deadline.Anytime, StopMode.SHOP_AND_CHARGE)
        assertEquals(PlanResult.StopTogether(null), planAction(form, standard.copy(wait = null)))
    }

    @Test
    fun anytimeDay_stillNeedsAUsableCharger() {
        val form = planFromButtons("장보기", PickupIntent.Pickup("학교 정문"), Deadline.Anytime, StopMode.SHOP_AND_CHARGE)
        assertEquals(PlanResult.Message("장보기만 또는 다른 시설"), planAction(form, standard.copy(units = emptyList())))
    }

    @Test
    fun anytimeDay_fullFlowReachesNavi() {
        val outcome = demoOutcome(
            15, standard, approved = true, observed = Route("학교 정문", listOf("마트")),
            deadline = Deadline.Anytime,
        )
        assertEquals(DemoOutcome.Requested(ChargingPrompt.COMPARE_CHARGING, VerifyResult.APPLIED), outcome)
    }

    @Test
    fun unsetTimeOnPickupDay_stillWaits() {
        // 반례 수정이 기존 규칙(픽업이 있는데 시각을 안 넣음 → 기다림)을 깨지 않는지 확인.
        val form = planFromButtons("장보기", PickupIntent.Pickup("학교 정문"), Deadline.Unset, StopMode.SHOP_ONLY)
        assertEquals(PlanResult.Message("픽업 시각 미정"), planAction(form, standard))
    }

    @Test
    fun timingWithoutDeadline_neverLate() {
        val t = timingWithoutDeadline(0, 25, 5, 30, 20, 90)
        assertEquals(150, t.arrival)
        assertTrue(t.status == TimingStatus.NO_DEADLINE)
    }

    @Test
    fun missingInput_gate() {
        assertEquals(null, missingInput(PickupIntent.NoPickup, Deadline.Unset))
        assertEquals(null, missingInput(PickupIntent.Pickup("학교 정문"), Deadline.Anytime))
        assertEquals(null, missingInput(PickupIntent.Pickup("학교 정문"), Deadline.At(60)))
        assertEquals("픽업 장소 선택", missingInput(PickupIntent.Pickup(null), Deadline.Anytime))
        assertEquals("픽업 시각 미정", missingInput(PickupIntent.Pickup("학교 정문"), Deadline.Unset))
    }
}
