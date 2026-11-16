package kr.gakim.charge.data.remote

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kr.gakim.charge.domain.itinerary.*
import kotlin.math.ceil

interface KecoChargers {
    val enabled: Boolean
    val status: SourceStatus
    suspend fun nearby(place: Place): RemoteResult<ChargingSite?>
}
data class ChargerRecord(
    val stationId: String, val chargerId: String, val name: String, val address: String,
    val latitude: Double?, val longitude: Double?, val powerKw: Double?, val operator: String,
    val useTime: String, val parkingFree: String, val limited: String, val deleted: Boolean,
    val state: String, val updatedAt: LocalDateTime?, val chargerType: String = "",
) {
    val key get() = stationId to chargerId
    fun point() = Place(stationId, name, latitude ?: Double.NaN, longitude ?: Double.NaN, address)
}
data class KecoPage(val items: List<ChargerRecord>, val total: Int?)
object KecoParser {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
    fun parse(raw: String, decoder: JsonDecoder = AndroidJsonDecoder): KecoPage {
        val root = decoder.decode(raw)
        val envelope = root as? Map<*, *>
        val code = envelope?.text("resultCode").orEmpty()
        if (code.isNotBlank() && code != "00") throw SourceFailure("한국환경공단 응답 오류 ($code)")
        val items = if (root is List<*>) root else {
            val value = envelope?.get("items") ?: error("items 누락")
            if (value is List<*>) value else value.obj()["item"] ?: emptyList<Any>()
        }
        val rows = when (items) {
            is List<*> -> items
            is Map<*, *> -> listOf(items)
            "" -> emptyList<Any>()
            else -> error("items 형식 오류")
        }
        return KecoPage(rows.mapNotNull { item ->
            val row = item.obj()
            val station = row.text("statId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val charger = row.text("chgerId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChargerRecord(station, charger, row.text("statNm"), row.text("addr"), row.number("lat"), row.number("lng"),
                row.number("output")?.takeIf { it > 0 }, row.text("busiNm"), row.text("useTime"),
                row.text("parkingFree"), row.text("limitYn"), row.text("delYn") == "Y", row.text("stat"),
                runCatching { LocalDateTime.parse(row.text("statUpdDt"), timestamp) }.getOrNull(), row.text("chgerType"))
        }, envelope?.text("totalCount")?.toIntOrNull())
    }
    fun sameSite(place: Place, charger: ChargerRecord, meters: Double): Boolean {
        val store = place.name.replace(" ", "")
        return meters <= 80 || store.isNotBlank() &&
            (charger.name + charger.address).replace(" ", "").contains(store)
    }
    // statUpdDt 는 '마지막으로 상태가 바뀐 시각'이다(한가한 충전기는 전날 시각이 정상, 2026-11-16 실호출 확인).
    // 신선도는 조회 시각(10분)으로 보고, 상태 변경이 하루 넘게 없으면 보고가 끊긴 것으로 보아 미확인 처리한다.
    private fun current(record: ChargerRecord, now: LocalDateTime, fetchedAt: LocalDateTime) =
        Evidence("", fetchedAt).fresh(now, 600) && record.updatedAt?.let { Evidence("", it).fresh(now, 86_400) } == true
    private val changeLabel = DateTimeFormatter.ofPattern("M/d HH:mm")
    fun site(place: Place, records: List<ChargerRecord>, now: LocalDateTime, fetchedAt: LocalDateTime = now): ChargingSite? {
        val nearby = records.filter { !it.deleted && it.point().validCoordinates }
            .map { it to distanceMeters(place, it.point()) }.filter { it.second <= 300 }
        val selected = nearby.sortedWith(compareBy<Pair<ChargerRecord, Double>> {
            val record = it.first
            if (record.state == "2" && record.limited != "Y" && current(record, now, fetchedAt)) 0 else 1
        }.thenBy { it.second }).firstOrNull() ?: return null
        val (charger, meters) = selected
        val fresh = current(charger, now, fetchedAt)
        val availability = when {
            !fresh || charger.limited == "Y" -> Availability.UNKNOWN
            charger.state == "2" -> Availability.AVAILABLE
            charger.state == "3" -> Availability.CHARGING
            charger.state in listOf("1", "4", "5", "9") -> Availability.UNAVAILABLE
            else -> Availability.UNKNOWN
        }
        val same = sameSite(place, charger, meters)
        val statusText = when (charger.state) { "2" -> "사용 가능"; "3" -> "충전 중"; else -> "사용 불가/미확인" }
        // 현장 실측값이 없으므로 보수적 가정으로 시간을 낸다(화면에 '추정'으로 표시). 급속 명목 출력은 차량 수전 한계를 모르니 50 kW 로 자른다.
        val walk = if (same) 2 else 2 * ceil(meters / 67.0).toInt()
        val estimatedKw = charger.powerKw?.let { minOf(it, 50.0) }
        val note = listOf(
            charger.name,
            if (same) "같은 주차장" else "근처 충전소 · 도보 ${ceil(meters).toInt()} m",
            when {
                fresh -> statusText
                charger.updatedAt == null || charger.updatedAt > now -> "$statusText · 상태 시각 미확인"
                else -> "$statusText · 오래된 정보"
            },
            charger.powerKw?.let { "명목 출력 기준 ${it} kW" }.orEmpty(),
            charger.operator, charger.useTime,
            when (charger.parkingFree) { "Y" -> "주차 무료"; "N" -> "주차 유료"; else -> "주차 요금 미확인" },
            if (charger.limited == "Y") "이용 제한 있음" else "",
            "주차·연결 약 ${6 + walk}분${estimatedKw?.let { "과 충전 속도 ${it.toInt()} kW" }.orEmpty()}는 추정 · 대기 시간 미확인",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        return ChargingSite(availability, walkingMinutes = walk, overlapVerified = true,
            effectivePowerKw = estimatedKw?.let { Measured(it, Evidence("충전 속도 추정(명목 출력 기준, 상한 50 kW)", now)) },
            evidence = Evidence("한국환경공단 · 마지막 상태 변경 ${charger.updatedAt?.format(changeLabel) ?: "미확인"} · 조회", if (fresh || charger.updatedAt == null) fetchedAt else charger.updatedAt),
            note = note, accessTimesKnown = true)
    }
}

class WebKecoChargers(
    private val session: RemoteSession, private val serviceKey: String,
    private val decoder: JsonDecoder = AndroidJsonDecoder,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val regions: KakaoRegionCodes? = null,
) : KecoChargers {
    override val enabled get() = session.enabled
    private var detail: SourceStatus? = null
    override val status get() = detail ?: session.status
    override suspend fun nearby(place: Place): RemoteResult<ChargingSite?> {
        val district = regions?.district(place) ?: run {
            detail = SourceStatus(session.name, clock(), "지역 코드 미확인")
            return RemoteResult.Unavailable("지역 코드 미확인")
        }
        val result = session.load(district, 600) {
            // 구가 있는 일반시(성남·수원·고양 등)는 KECO 가 구 코드(41131)가 아니라 시 코드(41130)로만 응답한다(2026-11-16 실호출 확인).
            pages("zcode=${district.take(2)}&zscode=$district").ifEmpty {
                val city = district.take(4) + "0"
                if (city == district) emptyList() else pages("zcode=${district.take(2)}&zscode=$city")
            }
        }
        return when (result) {
            is RemoteResult.Available -> {
                val nearby = result.value.filter { !it.deleted && it.point().validCoordinates && distanceMeters(place, it.point()) <= 300 }
                val updated = nearby.mapNotNull { it.updatedAt }.maxOrNull()
                detail = SourceStatus(session.name, result.fetchedAt,
                    "충전기 ${nearby.size}기 · 최근 상태 변경 ${updated?.format(timeLabel) ?: "미확인"}")
                RemoteResult.Available(KecoParser.site(place, nearby, clock(), result.fetchedAt), result.fetchedAt)
            }
            is RemoteResult.Unavailable -> { detail = SourceStatus(session.name, clock(), result.reason); result }
        }
    }
    private suspend fun pages(region: String): List<ChargerRecord> {
        val records = mutableListOf<ChargerRecord>()
        var page = 1
        val key = if ('%' in serviceKey) serviceKey else encode(serviceKey)
        while (true) {
            val url = "https://apis.data.go.kr/B552584/EvCharger/getChargerInfo?serviceKey=$key&dataType=JSON&pageNo=$page&numOfRows=9999&$region"
            val parsed = KecoParser.parse(session.request(url, timeout = 20_000), decoder)
            records += parsed.items
            if (parsed.total != null && page.toLong() * 9999 >= parsed.total || parsed.total == null && parsed.items.size < 9999) break
            if (parsed.items.isEmpty()) throw SourceFailure("충전소 페이지 누락")
            page++
        }
        return records.distinctBy { it.key }
    }
}
