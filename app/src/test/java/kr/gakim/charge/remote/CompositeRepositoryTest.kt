package kr.gakim.charge.remote

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.remote.*
import kr.gakim.charge.demo.ItineraryRepository
import kr.gakim.charge.domain.itinerary.*
import org.junit.Assert.*
import org.junit.Test

class CompositeRepositoryTest {
    private val at = LocalDateTime.of(2026, 11, 16, 12, 5)
    private val real = Place("real", "이마트 은평점", 37.635, 126.918, "서울 은평구", source = "카카오 장소 검색")
    private val status = SourceStatus("test", at, "조회 성공")
    private class Places(override val enabled: Boolean, override val status: SourceStatus, var result: RemoteResult<List<Place>>) : KakaoPlaceSearch {
        var calls = 0
        override suspend fun nearbyMarts(position: Place): RemoteResult<List<Place>> { calls++; return result }
    }
    private class Routes(override val status: SourceStatus, var result: RemoteResult<List<Leg>>) : KakaoDirections {
        override val enabled = true
        override suspend fun route(origin: Place, stops: List<Place>) = result
    }
    private class Chargers(override val status: SourceStatus, var result: RemoteResult<ChargingSite?>) : KecoChargers {
        override val enabled = true
        override suspend fun nearby(place: Place) = result
    }
    private val failed = RemoteResult.Unavailable("offline")
    private fun repo(places: Places, routes: Routes = Routes(status, failed), chargers: Chargers = Chargers(status, failed), calendar: MartClosingCalendar = MartClosingCalendar()) =
        CompositeItineraryRepository(places, routes, chargers, calendar)
    @Test fun failureUsesExactlyDemoCandidatesLegsAndCharger() = runTest {
        val repository = repo(Places(true, status, failed))
        repository.refreshRemote(Itinerary(), real, at)
        val demo = ItineraryRepository()
        val place = demo.candidates(Visit(1)).first()
        assertEquals(demo.candidates(Visit(1)), repository.candidates(Visit(1)))
        assertEquals(demo.leg(null, place, at, true), repository.leg(null, place, at, true))
        assertEquals(demo.charging(place, at), repository.charging(place, at))
    }
    @Test fun disabledAndUnknownPositionNeverSearch() = runTest {
        val places = Places(false, status, RemoteResult.Available(listOf(real), at))
        val repository = repo(places)
        repository.refreshRemote(Itinerary(), real, at)
        assertEquals(0, places.calls)
        val enabled = Places(true, status, RemoteResult.Available(listOf(real), at))
        repo(enabled).refreshRemote(Itinerary(), null, at)
        assertEquals(0, enabled.calls)
    }
    @Test fun successfulEmptySearchDoesNotBecomeDemoMarts() = runTest {
        val repository = repo(Places(true, status, RemoteResult.Available(emptyList(), at)))
        repository.refreshRemote(Itinerary(), real, at)
        assertTrue(repository.candidates(Visit(1)).isEmpty())
    }
    @Test fun realValuesOverrideDemoIndependently() = runTest {
        val evidence = Evidence("카카오모빌리티 길찾기", at)
        val leg = Leg(Measured(7, evidence), Measured(3.0, evidence))
        val site = ChargingSite(Availability.AVAILABLE, evidence = Evidence("한국환경공단", at), accessTimesKnown = false)
        val repository = repo(Places(true, status, RemoteResult.Available(listOf(real), at)), Routes(status, RemoteResult.Available(listOf(leg), at)), Chargers(status, RemoteResult.Available(site, at)))
        repository.refreshRemote(Itinerary(), real, at)
        assertEquals(listOf(real), repository.candidates(Visit(1)))
        assertEquals(leg, repository.leg(null, real, at, true))
        assertEquals(site, repository.charging(real, at))
        assertNull(repository.leg(null, real, at.plusMinutes(6), true).minutes)
    }
    @Test fun fixedPlacesArePreservedAndClosedCandidatesRemoved() = runTest {
        val calendar = MartClosingCalendar(permanentlyClosed = setOf(MartIdentity("이마트", "은평점")))
        val repository = repo(Places(true, status, RemoteResult.Available(listOf(real), at)), calendar = calendar)
        repository.refreshRemote(Itinerary(), real, at)
        assertTrue(repository.candidates(Visit(1)).isEmpty())
        val other = real.copy(id = "other", name = "코스트코 양평점")
        assertEquals(listOf(other), repository.candidates(Visit(1, place = other)))
    }
    @Test fun successfulNoChargerDoesNotResurrectDemoCharger() = runTest {
        val repository = repo(Places(true, status, failed), chargers = Chargers(status, RemoteResult.Available(null, at)))
        repository.refreshRemote(Itinerary(), real, at)
        assertNull(repository.charging(repository.candidates(Visit(1)).first(), at))
    }
}
