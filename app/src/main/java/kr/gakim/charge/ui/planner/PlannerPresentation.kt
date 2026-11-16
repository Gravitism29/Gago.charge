package kr.gakim.charge.ui.planner

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
internal fun plannerExamples(now: LocalDateTime): List<String> = listOf(
    shoppingExampleSentence(now),
    "병원 들렀다가 물건 수령처에서 물건 받고 갈게",
    "충전하면서 30분 밥 먹고 갈래",
)

/** 지금부터 약 2시간 30분 뒤를 다음 10분 단위로 올림한 마감. */
internal fun exampleDeadline(now: LocalDateTime): LocalDateTime {
    val target = now.plusMinutes(150).withSecond(0).withNano(0)
    return target.plusMinutes(((10 - target.minute % 10) % 10).toLong())
}

/** 파서가 알아듣는 12시간제 말투. 자정을 넘기면 '내일 오전 …'으로 말한다. */
internal fun spokenClock(time: LocalDateTime, now: LocalDateTime): String {
    val meridiem = if (time.hour < 12) "오전" else "오후"
    val hour = ((time.hour + 11) % 12) + 1
    val prefix = if (time.toLocalDate() != now.toLocalDate()) "내일 " else ""
    val minute = if (time.minute == 0) "" else " ${time.minute}분"
    return "$prefix$meridiem ${hour}시$minute"
}

internal fun shoppingExampleSentence(now: LocalDateTime) =
    "은평점에서 25분 장 보고 ${spokenClock(exampleDeadline(now), now)}까지 학교 가야 해"

/** 이름 뒤에 '로/으로'를 붙인 주 동작 문구. */
internal fun goLabel(name: String): String = withRo(name) + " 가기 →"

internal const val rangeNotice = "주행 가능 거리를 읽지 못했어요. 충전 판단 없이 시간만 비교할게요."

/** Keep the raw reading for the sources sheet; never substitute a distance inferred from SOC. */
internal fun Energy.usableRange(now: LocalDateTime): Measured<Double>? = rangeKm?.takeIf {
    it.evidence.fresh(now) && it.value.isFinite() && it.value >= 0 &&
        !(it.value == 0.0 && (socPercent?.takeIf { soc -> soc.evidence.fresh(now) }?.value ?: -1) >= 5)
}
internal val PlannerState.rangeUnknown: Boolean get() = energy.usableRange(now) == null
internal fun PlannerState.recommendedForDisplay(): Option? = recommend(options) ?: if(rangeUnknown)
    options.filter { it.mode==ChargeMode.NONE && it.selectable && !it.late && it.closingNotices.isEmpty() && it.totalMinutes!=null }.minByOrNull { it.totalMinutes!! }
    else null
internal fun ChargeMode.driverLabel() = when (this) {
    ChargeMode.AUTO -> "충전 방식 고르기"
    ChargeMode.NONE -> "충전 안 함"
    ChargeMode.TOP_UP -> "들른 김에 충전"
    ChargeMode.REQUIRED -> "충분히 충전"
}
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
internal fun clock(time: LocalDateTime?) = time?.format(timeFormat) ?: "–:––"
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
        detail = "${visit.label} ${visit.stayMinutes}분",
        slackMinutes = visit.deadline?.let { result.slackMinutes },
    )
}

/** 고른 방식이 충전할 때만 한 줄. 계산된 분과 장소만 쓰고, 모르면 그렇다고 말한다. */
internal fun Option.chargingRow(): String? {
    if (mode == ChargeMode.NONE) return null
    val id = chargeVisitId ?: return null
    val place = visits.firstOrNull { it.id == id }?.place?.name ?: "장소 미확인"
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
    question != null -> question.message
    pendingTime != null -> "오늘 일정인가요, 내일 일정인가요?"
    screen == 0 && plan.visits.all { it.placeholder } -> "어떤 일을 보고 가실까요?"
    screen == 0 && plan.visits.count { it.place == null } > 1 -> "어디에 들를지 먼저 골라 주세요."
    screen == 0 -> "${plan.visits.joinToString(" 하고 ") { it.label }}.\n이 일정으로 비교할까요?"
    screen == 1 -> "시간을 나란히 살펴보세요.\n어디로 갈까요?"
    phase == ApplyPhase.APPLIED -> "내비에 반영됐어요.\n이제 출발하셔도 돼요."
    phase == ApplyPhase.UNCONFIRMED -> if (actualRoute != null) "내비 경로가 계획과 달라요." else "아직 반영을 확인하지 못했어요."
    phase == ApplyPhase.FAILED -> "안내를 시작하지 못했어요."
    busy -> "내비에서 경로를 확인하고 있어요."
    else -> "들를 곳과 순서를 확인해 주세요."
}

internal fun PlannerState.candidateGroups(): List<List<Option>> {
    val recommended = recommendedForDisplay()?.id
    return options.groupBy { it.visits.map { v -> v.place?.id } }.values.sortedWith(
        compareBy<List<Option>> { group -> if (group.any { it.id == recommended }) 0 else 1 }
            .thenBy { group -> group.mapNotNull { it.results.lastOrNull()?.finish }.minOrNull() ?: LocalDateTime.MAX })
}
internal val PlannerState.demoMartFallback: Boolean get() =
    !searchingPlaces && options.any { o -> o.visits.any { it.activity == Activity.SHOP && it.place?.demo == true } }
internal const val martFallbackLine = "주변 마트를 불러오지 못해 시연 장소로 보여드려요"
