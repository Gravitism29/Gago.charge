package kr.gakim.charge.domain.itinerary

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.ceil

enum class Activity(val label: String) { SHOP("장보기"), MEAL("식사"), HOSPITAL("병원 방문"), COLLECT("물건 수령"), PICKUP("픽업"), CUSTOM("직접 입력"), CHARGE("충전") }
enum class ChargeMode(val label: String) { AUTO("알아서 비교"), NONE("충전 안 함"), TOP_UP("볼일 중 보충"), REQUIRED("필요한 양까지") }
enum class Availability { AVAILABLE, UNKNOWN, INCOMPATIBLE, UNAVAILABLE }

data class Evidence(val source: String, val observedAt: LocalDateTime, val demo: Boolean = false) {
    fun fresh(now: LocalDateTime, seconds: Long = 120): Boolean = Duration.between(observedAt, now).seconds in 0..seconds
}
data class Measured<T>(val value: T, val evidence: Evidence)
data class Place(val id: String, val name: String, val latitude: Double, val longitude: Double,
                 val address: String = "", val poiId: String = "", val poiSubId: String = "",
                 val demo: Boolean = false, val source: String = "사용자 지정") {
    val validCoordinates: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)
}
data class Visit(val id: Long, val activity: Activity = Activity.SHOP, val customLabel: String = "",
                 val place: Place? = null, val stayMinutes: Int = 20, val deadline: LocalDateTime? = null,
                 val placeholder: Boolean = false) {
    val label: String get() = customLabel.ifBlank { activity.label }
}
data class Itinerary(val visits: List<Visit> = listOf(Visit(1, placeholder = true)), val departure: LocalDateTime? = null,
                     val chargeMode: ChargeMode = ChargeMode.AUTO, val reserveKm: Double = 20.0)
data class Leg(val minutes: Measured<Int>?, val distanceKm: Measured<Double>?)
data class ChargingSite(val availability: Availability, val parkingMinutes: Int = 3, val walkingMinutes: Int = 2,
                        val connectMinutes: Int = 2, val disconnectMinutes: Int = 1,
                        val waitMinutes: Measured<Int>? = null, val effectivePowerKw: Measured<Double>? = null,
                        val overlapVerified: Boolean = false, val evidence: Evidence,
                        val note: String = "")
data class Energy(val rangeKm: Measured<Double>? = null, val kmPerKwh: Measured<Double>? = null,
                  val socPercent: Measured<Int>? = null)
enum class EnergyStatus { OPTIONAL, NEED_CHARGE, CANNOT_REACH, UNKNOWN }
data class EnergyDecision(val status: EnergyStatus, val missingKm: Double? = null, val note: String)
data class VisitResult(val visit: Visit, val arrival: LocalDateTime?, val finish: LocalDateTime?, val slackMinutes: Long?)
data class Option(val id: String, val visits: List<Visit>, val results: List<VisitResult>, val mode: ChargeMode,
                  val totalMinutes: Long?, val totalDistanceKm: Double?, val extraMinutes: Long?,
                  val chargeMinutes: Int?, val energyKwh: Double?, val energy: EnergyDecision,
                  val selectable: Boolean, val conditions: List<String>, val evidence: List<Evidence>,
                  val chargingStatus: Availability? = null, val chargeVisitId: Long? = null) {
    val critical: VisitResult? get() = results.filter { it.visit.deadline != null }.minByOrNull { it.slackMinutes ?: Long.MAX_VALUE } ?: results.lastOrNull()
    val late: Boolean get() = results.any { (it.slackMinutes ?: 0) < 0 }
}

/** Range is a reported vehicle value. Never derive range from SOC. Unknown and stale stay unknown. */
fun energyDecision(energy: Energy, legs: List<Leg>, chargeIndex: Int?, reserveKm: Double, now: LocalDateTime): EnergyDecision {
    val range = energy.rangeKm?.takeIf { it.evidence.fresh(now) && it.value.isFinite() && it.value >= 0 }?.value
    val distances = legs.map { it.distanceKm?.takeIf { d -> d.evidence.fresh(now, 600) && d.value.isFinite() && d.value >= 0 }?.value }
    if (range == null || distances.any { it == null }) return EnergyDecision(EnergyStatus.UNKNOWN, note = "주행 가능 거리·경로 거리 미확인 또는 갱신 필요 · 충전 여부를 골라 주세요")
    val all = distances.filterNotNull()
    if (chargeIndex != null && range < all.take(chargeIndex + 1).sum()) return EnergyDecision(EnergyStatus.CANNOT_REACH, note = "이 충전 장소까지 주행 가능 거리 부족 · 더 가까운 충전 장소 필요")
    if (chargeIndex == null && range < all.sum()) return EnergyDecision(EnergyStatus.CANNOT_REACH, note = "충전 없이 전체 경로를 주행하기에 주행 가능 거리 부족")
    val shortage = (all.sum() + reserveKm - range).coerceAtLeast(0.0)
    return if (shortage > 0) EnergyDecision(EnergyStatus.NEED_CHARGE, shortage, "전체 경로와 도착 후 여유 ${reserveKm.toInt()}km를 위해 충전 권장")
    else EnergyDecision(EnergyStatus.OPTIONAL, 0.0, "전체 경로와 도착 후 여유 ${reserveKm.toInt()}km 확보 · 충전은 선택")
}

/** Only verified overlap is subtracted once. Access/walking/connection/queue/disconnection stay separate. */
fun stopMinutes(stay: Int, charging: Int, site: ChargingSite): Int =
    site.parkingMinutes + site.walkingMinutes + site.connectMinutes + (site.waitMinutes?.value ?: 0) +
        stay + charging - (if (site.overlapVerified) minOf(stay, charging) else 0) + site.disconnectMinutes

fun evaluateItinerary(plan: Itinerary, legs: List<Leg>, mode: ChargeMode, chargeIndex: Int?, site: ChargingSite?,
                      energy: Energy, now: LocalDateTime): Option {
    require(plan.visits.isNotEmpty() && legs.size == plan.visits.size)
    val decision = energyDecision(energy, legs, chargeIndex, plan.reserveKm, now)
    val conditions = mutableListOf<String>()
    val charging = mode != ChargeMode.NONE && chargeIndex != null
    val chargingStatus = when {
        mode == ChargeMode.NONE -> null
        site == null || !site.evidence.fresh(now, 300) -> Availability.UNKNOWN
        else -> site.availability
    }
    val chargeVisitId = if (charging) plan.visits.getOrNull(chargeIndex!!)?.id else null
    var selectable = plan.visits.all { it.place?.validCoordinates == true && it.stayMinutes in 0..720 }
    val chargeVisits = plan.visits.filter { it.activity == Activity.CHARGE }
    val unmatchedChargeVisit = chargeVisits.singleOrNull()?.takeIf { it.id != chargeVisitId }
    val invalidChargeVisits = chargeVisits.size > 1 || unmatchedChargeVisit != null
    if (chargeVisits.size > 1) {
        selectable = false
        conditions += "충전 전용 방문은 한 곳만 계산할 수 있어요. 방문을 정리해 주세요."
    } else if (unmatchedChargeVisit != null) {
        selectable = false
        val name = unmatchedChargeVisit.place?.name ?: unmatchedChargeVisit.label
        conditions += "충전 방문 ${name}에서 충전하지 않는 선택입니다. 충전 위치 또는 활동을 바꿔 주세요."
    }
    var chargeMinutes: Int? = null
    var energyKwh: Double? = null
    if (charging) {
        if (chargingStatus in listOf(Availability.INCOMPATIBLE, Availability.UNAVAILABLE)) {
            selectable = false
            conditions += if (chargingStatus == Availability.INCOMPATIBLE) "차에 맞지 않는 충전기" else "사용 가능한 충전 시설 없음"
        } else if (site == null) {
            conditions += "충전기 상태 미확인 · 현장 확인 필요"
            conditions += "대기 시간 미확인 · 바로 충전할 경우의 조건부 예상"
            conditions += "필요 충전량·충전 시간 산정 근거 부족"
        } else {
            if (chargingStatus == Availability.UNKNOWN) conditions += "충전기 상태 미확인 · 현장 확인 필요"
            if (site.waitMinutes == null || !site.waitMinutes.evidence.fresh(now, 300)) conditions += "대기 시간 미확인 · 바로 충전할 경우의 조건부 예상"
            val efficiency = energy.kmPerKwh?.takeIf { it.evidence.fresh(now) && it.value.isFinite() && it.value > 0 }?.value
            val power = site.effectivePowerKw?.takeIf { it.evidence.fresh(now, 300) && it.value.isFinite() && it.value > 0 }?.value
            if (mode == ChargeMode.TOP_UP) {
                val chargeOnly = plan.visits[chargeIndex].activity == Activity.CHARGE
                chargeMinutes = if (chargeOnly || site.overlapVerified) plan.visits[chargeIndex].stayMinutes else null
                if (!chargeOnly && !site.overlapVerified) conditions += "볼일과 충전의 동시 진행 가능 여부 미확인"
            } else if (decision.missingKm != null && efficiency != null && power != null) {
                energyKwh = decision.missingKm / efficiency
                chargeMinutes = ceil(energyKwh / power * 60.0).toInt()
                val chargeVisit = plan.visits[chargeIndex]
                if (chargeVisit.activity == Activity.CHARGE && chargeMinutes > chargeVisit.stayMinutes) {
                    conditions += "필요 충전 ${chargeMinutes}분 · 입력한 충전 시간 ${chargeVisit.stayMinutes}분보다 ${chargeMinutes - chargeVisit.stayMinutes}분 초과"
                }
            } else conditions += "필요 충전량·충전 시간 산정 근거 부족"
            if (mode == ChargeMode.TOP_UP && efficiency != null && power != null && chargeMinutes != null) {
                energyKwh = power * chargeMinutes / 60.0
                val replenishedKm = energyKwh * efficiency
                if (decision.missingKm != null && replenishedKm + 0.001 < decision.missingKm) {
                    conditions += "볼일 중 보충만으로는 이후 경로의 여유 거리 부족"
                    selectable = false
                }
            }
            if (mode == ChargeMode.REQUIRED && chargeMinutes == 0) conditions += "현재 경로에는 추가 충전이 필요하지 않음"
        }
        if (decision.status == EnergyStatus.CANNOT_REACH) selectable = false
    } else if (mode != ChargeMode.NONE) {
        selectable = false
        conditions += "충전할 방문 장소를 정해 주세요."
    } else if (decision.status == EnergyStatus.CANNOT_REACH) {
        selectable = false
        conditions += decision.note
    } else if (decision.status == EnergyStatus.NEED_CHARGE) conditions += "충전 없이 가면 도착 후 여유 거리 부족"
    if (decision.status == EnergyStatus.UNKNOWN) conditions += decision.note
    val depart = plan.departure ?: now
    val departureElapsed = plan.departure?.isBefore(now) == true
    if (departureElapsed) {
        selectable = false
        conditions += "출발 예정 시각이 지났어요. 출발 시각을 다시 정해 주세요."
    }
    var cursor: LocalDateTime? = if (departureElapsed || invalidChargeVisits) null else depart
    val results = plan.visits.mapIndexed { index, visit ->
        val travel = legs[index].minutes?.takeIf { it.evidence.fresh(now, 600) && it.value >= 0 }?.value
        cursor = if (cursor != null && travel != null) cursor!!.plusMinutes(travel.toLong()) else null
        val arrival = cursor
        val activityMinutes = if (visit.activity == Activity.CHARGE) 0 else visit.stayMinutes
        val dwell = if (charging && index == chargeIndex) {
            if (site == null || chargeMinutes == null) null else stopMinutes(activityMinutes, chargeMinutes!!,
                site.copy(waitMinutes = site.waitMinutes?.takeIf { it.evidence.fresh(now, 300) }))
        } else activityMinutes
        cursor = if (cursor != null && dwell != null) cursor!!.plusMinutes(dwell.toLong()) else null
        VisitResult(visit, arrival, cursor, if (arrival != null && visit.deadline != null) Duration.between(arrival, visit.deadline).toMinutes() else null)
    }
    val allDistances = legs.map { it.distanceKm?.takeIf { d -> d.evidence.fresh(now, 600) && d.value >= 0 }?.value }
    val evidence = (legs.flatMap { listOfNotNull(it.minutes?.evidence, it.distanceKm?.evidence) } + listOfNotNull(energy.rangeKm?.evidence, energy.kmPerKwh?.evidence, site?.evidence)).distinct()
    return Option(plan.visits.joinToString("-") { it.place?.id ?: "?" } + ":${mode.name}:${chargeVisitId ?: "none"}", plan.visits, results, mode,
        cursor?.let { Duration.between(depart, it).toMinutes() }, if (allDistances.all { it != null }) allDistances.filterNotNull().sum() else null,
        null, chargeMinutes, energyKwh, decision, selectable, conditions.distinct(), evidence, chargingStatus, chargeVisitId)
}

fun recommend(options: List<Option>): Option? = options.filter { it.selectable && !it.late && it.totalMinutes != null &&
    (it.mode == ChargeMode.NONE || it.chargingStatus == Availability.AVAILABLE) &&
    (it.energy.status == EnergyStatus.OPTIONAL || it.energy.status == EnergyStatus.NEED_CHARGE && it.mode == ChargeMode.REQUIRED && it.chargeMinutes != null) }
    .minByOrNull { it.totalMinutes!! }

fun moveVisit(plan: Itinerary, index: Int, delta: Int): Itinerary {
    val target = index + delta
    if (index !in plan.visits.indices || target !in plan.visits.indices) return plan
    val visits = plan.visits.toMutableList()
    val item = visits.removeAt(index)
    visits.add(target, item)
    return plan.copy(visits = visits)
}
