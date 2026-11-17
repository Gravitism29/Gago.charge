package kr.gakim.charge.data

import kotlinx.coroutines.flow.Flow
import kr.gakim.charge.domain.itinerary.Measured

/*
 * 차량·내비·음성 SDK 를 가리는 인터페이스. 이 파일과 fake/ 는 Android 에 의존하지 않는다.
 * 규칙: SDK 실패·null·권한 거부·시간 초과는 모두 '알 수 없음'으로 돌려주고 예외를 밖으로 던지지 않는다.
 */

// ---------- 차량 ----------

sealed interface BatteryReading {
    /** 0%도 유효한 값이다. */
    data class Known(val percent: Int) : BatteryReading

    /** 미확인. [reason] 은 화면·로그용 설명. */
    data class Unknown(val reason: String) : BatteryReading
}

enum class ParkState { PARKED, MOVING, UNKNOWN }

sealed interface VehicleEnergyUpdate {
    data class Soc(val reading: BatteryReading) : VehicleEnergyUpdate
    data class Range(val reading: Measured<Double>?) : VehicleEnergyUpdate
}

interface VehicleGateway {
    val energyUpdates: Flow<VehicleEnergyUpdate> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun readBatterySoc(): BatteryReading
    suspend fun readRangeKm(): Measured<Double>? = null
    suspend fun readEfficiencyKmPerKwh(): Measured<Double>? = null
    /** 배터리에 남은 에너지(kWh). 전비를 차량이 주지 않을 때 '주행 가능 거리 ÷ 잔여 에너지' 추정에 쓴다. */
    suspend fun readBatteryEnergyKwh(): Measured<Double>? = null

    /** 주차 상태. 읽을 수 없으면 [ParkState.UNKNOWN] 이고, 이때는 사용자에게 직접 확인받는다. */
    suspend fun readParkState(): ParkState

    fun release()
}

// ---------- 내비 ----------

data class NaviPlace(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String = "",
    val poiSubId: String = "",
)

/**
 * 이름·주소·POI ID가 모두 비어 있는 장소. Pleos 내비(서비스 3.0.4)는 목적지가 없을 때도
 * onDestinationInfo 를 보내며 이런 빈 값을 채운다(2026-11-14 에뮬레이터에서 확인).
 */
fun NaviPlace.isBlankPlace(): Boolean = name.isBlank() && address.isBlank() && poiId.isBlank()

/** 내비에 지금 설정된 경로. 목적지가 없으면 [destination] 이 null. */
data class NaviRoute(val destination: NaviPlace?, val waypoints: List<NaviPlace>) {
    companion object {
        /** SDK 응답을 경로로 바꾼다. 빈 목적지는 null, 빈 경유지는 버린다. */
        fun fromSdk(destination: NaviPlace, waypoints: List<NaviPlace>) = NaviRoute(
            destination = destination.takeUnless { it.isBlankPlace() },
            waypoints = waypoints.filterNot { it.isBlankPlace() },
        )
    }
}

sealed interface NaviRead {
    data class Known(val route: NaviRoute) : NaviRead
    data class Unknown(val reason: String) : NaviRead
}

sealed interface NaviRequest {
    /** 요청을 보냈다. 반영됐다는 뜻이 아니다. 반드시 [NaviGateway.readRoute] 로 다시 읽어 확인한다. */
    data object Sent : NaviRequest
    data class Failed(val reason: String) : NaviRequest
}

interface NaviGateway {
    suspend fun currentPosition(): kr.gakim.charge.domain.itinerary.Place? = null
    /** 목적지와 경유지를 읽는다. 요청 뒤 반영 확인에도 같은 함수를 쓴다. */
    suspend fun readRoute(): NaviRead

    /** 목적지는 그대로 두고 [place] 를 첫 번째 경유지로 추가해 달라고 요청한다. */
    suspend fun addFirstWaypoint(place: NaviPlace): NaviRequest

    /** 시연 준비 전용(디버그 화면에서만 호출): 내비 목적지를 [place] 로 설정해 달라고 요청한다. */
    suspend fun setDestinationForDemo(place: NaviPlace): NaviRequest = NaviRequest.Failed("지원하지 않음")

    /** Public NaviHelper requestRouteWithWaypoints. Does not imply application success. */
    suspend fun applyRoute(route: NaviRoute): NaviRequest = NaviRequest.Failed("일괄 경로 요청 미지원")
    suspend fun readSavedPlaces(): List<NaviPlace> = emptyList()
    suspend fun previewTrip(place: NaviPlace): TripPreview? = null
    suspend fun openPlaceSearch(keyword: String): NaviRequest = NaviRequest.Failed("내비 장소 검색 화면 미지원")

    fun release()
}

data class TripPreview(val distanceKm: Double, val minutes: Int, val arrivalSoc: Int?,
                       val observedAt: java.time.LocalDateTime, val source: String = "NaviHelper requestTrip")

// ---------- 음성 ----------

sealed interface SpeechEvent {
    data class Level(val value: Float) : SpeechEvent
    data object Listening : SpeechEvent
    data class Partial(val text: String) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val reason: String) : SpeechEvent
}

interface SpeechGateway {
    /** 한 번 듣고 끝나는 흐름. 수집을 취소하면 듣기를 멈추고 자원을 반납한다. */
    fun listen(): Flow<SpeechEvent>

    fun release()
}

interface SpeechOutputGateway {
    fun speak(text: String)
    fun stop()
    fun release()
}

object SilentSpeechOutputGateway : SpeechOutputGateway {
    override fun speak(text: String) = Unit
    override fun stop() = Unit
    override fun release() = Unit
}

/** 한 벌의 게이트웨이. [usingFakes] 는 화면 표시에 쓴다. */
class GatewaySet(
    val vehicle: VehicleGateway,
    val navi: NaviGateway,
    val speech: SpeechGateway,
    val usingFakes: Boolean,
    val speechOutput: SpeechOutputGateway = SilentSpeechOutputGateway,
) {
    fun release() {
        vehicle.release()
        navi.release()
        speech.release()
        speechOutput.release()
    }
}
