package kr.gakim.charge.demo

import java.time.LocalDateTime
import kr.gakim.charge.domain.itinerary.*

/** Explicit fixture catalog; these names/coordinates are demonstration POIs, not verified businesses. */
open class ItineraryRepository {
    open val needsPosition: Boolean = false
    open val sourceStatuses: List<kr.gakim.charge.data.remote.SourceStatus> = emptyList()
    open val closingCalendar = MartClosingCalendar()
    open suspend fun refreshRemote(plan: Itinerary, position: Place?, now: LocalDateTime) = Unit
    open val searchingPlaces: Boolean = false
    open fun beginRemote(plan: Itinerary) = Unit
    open suspend fun refreshRemote(plan: Itinerary, position: Place?, now: LocalDateTime,
        isCurrent: () -> Boolean, onUpdate: () -> Unit, searchBudgetMillis: Long = 8_000) {
        refreshRemote(plan, position, now)
        if (isCurrent()) onUpdate()
    }
    private fun place(id: String, name: String, lat: Double, lon: Double) = Place(id, name, lat, lon, "시연 좌표 · 실제 점포 아님", demo = true, source = "시연 장소 목록")
    val catalog = listOf(
        CatalogEntry(place("mart-e", "은평점", 37.635, 126.918), setOf(Activity.SHOP), listOf("은평점", "은평마트")),
        CatalogEntry(place("mart-s", "상암점", 37.576, 126.897), setOf(Activity.SHOP), listOf("상암점", "상암마트")),
        CatalogEntry(place("mart-y", "연신내점", 37.619, 126.921), setOf(Activity.SHOP), listOf("연신내점")),
        CatalogEntry(place("meal-a", "느티 식당", 37.620, 126.922), setOf(Activity.MEAL), listOf("느티 식당")),
        CatalogEntry(place("meal-b", "마루 식당", 37.622, 126.924), setOf(Activity.MEAL), listOf("마루 식당")),
        CatalogEntry(place("meal-c", "골목 식당", 37.624, 126.919), setOf(Activity.MEAL), listOf("골목 식당")),
        CatalogEntry(place("collect", "물건 수령처", 37.625, 126.925), setOf(Activity.COLLECT), listOf("수령처", "물건 수령처")),
        CatalogEntry(place("hospital", "병원", 37.629, 126.929), setOf(Activity.HOSPITAL), listOf("병원")),
        CatalogEntry(place("school", "학교", 37.630, 126.931), setOf(Activity.PICKUP), listOf("학교")),
    )
    open fun candidates(visit: Visit): List<Place> = visit.place?.let { listOf(it) }
        ?: if(visit.activity==Activity.CHARGE) catalog.map { it.place }.filter {
            val site=charging(it,LocalDateTime.now())
            site!=null && site.availability !in listOf(Availability.INCOMPATIBLE,Availability.UNAVAILABLE)
        } else catalog.filter { visit.activity in it.activities }.map { it.place }

    open fun leg(from: Place?, to: Place, now: LocalDateTime, demoRoutes: Boolean): Leg {
        if (!demoRoutes || !to.demo || from?.demo == false) return Leg(null, null)
        val toMinutes = mapOf("mart-e" to 12, "mart-s" to 16, "mart-y" to 9, "meal-a" to 10, "meal-b" to 14, "meal-c" to 8, "collect" to 12, "hospital" to 24, "school" to 28)
        val minutes = when {
            from == null -> toMinutes[to.id] ?: 15
            from.id == to.id -> 0
            from.id == "collect" && to.id == "hospital" -> 17
            from.id.startsWith("mart") && to.id == "school" -> if (from.id == "mart-e") 18 else 25
            else -> 10 + kotlin.math.abs((toMinutes[to.id] ?: 15) - (toMinutes[from.id] ?: 15))
        }
        val distances = when {
            from == null -> mapOf("mart-e" to 8.0, "mart-s" to 11.0, "mart-y" to 6.0, "meal-a" to 7.0, "meal-b" to 10.0, "meal-c" to 5.0, "collect" to 8.0, "hospital" to 18.0, "school" to 20.0)[to.id] ?: 12.0
            from.id == to.id -> 0.0
            from.id == "collect" && to.id == "hospital" -> 11.0
            to.id == "school" -> 13.0
            else -> 9.0
        }
        val evidence = Evidence("시연 구간 표 · 실제 교통 미반영", now, true)
        return Leg(Measured(minutes, evidence), Measured(distances, evidence))
    }
    open fun charging(place: Place, now: LocalDateTime): ChargingSite? {
        if (!place.demo || place.id in listOf("collect", "hospital", "school")) return null
        val evidence = Evidence("시연 충전기·실효 출력", now, true)
        return ChargingSite(when(place.id) { "meal-c" -> Availability.INCOMPATIBLE; "mart-y" -> Availability.UNKNOWN; else -> Availability.AVAILABLE },
            waitMinutes = if (place.id == "mart-y") null else Measured(if (place.id == "mart-e") 8 else 0, evidence),
            effectivePowerKw = Measured(30.0, evidence), overlapVerified = place.id != "mart-y", evidence = evidence,
            note = "같은 주차장·보행·연결 시간은 시연 가정")
    }
    fun demoEnergy(now: LocalDateTime, rangeKm: Double = 25.0) = Energy(Measured(rangeKm, Evidence("시연 주행 가능 거리", now, true)),
        Measured(5.5, Evidence("시연 전비 km/kWh", now, true)), Measured(25, Evidence("시연 배터리", now, true)))
}
