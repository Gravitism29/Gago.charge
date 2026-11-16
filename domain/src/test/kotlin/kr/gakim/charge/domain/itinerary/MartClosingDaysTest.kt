package kr.gakim.charge.domain.itinerary

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MartClosingDaysTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val record = MartClosingRecord(MartIdentity("이마트", "은평점"),
        setOf(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 25)), setOf(YearMonth.of(2026, 11)),
        listOf(ClosingPattern(DayOfWeek.WEDNESDAY, setOf(2, 4))), source = "official")
    private fun calendar(store: MartClosingRecord = record) = MartClosingCalendar(listOf(store))
    @Test fun explicitDateIsPlainAndSelectableMetadata() {
        val notice = calendar().notice("이마트 은평점", LocalDate.of(2026, 11, 25), today)!!
        assertEquals("11월 25일(수) 휴무", notice.label)
        assertFalse(notice.estimated)
        assertEquals("official", notice.source)
    }
    @Test fun coveredOpenDayHasNoCaution() { assertNull(calendar().notice("이마트 은평점", LocalDate.of(2026, 11, 22), today)) }
    @Test fun laterMonthUsesStorePattern() { assertEquals("1월 13일(수) 휴무 (추정)", calendar().notice("이마트 은평점", LocalDate.of(2027, 1, 13), today)?.label) }
    @Test fun districtPatternPrecedesInference() {
        val store = record.copy(patterns = emptyList(), districtPatterns = listOf(ClosingPattern(DayOfWeek.SUNDAY, setOf(2, 4))))
        assertNotNull(calendar(store).notice("이마트 은평점", LocalDate.of(2026, 12, 13), today))
        assertNull(calendar(store).notice("이마트 은평점", LocalDate.of(2026, 12, 9), today))
    }
    @Test fun ownDatesProvideLastResortPattern() { assertNotNull(calendar(record.copy(patterns = emptyList())).notice("이마트 은평점", LocalDate.of(2026, 12, 9), today)) }
    @Test fun unknownStoreAndEarlierUncoveredMonthShowNothing() {
        assertNull(calendar().notice("이마트 다른점", LocalDate.of(2026, 12, 9), today))
        assertNull(calendar().notice("이마트 은평점", LocalDate.of(2026, 10, 14), today.minusMonths(2)))
    }
    @Test fun pastDatesAreIgnored() { assertNull(calendar().notice("이마트 은평점", LocalDate.of(2026, 11, 11), today)) }
    @Test fun monthStartingSundayHasCorrectOrdinals() {
        val pattern = ClosingPattern(DayOfWeek.SUNDAY, setOf(2, 4))
        assertFalse(pattern.matches(LocalDate.of(2026, 11, 1)))
        assertTrue(pattern.matches(LocalDate.of(2026, 11, 8)))
        assertTrue(pattern.matches(LocalDate.of(2026, 11, 22)))
        assertFalse(pattern.matches(LocalDate.of(2026, 11, 29)))
    }
    @Test fun aliasesMatchExactBrandAndBranch() {
        assertEquals(MartIdentity("코스트코", "일산점"), martIdentity("코스트코코리아 일산점"))
        assertEquals(MartIdentity("트레이더스", "고양점"), martIdentity("트레이더스 홀세일 클럽 고양점"))
        assertEquals(MartIdentity("트레이더스", "고양점"), martIdentity("이마트 트레이더스 고양점"))
        assertNull(martIdentity("BHC치킨 은평이마트점"))
    }
    @Test fun permanentClosuresUseExactBranch() {
        val calendar = MartClosingCalendar(permanentlyClosed = setOf(MartIdentity("홈플러스", "킨텍스점")))
        assertTrue(calendar.permanentlyClosed("홈플러스 킨텍스점"))
        assertFalse(calendar.permanentlyClosed("홈플러스 일산점"))
    }
    @Test fun noDatesOrPatternDoesNotInventRule() {
        assertNull(calendar(record.copy(dates = emptySet(), patterns = emptyList())).notice("이마트 은평점", LocalDate.of(2026, 12, 13), today))
    }
    @Test fun districtCanEstimateAnUnlistedStoreAfterObservedMonths() {
        val calendar = MartClosingCalendar(districtRules = listOf(DistrictClosingRule("서울", "은평구",
            ClosingPattern(DayOfWeek.WEDNESDAY, setOf(2, 4)), YearMonth.of(2026, 12), "official observations")))
        assertNotNull(calendar.notice("이마트 새점", LocalDate.of(2027, 1, 13), today, "서울특별시 은평구 통일로"))
        assertNull(calendar.notice("이마트 새점", LocalDate.of(2026, 12, 9), today, "서울특별시 은평구 통일로"))
        assertNull(calendar.notice("하나로마트 새점", LocalDate.of(2027, 1, 13), today, "서울특별시 은평구 통일로"))
    }
    @Test fun closedStoreRemainsSelectableButCannotBeRecommended() {
        val now = today.atTime(12, 0)
        val evidence = Evidence("test", now)
        val plan = Itinerary(visits = listOf(Visit(1, place = Place("store", "이마트 은평점", 37.6, 126.9))))
        val option = evaluateItinerary(plan, listOf(Leg(Measured(10, evidence), Measured(1.0, evidence))), ChargeMode.NONE,
            null, null, Energy(rangeKm = Measured(100.0, evidence)), now)
        assertNotNull(recommend(listOf(option)))
        val closed = option.copy(closingNotices = listOf(ClosingNotice(today, false, "official")))
        assertTrue(closed.selectable)
        assertNull(recommend(listOf(closed)))
    }
}
