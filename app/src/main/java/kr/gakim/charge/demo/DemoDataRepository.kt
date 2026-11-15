package kr.gakim.charge.demo

import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.domain.ChargerUnit
import kr.gakim.charge.domain.MartCandidate

/** 마트 후보 한 곳: 판단 규칙용 값 + 내비에 넘길 장소. */
data class MartInfo(
    val candidate: MartCandidate,
    val place: NaviPlace,
    val toMartMinutes: Int? = null,
    val martToDestinationMinutes: Int? = null,
)

/**
 * 후보 마트·소요 시간·충전기 상태를 주는 곳.
 * 지금은 [DemoDataRepository](시연 데이터)만 있다. 나중에 지도·충전소 API 구현으로 바꿔 끼운다.
 */
interface TripDataRepository {
    /** 화면에 그대로 보여 주는 출처 표시. 시연 데이터면 반드시 그 사실을 드러낸다. */
    val sourceLabel: String
    val isDemo: Boolean

    /** 출발 기준 시각(자정부터 분). */
    fun nowMinutes(): Int

    /** [destination] 으로 가는 길에 들를 수 있는 마트 후보. */
    fun martCandidates(destination: NaviPlace?): List<MartInfo>

    /** 마트를 거쳐 목적지에 도착할 때까지 줄어드는 배터리(%p). 모르면 null. */
    fun estimatedConsumptionPercent(): Int?

    /** 자동 충전 제안 기준(도착 잔량이 이 값 이하이면 충전을 비교). */
    fun chargeThresholdPercent(): Int

    /** 약속 시각 앞에 남겨 둘 여유(분). */
    fun marginMinutes(): Int
}

/**
 * ===== 시연 데이터 =====
 * 아래 값은 전부 가상의 값이다. 실제 마트·학교·충전소·교통 상황과 관계가 없다.
 * 외부 웹 API(지도, 충전소 상태)는 호출하지 않는다.
 *
 * 15:00 출발, 장보기 20분 기준으로 지시서의 시연 예시가 나온다.
 *  - A마트 장보기만:      15:00 + 주행 30 + 접근 5 + 20            = 3시 55분
 *  - A마트 충전까지:      15:00 + 주행 30 + 접근 5 + 대기 10 + 25  = 4시 10분
 *  - B마트 대기 없이 충전: 15:00 + 주행 27 + 접근 5 + 대기 0 + 20   = 3시 52분
 */
class DemoDataRepository : TripDataRepository {

    override val sourceLabel: String = "시연 데이터"
    override val isDemo: Boolean = true

    /** 시연 기준 시각 15:00 고정(실제 시계를 쓰지 않는다). */
    override fun nowMinutes(): Int = 15 * 60

    override fun martCandidates(destination: NaviPlace?): List<MartInfo> = listOf(MART_A, MART_B)

    override fun estimatedConsumptionPercent(): Int = 8

    override fun chargeThresholdPercent(): Int = 20

    override fun marginMinutes(): Int = 5

    companion object {
        /** Fake 내비에 미리 넣어 두는 목적지(시연). */
        val SCHOOL = NaviPlace(
            name = "학교 정문 (시연)",
            address = "시연용 주소",
            latitude = 37.5000,
            longitude = 127.0300,
        )

        private val usableUnit = ChargerUnit(compatible = true, available = true, current = true)
        private val busyUnit = ChargerUnit(compatible = true, available = false, current = true)

        val MART_A = MartInfo(
            candidate = MartCandidate(
                id = "demo-mart-a",
                name = "A마트 (시연)",
                driveMinutes = 30,
                accessMinutes = 5,
                hasChargingFacility = true,
                units = listOf(usableUnit, busyUnit),
                waitMinutes = 10,
                chargeMinutes = 25,
            ),
            place = NaviPlace("A마트 (시연)", "시연용 주소 A", 37.5080, 127.0180),
            toMartMinutes = 12, martToDestinationMinutes = 18,
        )

        val MART_B = MartInfo(
            candidate = MartCandidate(
                id = "demo-mart-b",
                name = "B마트 (시연)",
                driveMinutes = 27,
                accessMinutes = 5,
                hasChargingFacility = true,
                units = listOf(usableUnit, usableUnit),
                waitMinutes = 0,
                chargeMinutes = 20,
            ),
            place = NaviPlace("B마트 (시연)", "시연용 주소 B", 37.4950, 127.0420),
            toMartMinutes = 10, martToDestinationMinutes = 17,
        )
    }
}
