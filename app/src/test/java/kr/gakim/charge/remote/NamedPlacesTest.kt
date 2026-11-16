package kr.gakim.charge.remote

import java.net.URLDecoder
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
class NamedPlacesTest {
    private val at = LocalDateTime.of(2026, 11, 16, 12, 0)
    private val origin = Place("origin", "현재 위치", 37.4127, 127.0947)
    private fun raw(name: String) = FixtureJson.fixture(name)
    private fun source(reply: suspend (String) -> HttpReply) = WebKakaoPlaceSearch(
        RemoteSession("named-test", "fixture-key", HttpTransport { url, headers, _ ->
            assertEquals("KakaoAK fixture-key", headers["Authorization"])
            reply(url)
        }, { at }), FixtureJson, clock = { at })
    private fun repo(search: KakaoPlaceSearch, calendar: MartClosingCalendar = MartClosingCalendar()) = CompositeItineraryRepository(search,
        object : KakaoDirections {
            override val enabled = true
            override val status = SourceStatus("route", at, "성공")
            override suspend fun route(origin: Place, stops: List<Place>) = RemoteResult.Available(stops.map {
                Leg(Measured(5, Evidence("real route", at)), Measured(2.0, Evidence("real route", at)))
            }, at)
        }, object : KecoChargers {
            override val enabled = false
            override val status = SourceStatus("charger", at, "비활성")
            override suspend fun nearby(place: Place) = RemoteResult.Unavailable("비활성")
        }, calendar)
    private fun controller(repository: CompositeItineraryRepository, saved: List<NaviPlace> = emptyList()): ItineraryController {
        val navi = object : NaviGateway by FakeNaviGateway(null) {
            override suspend fun currentPosition() = origin
            override suspend fun readSavedPlaces() = saved
        }
        return ItineraryController(GatewaySet(FakeVehicleGateway(), navi, FakeSpeechGateway(), false), repository, { at })
    }
    @Test fun singleKeywordResultBindsRealDestinationAndKeepsLivePill() = runTest {
        val c = controller(repo(source { HttpReply(200, raw("named-single.json")) }))
        c.acceptTranscript("오후 1시 20분까지 판교역 가야 해")
        assertNull(c.state.value.plan.visits.single().place)
        assertTrue(c.state.value.options.isEmpty())
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals("판교역", c.state.value.plan.visits.single().place?.name)
        assertFalse(c.state.value.hasDemoData)
        assertTrue(c.state.value.options.isNotEmpty())
        // UX pass intentionally uses the shared 오전/오후 h:mm format, including resolved arrivals.
        assertEquals("오후 1:20까지", c.state.value.plan.visits.single().timeDetail(at))
    }
    @Test fun branchQueriesFilterTenantsAndOtherBranchesAndAskWithRealChoices() = runTest {
        val urls = mutableListOf<String>()
        val c = controller(repo(source { url -> if ("SW8" !in url) urls += URLDecoder.decode(url, "UTF-8"); HttpReply(200, raw("named-multiple.json")) }))
        c.acceptTranscript("은평점에서 25분 장 보고")
        withContext(Dispatchers.Default) { c.previewRoutes() }
        val s = c.state.value
        assertNull(s.plan.visits.single().place)
        assertEquals(listOf("이마트 은평점", "롯데마트 은평점"), s.question?.places?.map { it.name })
        assertTrue(s.question!!.places.all { !it.demo })
        assertTrue(s.options.isEmpty())
        assertEquals(5, urls.size)
        for (brand in listOf("이마트", "홈플러스", "롯데마트", "코스트코", "트레이더스"))
            assertTrue(urls.any { "query=$brand 은평점&" in it && "category_group_code=MT1" in it && "radius=20000" in it && "size=5" in it })
        assertTrue(placeChoiceLabel(s.question!!.places.first()).contains("km"))
        c.setPlace(1, s.question!!.places.first()); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertNull(c.state.value.question)
        assertFalse(c.state.value.hasDemoData)
    }
    @Test fun genericWordsStayUnboundAndNeverSearch() = runTest {
        for (word in listOf("학교", "병원", "집", "회사", "마트")) {
            val c = controller(repo(source { error("generic word requested") }))
            c.acceptTranscript("오후 1시 20분까지 $word 가야 해"); withContext(Dispatchers.Default) { c.previewRoutes() }
            val v = c.state.value.plan.visits.single()
            assertNull(v.place)
            assertEquals("$word · 장소 미정", v.placeLabel())
            assertTrue(c.allPlaces(v).none { it.demo })
        }
    }
    @Test fun savedHomeIsPreferredWithoutKeywordLookup() = runTest {
        val home = NaviPlace("집", "성남시", 37.4, 127.1)
        val c = controller(repo(source { error("saved home requested") }), listOf(home))
        c.refresh(); c.acceptTranscript("오후 1시 20분까지 집 가야 해"); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals("집", c.state.value.plan.visits.single().place?.name)
        assertFalse(c.state.value.plan.visits.single().place!!.demo)
    }
    @Test fun emptySearchRetriesNationwideOnceThenExplainsMissingPlace() = runTest {
        val urls = mutableListOf<String>()
        val c = controller(repo(source { if ("SW8" !in it) urls += it; HttpReply(200, raw("places-empty.json")) }))
        c.acceptTranscript("오후 1시 20분까지 없는역 가야 해"); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals(2, urls.size)
        assertTrue("radius=20000" in urls[0] && "sort=accuracy" in urls[0])
        assertFalse("radius=" in urls[1]); assertTrue("sort=accuracy" in urls[1])
        assertNull(c.state.value.plan.visits.single().place)
        assertEquals("'없는역'을 찾지 못했어요. 화면에서 장소를 골라 주세요", c.state.value.assistantLine())
        assertTrue(c.state.value.options.isEmpty())
    }
    @Test fun nationwideRetryCanBindAndRoundedPositionCacheLastsTenMinutes() = runTest {
        var calls = 0
        val search = source { url -> calls++; HttpReply(200, raw(if ("radius=" in url) "places-empty.json" else "named-single.json")) }
        val result = search.namedPlaces("판교역", Activity.ARRIVE, origin) as RemoteResult.Available
        assertEquals("판교역", resolveNamedPlace("판교역", result.value).place?.name)
        search.namedPlaces("판교역", Activity.ARRIVE, origin.copy(latitude = 37.41271))
        assertEquals(2, calls)
    }
    @Test fun confidentNameIgnoresSpacesAndAmbiguityIsLimitedToFour() {
        val station = KakaoParsers.places(raw("named-single.json"), at, FixtureJson, martsOnly = false).single()
        assertEquals(station, resolveNamedPlace("판교 역", listOf(station, station.copy(id = "other", name = "다른역"))).place)
        assertEquals(4, resolveNamedPlace("역", (1..6).map { station.copy(id = "$it", name = "역$it") }).choices.size)
    }
    @Test fun namedCacheExpiresAfterTenMinutesAndSeparatesQueriesAndPositions() = runTest {
        var time = at
        var calls = 0
        val search = WebKakaoPlaceSearch(RemoteSession("cache", "fixture-key", HttpTransport { _, _, _ ->
            calls++; HttpReply(200, raw("named-single.json"))
        }, { time }), FixtureJson, clock = { time })
        search.namedPlaces("판교역", Activity.ARRIVE, origin)
        time = at.plusMinutes(9)
        search.namedPlaces("판교역", Activity.ARRIVE, origin.copy(latitude = 37.41271))
        assertEquals(1, calls)
        time = at.plusMinutes(10).plusSeconds(1)
        search.namedPlaces("판교역", Activity.ARRIVE, origin)
        assertEquals(2, calls)
        search.namedPlaces("다른역", Activity.ARRIVE, origin)
        search.namedPlaces("판교역", Activity.ARRIVE, origin.copy(latitude = 37.5))
        assertEquals(4, calls)
    }
    @Test fun originalSentenceUsesRealBranchAndLeavesGenericSchoolUnbound() = runTest {
        val c = controller(repo(source { HttpReply(200, raw("named-multiple.json")) }))
        c.acceptTranscript("은평점에서 25분 장 보고 오후 1시 20분까지 학교 가야 해")
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertTrue(c.state.value.plan.visits.all { it.place == null })
        assertEquals(2, c.state.value.question?.places?.size)
        assertEquals(Activity.ARRIVE, c.state.value.plan.visits.last().activity)
        assertFalse(c.state.value.hasDemoData)
    }
    @Test fun allNamedVisitsResolveBeforeRealLegsAreCalculated() = runTest {
        val c = controller(repo(source { url -> HttpReply(200,
            raw(if ("query=" + java.net.URLEncoder.encode("판교역", "UTF-8") in url) "named-single.json" else "named-multiple.json")) }))
        c.acceptTranscript("이마트 은평점에서 25분 장 보고 오후 1시 20분까지 판교역 가야 해")
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals(listOf("이마트 은평점", "판교역"), c.state.value.plan.visits.map { it.place?.name })
        assertNull(c.state.value.question)
        val ordinary = c.state.value.options.filter { it.mode == ChargeMode.NONE }
        assertTrue(ordinary.isNotEmpty())
        assertTrue(ordinary.all { it.totalMinutes != null })
        assertFalse(c.state.value.hasDemoData)
    }
    @Test fun disabledSourceUsesDemoSpeechCatalog() = runTest {
        val search = WebKakaoPlaceSearch(RemoteSession("disabled", "", HttpTransport { _, _, _ -> error("disabled requested") }), FixtureJson)
        val c = controller(repo(search))
        c.acceptTranscript("은평점에서 25분 장 보고 오후 1시 20분까지 학교 가야 해"); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals(listOf("은평점", "학교"), c.state.value.plan.visits.map { it.place?.name })
        assertTrue(c.state.value.hasDemoData)
    }
    @Test fun failedNamedSourceFallsBackToExplicitDemoBranch() = runTest {
        val c = controller(repo(source { HttpReply(503, "{}") }))
        c.acceptTranscript("은평점에서 25분 장 보고"); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals("은평점", c.state.value.plan.visits.single().place?.name)
        assertTrue(c.state.value.plan.visits.single().place!!.demo)
    }
    @Test fun retryReplacesFailedDemoBindingWithRealPlace() = runTest {
        var online = false
        val c = controller(repo(source { if (online) HttpReply(200, raw("named-multiple.json")) else HttpReply(503, "{}") }))
        c.acceptTranscript("은평점에서 25분 장 보고 오후 1시 20분까지 학교 가야 해")
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertTrue(c.state.value.plan.visits.all { it.place?.demo == true })
        online = true
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertTrue(c.state.value.plan.visits.all { it.place == null })
        assertEquals(2, c.state.value.question?.places?.size)
        assertFalse(c.state.value.hasDemoData)
    }
    @Test fun resolvingPlacesPreservesChargeEditsMadeWhileWaiting() = runTest {
        val release = CompletableDeferred<Unit>()
        val station = origin.copy(id = "station", name = "판교역")
        val search = object : KakaoPlaceSearch {
            override val enabled = true
            override val status = SourceStatus("place", at, "조회 중")
            override suspend fun nearbyMarts(position: Place) = RemoteResult.Available(emptyList<Place>(), at)
            override suspend fun namedPlaces(text: String, activity: Activity, position: Place): RemoteResult<List<Place>> {
                release.await()
                return RemoteResult.Available(listOf(station), at)
            }
        }
        val c = controller(repo(search))
        c.acceptTranscript("오후 1시 20분까지 판교역 가야 해")
        val job = launch { c.previewRoutes() }; runCurrent()
        c.setCharge(ChargeMode.NONE); c.setReserve(35.0)
        release.complete(Unit); job.join()
        assertEquals(station, c.state.value.plan.visits.single().place)
        assertEquals(ChargeMode.NONE, c.state.value.plan.chargeMode)
        assertEquals(35.0, c.state.value.plan.reserveKm, 0.0)
        assertFalse(c.state.value.acknowledged)
    }
    @Test fun loadingNeverDisplaysDemoAndStaleSearchCannotBind() = runTest {
        val release = CompletableDeferred<Unit>()
        val c = controller(repo(source { release.await(); HttpReply(200, raw("named-single.json")) }))
        c.acceptTranscript("오후 1시 20분까지 판교역 가야 해")
        val job = launch { c.previewRoutes() }; runCurrent()
        assertTrue(c.state.value.searchingPlaces)
        assertEquals("주변 장소를 찾는 중이에요…", c.state.value.assistantLine())
        assertFalse(c.state.value.hasDemoData)
        c.setActivity(1, Activity.MEAL)
        release.complete(Unit); job.join()
        assertNull(c.state.value.plan.visits.single().place)
    }
    @Test fun permanentlyClosedMartsAreNeverOffered() = runTest {
        val calendar = MartClosingCalendar(permanentlyClosed = setOf(MartIdentity("이마트", "은평점")))
        val c = controller(repo(source { HttpReply(200, raw("named-multiple.json")) }, calendar))
        c.acceptTranscript("은평점에서 25분 장 보고"); withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals("롯데마트 은평점", c.state.value.plan.visits.single().place?.name)
    }
    @Test fun remoteExamplesUseRealMartOrGenericCompany() {
        assertFalse(plannerExamples(at, true).any { "은평점" in it || "수령처" in it })
        assertEquals("25분 장 보고 갈게", plannerExamples(at, true).first())
        val mart = origin.copy(name = "이마트 성남점")
        assertEquals("25분 장 보고 갈게", plannerExamples(at, true, listOf(mart)).first())
        assertTrue(plannerExamples(at, true, listOf(mart), "모란역").first().let { it.startsWith("이마트 성남점에서") && it.endsWith("모란역 가야 해") })
        assertTrue(plannerExamples(at, false).first().startsWith("은평점에서"))
    }
    @Test fun stationLinesAtTheSameSpotBindWithoutAskingAndShopsNamedAfterItAreIgnored() {
        fun place(id: String, name: String, lat: Double) = Place(id, name, lat, 127.1112)
        val results = listOf(place("shop", "반포식스 판교역점", 37.3990), place("s1", "판교역 신분당선", 37.3948), place("s2", "판교역 경강선", 37.3951))
        assertEquals("s1", resolveNamedPlace("판교역", results).place?.id)
        val far = results + place("s3", "판교역 환승주차장", 37.4200)
        assertEquals(listOf("s1", "s2", "s3"), resolveNamedPlace("판교역", far).choices.map { it.id })
    }
    @Test fun deadlineStaysOnDestinationAfterRealPlacesResolve() = runTest {
        val c = controller(repo(source { HttpReply(200, raw("named-single.json")) }))
        c.refresh()
        c.acceptTranscript("롯데마트 판교점에서 25분 장 보고 오후 2시까지 판교역 가야 해")
        assertEquals(listOf(null, at.withHour(14).withMinute(0)), c.state.value.plan.visits.map { it.deadline })
        withContext(Dispatchers.Default) { c.previewRoutes() }
        assertEquals(listOf(null, at.withHour(14).withMinute(0)), c.state.value.plan.visits.map { it.deadline })
    }
}
