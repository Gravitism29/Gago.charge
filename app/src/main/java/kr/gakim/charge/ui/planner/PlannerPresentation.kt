package kr.gakim.charge.ui.planner

import java.time.Duration
import java.time.LocalDateTime
import kr.gakim.charge.data.*
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.domain.itinerary.*
import kr.gakim.charge.ui.state.shortName
import kr.gakim.charge.ui.state.withRo

/**
 * 예시 문장은 지금 시계로 만든다. 첫 예시는 약 2시간 30분 뒤를 10분 단위로 올린 마감을 쓰므로,
 * 실제 시각이 16:10 을 지나도 '이미 지났어요' 확인 창이 뜨지 않는다.
 * 나머지 두 예시는 시각을 말하지 않아 언제 눌러도 비교할 수 있다.
 */
internal fun plannerExamples(now: LocalDateTime, remote: Boolean = false, marts: List<Place> = emptyList(), station: String? = null): List<String> = listOf(
    shoppingExampleSentence(now, remote, marts.filterNot { it.demo }.minByOrNull { it.searchDistanceMeters ?: Double.MAX_VALUE }, station),
    if (remote) "병원에 30분 들렀다 갈게" else "병원 들렀다가 물건 수령처에서 물건 받고 갈게",
    "충전하면서 30분 밥 먹고 갈래",
)

/** 지금부터 약 2시간 30분 뒤를 다음 10분 단위로 올림한 마감. */
internal fun exampleDeadline(now: LocalDateTime): LocalDateTime {
    val target = now.plusMinutes(150).withSecond(0).withNano(0)
    return target.plusMinutes(((10 - target.minute % 10) % 10).toLong())
}

/** 파서가 알아듣는 12시간제 말투. 자정을 넘기면 '내일 오전 …'으로 말한다. */
internal fun spokenClock(time: LocalDateTime, now: LocalDateTime): String {
    val prefix = if (time.toLocalDate() != now.toLocalDate()) "내일 " else ""
    return prefix + itineraryClock(time)
}

internal fun shoppingExampleSentence(now: LocalDateTime, remote: Boolean = false, mart: Place? = null, station: String? = null): String {
    val start = if (!remote) "은평점에서 " else mart?.takeUnless { it.demo }?.let { "${it.name}에서 " }.orEmpty()
    // 주변 마트를 아직 모르면 장소 없는 문장을 보여 준다('회사'처럼 풀 수 없는 장소로 막히지 않게).
    if (remote && (mart == null || station == null)) return "25분 장 보고 갈게"
    val destination = if (!remote) "학교" else station!!
    return "${start}25분 장 보고 ${spokenClock(exampleDeadline(now), now)}까지 $destination 가야 해"
}

internal fun Visit.placeLabel() = if (place == null && spokenPlace.isNotBlank()) "$spokenPlace · 장소 미정" else place?.name ?: "장소 미정"
internal fun Visit.timeDetail(now: LocalDateTime): String =
    (if (activity == Activity.ARRIVE && stayMinutes == 0) "" else "${stayMinutes}분 · ") +
        (deadline?.let { "${at(it, now)}까지" } ?: "정해진 시각 없음")

internal fun placeChoiceLabel(p: Place): String = "${p.name} · ${p.address} · " +
    (p.searchDistanceMeters?.let { if (it < 1000) "${it.toInt()}m" else "${"%.1f".format(java.util.Locale.KOREAN, it / 1000)}km" } ?: "거리 미확인")

internal val PlannerState.hasDemoData: Boolean get() = demoClock ||
    options.any { o -> o.evidence.any { it.demo } || o.visits.any { it.place?.demo == true } } ||
    plan.visits.any { it.place?.demo == true } ||
    listOfNotNull(energy.socPercent?.evidence, energy.rangeKm?.evidence, energy.kmPerKwh?.evidence).any { it.demo }

/** 이름 뒤에 '로/으로'를 붙인 주 동작 문구. */
// 버튼 폭(370dp)에 들어가는 이름만 넣는다. 긴 이름은 카드에 이미 보이므로 "여기로"로 줄인다.
/** 볼일과 겹치는 충전(들른 김에 충전)은 추가 시간이 '충전'이 아니라 주차·연결 시간이다. 화면마다 8분/30분이 엇갈려 보이지 않게 한 곳에서 정한다. */
internal fun Option.chargeOverlapsVisit(): Boolean {
    val stay=visits.firstOrNull { it.id==chargeVisitId }?.takeIf { it.activity!=Activity.CHARGE }?.stayMinutes ?: return false
    return mode==ChargeMode.TOP_UP || (chargeMinutes ?: Int.MAX_VALUE)<=stay
}
internal fun Option.extraStopLabel(): String = if(mode!=ChargeMode.NONE && chargeOverlapsVisit()) "주차·연결" else "충전"
internal fun Option.chargeDetail(visit: Visit): String = when {
    chargeMinutes==null -> "충전 시간 미확인"
    chargeOverlapsVisit() && visit.activity!=Activity.CHARGE -> "그동안 충전"
    else -> "충전 ${chargeMinutes}분"
}
/** 계산에 쓴 출발 시각. 화면이 '지금'으로 다시 세면 같은 계획의 이동·끝 시각이 화면마다 달라진다. */
internal fun Option.computedDeparture(): LocalDateTime? = totalMinutes?.let { total -> results.lastOrNull()?.finish?.minusMinutes(total) }
/** 사용자가 말하거나 고른 할 일이 하나라도 있는지(처음의 기본 칸은 제외). */
internal fun PlannerState.hasRealVisit(): Boolean = plan.visits.any { !it.placeholder }
/** 비교 카드에서 곳마다 다를 수 있는 정보: 충전기가 같은 주차장인지, 걸어서 얼마인지. 충전기 코드명은 운전자에게 뜻이 없어 뺀다. 시연 데이터는 보이지 않는다. */
internal fun ChargingSite.placeLine(): String? {
    if (evidence.demo) return null
    val parts = note.split(" · ")
    return if ("같은 주차장" in parts) "충전기 같은 주차장" else parts.firstOrNull { it.startsWith("도보 ") }?.let { "충전기까지 $it" }
}
/** 한 쪽에 보이는 곳들의 충전 방식별 끝 시각이 모두 같으면, 고를 기준이 시간이 아님을 알려 준다. */
internal fun sameTimesLine(groups: List<List<Option>>): String? =
    if (groups.size > 1 && groups.map { g -> g.map { it.mode to it.results.lastOrNull()?.finish }.toSet() }.distinct().size == 1)
        "${if (groups.size == 2) "두" else "모든"} 곳 모두 끝나는 시각이 같아요. 가고 싶은 곳으로 고르세요." else null
internal fun PlannerState.shownDeparture(option: Option? = selected): LocalDateTime = plan.departure ?: option?.computedDeparture() ?: now
internal fun goLabel(name: String): String = if (name.length > 9) "여기로 가기 →" else withRo(name) + " 가기 →"

internal const val rangeNotice = "주행 가능 거리를 읽지 못했어요. 충전 판단 없이 시간만 비교할게요."

/** Keep the raw reading for the sources sheet; never substitute a distance inferred from SOC. */
internal fun Energy.usableRange(now: LocalDateTime): Measured<Double>? = rangeKm?.takeIf {
    it.evidence.fresh(now) && it.value.isFinite() && it.value >= 0 &&
        !(it.value == 0.0 && (socPercent?.takeIf { soc -> soc.evidence.fresh(now) }?.value ?: -1) >= 5)
}
internal val PlannerState.rangeUnknown: Boolean get() = energy.usableRange(now) == null
internal fun PlannerState.recommendedForDisplay(): Option? = recommend(options,energy.socPercent?.takeIf { it.evidence.fresh(now) }?.value) ?: if(rangeUnknown)
    options.filter { it.mode==ChargeMode.NONE && it.selectable && !it.late && it.closingNotices.isEmpty() && it.totalMinutes!=null }.minByOrNull { it.totalMinutes!! }
    else null
internal fun ChargeMode.driverLabel() = when (this) {
    ChargeMode.AUTO -> "충전 방식 고르기"
    ChargeMode.NONE -> "충전 안 함"
    ChargeMode.TOP_UP -> "들른 김에 충전"
    ChargeMode.REQUIRED -> "필요한 만큼 충전"
}
internal fun clock(time: LocalDateTime?) = time?.let(::itineraryClock) ?: "시각 미확인"
internal fun at(time: LocalDateTime?, now: LocalDateTime): String = time?.let {
    (if (it.toLocalDate() == now.toLocalDate()) "" else if (it.toLocalDate() == now.toLocalDate().plusDays(1)) "내일 " else "${it.monthValue}/${it.dayOfMonth} ") + clock(it)
} ?: "미확인"
internal fun routeText(route: NaviRoute?) = route?.let {
    (it.waypoints + listOfNotNull(it.destination)).joinToString(" → ") { p -> p.name }.ifBlank { "목적지 없음" }
} ?: "경로 미확인"
internal fun Option.placeName(s: PlannerState): String = visits.firstOrNull { v ->
    s.plan.visits.firstOrNull { it.id == v.id }?.place == null
}?.place?.name ?: visits.firstOrNull()?.place?.name ?: "장소 미정"

internal fun PlannerState.removedPlaces(): List<NaviPlace> {
    val planned = selected?.visits?.mapNotNull { it.place?.toNavi() } ?: return emptyList()
    val route = (currentRoute as? NaviRead.Known)?.route ?: return emptyList()
    return (route.waypoints + listOfNotNull(route.destination)).filterNot { old -> planned.any { PlaceMatcher.samePlace(old, it) } }
}
internal fun PlannerState.canKeepDestination(): Boolean {
    val destination = (currentRoute as? NaviRead.Known)?.route?.destination ?: return false
    return selected?.visits?.size?.let { it < 3 } == true && destination.toPlace("현재 내비").validCoordinates &&
        removedPlaces().any { PlaceMatcher.samePlace(it, destination) }
}

/**
 * 축 위 라벨 하나. [left]·[width] 는 축과 같은 픽셀 좌표이고, [row] 는 겹칠 때 밀어 둔 줄이다.
 * 화면은 이 값만 보고 그리므로 배치 규칙을 단위 테스트할 수 있다.
 */
internal data class AxisLabel(val text: String, val left: Float, val width: Float, val row: Int)

/**
 * 각 라벨을 자기 구간의 한가운데에 두되 막대 밖으로 나가지 않게 하고,
 * 같은 줄에서 겹치면 다음 줄로 밀어 둔다. 세그먼트와 라벨 수가 다르면 라벨 수만큼만 배치한다.
 */
internal fun axisLabelLayout(
    segments: List<ClosedFloatingPointRange<Float>>,
    texts: List<String>,
    widthsPx: List<Float>,
    axisWidthPx: Float,
    gapPx: Float,
): List<AxisLabel> {
    if (axisWidthPx <= 0f) return emptyList()
    val rows = mutableListOf<MutableList<AxisLabel>>()
    val result = mutableListOf<AxisLabel>()
    for (index in texts.indices) {
        val segment = segments.getOrNull(index) ?: continue
        val width = (widthsPx.getOrNull(index) ?: 0f).coerceIn(0f, axisWidthPx)
        val center = ((segment.start.coerceIn(0f, 1f) + segment.endInclusive.coerceIn(0f, 1f)) / 2f) * axisWidthPx
        val left = (center - width / 2f).coerceIn(0f, (axisWidthPx - width).coerceAtLeast(0f))
        var row = 0
        while (row < texts.size) {
            val placed = rows.getOrNull(row)
            val fits = placed == null || placed.none { left < it.left + it.width + gapPx && it.left < left + width + gapPx }
            if (fits) {
                if (row == rows.size) rows.add(mutableListOf())
                val label = AxisLabel(texts[index], left, width, row)
                rows[row].add(label)
                result.add(label)
                break
            }
            row++
        }
    }
    return result
}

/** 세 구간 라벨: 출발→첫 장소, 첫 장소에서 머무는 시간, 다음 장소까지. 한 곳뿐이면 앞의 둘만. */
internal fun Option.timelineLabelTexts(departure: LocalDateTime): List<String>? {
    val minutes = timeline(departure) ?: return null
    val first = visits.getOrNull(0)?.place?.name ?: "첫 장소"
    val labels = mutableListOf("${shortName(first)}까지 ${minutes[0]}분", "머무는 시간 ${minutes[1]}분")
    visits.getOrNull(1)?.place?.name?.let { next -> labels += "${shortName(next)}까지 ${minutes[2]}분" }
    return labels
}

/** 비교 카드에 한 줄씩 보여 줄 방문 일정. 값은 계산된 결과에서만 가져온다. */
internal data class ScheduleRow(val arrival: String, val place: String, val detail: String, val slackMinutes: Long?)

internal fun Option.scheduleRows(): List<ScheduleRow> = results.map { result ->
    val visit = result.visit
    ScheduleRow(
        arrival = clock(result.arrival),
        place = visit.place?.name ?: visit.label,
        detail = if (visit.activity == Activity.ARRIVE && visit.stayMinutes == 0)
            visit.deadline?.let { "${clock(it)}까지" } ?: visit.activity.label else "${visit.activityName()} ${visit.stayMinutes}분",
        slackMinutes = visit.deadline?.let { result.slackMinutes },
    )
}

/** 고른 방식이 충전할 때만 한 줄. 계산된 분과 장소만 쓰고, 모르면 그렇다고 말한다. */
internal fun Option.chargingRow(): String? {
    if (mode == ChargeMode.NONE) return null
    val id = chargeVisitId ?: return null
    val place = visits.firstOrNull { it.id == id }?.place?.name ?: "장소 미확인"
    val visit = visits.firstOrNull { it.id == id }
    if (chargeMinutes != null && visit != null && chargeOverlapsVisit())
        return "${visit.activityName()} ${visit.stayMinutes}분 동안 충전(추정)" + (extraMinutes?.takeIf { it > 0 }?.let { " · 주차·연결 ${it}분 추가" } ?: "")
    val minutes = chargeMinutes?.let { "${it}분" } ?: "시간 미확인"
    return "충전 $minutes · $place"
}

/** 계산이 이미 준 여유 분을 칩 상태로만 옮긴다(5분 미만은 촉박). */
internal fun timingStatus(slackMinutes: Long): TimingStatus = when {
    slackMinutes < 0 -> TimingStatus.LATE
    slackMinutes < 5 -> TimingStatus.TIGHT
    else -> TimingStatus.OK
}

/** Three proportional segments derived from v3 arrival/finish results, including all stop overhead. */
internal fun Option.timeline(departure: LocalDateTime): List<Long>? {
    val end = if (critical?.visit?.deadline != null) critical?.arrival else results.lastOrNull()?.finish
    end ?: return null
    val first = results.firstOrNull() ?: return null
    val arrival = first.arrival ?: return null
    val finish = first.finish ?: return null
    val firstDrive = Duration.between(departure, arrival).toMinutes().coerceAtLeast(0)
    val stop = Duration.between(arrival, minOf(finish, end)).toMinutes().coerceAtLeast(0)
    val rest = Duration.between(minOf(finish, end), end).toMinutes().coerceAtLeast(0)
    return listOf(firstDrive, stop, rest)
}

internal fun PlannerState.assistantLine(): String = when {
    park == ParkState.MOVING -> "주차 후에 이어서 도와드릴게요."
    listening -> "듣고 있어요…"
    searchingPlaces -> "주변 장소를 찾는 중이에요…"
    question != null -> question.message
    pendingTime != null -> "오늘 일정인가요, 내일 일정인가요?"
    screen == 0 && plan.visits.all { it.placeholder } -> "어떤 일을 보고 가실까요?"
    screen == 0 && plan.visits.count { it.place == null } > 1 -> "어디에 들를지 먼저 골라 주세요."
    screen == 0 -> "${planSummary()}.\n이 일정으로 비교할까요?"
    screen == 1 -> "시간을 나란히 살펴보세요.\n어디로 갈까요?"
    phase == ApplyPhase.APPLIED -> "내비에 반영됐어요.\n이제 출발하셔도 돼요."
    phase == ApplyPhase.UNCONFIRMED -> if (actualRoute != null) "내비 경로가 계획과 달라요." else "아직 반영을 확인하지 못했어요."
    phase == ApplyPhase.FAILED -> "안내를 시작하지 못했어요."
    busy -> "내비에서 경로를 확인하고 있어요."
    else -> "들를 곳과 순서를 확인해 주세요."
}

/** 들은 내용을 시간·마감·충전 의도까지 되읽는다('식사.'처럼 이름만 읽으면 알아들었는지 알 수 없다). */
internal fun PlannerState.planSummary(): String = plan.visits.joinToString(", ") { v ->
    if (v.activity == Activity.ARRIVE && v.stayMinutes == 0) (v.deadline?.let { "${at(it, now)}까지 " } ?: "") + (v.place?.name ?: v.spokenPlace.ifBlank { v.label })
    else "${v.activityName()} ${v.stayMinutes}분" + (v.deadline?.let { " · ${at(it, now)}까지" } ?: "")
} + if (plan.chargeMode == ChargeMode.TOP_UP) ", 충전도 같이" else ""

/** 말로 되묻는 한 문장. 화면이 묻는 것과 같은 물음으로 끝나야 "응"으로 답할 수 있다. */
internal fun PlannerState.spokenPrompt(): String = when {
    question != null -> question.message
    message == speechNotUnderstood -> speechNotUnderstood
    screen == 0 -> "${planSummary()}. 이 일정으로 비교할까요?"
    screen == 1 -> (selected ?: recommendedForDisplay())?.let { o -> "${o.placeName(this)}, ${clock(o.results.lastOrNull()?.finish)}에 끝나요. 여기로 갈까요?" } ?: "주변 장소를 찾고 있어요."
    phase == ApplyPhase.APPLIED -> "내비에 넣었어요."
    removedPlaces().isNotEmpty() && !acknowledged -> "기존 목적지도 들를까요, 새 목적지만 갈까요?"
    else -> selected?.let { "${clock(it.results.lastOrNull()?.finish)}에 끝나요. 안내를 시작할까요?" } ?: ""
}

internal fun PlannerState.candidateGroups(): List<List<Option>> {
    val recommended = recommendedForDisplay()?.id
    return cardOptions.groupBy { it.visits.map { v -> v.place?.id } }.values.sortedWith(
        compareBy<List<Option>> { group -> if (group.any { it.id == recommended }) 0 else 1 }
            .thenBy { group -> group.mapNotNull { it.results.lastOrNull()?.finish }.minOrNull() ?: LocalDateTime.MAX })
}
internal val PlannerState.demoMartFallback: Boolean get() =
    !searchingPlaces && options.any { o -> o.visits.any { it.activity == Activity.SHOP && it.place?.demo == true } }
internal const val martFallbackLine = "주변 마트를 불러오지 못해 시연 장소로 보여드려요"

/** All three computed modes remain available to compare, even after a mode was requested. */
internal val PlannerState.cardOptions: List<Option> get() = comparisonOptions.ifEmpty { options }

internal fun PlannerState.canStartGuidance(): Boolean = screen==2 && !busy && park!=ParkState.MOVING &&
    phase !in listOf(ApplyPhase.APPLIED, ApplyPhase.UNCONFIRMED) && !departureExpired &&
    selected?.selectable==true && pendingTime==null && question==null && pendingImport==null &&
    (removedPlaces().isEmpty() || acknowledged) && (park==ParkState.PARKED || parkedConfirmed)

/** Presentation only: original evidence strings remain intact for provenance and matching. */
internal fun sourceLabel(raw: String): String = clockText(raw)
    .replace("Android RANGE_REMAINING", "차량 주행 가능 거리")
    .replace("Pleos Vehicle SDK 주행 가능 거리", "차량 주행 가능 거리")
    .replace("Vehicle SOC", "차량 배터리 잔량")
    .replace(Regex("명목 출력 기준\\s*(\\d+(?:\\.\\d+)?)\\s*kW")) { powerLabel(it.groupValues[1].toDouble()) }
    .replace("typed availability", "충전기 상태")
    .replace("필요한 양까지", "필요한 만큼 충전")
    .replace("충분히 충전", "필요한 만큼 충전")
    .replace("바로 충전할 경우의 조건부 예상", "바로 충전할 때의 예상 시간")
    .replace("조건부 예상", "바로 충전할 때의 예상 시간")

internal fun powerLabel(kw: Double): String = "${if (kw % 1.0 == 0.0) kw.toInt().toString() else "%.1f".format(java.util.Locale.KOREAN,kw)}kW " +
    when { kw<=11 -> "완속"; kw>=40 -> "급속"; else -> "중속" }

internal fun Option.rangeGain(energy: Energy, now: LocalDateTime): Int? {
    val efficiency=energy.kmPerKwh?.takeIf { it.evidence.fresh(now,EFFICIENCY_FRESH_SECONDS) && it.value.isFinite() && it.value>0 }?.value ?: return null
    return energyKwh?.takeIf { it.isFinite() && it>=0 }?.let { kotlin.math.round(it*efficiency).toInt() }
}
/** 차량 전비를 모를 때의 어림값. 충전 속도(추정) × 시간 × 5 km/kWh, 5km 단위로 내림. */
internal fun estimatedRangeGain(chargeMinutes: Int?, site: ChargingSite?): Int? {
    val kw=site?.effectivePowerKw?.value?.takeIf { it.isFinite() && it>0 } ?: return null
    val minutes=chargeMinutes?.takeIf { it>0 } ?: return null
    return (kw*minutes/60.0*5.0).let { if(it>=10) (it/5).toInt()*5 else it.toInt() }.takeIf { it>0 }
}
internal fun Option.modeResult(energy: Energy, now: LocalDateTime): String =
    if(mode==ChargeMode.NONE) "+0분" else listOfNotNull(extraMinutes?.let { "${if(it>=0) "+" else ""}${it}분" } ?: "시간 미확인",
        rangeGain(energy,now)?.let { "+${it}km" }).joinToString(" · ")

internal fun benefitLine(option: Option, energy: Energy, now: LocalDateTime, site: ChargingSite?): String {
    val needed=option.energy.status in listOf(EnergyStatus.NEED_CHARGE,EnergyStatus.CANNOT_REACH)
    val soc=energy.socPercent?.takeIf { it.evidence.fresh(now) }?.value
    // 평가(2026-11-17): '오늘은 충전 안 해도 돼요'는 이번 일정만 본 판단을 하루 전체로 넓힌 말이었고, 충전으로 얻는 양이 보이지 않았다.
    if(option.mode==ChargeMode.NONE) return when {
        needed -> "충전 없이는 도착 후 여유가 부족해요"
        option.energy.status==EnergyStatus.OPTIONAL -> "이번 일정은 충전 없이 갈 수 있어요." +
            if(soc!=null && soc<=30) " 배터리가 ${soc}%라 들른 김에 채워 두면 좋아요." else ""
        else -> "주행 가능 거리를 확인해 주세요. 시간만 비교했어요."
    }
    if(!option.selectable) return "이 방식으로는 갈 수 없어요. 다른 방식을 골라 주세요."
    val timing=option.extraMinutes?.let { when {
        it>0 -> "${it}분 더 걸려요."
        it<0 -> "${-it}분 일찍 끝나요."
        else -> "충전해도 끝나는 시각은 같아요."
    } } ?: "끝나는 시각은 아직 계산할 수 없어요."
    if(needed) return "충전하면 여유 있게 갈 수 있어요. $timing"
    val gain=option.rangeGain(energy,now) ?: estimatedRangeGain(option.chargeMinutes,site)
    val activity=when(option.visits.firstOrNull { it.id==option.chargeVisitId }?.activity) {
        Activity.SHOP -> "장 보는 동안"; Activity.MEAL -> "식사하는 동안"; Activity.HOSPITAL -> "진료받는 동안"; else -> "볼일 보는 동안"
    }
    // 같은 주차장이 아니면 볼일 '하는 동안'이 아니라 충전기에 세워 두고 걸어서 다녀오는 동안이다(도보 왕복은 체류 시간에 들어 있다).
    val prefix=when { site?.overlapVerified!=true -> "";site.sameLot==false -> "충전기에 세워 두고 걸어서 다녀오는 동안 ";else -> "$activity " }
    val lead=gain?.let { "${prefix}약 +${it}km(추정) 충전돼요." } ?: "충전량은 아직 확인할 수 없어요."
    return (if(soc!=null && soc>=80) "지금도 충분하지만, " else "") + "$lead $timing"
}
/** 60분 이상은 '1시간 54분'처럼 읽기 쉽게. */
internal fun spanLabel(minutes: Long): String = if(minutes<60) "${minutes}분" else "${minutes/60}시간" + if(minutes%60==0L) "" else " ${minutes%60}분"
internal fun Option.deadlineLabel(): String {
    val result=critical
    val slack=result?.slackMinutes
    return when { result?.visit?.deadline==null -> "정해진 시각 없음";slack==null -> "도착 시각 미확인";
        slack<0 -> "${spanLabel(-slack)} 늦어요";slack==0L -> "딱 맞춰 도착";else -> "${spanLabel(slack)} 일찍 도착" }
}
internal fun PlannerState.recommendationLabel(displayed:Option?=recommendedForDisplay()): String {
    val chosen=displayed ?: return "추천"
    val finish=chosen.results.lastOrNull()?.finish ?: return "추천"
    val otherGroups=cardOptions.filter { it.selectable && it.visits.map { v->v.place?.id }!=chosen.visits.map { v->v.place?.id } }
        .groupBy { it.visits.map { v->v.place?.id } }.values
    if(otherGroups.any { group -> group.none { it.results.lastOrNull()?.finish!=null } }) return "추천"
    val others=otherGroups.mapNotNull { group -> group.filter { it.results.lastOrNull()?.finish!=null }.minByOrNull { it.results.last().finish!! } }
    if(others.isEmpty()) return "추천"
    if(others.all { finish<it.results.last().finish!! }) return "추천 · 가장 빨리 끝나요"
    val tied=others.filter { it.results.last().finish==finish }
    if(others.any { it.results.last().finish!!<finish } || tied.isEmpty()) return "추천"
    val travel=chosen.travelMinutes(shownDeparture(chosen))
    val closer=tied.all { other ->
        val otherTravel=other.travelMinutes(shownDeparture(other))
        val distance=chosen.totalDistanceKm
        val otherDistance=other.totalDistanceKm
        (travel!=null && otherTravel!=null && travel<otherTravel) ||
            (distance!=null && otherDistance!=null && distance<otherDistance)
    }
    return if(closer) "추천 · 더 가까워요" else "추천"
}

internal fun Option.travelMinutes(departure:LocalDateTime): Long? = elapsedSegments(departure)?.first()
internal fun Option.activityLabel(): String = visits.filter { it.activity !in listOf(Activity.ARRIVE,Activity.CHARGE) }
    .map { it.activityName() }.distinct().singleOrNull() ?: "볼일"
internal fun Visit.activityName(): String = when(activity) { Activity.HOSPITAL -> "병원";Activity.CUSTOM -> customLabel.ifBlank { "볼일" };else -> label }
internal fun batteryFull(energy: Energy, now: LocalDateTime): Boolean = (energy.socPercent?.takeIf { it.evidence.fresh(now,600) }?.value ?: 0) >= 95
internal fun Option.chargeControlEnabled(): Boolean = selectable && results.lastOrNull()?.finish!=null &&
    !(mode==ChargeMode.REQUIRED && energy.status==EnergyStatus.OPTIONAL)
internal fun chargeControlLine(option:Option?): String = when {
    option==null -> "시간 계산 불가"
    option.mode==ChargeMode.REQUIRED && option.energy.status==EnergyStatus.OPTIONAL -> "지금은 필요 없어요"
    option.results.lastOrNull()?.finish==null -> "시간 계산 불가"
    else -> "${clock(option.results.last().finish)}에 끝" + (option.extraMinutes?.takeIf { it>0 }?.let { " · +${it}분" } ?: "")
}
/** 충전기 정보가 없을 때 따로 들르는 정차의 주차·연결·분리 어림(ChargingSite 기본값 3+2+2+1). */
internal const val SEPARATE_STOP_OVERHEAD_MINUTES = 8

/**
 * 같은 양을 충전하러 충전소에 따로 들르면 드는 시간(추정): 실제 충전 분 + 주차·연결·분리.
 * 우회 이동은 알 수 없어 넣지 않는다(그만큼 보수적인 값). 볼일과 겹치는 '들른 김에 충전'에서만 낸다.
 */
internal fun separateStopMinutes(option: Option, site: ChargingSite?): Int? {
    if (option.mode != ChargeMode.TOP_UP || !option.selectable || !option.chargeOverlapsVisit()) return null
    var charge = option.chargeMinutes?.takeIf { it > 0 } ?: return null
    // 80% 상한으로 충전량이 줄었다면, 따로 들러도 그만큼만 충전하면 된다.
    val kw = site?.effectivePowerKw?.value?.takeIf { it.isFinite() && it > 0 }
    val kwh = option.energyKwh?.takeIf { it.isFinite() && it >= 0 }
    if (kw != null && kwh != null) charge = minOf(charge, kotlin.math.ceil(kwh / kw * 60.0).toInt())
    if (charge <= 0) return null
    val overhead = site?.let { it.parkingMinutes + it.walkingMinutes + it.connectMinutes + it.disconnectMinutes } ?: SEPARATE_STOP_OVERHEAD_MINUTES
    return charge + overhead
}

/** 따로 충전하러 가는 것보다 아끼는 분(추정). 5분 미만이면 말하지 않는다. */
internal fun savedMinutes(option: Option, site: ChargingSite?): Long? {
    val separate = separateStopMinutes(option, site) ?: return null
    val extra = option.extraMinutes ?: return null
    return (separate - extra).takeIf { it >= 5 }
}

internal fun savedTimeLine(option: Option, site: ChargingSite?): String? {
    val saved = savedMinutes(option, site) ?: return null
    return "따로 충전하면 +${separateStopMinutes(option, site)}분 → ${saved}분 아껴요(추정)"
}

/** 충전 전→후 배터리(추정). 용량 추정·SOC 가 모두 새 값일 때만. 들른 김에 충전은 80%, 필요한 만큼은 100%에서 멈춘다. */
internal fun socChangeLine(option: Option, energy: Energy, now: LocalDateTime): String? {
    if (option.mode == ChargeMode.NONE) return null
    val capacity = energy.batteryCapacityKwh?.takeIf { it.evidence.fresh(now, EFFICIENCY_FRESH_SECONDS) && it.value.isFinite() && it.value > 0 }?.value ?: return null
    val soc = energy.socPercent?.takeIf { it.evidence.fresh(now, 600) && it.value in 0..100 }?.value ?: return null
    val kwh = option.energyKwh?.takeIf { it.isFinite() && it > 0 } ?: return null
    val cap = if (option.mode == ChargeMode.TOP_UP) maxOf(soc, TOP_UP_TARGET_SOC_PERCENT) else 100
    val after = kotlin.math.round(soc + kwh / capacity * 100.0).toInt().coerceAtMost(cap)
    return if (after - soc >= 1) "$soc% → 약 $after%(추정)" else null
}

/** '필요한 만큼 충전'이 지금 필요 없으면 버튼을 숨긴다(꺼진 버튼이 세 번째 선택처럼 보였다). */
internal fun visibleChargeModes(requiredTarget: Option?): List<ChargeMode> =
    if (requiredTarget?.mode == ChargeMode.REQUIRED && requiredTarget.energy.status == EnergyStatus.OPTIONAL) listOf(ChargeMode.NONE, ChargeMode.TOP_UP)
    else listOf(ChargeMode.NONE, ChargeMode.TOP_UP, ChargeMode.REQUIRED)

/** 카드 아래 한 줄. [risk] 면 주황, 아니면 추정 안내라 흐린 글씨. */
internal data class WarningSummary(val text: String, val risk: Boolean)

/** 실제로 일정이 틀어질 수 있는 것만 센다: 휴무, 쓸 수 없는 충전기, 늦거나 촉박한 도착, 급속 1시간 이동, 갈 수 없는 선택. */
internal fun Option.riskWarnings(): List<String> = buildList {
    closingNotices.forEach { add(it.label) }
    if (mode != ChargeMode.NONE && chargingStatus in listOf(Availability.INCOMPATIBLE, Availability.UNAVAILABLE)) add("사용할 수 없는 충전기")
    val slack = critical?.takeIf { it.visit.deadline != null }?.slackMinutes
    if (slack != null && slack < 5) add(if (slack < 0) "마감에 늦어요" else "마감이 촉박해요")
    if (FAST_CHARGER_NOTE in conditions) add(FAST_CHARGER_NOTE)
    if (!selectable && isEmpty()) add("이 방식으로는 갈 수 없어요")
}.distinct()

internal fun Option.warningSummary(): WarningSummary? {
    val risks = riskWarnings()
    if (risks.isNotEmpty()) return WarningSummary("확인할 내용 ${risks.size}가지", true)
    return if (reviewWarnings().isNotEmpty()) WarningSummary("추정값 포함", false) else null
}

/** 처리 중 안내. 말하는 중이 아니고 장소·차량 정보를 읽는 동안만. */
internal fun PlannerState.processingLine(): String? = if (!listening && (searchingPlaces || loading)) "일정에 맞는 곳을 찾는 중…" else null

/** 마이크를 눌러 끝냈을 때 들은 중간 문장(있으면 확정 문장처럼 처리한다). */
internal fun PlannerState.stopTranscript(): String? = partial.trim().takeIf { listening && it.isNotEmpty() }

/** 확인 화면에서 명령이 아닌 말을 들었을 때의 안내. 경고가 아니라 안내 색으로 보인다. */
internal const val confirmIgnoredSuffix = " — 일정은 그대로예요. ‘안내 시작’ 또는 ‘다른 곳으로’라고 말해 주세요."
internal fun isInfoApplyMessage(message: String): Boolean = message.endsWith(confirmIgnoredSuffix)

/** 비교 화면에서 본 끝 시각보다 늦어졌으면(지금 출발 기준이라 시계가 흘렀으면) 한 줄로 알린다. */
internal fun PlannerState.departureShiftLine(): String? {
    if (plan.departure != null) return null
    val shown = shownFinish ?: return null
    val finish = selected?.results?.lastOrNull()?.finish ?: return null
    val minutes = Duration.between(shown, finish).toMinutes()
    return if (minutes > 0) "출발이 ${minutes}분 늦어져 ${clock(finish)}에 끝나요" else null
}

/** 출처 목록의 '오래된 정보' 기준. 전비·배터리 용량 추정은 30분, 나머지는 10분. */
internal fun evidenceStale(e: Evidence, energy: Energy, now: LocalDateTime): Boolean {
    val window = if (e == energy.kmPerKwh?.evidence || e == energy.batteryCapacityKwh?.evidence) EFFICIENCY_FRESH_SECONDS else 600L
    return !e.fresh(now, window)
}

/** 막대 아래 세 라벨. 볼일과 겹쳐 충전하면 볼일 구간에 '동안 충전'을 붙인다. */
internal fun Option.timelineSegmentLabels(segments: List<Long>): List<String> {
    val chargingDuringErrand = mode != ChargeMode.NONE && chargeOverlapsVisit()
    return listOf("이동 ${segments[0]}분", "${activityLabel()} ${segments[1]}분" + if (chargingDuringErrand) " 동안 충전" else "", "${extraStopLabel()} ${segments[2]}분")
}

internal fun Option.statusChips(site: ChargingSite?, demo: Boolean): List<String> {
    val status=when(chargingStatus) { Availability.AVAILABLE -> "사용 가능";Availability.CHARGING -> "사용 중";Availability.INCOMPATIBLE -> "차량과 맞지 않음";Availability.UNAVAILABLE -> "사용 불가";else -> "상태 확인 필요" }
    if(mode==ChargeMode.NONE) return if(demo) listOf("시연 값") else emptyList()
    val power=site?.effectivePowerKw?.value?.let(::powerLabel)?.let { if(site?.effectivePowerKw?.evidence?.source?.contains("추정")==true) "$it · 추정" else it }
    val access=when {
        demo -> "시연 값"
        site?.note?.contains("이용 제한")==true -> "이용 제한"
        site?.note?.contains("주차 유료")==true -> "주차 유료"
        site?.note?.contains("같은 주차장")==true -> "같은 주차장"
        else -> site?.walkingMinutes?.let { "도보 ${it}분" } ?: "추정"
    }
    return listOfNotNull(status,power,access).take(3)
}
internal fun Option.detailsConclusion(): String = when {
    !selectable -> "이 방식으로는 갈 수 없어요"
    mode!=ChargeMode.NONE && chargingStatus==Availability.AVAILABLE -> "지금은 충전기를 쓸 수 있어요. 자리는 도착해서 확인해요."
    mode!=ChargeMode.NONE -> "충전기 자리는 도착해야 알 수 있어요"
    energy.status==EnergyStatus.OPTIONAL -> "이번 일정은 충전 없이 갈 수 있어요"
    energy.status==EnergyStatus.UNKNOWN -> "시간만 비교했어요. 주행 가능 거리를 확인해 주세요."
    else -> "충전 후 갈 수 있어요. 여유 거리를 확인해 주세요."
}
internal fun Option.reviewWarnings(): List<String> =
    (conditions+listOfNotNull(energy.note.takeIf { it.isNotBlank() && energy.status!=EnergyStatus.OPTIONAL }))
        .filter { it.isNotBlank() }.map { it.replace("볼일",activityLabel()) }.distinct()

internal fun warningFallback(raw: String, option: Option, baseline: Option?): String {
    return if(raw==option.chargingNote && raw.isNotBlank()) when {
        raw.contains("이용 제한") -> "충전소 이용 제한이 있어요"
        raw.contains("오래된 정보") || raw.contains("상태 시각 미확인") -> "충전기 상태 정보가 오래됐거나 시각이 미확인이에요"
        else -> "충전 속도와 주차·연결 시간은 추정이에요"
    } else when {
        raw.contains("대기") -> "대기 줄이 있을 수 있어요"
        raw.contains("충전기 상태") -> "충전기 상태는 현장에서 확인해 주세요"
        else -> sourceLabel(raw)
    }
}

internal fun chargingFallback(option:Option,baseline:Option?): String? = if(option.mode==ChargeMode.NONE) null else
    baseline?.takeIf { it.selectable }?.results?.lastOrNull()?.finish?.let { "충전기를 못 쓰면 충전 없이 ${clock(it)}에 끝나요" }

internal const val speechNotUnderstood="잘 못 들었어요. 다시 말하거나 예시를 눌러 주세요"
internal const val noNearbyPlace="주변에서 찾지 못했어요. 장소 이름을 말해 주세요"
internal const val chargerDataMissing="충전소 정보를 불러오지 못했어요 · 시간만 비교해요"
internal data class PlannerIssue(val line:String,val retry:Boolean=false)
internal fun PlannerState.presentationIssue(): PlannerIssue? {
    val failed=sourceStatuses.filter { it.status.contains("실패") || it.status.contains("연결 오류") || it.status.contains("시간 초과") }
    if(failed.any { !it.source.contains("환경공단") && !it.source.contains("충전") }) return PlannerIssue("인터넷 연결을 확인해 주세요",true)
    if(!searchingPlaces && screen==1 && cardOptions.isEmpty()) return PlannerIssue(noNearbyPlace)
    if(failed.isNotEmpty() || screen==1 && cardOptions.isNotEmpty() && chargingSites.isEmpty()) return PlannerIssue(chargerDataMissing,true)
    if(message==speechNotUnderstood) return PlannerIssue(speechNotUnderstood)
    return null
}

/** Sequential, non-overlapping elapsed segments. Verified concurrent charging is counted once. */
internal fun Option.elapsedSegments(departure: LocalDateTime): List<Long>? {
    var previous=departure
    var drive=0L
    var stops=0L
    for(result in results) {
        val arrival=result.arrival ?: return null
        val finish=result.finish ?: return null
        drive+=Duration.between(previous,arrival).toMinutes().coerceAtLeast(0)
        stops+=Duration.between(arrival,finish).toMinutes().coerceAtLeast(0)
        previous=finish
    }
    val errands=visits.sumOf { if(it.activity==Activity.CHARGE) 0 else it.stayMinutes }.toLong()
    return listOf(drive,errands,(stops-errands).coerceAtLeast(0))
}

/** Display normalisation only: keep the recogniser's original transcript as the parser input. */
internal fun clockText(text: String): String = text
    .replace(Regex("(오전|오후)\\s*(\\d{1,2})시(?:\\s*(\\d{1,2})분)?")) { match ->
        val hour=match.groupValues[2].toInt()
        val minute=match.groupValues[3].toIntOrNull() ?: 0
        if(hour !in 1..12 || minute !in 0..59) match.value else itineraryClock(LocalDateTime.of(2000,1,1,hour%12+if(match.groupValues[1]=="오후") 12 else 0,minute))
    }
    .replace(Regex("(?<![0-9])(?<!오전 )(?<!오후 )([01]?\\d|2[0-3]):([0-5]\\d)(?![0-9])")) { match ->
        itineraryClock(LocalDateTime.of(2000,1,1,match.groupValues[1].toInt(),match.groupValues[2].toInt()))
    }
