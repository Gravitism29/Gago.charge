package kr.gakim.charge.remote

import java.time.LocalDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kr.gakim.charge.data.remote.*
import kr.gakim.charge.domain.itinerary.*
import org.junit.Assert.*
import org.junit.Test

class RemoteSourcesTest {
    private val at = LocalDateTime.of(2026, 11, 16, 12, 5)
    private val origin = Place("origin", "현재 위치", 37.635, 126.918, "서울 은평구 통일로")
    private fun raw(name: String) = FixtureJson.fixture(name)
    private fun session(name: String = "source", key: String = "test", reply: HttpReply = HttpReply(200, raw("places.json"))) =
        RemoteSession(name, key, HttpTransport { _, _, _ -> reply }, { at })
    private fun regions() = KakaoRegionCodes(RemoteSession("region-test", "kakao", HttpTransport { url, headers, _ ->
        assertTrue("coord2regioncode.json?x=126.918&y=37.635" in url)
        assertEquals("KakaoAK kakao", headers["Authorization"])
        HttpReply(200, """{"documents":[{"region_type":"B","code":"1138010800"}]}""")
    }, { at }), FixtureJson)
    private fun charger() = KecoParser.parse(raw("keco-info.json"), FixtureJson).items.single()

    @Test fun martParserRejectsConvenienceStoresAndTenants() {
        val places = KakaoParsers.places(raw("places.json"), at, FixtureJson)
        assertEquals(listOf("이마트 은평점", "코스트코코리아 일산점", "트레이더스 홀세일 클럽 고양점", "홈플러스 킨텍스점"), places.map { it.name })
        assertTrue(places.all { !it.demo && it.validCoordinates && it.source == "카카오 장소 검색 · 12:05" })
    }
    @Test fun emptyPlaceSearchIsSuccessfulEmpty() = runTest {
        val source = WebKakaoPlaceSearch(session(reply = HttpReply(200, raw("places-empty.json"))), FixtureJson, clock = { at })
        assertEquals(emptyList<Place>(), (source.nearbyMarts(origin) as RemoteResult.Available).value)
    }
    @Test fun martSearchMergesDeduplicatesFiltersClosedAndKeepsNearestThree() = runTest {
        val urls = mutableListOf<String>()
        val transport = HttpTransport { url, headers, timeout ->
            urls += url
            assertEquals("KakaoAK test", headers["Authorization"])
            assertEquals(6000, timeout)
            HttpReply(200, raw("places.json"))
        }
        val source = WebKakaoPlaceSearch(RemoteSession("places", "test", transport, { at }), FixtureJson,
            closed = { it.name == "홈플러스 킨텍스점" }, clock = { at })
        val result = (source.nearbyMarts(origin) as RemoteResult.Available).value
        assertEquals(3, result.size)
        assertEquals("이마트 은평점", result.first().name)
        assertEquals(6, urls.size)
        assertTrue(urls.all { "radius=20000" in it && "size=15" in it && "category_group_code=MT1" in it })
        source.nearbyMarts(origin)
        assertEquals(6, urls.size)
    }
    @Test fun invalidLocationNeverRequests() = runTest {
        val source = WebKakaoPlaceSearch(RemoteSession("places", "test", HttpTransport { _, _, _ -> error("network called") }), FixtureJson)
        assertTrue(source.nearbyMarts(origin.copy(latitude = Double.NaN)) is RemoteResult.Unavailable)
    }
    @Test fun disabledSourceNeverRequests() = runTest {
        val source = WebKakaoPlaceSearch(RemoteSession("places", "", HttpTransport { _, _, _ -> error("network called") }), FixtureJson)
        assertFalse(source.enabled)
        assertTrue(source.nearbyMarts(origin) is RemoteResult.Unavailable)
    }
    @Test fun localProduct403HasActionableReason() = runTest {
        val source = WebKakaoPlaceSearch(session(reply = HttpReply(403, raw("http-error.json"))), FixtureJson)
        assertEquals("카카오맵(로컬) 사용 설정이 꺼져 있어요", (source.nearbyMarts(origin) as RemoteResult.Unavailable).reason)
        assertEquals("카카오맵(로컬) 사용 설정이 꺼져 있어요", source.status.status)
    }
    @Test fun malformedPlacesBecomeUnavailable() = runTest {
        val source = WebKakaoPlaceSearch(session(reply = HttpReply(200, raw("malformed.json"))), FixtureJson)
        assertTrue(source.nearbyMarts(origin) is RemoteResult.Unavailable)
    }
    @Test fun directionsUsesSectionsAndRoundsUp() {
        val result = KakaoParsers.directions(raw("directions.json"), at, FixtureJson)
        assertEquals(listOf(2, 2), result.map { it.minutes!!.value })
        assertEquals(listOf(1.234, 3.0), result.map { it.distanceKm!!.value })
        assertEquals("카카오모빌리티 길찾기", result.first().minutes!!.evidence.source)
    }
    @Test fun directionsErrorsNeverReturnFabricatedLegs() = runTest {
        for (reply in listOf(HttpReply(200, raw("directions-empty.json")), HttpReply(200, raw("malformed.json")), HttpReply(500, raw("http-error.json")))) {
            assertTrue(WebKakaoDirections(session(reply = reply), FixtureJson).route(origin, listOf(origin)) is RemoteResult.Unavailable)
        }
    }
    @Test fun directionsValidatesSectionCountAndWaypointEncoding() = runTest {
        var url = ""
        val source = WebKakaoDirections(RemoteSession("route", "test", HttpTransport { u, _, _ -> url = u; HttpReply(200, raw("directions.json")) }), FixtureJson)
        assertTrue(source.route(origin, listOf(origin, origin)) is RemoteResult.Available)
        assertTrue("waypoints=" in url)
        assertTrue(source.route(origin, listOf(origin)) is RemoteResult.Unavailable)
    }
    @Test fun kecoParsesOptionalStringsAndBareArray() {
        val record = charger()
        assertEquals(100.0, record.powerKw!!, 0.0)
        assertEquals("S1" to "01", record.key)
        val status = KecoParser.parse(raw("keco-status.json"), FixtureJson).items.single()
        assertEquals("3", status.state)
        assertNull(status.powerKw)
        assertEquals(1, KecoParser.parse("[{\"statId\":\"S1\",\"chgerId\":\"01\"}]", FixtureJson).items.size)
        assertTrue(KecoParser.parse(raw("keco-empty.json"), FixtureJson).items.isEmpty())
    }
    @Test fun nearbyRuleIsConservativeAtBoundary() {
        val record = charger().copy(name = "공영 주차장", address = "다른 주소")
        assertTrue(KecoParser.sameSite(origin, record, 80.0))
        assertFalse(KecoParser.sameSite(origin, record, 80.1))
        assertTrue(KecoParser.sameSite(origin.copy(name = "이마트 은평점"), charger(), 200.0))
    }
    @Test fun nearbyChargerTimesAreLabelledEstimatesCappedAt50Kw() {
        val record = charger().copy(latitude = origin.latitude + 0.001, name = "공영 주차장", address = "다른 주소")
        val site = KecoParser.site(origin, listOf(record), at)!!
        assertTrue(site.note.contains("근처 충전소 · 도보"))
        assertTrue(site.note.contains("명목 출력 기준 100.0 kW"))
        // 2026-11-16: 실데이터에서 충전 옵션 시각이 전부 '—:—' 가 되어, 추정임을 밝히고 보수적 가정으로 시간을 낸다.
        assertEquals(50.0, site.effectivePowerKw!!.value, 0.0)
        assertTrue("추정" in site.effectivePowerKw!!.evidence.source)
        assertTrue("는 추정 · 대기 시간 미확인" in site.note)
        assertNull(site.waitMinutes)
        assertTrue(site.walkingMinutes >= 4)
        assertTrue(site.accessTimesKnown)
    }
    @Test fun outsideRadiusAndDeletedChargersAreExcluded() {
        assertNull(KecoParser.site(origin, listOf(charger().copy(latitude = 38.0)), at))
        assertNull(KecoParser.site(origin, listOf(charger().copy(deleted = true)), at))
    }
    @Test fun statusAndStalenessMapping() {
        assertEquals(Availability.AVAILABLE, KecoParser.site(origin, listOf(charger()), at)?.availability)
        assertEquals(Availability.CHARGING, KecoParser.site(origin, listOf(charger().copy(state = "3")), at)?.availability)
        assertEquals(Availability.UNAVAILABLE, KecoParser.site(origin, listOf(charger().copy(state = "4")), at)?.availability)
        val old = KecoParser.site(origin, listOf(charger().copy(updatedAt = at.minusHours(25))), at)!!
        assertEquals(Availability.UNKNOWN, old.availability)
        assertTrue("오래된 정보" in old.note)
        // 전날 저녁부터 비어 있는 충전기는 '사용 가능'이다(상태 변경 시각 ≠ 조회 시각).
        assertEquals(Availability.AVAILABLE, KecoParser.site(origin, listOf(charger().copy(updatedAt = at.minusHours(14))), at)?.availability)
        assertEquals(Availability.UNKNOWN, KecoParser.site(origin, listOf(charger()), at, at.minusMinutes(11))?.availability)
        assertEquals(Availability.UNKNOWN, KecoParser.site(origin, listOf(charger().copy(updatedAt = null)), at)?.availability)
    }
    @Test fun kecoUsesDistrictInfoWithoutDoubleEncodingKey() = runTest {
        val urls = mutableListOf<String>()
        val transport = HttpTransport { url, _, timeout ->
            urls += url
            val info = "getChargerInfo" in url
            assertEquals(if (info) 20000 else 6000, timeout)
            HttpReply(200, raw(if (info) "keco-info.json" else "keco-status.json"))
        }
        val source = WebKecoChargers(RemoteSession("keco", "test%2Bkey", transport, { at }), "test%2Bkey", FixtureJson, { at }, regions())
        val result = (source.nearby(origin) as RemoteResult.Available).value!!
        assertTrue("사용 가능" in result.note)
        assertTrue(urls.all { "test%2Bkey" in it && "%252B" !in it })
        assertTrue(urls.first().contains("zscode=11380"))
        source.nearby(origin)
        assertEquals(1, urls.size)
    }
    @Test fun kecoEmptyMalformedHttpAndTimeoutResults() = runTest {
        for (reply in listOf(HttpReply(200, raw("malformed.json")), HttpReply(503, raw("http-error.json")))) {
            val source = WebKecoChargers(session(reply = reply), "test", FixtureJson, regions = regions())
            assertTrue(source.nearby(origin) is RemoteResult.Unavailable)
        }
        val empty = WebKecoChargers(session(reply = HttpReply(200, raw("keco-empty.json"))), "test", FixtureJson, regions = regions())
        assertNull((empty.nearby(origin) as RemoteResult.Available).value)
        val timeout = WebKecoChargers(RemoteSession("keco", "test", HttpTransport { _, _, _ -> throw java.net.SocketTimeoutException() }), "test", FixtureJson, regions = regions())
        assertEquals("응답 시간 초과", (timeout.nearby(origin) as RemoteResult.Unavailable).reason)
    }
    @Test fun sourceSerializesConcurrentCallsAndHonorsTtl() = runTest {
        var time = at
        var calls = 0
        var active = 0
        var maximum = 0
        val session = RemoteSession("test", "key", HttpTransport { _, _, _ -> error("unused") }, { time })
        suspend fun load() = session.load("key", 120) {
            calls++; active++; maximum = maxOf(maximum, active); delay(5); active--; "value"
        }
        val a = async { load() }; val b = async { load() }; a.await(); b.await()
        assertEquals(1, calls); assertEquals(1, maximum)
        time = at.plusSeconds(121); load(); assertEquals(2, calls)
    }
    @Test fun logRedactsKeyAndOnlyLogsFirstResponse() = runTest {
        val logs = mutableListOf<String>()
        val session = RemoteSession("logging-redaction", "secret+value", HttpTransport { _, _, _ -> HttpReply(200, "secret+value secret%2Bvalue") }, { at }, logs::add)
        repeat(2) { session.load("$it", 1) { session.request("https://example.test") } }
        assertEquals(listOf("[redacted] [redacted]"), logs)
    }
    @Test fun loggingIsBoundedAcrossEndpointsAndSessionInstances() = runTest {
        val logs = mutableListOf<String>()
        repeat(2) {
            val session = RemoteSession("bounded-log", "secret", HttpTransport { _, _, _ ->
                HttpReply(200, "secret" + "x".repeat(5000))
            }, { at }, logs::add)
            session.request("https://example.test", endpoint = "$it")
        }
        assertEquals(1, logs.size)
        assertEquals(1500, logs.single().length)
        assertFalse("secret" in logs.single())
    }
    @Test fun noKakaoKeySkipsChargersWithoutProvinceFallback() = runTest {
        val source = WebKecoChargers(RemoteSession("keco-missing-region", "test", HttpTransport { _, _, _ ->
            error("Must not request province")
        }), "test", FixtureJson)
        assertEquals("지역 코드 미확인", (source.nearby(origin) as RemoteResult.Unavailable).reason)
        assertEquals("지역 코드 미확인", source.status.status)
    }
    @Test fun districtComesFromCoordinatesEvenWhenAddressIsWrong() = runTest {
        val geo = KakaoRegionCodes(RemoteSession("seongnam-region", "kakao", HttpTransport { url, headers, _ ->
            assertTrue("x=127.0947&y=37.4127" in url)
            assertEquals("KakaoAK kakao", headers["Authorization"])
            HttpReply(200, """{"documents":[{"region_type":"H","code":"4113151000"},{"region_type":"B","code":"4113111500"}]}""")
        }, { at }), FixtureJson)
        var calls = 0
        val source = WebKecoChargers(RemoteSession("seongnam-keco", "test", HttpTransport { url, _, _ ->
            calls++
            assertTrue("getChargerInfo" in url)
            // 구 코드로 0건이면 시 코드(41130)로 한 번 더 묻는다.
            assertTrue("numOfRows=9999&zcode=41&zscode=${if (calls == 1) "41131" else "41130"}" in url)
            HttpReply(200, raw("keco-empty.json"))
        }, { at }), "test", FixtureJson, { at }, geo)
        val place = origin.copy(latitude = 37.4127, longitude = 127.0947)
        source.nearby(place); source.nearby(place.copy(id = "other", latitude = 37.41271))
        assertEquals(2, calls)
    }
    @Test fun assetFixturesLoadOfficialDatesAliasesAndPermanentClosures() {
        val calendar = MartClosingAssets.load({ raw(it) }, FixtureJson)
        val costco = calendar.notice("코스트코 양평점", at.toLocalDate().withDayOfMonth(22), at.toLocalDate())!!
        assertEquals("11월 22일(일) 휴무", costco.label)
        assertTrue(calendar.permanentlyClosed("홈플러스 킨텍스점"))
        assertNotNull(calendar.notice("코스트코코리아 양평점", java.time.LocalDate.of(2027, 1, 10), at.toLocalDate()))
    }
    @Test fun kecoPagesDistrictInfoAndCachesForTenMinutes() = runTest {
        var time = at
        var infoCalls = 0
        var statusCalls = 0
        val transport = HttpTransport { url, _, _ ->
            if ("getChargerInfo" in url) {
                infoCalls++
                val body = raw("keco-info.json").replace("\"totalCount\": \"1\"", "\"totalCount\": \"10000\"")
                HttpReply(200, if ("pageNo=2" in url) body.replace("\"01\"", "\"02\"") else body)
            } else { statusCalls++; HttpReply(200, raw("keco-status.json")) }
        }
        val source = WebKecoChargers(RemoteSession("keco", "raw+key", transport, { time }), "raw+key", FixtureJson, { time }, regions())
        assertTrue(source.nearby(origin) is RemoteResult.Available)
        assertEquals(2, infoCalls)
        time = time.plusSeconds(121)
        assertTrue(source.nearby(origin) is RemoteResult.Available)
        assertEquals(2, infoCalls)
        assertEquals(0, statusCalls)
        time = time.plusSeconds(480)
        assertTrue(source.nearby(origin) is RemoteResult.Available)
        assertEquals(4, infoCalls)
    }
    @Test fun placesAndRoutesExpireAtTheirConfiguredTtls() = runTest {
        var time = at
        var placeCalls = 0
        var routeCalls = 0
        val places = WebKakaoPlaceSearch(RemoteSession("places", "test", HttpTransport { _, _, _ -> placeCalls++; HttpReply(200, raw("places.json")) }, { time }), FixtureJson, clock = { time })
        val routes = WebKakaoDirections(RemoteSession("routes", "test", HttpTransport { _, _, _ -> routeCalls++; HttpReply(200, raw("directions.json")) }, { time }), FixtureJson, { time })
        places.nearbyMarts(origin); routes.route(origin, listOf(origin, origin))
        time = at.plusSeconds(301)
        places.nearbyMarts(origin); routes.route(origin, listOf(origin, origin))
        assertEquals(6, placeCalls); assertEquals(2, routeCalls)
        time = at.plusSeconds(601); places.nearbyMarts(origin)
        assertEquals(12, placeCalls)
    }
    @Test fun tradersDatasetBrandIsNormalized() {
        val json = """{"stores":[{"brand":"이마트 트레이더스","branch":"고양점","closedDates":["2026-11-25"]}]}"""
        val calendar = MartClosingAssets.load({ if(it == "mart_closing_days.json") json else null }, FixtureJson)
        assertNotNull(calendar.notice("트레이더스 홀세일 클럽 고양점", at.toLocalDate().withDayOfMonth(25), at.toLocalDate()))
    }
}
