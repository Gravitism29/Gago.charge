package kr.gakim.charge

import kr.gakim.charge.data.*
import kr.gakim.charge.domain.*
import kr.gakim.charge.ui.state.*
import org.junit.Assert.*
import org.junit.Test

class AssistantLinesTest {
    private val school = NaviPlace("학교", "", 1.0, 1.0)
    private val base = UiState(usingFakes = true, dataLabel = "시연", nowMinutes = 900, thresholdPercent = 20,
        navi = NaviRead.Known(NaviRoute(school, emptyList())))
    private val a = MartCandidate("a", "A마트", 30, 5, true, emptyList(), 0, 20)
    private fun option(name: String, arrival: Int, status: TimingStatus = TimingStatus.OK, mode: StopMode = StopMode.SHOP_ONLY, selectable: Boolean = true) =
        OptionUi(MartOption(a.copy(id = name, name = name), mode, arrival, status, selectable), school.copy(name = name))
    private val comparison = base.copy(screen = Screen.COMPARE, form = TodayForm(deadline = Deadline.At(960)), arrivalSoc = 92,
        options = listOf(option("A마트", 955), option("B마트", 952)))
    @Test fun idleExact() {
        assertEquals(AssistantUi("장 보고 가실 건가요?", "지금 내비 목적지는 학교예요. 말하거나 아래 예시를 눌러 보세요."), AssistantLines.build(base))
    }
    @Test fun listeningDoesNotSpeak() {
        assertEquals(AssistantUi("듣고 있어요…", "\"네 시\"", false), AssistantLines.build(base.copy(speech = SpeechUi(listening = true, heard = "네 시"))))
    }
    @Test fun noDestinationExact() {
        assertEquals(AssistantUi("내비에 목적지가 없어요.", "먼저 내비에서 가실 곳을 정해 주세요."), AssistantLines.build(base.copy(navi = NaviRead.Known(NaviRoute(null, emptyList())))))
    }
    @Test fun missingDeadlineExact() {
        assertEquals(AssistantUi("몇 시까지 학교에 도착해야 하나요?", "\"네 시\", \"네 시 반까지\"처럼 말하거나 시각을 눌러 주세요."), AssistantLines.build(base.copy(spokenSlots = setOf(Slot.PICKUP))))
    }
    @Test fun completedExact() {
        val s = base.copy(form = TodayForm(deadline = Deadline.At(960)), spokenSlots = setOf(Slot.DEADLINE))
        assertEquals("장 보고 4시까지 학교군요.", AssistantLines.build(s).line)
    }
    @Test fun noPickupAndAnytimeVariants() {
        assertEquals("장만 보고 가는 길이군요.", AssistantLines.build(base.copy(form = TodayForm(pickup = false), spokenSlots = setOf(Slot.PICKUP))).line)
        assertTrue(AssistantLines.build(base.copy(form = TodayForm(deadline = Deadline.Anytime), spokenSlots = setOf(Slot.DEADLINE))).line.startsWith("시간은 여유 있게,"))
    }
    @Test fun unavailableExact() {
        assertEquals("마이크를 쓸 수 없어요. 아래를 눌러서 정할 수 있어요.", AssistantLines.build(base.copy(speech = SpeechUi(available = false))).line)
    }
    @Test fun unknownBatteryDoesNotBlock() {
        val s = base.copy(battery = BatteryReading.Unknown("no"), form = TodayForm(pickup = false))
        assertNull(s.blocker); assertTrue(AssistantLines.build(s).sub!!.contains("배터리 잔량을 알 수 없어요."))
    }
    @Test fun fastestExactAndSub() {
        assertEquals(AssistantUi("B마트가 3분 더 빨라요. B마트로 갈까요?", "학교 4시까지 · 도착 때 잔량 92%"), AssistantLines.build(comparison))
    }
    @Test fun equalExact() {
        assertEquals("두 곳 다 제시간이에요.", AssistantLines.build(comparison.copy(options = listOf(option("A마트", 955), option("B마트", 955)))).line)
    }
    @Test fun allLateExact() {
        assertEquals("두 곳 모두 4시까지는 어려워요. 그래도 들를까요?", AssistantLines.build(comparison.copy(options = listOf(option("A마트", 980, TimingStatus.LATE), option("B마트", 990, TimingStatus.LATE)))).line)
    }
    @Test fun chargingExact() {
        val s = comparison.copy(options = listOf(option("A마트", 970, mode = StopMode.SHOP_AND_CHARGE), option("B마트", 952, mode = StopMode.SHOP_AND_CHARGE)))
        assertEquals("B마트가 18분 더 빨라요. B마트로 갈까요?", AssistantLines.build(s).line)
    }
    @Test fun recommendationSkipsLateUnavailableAndKeepsFirstTie() {
        val s = comparison.copy(options = listOf(option("A", 900, TimingStatus.LATE), option("B", 910, selectable = false), option("C", 920), option("D", 920)))
        assertEquals(2, AssistantLines.recommendedIndex(s))
        assertNull(AssistantLines.recommendedIndex(s.copy(options = s.options.take(2))))
    }
    @Test fun guideExact() {
        assertEquals(AssistantUi("A마트에 들렀다가 학교로 갈게요. 이대로 안내할까요?", "확인하기 전에는 내비를 바꾸지 않아요."), AssistantLines.build(comparison.copy(screen = Screen.GUIDE, selectedIndex = 0)))
    }
    @Test fun guideResultsExact() {
        val s = comparison.copy(screen = Screen.GUIDE, selectedIndex = 0)
        assertEquals("A마트를 경유지로 넣었어요. 학교는 그대로예요.", AssistantLines.build(s.copy(guide = GuideUi(verify = VerifyResult.APPLIED))).line)
        assertEquals("아직 확인하지 못했어요.", AssistantLines.build(s.copy(guide = GuideUi(verify = VerifyResult.PENDING))).line)
        assertEquals("내비 경로가 계획과 달라요. 내비 화면을 확인해 주세요.", AssistantLines.build(s.copy(guide = GuideUi(verify = VerifyResult.MISMATCH))).line)
    }
    @Test fun failedRequeryShowsFailureInsteadOfOldPending() {
        val s = comparison.copy(screen = Screen.GUIDE, selectedIndex = 0,
            guide = GuideUi(verify = VerifyResult.PENDING, message = "내비 상태를 알 수 없어요."))
        assertEquals("내비 상태를 알 수 없어요.", AssistantLines.build(s).line)
    }
}
