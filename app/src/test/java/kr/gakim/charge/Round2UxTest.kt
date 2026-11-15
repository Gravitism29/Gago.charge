package kr.gakim.charge

import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.demo.*
import kr.gakim.charge.domain.*
import kr.gakim.charge.ui.state.*
import org.junit.Assert.*
import org.junit.Test

class Round2UxTest {
    private val navi = FakeNaviGateway(DemoDataRepository.SCHOOL)
    private val vehicle = FakeVehicleGateway(soc = 100)
    private fun controller(repository: TripDataRepository = DemoDataRepository()) = PlanController(
        GatewaySet(vehicle, navi, FakeSpeechGateway(), true), repository, nowMs = { 100L })

    @Test fun firstPlusAndMinimum() = runTest {
        val c = controller(); c.refresh(); c.nudgeDeadline(10)
        assertEquals(Deadline.At(930), c.state.value.form.deadline)
        repeat(10) { c.nudgeDeadline(-10) }
        assertEquals(Deadline.At(910), c.state.value.form.deadline)
        c.setDeadline(Deadline.At(909))
        assertEquals(Deadline.At(910), c.state.value.form.deadline)
        assertEquals("지금은 3시예요. 그보다 뒤로 정해 주세요.", c.state.value.assistant.line)
    }
    @Test fun rejectsPastVoiceWithoutChangingPlanOrScheduling() = runTest {
        val c = controller(); c.refresh()
        for (sentence in listOf("오후 두 시 오십 분까지", "오후 세 시 오 분까지", "5분 뒤")) {
            val before = c.state.value.form
            c.onSpokenSentence(sentence)
            assertEquals(before, c.state.value.form)
            assertNull(c.state.value.autoAdvanceAtMs)
            assertEquals(deadlineMessage(900), c.state.value.assistant.line)
        }
        c.onSpokenSentence("오후 세 시 십 분까지")
        assertEquals(Deadline.At(910), c.state.value.form.deadline)
    }
    @Test fun validationUsesRepositoryNowAndRefreshRevalidates() = runTest {
        var now = 900
        val repo = object : TripDataRepository by DemoDataRepository() { override fun nowMinutes() = now }
        val c = controller(repo); c.refresh(); c.setDeadline(Deadline.At(930))
        now = 925; c.openCompare()
        assertEquals(Screen.TODAY, c.state.value.screen)
        assertEquals(deadlineMessage(925), c.state.value.blocker)
        c.onSpokenSentence("오후 세 시 삼십 분까지")
        assertEquals("지금은 3시 25분이에요. 그보다 뒤로 정해 주세요.", c.state.value.assistant.line)
    }
    @Test fun nextHalfHourRespectsTenMinuteMinimum() {
        assertEquals(930, deadlineChoices(900).first())
        assertEquals(930, deadlineChoices(920).first())
        assertEquals(960, deadlineChoices(921).first())
    }
    @Test fun recommendedIsSelectedWithoutNaviMutationAtFullBattery() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); c.openCompare()
        val s = c.state.value
        assertEquals(s.recommendedIndex, s.selectedIndex)
        assertEquals("B마트", shortName(s.selected!!.place.name))
        assertEquals(StopMode.SHOP_ONLY, s.selected!!.option.mode)
        assertEquals(ChargingPrompt.DEFAULT_ROUTE, s.prompt)
        assertEquals(BatteryReading.Known(100), s.battery)
        assertEquals(4, s.options.size)
        assertEquals(0, navi.requestCount)
        assertEquals("B마트가 3분 더 빨라요. B마트로 갈까요?", s.assistant.line)
    }
    @Test fun lowBatteryStillRecommendsCharging() = runTest {
        vehicle.soc = 25
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); c.openCompare()
        assertEquals(StopMode.SHOP_AND_CHARGE, c.state.value.selected!!.option.mode)
        assertEquals(c.state.value.recommendedIndex, c.state.value.selectedIndex)
    }
    @Test fun timelineSplitPreservesEveryArrivalAndStop() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); c.openCompare()
        val s = c.state.value
        assertEquals(listOf(955, 970, 952, 952), s.options.map { it.option.arrival })
        s.options.forEach { ui ->
            val segments = timelineSegments(ui, 900)
            assertEquals(listOf(SegmentKind.DRIVE, SegmentKind.STOP, SegmentKind.DRIVE), segments.map { it.kind })
            assertEquals(ui.option.candidate.driveMinutes, segments[0].minutes + segments[2].minutes)
            assertEquals(ui.option.arrival!! - 900, segments.sumOf { it.minutes })
            assertTrue(comparisonFacts(s, ui).size <= 3)
        }
    }
    @Test fun missingSplitIsNotInvented() {
        val o = compareMarts(listOf(DemoDataRepository.MART_A.candidate), 900, 20, 960, 5, ChargingPrompt.DEFAULT_ROUTE).first()
        assertTrue(timelineSegments(OptionUi(o, DemoDataRepository.MART_A.place), 900).isEmpty())
    }
    @Test fun particlesCoverConsonantVowelAndRieul() {
        assertEquals("학교 정문이에요", withIeyo("학교 정문"))
        assertEquals("학교예요", withIeyo("학교"))
        assertEquals("학교 정문으로", withRo("학교 정문"))
        assertEquals("서울로", withRo("서울"))
        assertEquals("학교로", withRo("학교"))
        assertEquals("학교 정문은", withNeun("학교 정문"))
        assertEquals("학교는", withNeun("학교"))
        assertEquals("학교 정문이", withGa("학교 정문"))
        assertEquals("학교가", withGa("학교"))
        assertEquals("학교 정문을", withReul("학교 정문"))
        assertEquals("학교를", withReul("학교"))
        assertEquals("학교 정문이군요", withIgunyo("학교 정문"))
        assertEquals("학교군요", withIgunyo("학교"))
    }
    @Test fun understoodAndTouchUseSeparateLines() = runTest {
        val c = controller(); c.refresh(); c.onSpokenSentence("장 보고 4시까지 애 데리러 가야 해")
        assertEquals(AssistantUi("장 보고 4시까지 학교 정문이군요.", "두 곳을 비교할게요."), c.state.value.assistant)
        c.setDeadline(Deadline.At(970))
        assertEquals(AssistantUi("4시 10분까지 학교 정문이군요.", "비교하려면 아래 버튼을 눌러 주세요."), c.state.value.assistant)
        assertNull(c.state.value.autoAdvanceAtMs)
    }
    @Test fun allLateHasFastestLatenessAndNoRecommendation() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(940)); c.openCompare()
        assertEquals(AssistantUi("두 곳 모두 3시 40분까지는 어려워요. 그래도 들를까요?", "가장 빠른 B마트도 12분 늦어요."), c.state.value.assistant)
        assertNull(c.state.value.recommendedIndex)
        assertNull(c.state.value.selectedIndex)
    }
    @Test fun ttsWaitAndCancelMessage() = runTest {
        val c = controller(); c.refresh(); c.onSpokenSentence("장 보고 4시까지 애 데리러 가야 해")
        assertTrue(c.state.value.autoAdvanceAtMs!! >= 2600L)
        assertTrue(c.state.value.autoAdvanceDurationMs >= c.state.value.assistant.line.length * 180L)
        c.cancelAutoAdvance()
        assertEquals("멈췄어요. 준비되면 눌러 주세요.", c.state.value.assistant.line)
        assertNull(c.state.value.autoAdvanceAtMs)
    }
    @Test fun secondExampleAsksForTime() = runTest {
        val c = controller(); c.refresh(); c.onSpokenSentence("장 보고 애 데리러 가야 해")
        assertEquals("몇 시까지 학교 정문에 도착해야 하나요?", c.state.value.assistant.line)
        assertTrue(needsDeadlineEditor(c.state.value))
        assertNull(c.state.value.autoAdvanceAtMs)
    }
    @Test fun freshMovingReadBlocksCompare() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960))
        vehicle.parkState = ParkState.MOVING
        c.openCompare()
        assertEquals(Screen.TODAY, c.state.value.screen)
        assertTrue(c.state.value.options.isEmpty())
        assertEquals(0, navi.requestCount)
    }

    @Test fun explicitChargingComparisonDescribesDisplayedArrivals() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); c.setCharge(ChargePreference.WANT)
        c.openCompare()
        assertEquals("B마트가 18분 더 빨라요. B마트로 갈까요?", c.state.value.assistant.line)
    }

    @Test fun modeChoicesRemainConsistentAcrossCardsAndAssistant() = runTest {
        val c = controller(); c.refresh(); c.setDeadline(Deadline.At(960)); c.openCompare()
        c.select(1); c.select(2)
        assertEquals(mapOf("demo-mart-a" to 1, "demo-mart-b" to 2), c.state.value.cardModeIndices)
        assertEquals("B마트가 18분 더 빨라요. B마트로 갈까요?", c.state.value.assistant.line)
        c.back(); c.setActivityMinutes(30)
        assertTrue(c.state.value.cardModeIndices.isEmpty())
    }

}
