package kr.gakim.charge.data.remote

import java.time.LocalDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*

class CompositeItineraryRepository(
    private val places: KakaoPlaceSearch, private val directions: KakaoDirections, private val chargers: KecoChargers,
    override val closingCalendar: MartClosingCalendar = MartClosingCalendar(),
) : ItineraryRepository() {
    override val needsPosition get() = places.enabled || directions.enabled
    private var remotePlaces: List<Place>? = if (places.enabled) emptyList() else null
    override var searchingPlaces: Boolean = places.enabled
        private set
    private var legs = emptyMap<Pair<String?, String>, Leg>()
    private var sites = emptyMap<String, ChargingSite?>()
    private var statuses = emptyList<SourceStatus>()
    override val sourceStatuses get() = statuses.ifEmpty { listOf(places.status, directions.status, chargers.status) }
    override fun beginRemote(plan: Itinerary) {
        searchingPlaces = places.enabled && plan.visits.any { it.activity == Activity.SHOP && it.place == null }
        if (searchingPlaces) {
            remotePlaces = emptyList()
            statuses = sourceStatuses.map { if (it.source == places.status.source) it.copy(status = "조회 중") else it }
        }
    }
    override fun candidates(visit: Visit): List<Place> = (visit.place?.let { listOf(it) }
        ?: if (visit.activity == Activity.SHOP) remotePlaces ?: super.candidates(visit) else super.candidates(visit))
        .filterNot { closingCalendar.permanentlyClosed(it.name) }
    override fun leg(from: Place?, to: Place, now: LocalDateTime, demoRoutes: Boolean): Leg =
        legs[from?.id to to.id]?.takeIf { it.minutes?.evidence?.fresh(now, 300) == true }
            ?: super.leg(from, to, now, demoRoutes)
    override fun charging(place: Place, now: LocalDateTime): ChargingSite? {
        if (!sites.containsKey(place.id)) return super.charging(place, now)
        val site = sites[place.id] ?: return null
        return if (site.evidence.fresh(now, 600)) site else site.copy(availability = Availability.UNKNOWN,
            note = site.note.takeIf { "오래된 정보" in it || "상태 시각 미확인" in it } ?: "${site.note} · 오래된 정보")
    }

    override suspend fun refreshRemote(plan: Itinerary, position: Place?, now: LocalDateTime) =
        refreshRemote(plan, position, now, { true }, {})

    override suspend fun refreshRemote(plan: Itinerary, position: Place?, now: LocalDateTime,
        isCurrent: () -> Boolean, onUpdate: () -> Unit, searchBudgetMillis: Long) = coroutineScope {
        val nextStatuses = linkedMapOf<String, SourceStatus>()
        listOf(places.status, directions.status, chargers.status).forEach { nextStatuses[it.source] = it }
        fun status(source: SourceStatus, result: RemoteResult<*>, detail: String = "조회 성공") {
            nextStatuses[source.source] = when (result) {
                is RemoteResult.Available -> SourceStatus(source.source, result.fetchedAt, detail)
                is RemoteResult.Unavailable -> SourceStatus(source.source, now, result.reason)
            }
        }
        var nextPlaces = remotePlaces
        val nextLegs = mutableMapOf<Pair<String?, String>, Leg>()
        val nextSites = mutableMapOf<String, ChargingSite?>()
        fun publish() {
            if (!isCurrent()) return
            remotePlaces = nextPlaces
            legs = nextLegs.toMap()
            sites = nextSites.toMap()
            statuses = nextStatuses.values.toList()
            onUpdate()
        }
        if (plan.visits.any { it.activity == Activity.SHOP && it.place == null }) {
            if (!isCurrent()) return@coroutineScope
            searchingPlaces = places.enabled
            nextPlaces = if (places.enabled) emptyList() else null
            if (places.enabled) nextStatuses[places.status.source] = SourceStatus(places.status.source, now, "조회 중")
            publish()
            val result = when {
                !places.enabled -> RemoteResult.Unavailable("키 없음 · 비활성")
                position == null -> RemoteResult.Unavailable("현재 위치 미확인")
                else -> withTimeoutOrNull(searchBudgetMillis) { places.nearbyMarts(position) }
                    ?: RemoteResult.Unavailable("응답 시간 초과")
            }
            if (!isCurrent()) return@coroutineScope
            searchingPlaces = false
            nextPlaces = (result as? RemoteResult.Available)?.value
            status(places.status, result, "${nextPlaces?.size ?: 0}곳")
        } else {
            if (!isCurrent()) return@coroutineScope
            searchingPlaces = false
        }
        publish()
        val unresolved = plan.visits.indexOfFirst { it.place == null }
        val alternatives = if (unresolved < 0) listOf(plan.visits) else {
            val visit = plan.visits[unresolved]
            val candidates = if (visit.activity == Activity.SHOP) nextPlaces ?: super.candidates(visit) else super.candidates(visit)
            candidates.filterNot { closingCalendar.permanentlyClosed(it.name) }.map { candidate ->
                plan.visits.mapIndexed { i, v -> if (i == unresolved) v.copy(place = candidate) else v }
            }
        }
        val permits = Semaphore(3)
        if (directions.enabled && alternatives.isNotEmpty()) {
            nextStatuses[directions.status.source] = SourceStatus(directions.status.source, now, "조회 중")
            publish()
        }
        // Finish routes before starting charger work; each candidate becomes usable immediately.
        alternatives.map { visits -> async {
            permits.withPermit {
                val stops = visits.mapNotNull { it.place }
                if (stops.size != visits.size || !directions.enabled) return@withPermit
                val route = if (position == null) RemoteResult.Unavailable("현재 위치 미확인") else directions.route(position, stops)
                if (!isCurrent()) return@withPermit
                status(directions.status, route)
                if (route is RemoteResult.Available && route.value.size == stops.size)
                    stops.forEachIndexed { i, stop -> nextLegs[stops.getOrNull(i - 1)?.id to stop.id] = route.value[i] }
                publish()
            }
        } }.awaitAll()
        if (!isCurrent()) return@coroutineScope
        if (chargers.enabled && alternatives.isNotEmpty()) {
            nextStatuses[chargers.status.source] = SourceStatus(chargers.status.source, now, "조회 중")
            publish()
        }
        alternatives.flatten().mapNotNull { it.place }.distinctBy { it.id }.map { place -> async {
            permits.withPermit {
                if (!chargers.enabled) return@withPermit
                val result = chargers.nearby(place)
                if (!isCurrent()) return@withPermit
                status(chargers.status, result, chargers.status.status.takeUnless { it == "아직 조회하지 않음" } ?: "조회 성공")
                if (result is RemoteResult.Available) nextSites[place.id] = result.value
                publish()
            }
        } }.awaitAll()
        publish()
    }
}
