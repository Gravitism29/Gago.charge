package kr.gakim.charge.data.remote

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kr.gakim.charge.domain.itinerary.*
import kotlin.math.*

interface KakaoPlaceSearch {
    val enabled: Boolean
    val status: SourceStatus
    suspend fun nearbyMarts(position: Place): RemoteResult<List<Place>>
    /** 예시 문장에 쓸 가장 가까운 지하철역 이름(예: "판교역"). 모르면 null. */
    suspend fun nearestStationName(position: Place): String? = null
    /** 장소를 말하지 않은 볼일의 주변 후보. 장보기는 대형마트, 식사·병원은 카카오 카테고리 검색. */
    suspend fun nearbyFor(activity: Activity, position: Place): RemoteResult<List<Place>> =
        if (activity == Activity.SHOP) nearbyMarts(position) else RemoteResult.Unavailable("주변 검색 미지원")
    suspend fun namedPlaces(text: String, activity: Activity, position: Place): RemoteResult<List<Place>> =
        RemoteResult.Unavailable("장소 검색 미지원")
}
interface KakaoDirections {
    val enabled: Boolean
    val status: SourceStatus
    suspend fun route(origin: Place, stops: List<Place>): RemoteResult<List<Leg>>
}
/** 주변 후보를 찾을 수 있는 볼일과 카카오 카테고리 그룹 코드(FD6 음식점, HP8 병원). */
val nearbyCategories = mapOf(Activity.MEAL to "FD6", Activity.HOSPITAL to "HP8")
fun nearbySearchable(activity: Activity) = activity == Activity.SHOP || activity in nearbyCategories
internal val timeLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
fun distanceMeters(a: Place, b: Place): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}
object KakaoParsers {
    private val excluded = listOf("이마트24", "익스프레스", "에브리데이", "노브랜드", "슈퍼", "마켓", "편의점")
    fun isMart(name: String, category: String): Boolean = category == "MT1" &&
        listOf("이마트", "코스트코", "홈플러스", "롯데마트", "트레이더스", "하나로마트").any { name.startsWith(it) } &&
        excluded.none { name.contains(it) }

    fun places(raw: String, at: LocalDateTime, decoder: JsonDecoder = AndroidJsonDecoder, martsOnly: Boolean = true): List<Place> =
        decoder.decode(raw).obj()["documents"].array().mapNotNull { item ->
            val row = item.obj()
            val name = row.text("place_name")
            if (name.isBlank() || martsOnly && !isMart(name, row.text("category_group_code"))) return@mapNotNull null
            val id = row.text("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val lat = row.number("y") ?: return@mapNotNull null
            val lon = row.number("x") ?: return@mapNotNull null
            Place("kakao:$id", name, lat, lon, row.text("road_address_name").ifBlank { row.text("address_name") },
                source = "카카오 장소 검색 · ${at.format(timeLabel)}",
                searchDistanceMeters = row.number("distance")?.takeIf { it >= 0 }).takeIf { it.validCoordinates }
        }

    fun directions(raw: String, at: LocalDateTime, decoder: JsonDecoder = AndroidJsonDecoder): List<Leg> {
        val route = decoder.decode(raw).obj()["routes"].array().firstOrNull()?.obj() ?: throw SourceFailure("경로 없음")
        if (route.text("result_code") != "0") throw SourceFailure("길찾기 결과 없음")
        val evidence = Evidence("카카오모빌리티 길찾기", at)
        return route["sections"].array().map { item ->
            val row = item.obj()
            val meters = row.number("distance")?.takeIf { it >= 0 } ?: error("거리 누락")
            val seconds = row.number("duration")?.takeIf { it >= 0 && it <= Int.MAX_VALUE.toDouble() } ?: error("시간 누락")
            Leg(Measured(ceil(seconds / 60).toInt(), evidence), Measured(meters / 1000, evidence))
        }.ifEmpty { throw SourceFailure("경로 없음") }
    }
}
class WebKakaoPlaceSearch(
    private val session: RemoteSession, private val decoder: JsonDecoder = AndroidJsonDecoder,
    private val closed: (Place) -> Boolean = { false },
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : KakaoPlaceSearch {
    override val enabled get() = session.enabled
    override val status get() = session.status
    override suspend fun namedPlaces(text: String, activity: Activity, position: Place): RemoteResult<List<Place>> {
        if (!position.validCoordinates) return RemoteResult.Unavailable("현재 위치 미확인")
        val branch = activity == Activity.SHOP && isBareMartBranch(text)
        val mart = activity == Activity.SHOP && (branch || martPrefixes.any { text.replace(" ", "").startsWith(it) })
        val queries = if (branch) martPrefixes.map { "$it $text" } else listOf(text)
        val rounded = "${round(position.latitude * 1000) / 1000},${round(position.longitude * 1000) / 1000}"
        return session.load("named:$activity:$text:$rounded", 600) {
            suspend fun search(local: Boolean): List<Place> {
                val results = mutableListOf<Place>()
                for (query in queries) {
                    val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=${encode(query)}&x=${position.longitude}&y=${position.latitude}" +
                        (if (branch) "&category_group_code=MT1" else "") +
                        (if (local) "&radius=20000&sort=${if (branch) "distance" else "accuracy"}" else "&sort=accuracy") + "&size=5"
                    results += KakaoParsers.places(session.request(url, kakao = true), clock(), decoder, martsOnly = mart)
                }
                return results.distinctBy { it.id }.filterNot(closed)
                    .filter { !branch || exactMartBranch(it.name, text) }
                    .map { it.copy(searchDistanceMeters = distanceMeters(position, it)) }
            }
            search(true).ifEmpty { search(false) }
        }
    }
    override suspend fun nearestStationName(position: Place): String? {
        if (!position.validCoordinates) return null
        val result = session.load("station:${round(position.latitude * 100) / 100},${round(position.longitude * 100) / 100}", 3600) {
            val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=SW8&x=${position.longitude}&y=${position.latitude}&radius=10000&sort=distance&size=1"
            KakaoParsers.places(session.request(url, kakao = true), clock(), decoder, martsOnly = false)
        }
        return (result as? RemoteResult.Available)?.value?.firstOrNull()?.name?.trim()?.substringBefore(' ')?.takeIf { it.endsWith("역") }
    }
    override suspend fun nearbyFor(activity: Activity, position: Place): RemoteResult<List<Place>> {
        val category = nearbyCategories[activity] ?: return super.nearbyFor(activity, position)
        if (!position.validCoordinates) return RemoteResult.Unavailable("현재 위치 미확인")
        return session.load("near:$category:${position.latitude},${position.longitude}", 600) {
            val url = "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=$category&x=${position.longitude}&y=${position.latitude}&radius=5000&sort=distance&size=15"
            KakaoParsers.places(session.request(url, kakao = true), clock(), decoder, martsOnly = false)
                .distinctBy { it.id }.sortedBy { distanceMeters(position, it) }.take(12)
        }
    }
    override suspend fun nearbyMarts(position: Place): RemoteResult<List<Place>> {
        if (!position.validCoordinates) return RemoteResult.Unavailable("현재 위치 미확인")
        return session.load("${position.latitude},${position.longitude}", 600) {
            val places = mutableListOf<Place>()
            for (brand in listOf("이마트", "코스트코", "홈플러스", "롯데마트", "트레이더스", "하나로마트")) {
                val url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=${encode(brand)}&x=${position.longitude}&y=${position.latitude}&category_group_code=MT1&radius=20000&sort=distance&size=15"
                places += KakaoParsers.places(session.request(url, kakao = true), clock(), decoder)
            }
            places.distinctBy { it.id }.filterNot(closed).filter { distanceMeters(position, it) <= 20_000 }
                .sortedBy { distanceMeters(position, it) }.take(3)
        }
    }
}
class WebKakaoDirections(
    private val session: RemoteSession, private val decoder: JsonDecoder = AndroidJsonDecoder,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) : KakaoDirections {
    override val enabled get() = session.enabled
    override val status get() = session.status
    override suspend fun route(origin: Place, stops: List<Place>): RemoteResult<List<Leg>> {
        if (!origin.validCoordinates || stops.isEmpty() || stops.any { !it.validCoordinates }) return RemoteResult.Unavailable("경로 좌표 미확인")
        fun point(p: Place) = "${p.longitude},${p.latitude}"
        val key = (listOf(origin) + stops).joinToString("|") { point(it) }
        return session.load(key, 300) {
            val url = "https://apis-navi.kakaomobility.com/v1/directions?origin=${point(origin)}&destination=${point(stops.last())}" +
                if (stops.size > 1) "&waypoints=${encode(stops.dropLast(1).joinToString("|") { point(it) })}" else ""
            KakaoParsers.directions(session.request(url, kakao = true), clock(), decoder).also {
                if (it.size != stops.size) throw SourceFailure("경로 구간 수 불일치")
            }
        }
    }
}

class KakaoRegionCodes(private val session: RemoteSession, private val decoder: JsonDecoder = AndroidJsonDecoder) {
    suspend fun district(place: Place): String? {
        if (!session.enabled || !place.validCoordinates) return null
        val result = session.load("${place.longitude},${place.latitude}", 600) {
            val raw = session.request("https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=${place.longitude}&y=${place.latitude}", kakao = true)
            decoder.decode(raw).obj()["documents"].array().map { it.obj() }
                .sortedBy { if (it.text("region_type") == "B") 0 else 1 }
                .firstOrNull { it.text("region_type") in listOf("B", "H") && it.text("code").matches(Regex("[0-9]{10}")) }
                ?.text("code")?.take(5) ?: throw SourceFailure("지역 코드 미확인")
        }
        return (result as? RemoteResult.Available)?.value
    }
}
