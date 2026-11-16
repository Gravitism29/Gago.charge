package kr.gakim.charge.data.remote

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kr.gakim.charge.domain.itinerary.*

object MartClosingAssets {
    fun load(read: (String) -> String?, decoder: JsonDecoder = AndroidJsonDecoder): MartClosingCalendar {
        fun document(name: String): Map<*, *> = runCatching { decoder.decode(read(name) ?: return@runCatching emptyMap<String, Any>()).obj() }.getOrDefault(emptyMap<String, Any>())
        val districts = (document("mart_closing_rules_by_district.json")["districts"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val stores = listOf("mart_closing_days.json", "mart_closing_days_costco.json").flatMap { name ->
            val root = document(name)
            (root["stores"] as? List<*>).orEmpty().mapNotNull { item -> runCatching {
                val row = item.obj()
                val identity = martIdentity("${row.text("brand")} ${row.text("branch")}") ?: return@runCatching null
                val dates = (row["closedDates"] as? List<*>).orEmpty().mapNotNull { runCatching { LocalDate.parse(it.toString()) }.getOrNull() }.toSet()
                val months = ((row["coveredMonths"] ?: root["coveredMonths"]) as? List<*>).orEmpty()
                    .mapNotNull { runCatching { YearMonth.parse(it.toString()) }.getOrNull() }.toSet().ifEmpty { dates.map(YearMonth::from).toSet() }
                val patterns = (row["pattern"] as? List<*>).orEmpty().mapNotNull { pattern ->
                    val p = pattern.obj()
                    val day = DayOfWeek.entries.firstOrNull { it.name.startsWith(p.text("weekday")) && p.text("weekday").isNotBlank() }
                    day?.let { ClosingPattern(it, weeks(p["weeks"])) }
                }
                val district = districts.singleOrNull { it.text("sido") == row.text("sido") && it.text("sigungu") == row.text("sigungu") }
                val districtDay = district?.text("weekday")?.toIntOrNull()?.takeIf { it in 0..6 }
                val districtPatterns = if (districtDay == null) emptyList() else listOf(ClosingPattern(DayOfWeek.of(if (districtDay == 0) 7 else districtDay), weeks(district["weeks"])))
                val source = listOf(row.text("source"), row.text("sourceUrl"), row.text("sourceQuote"),
                    "${row.text("fetchedAt").ifBlank { root.text("collectedAt") }} 확인").filter { it.isNotBlank() }.joinToString(" · ")
                MartClosingRecord(identity, dates, months, patterns, districtPatterns, source)
            }.getOrNull() }
        }
        val closed = (document("mart_closed_stores.json")["stores"] as? List<*>).orEmpty().mapNotNull {
            val row = it as? Map<*, *> ?: return@mapNotNull null
            MartIdentity(row.text("brand"), row.text("branch").replace(" ", ""))
        }.toSet()
        val districtRules = districts.mapNotNull { row ->
            val day = row.text("weekday").toIntOrNull()?.takeIf { it in 0..6 } ?: return@mapNotNull null
            val dates = (row["exampleDates"] as? List<*>).orEmpty().mapNotNull { runCatching { LocalDate.parse(it.toString()) }.getOrNull() }
            val through = dates.maxOrNull()?.let(YearMonth::from) ?: return@mapNotNull null
            DistrictClosingRule(row.text("sido"), row.text("sigungu"), ClosingPattern(DayOfWeek.of(if(day == 0) 7 else day), weeks(row["weeks"])),
                through, row.text("basis"))
        }
        return MartClosingCalendar(stores, closed, districtRules)
    }
    private fun weeks(value: Any?) = (value as? List<*>).orEmpty().mapNotNull { it.toString().toIntOrNull()?.takeIf { n -> n in 1..5 } }.toSet()
}
