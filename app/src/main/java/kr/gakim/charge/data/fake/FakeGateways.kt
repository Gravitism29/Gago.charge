package kr.gakim.charge.data.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.NaviGateway
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.NaviRequest
import kr.gakim.charge.data.NaviRoute
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.data.SpeechEvent
import kr.gakim.charge.data.SpeechGateway
import kr.gakim.charge.data.VehicleGateway
import kr.gakim.charge.data.SpeechOutputGateway
import kr.gakim.charge.domain.MAX_WAYPOINTS

/*
 * Pleos 에뮬레이터 없이 화면 흐름을 끝까지 돌려 보기 위한 가짜 구현.
 * 값은 모두 시연용이며 실제 차량·내비·마이크를 쓰지 않는다.
 */

class FakeVehicleGateway(
    /** null 이면 '읽지 못함'을 흉내 낸다. */
    var soc: Int? = 25,
    var parkState: ParkState = ParkState.PARKED,
) : VehicleGateway {
    override val energyUpdates = kotlinx.coroutines.flow.MutableSharedFlow<kr.gakim.charge.data.VehicleEnergyUpdate>(extraBufferCapacity = 64)
    var range: kr.gakim.charge.domain.itinerary.Measured<Double>? = null
    fun pushSoc(value: Int?) {
        soc = value
        energyUpdates.tryEmit(kr.gakim.charge.data.VehicleEnergyUpdate.Soc(value?.let { BatteryReading.Known(it) } ?: BatteryReading.Unknown("Unavailable")))
    }
    fun pushRange(value: kr.gakim.charge.domain.itinerary.Measured<Double>?) {
        range = value
        energyUpdates.tryEmit(kr.gakim.charge.data.VehicleEnergyUpdate.Range(value))
    }
    override suspend fun readRangeKm() = range
    override suspend fun readBatterySoc(): BatteryReading =
        soc?.let { BatteryReading.Known(it) } ?: BatteryReading.Unknown("시연: 배터리 값을 읽지 못함")

    override suspend fun readParkState(): ParkState = parkState

    override fun release() = Unit
}

class FakeNaviGateway(
    destination: NaviPlace?,
    waypoints: List<NaviPlace> = emptyList(),
) : NaviGateway {
    private var route = NaviRoute(destination, waypoints)

    /** true 면 조회가 실패한다. */
    var unavailable = false

    /** true 면 요청은 받지만 경로에 반영하지 않는다(요청 성공 ≠ 반영 완료). */
    var ignoreRequests = false

    /** 지금까지 받은 경유지 추가 요청 수. */
    var requestCount = 0
        private set

    override suspend fun readRoute(): NaviRead =
        if (unavailable) NaviRead.Unknown("시연: 내비 응답 없음") else NaviRead.Known(route)

    override suspend fun addFirstWaypoint(place: NaviPlace): NaviRequest {
        if (unavailable) return NaviRequest.Failed("시연: 내비 응답 없음")
        requestCount++
        if (!ignoreRequests && route.destination != null && route.waypoints.size < MAX_WAYPOINTS) {
            route = route.copy(waypoints = listOf(place) + route.waypoints)
        }
        return NaviRequest.Sent
    }

    override suspend fun applyRoute(route: NaviRoute): NaviRequest {
        if (unavailable) return NaviRequest.Failed("시연: 내비 응답 없음")
        if (route.destination == null || route.waypoints.size > MAX_WAYPOINTS) return NaviRequest.Failed("목적지 또는 경유지 한도 확인")
        requestCount++
        if (!ignoreRequests) this.route = route
        return NaviRequest.Sent
    }

    /** 사용자가 내비에서 직접 경로를 바꾼 상황을 흉내 낸다. */
    fun setRoute(newRoute: NaviRoute) {
        route = newRoute
    }

    override fun release() = Unit
}

class FakeSpeechGateway(
    private val script: String = "장 보고 4시까지 애 데리러 가야 해",
    private val stepDelayMs: Long = 500,
) : SpeechGateway {
    override fun listen(): Flow<SpeechEvent> = flow {
        emit(SpeechEvent.Listening)
        delay(stepDelayMs)
        emit(SpeechEvent.Partial(script.take(script.length / 2)))
        delay(stepDelayMs)
        emit(SpeechEvent.Final(script))
    }

    override fun release() = Unit
}

class FakeSpeechOutputGateway : SpeechOutputGateway {
    val spoken = mutableListOf<String>()
    var stopCount = 0
        private set
    var released = false
        private set
    override fun speak(text: String) { if (!released) spoken += text }
    override fun stop() { stopCount++ }
    override fun release() { stop(); released = true }
}
