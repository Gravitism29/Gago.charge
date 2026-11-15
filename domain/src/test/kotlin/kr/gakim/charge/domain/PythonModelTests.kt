package kr.gakim.charge.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/*
 * `가는 김에 충전_판단규칙 검증.py` 의 run() 이 확인하는 기대값 137개를 그대로 옮긴 테스트.
 * 한 기대값이 테스트 한 개가 되도록 Parameterized 로 썼다.
 *
 *   동일 충전기 64 + 시간 조합 18 + 시간 경계 6 + 상태 갱신 6 + 내비 요청 9
 *   + 내비 결과 4 + 무음성 입력 10 + 충전 제안 10 + 무음성 모의 전체 흐름 10 = 137
 *
 * Python 의 문자열 기대값은 Kotlin enum 의 label 과 비교한다.
 */

/** 동일 충전기: 64개. 7(=111)은 세 조건을 모두 만족한 충전기 한 대를 뜻한다. */
@RunWith(Parameterized::class)
class SameUnitTest(private val a: Int, private val b: Int) {
    companion object {
        @JvmStatic
        @Parameters(name = "units {0},{1}")
        fun data(): List<Array<Any>> = (0 until 8).flatMap { a -> (0 until 8).map { b -> arrayOf<Any>(a, b) } }
    }

    @Test
    fun usableOnlyWhenOneUnitMeetsAllThree() {
        val units = listOf(a, b).map { n -> ChargerUnit(n and 4 != 0, n and 2 != 0, n and 1 != 0) }
        val expected = a == 7 || b == 7
        assertEquals(expected, hasUsableUnit(units))
    }
}

/** 시간 조합: 18개. 15시를 0으로 둔 분 단위. */
@RunWith(Parameterized::class)
class TimingCombinationTest(private val d: Int, private val q: Int, private val c: Int) {
    companion object {
        private val onTime = setOf(Triple(0, 0, 10), Triple(0, 0, 20), Triple(5, 0, 10), Triple(5, 0, 20))

        @JvmStatic
        @Parameters(name = "depart {0}, queue {1}, charging {2}")
        fun data(): List<Array<Any>> =
            listOf(0, 5, 10).flatMap { d ->
                listOf(0, 10).flatMap { q -> listOf(10, 20, 35).map { c -> arrayOf<Any>(d, q, c) } }
            }
    }

    @Test
    fun okOnlyForHandPickedCombinations() {
        val status = timing(d, 25, 5, q, 20, c, 60, 5).status
        assertEquals(Triple(d, q, c) in onTime, status == TimingStatus.OK)
    }
}

/** 시간 경계: 6개. */
@RunWith(Parameterized::class)
class TimingBoundaryTest(
    private val q: Int?,
    private val c: Int?,
    private val end: Int?,
    private val expected: String,
) {
    companion object {
        @JvmStatic
        @Parameters(name = "queue {0}, charging {1}, deadline {2} -> {3}")
        fun data(): List<Array<Any?>> = listOf(
            arrayOf(null, 20, 60, "시간 미확인"),
            arrayOf(0, null, 60, "시간 미확인"),
            arrayOf(0, 20, null, "시간 미확인"),
            arrayOf(0, 20, 55, "여유"),
            arrayOf(0, 20, 54, "촉박"),
            arrayOf(0, 20, 49, "늦음"),
        )
    }

    @Test
    fun boundary() {
        assertEquals(expected, timing(0, 25, 5, q, 20, c, end, 5).status.label)
    }
}

/** 상태 갱신: 6개. */
@RunWith(Parameterized::class)
class ApplyChangesTest(
    @Suppress("unused") private val name: String,
    private val updates: List<UnitUpdate>,
    private val answer: Map<UnitKey, UnitStatus>,
) {
    companion object {
        private val k = UnitKey("A", "01")
        private val other = UnitKey("B", "01")
        private val saved = mapOf(k to UnitStatus(100, "가능"), other to UnitStatus(101, "충전중"))

        @JvmStatic
        @Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf("갱신 없음", emptyList<UnitUpdate>(), saved),
            arrayOf("더 오래된 값은 무시", listOf(UnitUpdate(k, 90, "고장")), saved),
            arrayOf("같은 시각 같은 상태", listOf(UnitUpdate(k, 100, "가능")), saved),
            arrayOf("같은 시각 다른 상태는 재조회", listOf(UnitUpdate(k, 100, "고장")), saved + (k to UnitStatus(100, "재조회"))),
            arrayOf(
                "순서가 뒤바뀌어도 최신 유지",
                listOf(UnitUpdate(k, 105, "충전중"), UnitUpdate(k, 104, "가능")),
                saved + (k to UnitStatus(105, "충전중")),
            ),
            arrayOf("다른 충전기만 갱신", listOf(UnitUpdate(other, 106, "가능")), saved + (other to UnitStatus(106, "가능"))),
        )
    }

    @Test
    fun merge() {
        assertEquals(answer, applyChanges(saved, updates))
    }
}

/** 내비 요청: 9개. */
@RunWith(Parameterized::class)
class RouteDecisionTest(
    private val current: Route,
    private val wanted: Route,
    private val parked: Boolean,
    private val confirmed: Boolean,
    private val sent: Boolean,
    private val expected: String,
) {
    companion object {
        private val original = Route("집", listOf("학교"))
        private val added = Route("집", listOf("마트", "학교"))

        @JvmStatic
        @Parameters(name = "{5}: {0} -> {1}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf(original, added, true, true, false, "요청 한 번"),
            arrayOf(original, added, true, false, false, "보류"),
            arrayOf(original, added, false, true, false, "보류"),
            arrayOf(original, added, true, true, true, "현재 경로 재조회"),
            arrayOf(Route("병원", emptyList()), added, true, true, false, "계획 다시 비교"),
            arrayOf(original, original, true, true, false, "이미 반영"),
            arrayOf(original, Route("집", listOf("약국", "마트", "학교")), true, true, false, "경유지 한도"),
            arrayOf(original, Route("집", listOf("마트")), true, true, false, "기존 일정 확인"),
            arrayOf(original, Route("학원", listOf("마트")), true, true, false, "목적지 확인"),
        )
    }

    @Test
    fun decision() {
        assertEquals(expected, routeDecision(original, current, wanted, parked, confirmed, sent).label)
    }
}

/** 내비 결과: 4개. */
@RunWith(Parameterized::class)
class VerifyReplyTest(private val actual: Route?, private val expected: String) {
    companion object {
        private val original = Route("집", listOf("학교"))
        private val added = Route("집", listOf("마트", "학교"))

        @JvmStatic
        @Parameters(name = "{0} -> {1}")
        fun data(): List<Array<Any?>> = listOf(
            arrayOf(null, "반영 확인 중"),
            arrayOf(added, "반영 확인"),
            arrayOf(original, "경로 불일치"),
            arrayOf(Route("집", listOf("학교", "마트")), "경로 불일치"),
        )
    }

    @Test
    fun verify() {
        assertEquals(expected, verifyReply(actual, added).label)
    }
}

private val standard = StopInputs(
    depart = 0, access = 5, facility = true, units = listOf(ChargerUnit(true, true, true)),
    wait = 0, chargeMinutes = 20,
)

/** 무음성 입력: 10개. Python 의 pickup=None → Pickup(null), when=None → Deadline.Unset. */
@RunWith(Parameterized::class)
class PlanActionTest(
    @Suppress("unused") private val name: String,
    private val form: PlanInput,
    private val inputs: StopInputs,
    private val expected: PlanResult,
) {
    companion object {
        private val form = planFromButtons(
            "장보기", PickupIntent.Pickup("학교 정문"), Deadline.At(60), StopMode.SHOP_AND_CHARGE,
        )
        private fun msg(s: String) = PlanResult.Message(s)

        @JvmStatic
        @Parameters(name = "{0}")
        fun data(): List<Array<Any>> = listOf(
            arrayOf("함께 정차 예상 50", form, standard, PlanResult.StopTogether(50)),
            arrayOf(
                "충전 선택 안 함", form.copy(charge = StopMode.SHOP_ONLY), standard,
                // Python: ("장보기", "학교 정문"). 도착 50분은 Kotlin 에서 덧붙인 값.
                PlanResult.ShopOnly("장보기", "학교 정문", 50),
            ),
            arrayOf("픽업 장소 없음", form.copy(pickup = PickupIntent.Pickup(null)), standard, msg("픽업 장소 선택")),
            arrayOf("픽업 시각 없음", form.copy(deadline = Deadline.Unset), standard, msg("픽업 시각 미정")),
            arrayOf("대기 시간 모름", form, standard.copy(wait = null), msg("시간 미확인")),
            arrayOf("충전 35분", form, standard.copy(chargeMinutes = 35), msg("늦음")),
            arrayOf("충전 시설 없음", form, standard.copy(facility = false), msg("장보기만 또는 다른 시설")),
            arrayOf("충전기 없음", form, standard.copy(units = emptyList()), msg("장보기만 또는 다른 시설")),
            arrayOf("늦게 출발", form.copy(charge = StopMode.SHOP_ONLY), standard.copy(depart = 15), msg("늦음")),
            arrayOf(
                "충전 안 함 + 시각 없음", form.copy(charge = StopMode.SHOP_ONLY, deadline = Deadline.Unset), standard,
                msg("픽업 시각 미정"),
            ),
        )
    }

    @Test
    fun action() {
        assertEquals(expected, planAction(form, inputs))
    }
}

/** 충전 제안: 10개. */
@RunWith(Parameterized::class)
class ChargingPromptTest(
    private val choice: String,
    private val estimate: Int?,
    private val threshold: Int?,
    private val expected: String,
) {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}, 도착 {1}%, 기준 {2}% -> {3}")
        fun data(): List<Array<Any?>> = listOf(
            arrayOf("자동", 19, 20, "충전 비교"),
            arrayOf("자동", 20, 20, "충전 비교"),
            arrayOf("자동", 21, 20, "기본 경로"),
            arrayOf("자동", null, 20, "사용자 선택"),
            arrayOf("자동", -1, 20, "사용자 선택"),
            arrayOf("자동", 101, 20, "사용자 선택"),
            arrayOf("자동", 40, null, "사용자 선택"),
            arrayOf("원함", 85, 20, "충전 비교"),
            arrayOf("자동", 40, 120, "사용자 선택"),
            arrayOf("원하지 않음", 15, 20, "기본 경로"),
        )
    }

    @Test
    fun prompt() {
        val preference = ChargePreference.entries.single { it.label == choice }
        assertEquals(expected, chargingPrompt(preference, estimate, threshold).label)
    }
}

/** 무음성 모의 전체 흐름: 10개. */
@RunWith(Parameterized::class)
class DemoOutcomeTest(
    @Suppress("unused") private val name: String,
    private val estimate: Int?,
    private val inputs: StopInputs,
    private val approved: Boolean,
    private val observed: Route?,
    private val unspecified: Boolean,
    private val expected: Any,
) {
    companion object {
        private val applied = Route("학교 정문", listOf("마트"))

        @JvmStatic
        @Parameters(name = "{0}")
        fun data(): List<Array<Any?>> = listOf(
            arrayOf("잔량 충분", 70, standard, true, applied, false, "기본 경로" to "반영 확인"),
            arrayOf("잔량 부족", 15, standard, true, applied, false, "충전 비교" to "반영 확인"),
            arrayOf("충전 35분", 15, standard.copy(chargeMinutes = 35), true, applied, false, "늦음"),
            arrayOf("늦게 출발", 70, standard.copy(depart = 15), true, applied, false, "늦음"),
            arrayOf("잔량 모름", null, standard, true, applied, false, "사용자 선택"),
            arrayOf("대기 시간 모름", 15, standard.copy(wait = null), true, applied, false, "시간 미확인"),
            arrayOf("사용자 미확인", 15, standard, false, applied, false, "보류"),
            arrayOf("내비 응답 없음", 15, standard, true, null, false, "충전 비교" to "반영 확인 중"),
            arrayOf("경유지 미반영", 15, standard, true, Route("학교 정문", emptyList()), false, "충전 비교" to "경로 불일치"),
            arrayOf("시각 미입력", 15, standard, true, applied, true, "픽업 시각 미정"),
        )
    }

    @Test
    fun outcome() {
        val deadline = if (unspecified) Deadline.Unset else Deadline.At(60)
        val actual: Any = when (val o = demoOutcome(estimate, inputs, approved, observed, deadline)) {
            is DemoOutcome.Stopped -> o.label
            is DemoOutcome.Requested -> o.prompt.label to o.verify.label
        }
        assertEquals(expected, actual)
    }
}
