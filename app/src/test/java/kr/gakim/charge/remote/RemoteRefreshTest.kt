package kr.gakim.charge.remote

import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.gakim.charge.data.*
import kr.gakim.charge.data.fake.*
import kr.gakim.charge.data.remote.*
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.planner.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteRefreshTest {
    private val at = LocalDateTime.of(2026, 11, 22, 12, 0)
    private val mart = Place("real", "이마트 성남점", 37.4127, 127.0947, "경기 성남시 수정구")
    private fun repository(
        search: suspend () -> List<Place> = { listOf(mart) },
        charge: suspend (Place) -> ChargingSite? = { null },
        calendar: MartClosingCalendar = MartClosingCalendar(),
    ) = CompositeItineraryRepository(object : KakaoPlaceSearch {
        override val enabled = true
        override val status = SourceStatus("places", null, "아직 조회하지 않음")
        override suspend fun nearbyMarts(position: Place) = RemoteResult.Available(search(), at)
    }, object : KakaoDirections {
        override val enabled = true
        override val status = SourceStatus("directions", null, "아직 조회하지 않음")
        override suspend fun route(origin: Place, stops: List<Place>) = RemoteResult.Available(stops.map {
            Leg(Measured(5, Evidence("route", at)), Measured(2.0, Evidence("route", at)))
        }, at)
    }, object : KecoChargers {
        override val enabled = true
        override val status = SourceStatus("chargers", null, "아직 조회하지 않음")
        override suspend fun nearby(place: Place) = RemoteResult.Available(charge(place), at)
    }, calendar)
    private fun controller(repo: CompositeItineraryRepository, vehicle: FakeVehicleGateway = FakeVehicleGateway()): ItineraryController {
        val navi = object : NaviGateway by FakeNaviGateway(null) {
            override suspend fun currentPosition() = mart
        }
        return ItineraryController(GatewaySet(vehicle, navi, FakeSpeechGateway(), false), repo, { at })
    }

    @Test fun routesPublishWhileChargersWaitAndEnergyTicksContinue() = runTest {
        val release = CompletableDeferred<Unit>()
        val vehicle = FakeVehicleGateway()
        val repo = repository(charge = { release.await(); ChargingSite(Availability.AVAILABLE, evidence = Evidence("charger", at)) })
        val c = controller(repo, vehicle)
        backgroundScope.launch { c.observeEnergy() }; runCurrent()
        val job = launch { c.previewRoutes() }; runCurrent()
        assertFalse(job.isCompleted)
        assertFalse(c.state.value.searchingPlaces)
        assertEquals(mart, c.state.value.options.first().visits.first().place)
        assertNotNull(c.state.value.options.first().totalMinutes)
        assertEquals("1곳", c.state.value.sourceStatuses.first().status)
        repeat(3) { vehicle.pushSoc(70 - it); c.refreshPark(); runCurrent() }
        release.complete(Unit); job.join()
        assertEquals(Availability.AVAILABLE, repo.charging(mart, at)?.availability)
        assertEquals(at, c.state.value.sourceStatuses.last().at)
    }

    @Test fun changedPlanDropsSlowPlacesAndStatuses() = runTest {
        val release = CompletableDeferred<Unit>()
        val repo = repository(search = { release.await(); listOf(mart) })
        val c = controller(repo)
        val job = launch { c.previewRoutes() }; runCurrent()
        c.setActivity(1, Activity.MEAL)
        val before = c.state.value.sourceStatuses
        release.complete(Unit); job.join()
        assertEquals(before, c.state.value.sourceStatuses)
        assertTrue(c.state.value.options.none { o -> o.visits.any { it.place == mart } })
        assertTrue(repo.candidates(Visit(1)).isEmpty())
    }

    @Test fun searchShowsNoDemoUntilEightSecondTimeout() = runTest {
        val c = controller(repository(search = { delay(20_000); listOf(mart) }))
        val job = launch { c.previewRoutes() }; runCurrent()
        assertTrue(c.state.value.searchingPlaces)
        assertTrue(c.state.value.options.isEmpty())
        advanceTimeBy(7_000); runCurrent()
        assertTrue(c.state.value.options.isEmpty())
        advanceTimeBy(1_000); runCurrent(); job.join()
        assertFalse(c.state.value.searchingPlaces)
        assertTrue(c.state.value.demoMartFallback)
        assertEquals("응답 시간 초과", c.state.value.sourceStatuses.first().status)
    }

    @Test fun candidateChargerConcurrencyIsBoundedToThree() = runTest {
        var active = 0
        var maximum = 0
        val repo = repository(search = { (1..7).map { mart.copy(id = "$it") } }, charge = {
            active++; maximum = maxOf(active, maximum); delay(100); active--; null
        })
        repo.refreshRemote(Itinerary(), mart, at)
        assertEquals(3, maximum)
    }

    @Test fun retryHidesDemoBeforePositionReadCompletes() = runTest {
        val repo = repository()
        repo.refreshRemote(Itinerary(), null, at)
        assertTrue(repo.candidates(Visit(1)).all { it.demo })
        repo.beginRemote(Itinerary())
        assertTrue(repo.searchingPlaces)
        assertTrue(repo.candidates(Visit(1)).isEmpty())
        assertEquals("조회 중", repo.sourceStatuses.first().status)
    }

    @Test fun realAssetClosingDateAppearsAndExcludesRecommendation() = runTest {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val calendar = MartClosingAssets.load({ File(assets, it).readText() }, FixtureJson)
        val c = controller(repository(calendar = calendar))
        c.previewRoutes()
        assertTrue(c.state.value.options.isNotEmpty())
        assertTrue(c.state.value.options.all { it.closingNotices.single().label == "11월 22일(일) 휴무" })
        assertNull(c.state.value.selectedId)
        assertNull(c.state.value.recommendedForDisplay())
    }

    @Test fun recommendedCardIsFirstThenFinishTime() = runTest {
        val c = ItineraryController(GatewaySet(FakeVehicleGateway(), FakeNaviGateway(null), FakeSpeechGateway(), true), clock = { at })
        c.setDemoEnergy(true); c.compare()
        val s = c.state.value
        val groups = s.candidateGroups()
        assertTrue(groups.first().any { it.id == s.recommendedForDisplay()?.id })
        val finishes = groups.drop(1).map { it.mapNotNull { o -> o.results.lastOrNull()?.finish }.minOrNull() }
        assertEquals(finishes.sortedWith(nullsLast(naturalOrder())), finishes)
    }
}
