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
    override val remotePlacesEnabled get() = places.enabled
    private var namedResults = emptyMap<Pair<String, Activity>, PlaceResolution>()
    override val namedPlaceChoices get() = namedResults.values.flatMap { it.choices }.distinctBy { it.id }
    private var placeSourceFailed = false
    private var namedSourceFailed = false
    override val allowDemoPlaces get() = !places.enabled || placeSourceFailed || namedSourceFailed
    override suspend fun resolveNamedVisits(plan: Itinerary, position: Place?, isCurrent: () -> Boolean): Map<Long, PlaceResolution> = coroutineScope {
        var failed = false
        val searched = plan.visits.filter { it.place == null && it.spokenPlace.isNotBlank() }.map { visit -> async {
            val result = when {
                isGenericPlace(visit.spokenPlace) -> PlaceResolution()
                else -> {
                    val remote = if (!places.enabled || position == null) RemoteResult.Unavailable("현재 위치 미확인")
                        else withTimeoutOrNull(8_000) { places.namedPlaces(visit.spokenPlace, visit.activity, position) }
                            ?: RemoteResult.Unavailable("응답 시간 초과")
                    when (remote) {
                        is RemoteResult.Available -> resolveNamedPlace(visit.spokenPlace,
                            remote.value.filterNot { closingCalendar.permanentlyClosed(it.name) })
                        is RemoteResult.Unavailable -> {
                            failed = true
                            val demo = catalog.filter { visit.spokenPlace in it.aliases }.map { it.place }
                            PlaceResolution(place = demo.singleOrNull(), choices = if (demo.size > 1) demo.take(4) else emptyList())
                        }
                    }
                }
            }
            visit to result
        } }.awaitAll()
        val results = searched.map { (visit, result) ->
            val fallback = if (failed && isGenericPlace(visit.spokenPlace))
                catalog.filter { visit.spokenPlace in it.aliases }.map { it.place }.singleOrNull() else null
            visit to (fallback?.let { PlaceResolution(place = it) } ?: result)
        }
        if (isCurrent()) {
            namedResults = results.associate { (v, r) -> (v.spokenPlace to v.activity) to r }
            namedSourceFailed = failed
        }
        results.associate { it.first.id to it.second }
    }
    override val needsPosition get() = places.enabled || directions.enabled
    private var remotePlaces: List<Place>? = if (places.enabled) emptyList() else null
    /** remotePlaces 가 어느 볼일의 후보인지. 장소 미정인 첫 볼일을 따른다. */
    private var remoteActivity: Activity = Activity.SHOP
    override var exampleDestination: String? = null
        private set
    override var searchingPlaces: Boolean = places.enabled
        private set
    private var legs = emptyMap<Pair<String?, String>, Leg>()
    private var sites = emptyMap<String, ChargingSite?>()
    private var statuses = emptyList<SourceStatus>()
    override val sourceStatuses get() = statuses.ifEmpty { listOf(places.status, directions.status, chargers.status) }
    override fun beginRemote(plan: Itinerary) {
        searchingPlaces = places.enabled && plan.visits.any { it.place == null &&
            (nearbySearchable(it.activity) || it.spokenPlace.isNotBlank() && !isGenericPlace(it.spokenPlace)) }
        if (searchingPlaces) {
            namedResults = emptyMap()
            namedSourceFailed = false
            placeSourceFailed = false
            remotePlaces = emptyList()
            statuses = sourceStatuses.map { if (it.source == places.status.source) it.copy(status = "조회 중") else it }
        }
    }
    override fun candidates(visit: Visit): List<Place> = (visit.place?.let { listOf(it) }
        ?: if (places.enabled && visit.spokenPlace.isNotBlank() && !isGenericPlace(visit.spokenPlace)) emptyList()
        else if (visit.activity == remoteActivity || visit.activity == Activity.SHOP && !places.enabled) remotePlaces ?: super.candidates(visit)
        else if (places.enabled && nearbySearchable(visit.activity)) emptyList()
        else if (!allowDemoPlaces) emptyList() else super.candidates(visit))
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
        if (places.enabled && position != null && exampleDestination == null)
            exampleDestination = withTimeoutOrNull(searchBudgetMillis) { places.nearestStationName(position) }
        val open = plan.visits.firstOrNull { it.place == null && nearbySearchable(it.activity) &&
                (it.spokenPlace.isBlank() || isGenericPlace(it.spokenPlace)) }
        if (open != null) {
            remoteActivity = open.activity
            if (!isCurrent()) return@coroutineScope
            searchingPlaces = places.enabled
            nextPlaces = if (places.enabled) emptyList() else null
            if (places.enabled) nextStatuses[places.status.source] = SourceStatus(places.status.source, now, "조회 중")
            publish()
            val result = when {
                !places.enabled -> RemoteResult.Unavailable("키 없음 · 비활성")
                position == null -> RemoteResult.Unavailable("현재 위치 미확인")
                else -> withTimeoutOrNull(searchBudgetMillis) { places.nearbyFor(open.activity, position) }
                    ?: RemoteResult.Unavailable("응답 시간 초과")
            }
            if (!isCurrent()) return@coroutineScope
            var found = (result as? RemoteResult.Available)?.value
            // 식당·병원은 후보가 많다. 충전기가 붙어 있는 곳을 먼저, 그다음 가까운 순으로 3곳만 비교한다.
            if (found != null && found.size > 3) {
                if (chargers.enabled) {
                    // 첫 곳을 먼저 조회해 시군구 충전기 목록(수 MB)을 캐시에 올린다. 동시에 시작하면 같은 목록을 여러 번 내려받는다(에뮬레이터가 재부팅됐다).
                    (chargers.nearby(found.first()) as? RemoteResult.Available)?.let { nextSites[found.first().id] = it.value }
                    val gate = Semaphore(2)
                    found.drop(1).map { place -> async { gate.withPermit {
                        (chargers.nearby(place) as? RemoteResult.Available)?.let { nextSites[place.id] = it.value }
                    } } }.awaitAll()
                    if (!isCurrent()) return@coroutineScope
                }
                found = found.withIndex().sortedWith(compareBy({ if (nextSites[it.value.id] != null) 0 else 1 }, { it.index }))
                    .map { it.value }.take(3)
            }
            searchingPlaces = false
            nextPlaces = found
            placeSourceFailed = result is RemoteResult.Unavailable
            status(places.status, result, "${nextPlaces?.size ?: 0}곳")
        } else {
            if (!isCurrent()) return@coroutineScope
            searchingPlaces = false
        }
        publish()
        val unresolved = plan.visits.indexOfFirst { it.place == null }
        val alternatives = if (unresolved < 0) listOf(plan.visits) else {
            val visit = plan.visits[unresolved]
            val candidates = candidates(visit)
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
