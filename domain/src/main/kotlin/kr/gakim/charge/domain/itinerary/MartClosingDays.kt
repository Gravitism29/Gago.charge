package kr.gakim.charge.domain.itinerary

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ClosingPattern(val weekday: DayOfWeek, val weeks: Set<Int>) {
    fun matches(date: LocalDate) = date.dayOfWeek == weekday && (date.dayOfMonth - 1) / 7 + 1 in weeks
}
data class MartIdentity(val brand: String, val branch: String)
fun martIdentity(name: String): MartIdentity? {
    val prefixes = linkedMapOf("이마트 트레이더스" to "트레이더스", "트레이더스 홀세일 클럽" to "트레이더스",
        "트레이더스" to "트레이더스", "코스트코코리아" to "코스트코", "코스트코" to "코스트코",
        "홈플러스" to "홈플러스", "롯데마트" to "롯데마트", "이마트" to "이마트")
    val compact = name.replace(" ", "")
    val prefix = prefixes.keys.firstOrNull { compact.startsWith(it.replace(" ", "")) } ?: return null
    return MartIdentity(prefixes.getValue(prefix), compact.removePrefix(prefix.replace(" ", "")))
}
data class MartClosingRecord(
    val identity: MartIdentity, val dates: Set<LocalDate>, val coveredMonths: Set<YearMonth>,
    val patterns: List<ClosingPattern> = emptyList(), val districtPatterns: List<ClosingPattern> = emptyList(),
    val source: String = "",
)
data class ClosingNotice(val date: LocalDate, val estimated: Boolean, val source: String) {
    val label get() = date.format(DateTimeFormatter.ofPattern("M월 d일(E)", Locale.KOREAN)) + " 휴무" + if (estimated) " (추정)" else ""
}
data class DistrictClosingRule(val province: String, val district: String, val pattern: ClosingPattern,
                               val coveredThrough: YearMonth, val source: String)
class MartClosingCalendar(val stores: List<MartClosingRecord> = emptyList(), val permanentlyClosed: Set<MartIdentity> = emptySet(),
                          val districtRules: List<DistrictClosingRule> = emptyList()) {
    fun permanentlyClosed(name: String) = martIdentity(name) in permanentlyClosed
    fun notice(name: String, visit: LocalDate, today: LocalDate, address: String = ""): ClosingNotice? {
        if (visit < today) return null
        val identity = martIdentity(name) ?: return null
        val store = stores.singleOrNull { it.identity == identity }
        val month = YearMonth.from(visit)
        if (store == null) {
            val district = districtRules.singleOrNull { address.startsWith(it.province) && it.district in address.split(" ") } ?: return null
            return if (month > district.coveredThrough && district.pattern.matches(visit)) ClosingNotice(visit, true, district.source) else null
        }
        if (month in store.coveredMonths) return if (visit in store.dates) ClosingNotice(visit, false, store.source) else null
        val lastMonth = store.coveredMonths.maxOrNull() ?: return null
        if (month <= lastMonth) return null
        val patterns = store.patterns.ifEmpty { store.districtPatterns }.ifEmpty {
            store.dates.groupBy { it.dayOfWeek }.map { (weekday, dates) ->
                ClosingPattern(weekday, dates.map { (it.dayOfMonth - 1) / 7 + 1 }.toSet())
            }
        }
        return if (patterns.any { it.matches(visit) }) ClosingNotice(visit, true, store.source) else null
    }
}
