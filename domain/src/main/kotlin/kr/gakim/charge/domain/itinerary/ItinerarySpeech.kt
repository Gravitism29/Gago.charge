package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import kr.gakim.charge.domain.speech.KoreanTimeParser

data class CatalogEntry(val place: Place, val activities: Set<Activity>, val aliases: List<String> = listOf(place.name))
data class SpeechQuestion(val message: String, val visitId: Long? = null, val places: List<Place> = emptyList(), val timeRole: Boolean = false, val timeChoices: List<LocalDateTime> = emptyList())
data class SpeechUpdate(val plan: Itinerary, val applied: List<String>, val question: SpeechQuestion? = null)

sealed interface PlannerVoiceCommand {
    data class Proceed(val placeName: String? = null) : PlannerVoiceCommand
    data class Charge(val mode: ChargeMode) : PlannerVoiceCommand
    /** 화면에 보이는 후보를 순서로 고른다(0 = 왼쪽). 고르기만 하고 넘어가지는 않는다. */
    data class Pick(val index: Int) : PlannerVoiceCommand
    data object PickRecommended : PlannerVoiceCommand
    data object OtherPlaces : PlannerVoiceCommand
    data object Compare : PlannerVoiceCommand
    data object Confirm : PlannerVoiceCommand
    data object Back : PlannerVoiceCommand
    data object KeepDestination : PlannerVoiceCommand
    data object ReplaceDestination : PlannerVoiceCommand
}

private val yesWords = setOf("응", "네", "예", "그래", "좋아")

/** Whole-utterance commands: quoted examples, negations and plan edits never authorise a route. */
fun plannerVoiceCommand(text: String, screen: Int): PlannerVoiceCommand? {
    // "좋아요"·"들를게요"처럼 끝의 '요'만 다른 말은 같은 명령이다.
    val words = text.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), "").let { if (it.length > 1) it.removeSuffix("요") else it }
    // "네?"처럼 되묻는 말은 동의가 아니다.
    val asking = text.trim().trimEnd('.', '!', '~', ' ').endsWith("?")
    if (screen == 0) return when (words) {
        // 화면이 "이 일정으로 비교할까요?"라고 묻는다. 그 물음에 대한 대답만 받는다.
        in yesWords, "비교", "비교해", "비교해줘", "비교하자", "비교해봐", "찾아줘", "찾아봐", "보여줘", "보여봐", "그렇게해", "그렇게해줘" ->
            PlannerVoiceCommand.Compare.takeUnless { asking }
        else -> null
    }
    if (screen == 2) return when (words) {
        "들를게", "들르자", "경유", "경유할게", "추가", "추가해줘", "거기도", "거기도가자" -> PlannerVoiceCommand.KeepDestination
        "빼고", "빼고갈게", "그것만", "거기만", "거기만가자" -> PlannerVoiceCommand.ReplaceDestination
        in yesWords, "안내시작", "안내시작해", "안내시작해줘", "안내해", "안내해줘", "가자", "그래가자", "응가자", "출발", "출발해", "출발하자", "이경로로안내" ->
            PlannerVoiceCommand.Confirm.takeUnless { asking }
        "아니", "뒤로", "취소", "다른곳", "다른데", "다른곳으로", "다른곳보여줘" -> PlannerVoiceCommand.Back
        else -> null
    }
    if (screen != 1) return null
    return when (words) {
        in yesWords, "이걸로", "그걸로", "여기로", "이걸로가자", "그걸로가자", "여기로가자", "이걸로갈게", "그걸로갈게", "여기로갈게",
        "이걸로할게", "그걸로할게", "여기로할게", "이걸로해", "그걸로해", "이걸로해줘", "그걸로해줘", "네그걸로", "네이걸로", "응그걸로", "응이걸로",
        "출발", "출발해", "출발하자", "안내시작", "안내시작해", "안내시작해줘", "안내해", "안내해줘" ->
            PlannerVoiceCommand.Proceed().takeUnless { asking }
        "충전하고", "충전하고갈게", "충전하고갈래", "충전할게", "충전할래" -> PlannerVoiceCommand.Charge(ChargeMode.TOP_UP)
        "충전안해", "충전안할게", "충전안할래", "충전없이갈게", "충전없이" -> PlannerVoiceCommand.Charge(ChargeMode.NONE)
        "충분히충전", "충분히충전하고갈게", "필요한만큼충전" -> PlannerVoiceCommand.Charge(ChargeMode.REQUIRED)
        "다른곳", "다른데", "다른거", "딴데", "딴곳", "딴거", "다음", "다른곳보여줘", "다른거보여줘", "다른곳으로" -> PlannerVoiceCommand.OtherPlaces
        "뒤로", "이전", "이전으로", "일정수정", "처음으로" -> PlannerVoiceCommand.Back
        "추천", "추천대로", "추천으로", "추천한곳", "추천한곳으로" -> PlannerVoiceCommand.PickRecommended
        else -> Regex("^(첫번째|1번|일번|왼쪽|두번째|2번|이번|오른쪽)(?:걸로|거|으로|로)?$").matchEntire(words)?.groupValues?.get(1)
            ?.let { PlannerVoiceCommand.Pick(if (it in setOf("첫번째", "1번", "일번", "왼쪽")) 0 else 1) }
            ?: Regex("^(.+?)(?:으로|로)(?:가자|갈게|갈래|가줘|할게|해줘|해)?$").matchEntire(words)?.groupValues?.get(1)
                // "40분으로"·"세 시로"는 장소가 아니라 일정 수정이다.
                ?.takeUnless { it.contains("충전") || it.startsWith("아니") || it.length < 2 || it.contains(Regex("\\d")) ||
                    spokenDurations(it).isNotEmpty() || KoreanTimeParser.mentions(it).isNotEmpty() }
                ?.let { PlannerVoiceCommand.Proceed(it) }
    }
}

/** 말한 길이 하나. [anchor]는 정정("30분, 아니 40분")하기 전 처음 말한 자리로, 가까운 방문을 찾는 데 쓴다. */
data class SpokenDuration(val range: IntRange, val minutes: Int, val anchor: IntRange = range)

private val nativeHourCounts = mapOf("열한" to 11, "열두" to 12, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7,
    "여섯" to 6, "다섯" to 5, "네" to 4, "세" to 3, "두" to 2, "한" to 1)
private const val SINO = "[일이삼사오육칠팔구십]{1,3}"
private fun countOf(token: String): Int? = token.toIntOrNull() ?: KoreanTimeParser.sinoNumber(token)

/**
 * "30분", "삼십 분", "한 시간 반", "반 시간", "2시간 10분" 같은 길이 표현. 원문 위치를 그대로 돌려주므로
 * 방문 이름과의 거리 계산이 원문 좌표에서 이루어진다. 시각("세 시 삼십 분")과 "…분까지"는 길이로 보지 않는다.
 */
internal fun spokenDurations(text: String): List<SpokenDuration> {
    val hours = Regex("(?:(\\d+)|(?<![가-힣])(열한|열두|열|아홉|여덟|일곱|여섯|다섯|네|세|두|한))\\s*시간(?:\\s*(\\d+|$SINO)\\s*분|\\s*(반))?")
        .findAll(text).mapNotNull { m ->
            val h = m.groupValues[1].toIntOrNull() ?: nativeHourCounts[m.groupValues[2]] ?: return@mapNotNull null
            val extra = when {
                m.groupValues[4].isNotEmpty() -> 30
                m.groupValues[3].isNotEmpty() -> countOf(m.groupValues[3]) ?: return@mapNotNull null
                else -> 0
            }
            SpokenDuration(m.range, h * 60 + extra)
        }.toList()
    val halfHour = Regex("(?<![가-힣])반\\s*시간").findAll(text).map { SpokenDuration(it.range, 30) }
        .filter { h -> hours.none { h.range.first in it.range } }.toList()
    val minutes = Regex("(?:(\\d+)|(?<![가-힣])($SINO))\\s*분(?![들께해하석류명야노위])").findAll(text).filter { m ->
        val before = text.substring(0, m.range.first).trimEnd()
        !before.endsWith("시") && !text.substring(m.range.last + 1).trimStart().startsWith("까지") &&
            (hours + halfHour).none { m.range.first in it.range }
    }.mapNotNull { m ->
        val digits = m.groupValues[1]
        val n = digits.toIntOrNull() ?: KoreanTimeParser.sinoNumber(m.groupValues[2]) ?: return@mapNotNull null
        // "이 분", "일 분"은 길이보다 다른 말일 때가 많다.
        if (digits.isEmpty() && m.groupValues[2].length == 1 && n < 5) null else SpokenDuration(m.range, n)
    }.toList()
    return (hours + halfHour + minutes).sortedBy { it.range.first }
}

data class SpokenPlaceMention(val position: Int, val text: String, val destination: Boolean)

/** "돌아가야 돼"·"빨리 가야 해"의 앞말은 장소 이름이 아니다. */
private val notPlaceWords = setOf("들어", "돌아", "다시", "빨리", "일찍", "얼른", "먼저", "그냥", "꼭", "이제", "지금", "곧")

/** Clock masking preserves offsets, so place and activity anchors still share one coordinate system. */
fun spokenPlaceMentions(text: String): List<SpokenPlaceMention> {
    val masked = KoreanTimeParser.maskTimeExpressions(text)
        .replace(Regex("오늘|내일|오전|오후")) { " ".repeat(it.value.length) }
    val stores = Regex("([가-힣A-Za-z0-9]+(?:\\s+[가-힣A-Za-z0-9]+)?)에서").findAll(masked).map { m ->
        val words = m.groupValues[1].split(Regex("\\s+"))
        val name = if (words.size > 1 && words.first() in listOf("이마트", "홈플러스", "롯데마트", "코스트코", "트레이더스"))
            words.joinToString(" ") else words.last()
        SpokenPlaceMention(m.range.first + m.groupValues[1].lastIndexOf(name), name, false)
    }
    val destinations = Regex("([가-힣A-Za-z0-9]+?)(?:까지|에|으로|로)?\\s*(?:까지\\s*)?(?:(?:데리러|픽업|태우러|받으러)\\s*)?(?:(?:들어|돌아)\\s*)?(?:가야\\s*(?:해|돼)|도착해야\\s*(?:해|돼))")
        .findAll(masked).filter { it.groupValues[1] !in notPlaceWords }.map {
            val name = it.groupValues[1]
            // An added activity ("병원도") keeps its existing activity inference.
            val addedActivity = name in setOf("병원도", "식당도", "마트도")
            SpokenPlaceMention(it.range.first, if (addedActivity) name.removeSuffix("도") else name, !addedActivity)
        }.toList()
    // "…세 시까지 집", "집으로 갈게", "집 도착": 집·회사·학교는 '가야 해' 없이도 도착지다.
    val homes = Regex("(?<![가-힣A-Za-z0-9])(집|회사|학교)(?:으로|로|에|까지)?\\s*(?:(?:들어|돌아)\\s*)?(?=도착|갈게|갈래|가자|가려고|갈\\s*거|들어갈|돌아갈|$|[,.!?])")
        .findAll(masked).filter { m -> destinations.none { m.range.first in it.position until it.position + it.text.length + 1 } }
        .map { SpokenPlaceMention(it.range.first, it.groupValues[1], true) }
    return (stores + destinations + homes).sortedBy { it.position }.toList()
}

/** A final transcript produces a patch over the existing plan. Missing fields never erase user input. */
fun interpretItinerary(text: String, current: Itinerary, catalog: List<CatalogEntry>, now: LocalDateTime,
                       focusedVisitId: Long? = null, remotePlaces: Boolean = false): SpeechUpdate {
    var plan = current
    val applied = mutableListOf<String>()
    var question: SpeechQuestion? = null
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    // Place-only edits on the editor target that visit, including remote names awaiting resolution.
    val placeCorrection=Regex("^([가-힣A-Za-z][가-힣A-Za-z0-9 ]{1,30}?)(?:으로|로)$").matchEntire(normalized)
        ?.groupValues?.get(1)?.trim()?.takeIf {
            KoreanTimeParser.mentions(normalized).isEmpty() && !it.startsWith("아니") &&
                !Regex("\\d+\\s*(?:시간|분)|충전|시간\\s*제약").containsMatchIn(normalized) && spokenDurations(normalized).isEmpty()
        }
    val focusedVisit=current.visits.firstOrNull { it.id==focusedVisitId }
    if(placeCorrection!=null && focusedVisit!=null) {
        val matches=catalog.filter { placeCorrection in it.aliases || placeCorrection==it.place.name }.distinctBy { it.place.id }
        if(matches.size>1) return SpeechUpdate(plan,emptyList(),SpeechQuestion("어느 ${placeCorrection}인가요?",focusedVisit.id,matches.map { it.place }))
        val place=matches.singleOrNull()?.place
        if(place==null && !remotePlaces) return SpeechUpdate(plan,emptyList(),SpeechQuestion("장소 이름을 다시 확인해 주세요.",focusedVisit.id))
        return SpeechUpdate(plan.copy(visits=plan.visits.map { if(it.id==focusedVisit.id) it.copy(place=place,spokenPlace=placeCorrection,placeholder=false) else it }),listOf("${focusedVisit.label} 장소 $placeCorrection"))
    }
    val correctionOnly = Regex("^(아니|정정|시간은|도착은)").containsMatchIn(normalized)
    val spoken = spokenPlaceMentions(normalized)
    val destination = spoken.lastOrNull { it.destination }
    val pickup = Regex("픽업|데리러|태우러|받으러").containsMatchIn(normalized)
    val activityPatterns = listOf(Activity.SHOP to Regex("장\\s*보|장\\s*봐|마트|홈플러스|코스트코|트레이더스|다이소"),
        Activity.MEAL to Regex("식사|밥\\s*먹|식당|(?:점심|저녁|아침)\\s*(?:을\\s*|은\\s*)?(?:먹|식사)"),
        Activity.COLLECT to Regex("물건|수령|택배"), Activity.HOSPITAL to Regex("병원"), Activity.PICKUP to Regex("픽업|데리러|태우러|받으러"),
        Activity.CHARGE to Regex("충전소만|충전\\s*할\\s*곳|충전소\\s*찾"))
    // "마트는 안 들러도 돼", "마트 빼 줘": 말한 볼일을 하지 않겠다는 뜻이다.
    val negatedTail = Regex("^\\s*(?:지\\s*(?:말|않)|[은는도]?\\s*말고|[은는도을를]?\\s*(?:안\\s*(?:들|가|갈|봐|볼|해|할)|빼|제외|생략|취소|필요\\s*없))")
    val activities = activityPatterns
        .mapNotNull { (kind, regex) -> regex.find(normalized)?.takeUnless { match ->
            val tail = normalized.substring(match.range.last+1)
            negatedTail.containsMatchIn(tail) ||
                destination?.let { match.range.first in it.position until it.position + it.text.length } == true
        }?.let { Triple(it.range.first, kind, it.value) } }
        .plus(if (destination != null && !pickup) listOf(Triple(destination.position, Activity.ARRIVE, destination.text)) else emptyList())
        .sortedBy { it.first }

    // A deadline belongs to one visit. Do not interpret this correction as a new activity or place.
    val noDeadline = Regex("시간\\s*(제약|제한)\\s*없|정해진\\s*(시각|시간)\\s*없|몇\\s*시든|아무\\s*때나|시간은?\\s*상관\\s*없").containsMatchIn(normalized)
    if (noDeadline && (plan.visits.any { it.deadline != null } || activities.isEmpty() || correctionOnly || focusedVisitId != null)) {
        val all = Regex("모든|전체").containsMatchIn(normalized)
        val focusedVisit = plan.visits.firstOrNull { it.id == focusedVisitId }
        val namedPlaces = plan.visits.filter { visit ->
            val placeName = visit.place?.name
            (placeName != null && placeName.length >= 2 && normalized.contains(placeName)) ||
                (visit.customLabel.isNotBlank() && normalized.contains(visit.customLabel))
        }
        val named = if (namedPlaces.isNotEmpty()) namedPlaces else
            plan.visits.filter { visit -> activities.any { it.second == visit.activity } }
        val withDeadline = plan.visits.filter { it.deadline != null }
        val target = focusedVisit ?: named.singleOrNull() ?: if (named.isEmpty()) withDeadline.singleOrNull() else null
        if (!all && target == null && (named.size > 1 || withDeadline.size > 1)) {
            return SpeechUpdate(plan, applied, SpeechQuestion("어느 방문의 시간 제약을 없앨까요? 해당 방문에서 직접 수정해 주세요."))
        }
        if (all) {
            plan = plan.copy(visits = plan.visits.map { it.copy(deadline = null) })
            applied += "모든 방문 정해진 시각 없음"
        } else if (target != null) {
            plan = plan.copy(visits = plan.visits.map { if (it.id == target.id) it.copy(deadline = null) else it })
            applied += "${target.place?.name ?: target.label} 정해진 시각 없음"
        } else if (plan.visits.size == 1) {
            applied += "정해진 시각 없음"
        }
        return SpeechUpdate(plan, applied)
    }

    var focused = plan.visits.indexOfFirst { it.id == focusedVisitId }.takeIf { it >= 0 } ?: 0
    if (!correctionOnly && activities.isNotEmpty()) {
        val kind = activities.first().second
        val matches = plan.visits.withIndex().filter { !it.value.placeholder && it.value.activity == kind }
        val namedMatches = matches.filter { it.value.place?.name?.let { name -> normalized.contains(name) } == true }
        val selected = matches.firstOrNull { it.value.id == focusedVisitId }
            ?: namedMatches.singleOrNull() ?: matches.singleOrNull()
        when {
            selected != null -> focused = selected.index
            matches.size > 1 -> return SpeechUpdate(plan, applied, SpeechQuestion("어느 ${kind.label} 방문인지 화면에서 선택해 주세요."))
            plan.visits[focused].placeholder -> {
                val visits = plan.visits.toMutableList()
                visits[focused] = visits[focused].copy(activity = kind, customLabel = "", placeholder = false,
                    stayMinutes = if (kind == Activity.ARRIVE) 0 else visits[focused].stayMinutes)
                plan = plan.copy(visits = visits)
            }
            plan.visits.size < 3 -> {
                plan = plan.copy(visits = plan.visits + Visit((plan.visits.maxOfOrNull { it.id } ?: 0) + 1, activity = kind,
                    stayMinutes = if (kind == Activity.ARRIVE) 0 else 20))
                focused = plan.visits.lastIndex
            }
            else -> return SpeechUpdate(plan, applied, SpeechQuestion("방문지는 최대 세 곳입니다. 방문 순서를 먼저 정리해 주세요."))
        }
        if (kind == Activity.CHARGE && plan.chargeMode == ChargeMode.AUTO) {
            plan = plan.copy(chargeMode = ChargeMode.TOP_UP)
        }
        applied += "할 일 ${kind.label}"
    }
    // Resolve named places against actual saved places and explicitly labelled demo entries.
    val mentions = (if (remotePlaces) emptyList() else catalog).mapNotNull { entry ->
        entry.aliases.filter { it.length >= 2 }.mapNotNull { alias ->
            normalized.indexOf(alias).takeIf { it >= 0 && !negatedTail.containsMatchIn(normalized.substring(it + alias.length)) }
                ?.let { Pair(it, alias) }
        }.minByOrNull { it.first }?.let { Triple(it.first, it.second, entry) }
    }.groupBy { it.first }.toSortedMap()
    for ((_, samePosition) in mentions) {
        val longest = samePosition.maxOf { it.second.length }
        val entries = samePosition.filter { it.second.length == longest }.map { it.third }.distinctBy { it.place.id }
        val existingPlace = plan.visits.mapNotNull { it.place }.firstOrNull { p -> entries.any { it.place.id == p.id } }
        val entry = if (entries.size == 1) entries.first() else entries.firstOrNull { it.place.id == existingPlace?.id }
        val catalogKind = (entry ?: entries.first()).activities.firstOrNull() ?: plan.visits[focused].activity
        val kind = if (!pickup && (destination?.position == samePosition.first().first || catalogKind == Activity.PICKUP))
            Activity.ARRIVE else catalogKind
        var target = plan.visits.indexOfFirst { it.place?.id == entry?.place?.id && entry != null }
        if (target < 0) {
            val matching = plan.visits.withIndex().filter { it.value.activity == kind && it.value.place == null }
            val preferred = matching.firstOrNull { it.value.id == focusedVisitId }
                ?: matching.singleOrNull() ?: matching.firstOrNull { it.index == focused && matching.size == 1 }
            if (matching.size > 1 && preferred == null) {
                question = SpeechQuestion("어느 ${kind.label} 방문의 장소인지 화면에서 선택해 주세요.")
                continue
            }
            target = preferred?.index ?: -1
        }
        if (target < 0 && plan.visits.size < 3) {
            plan = plan.copy(visits = plan.visits + Visit((plan.visits.maxOfOrNull { it.id } ?: 0) + 1, activity = kind,
                stayMinutes = if (kind == Activity.ARRIVE) 0 else 20))
            target = plan.visits.lastIndex
        }
        if (target < 0) { question = SpeechQuestion("방문지는 최대 세 곳입니다. 방문 순서를 먼저 정리해 주세요."); continue }
        if (kind == activities.firstOrNull()?.second) focused = target
        if (entry == null) {
            question = SpeechQuestion("어느 ${samePosition.first().second}인가요?", plan.visits[target].id, entries.map { it.place })
        } else {
            val visits = plan.visits.toMutableList()
            visits[target] = visits[target].copy(place = entry.place, placeholder = false)
            plan = plan.copy(visits = visits)
            applied += "${visits[target].label} 장소 ${entry.place.name}"
        }
    }
    if (!remotePlaces) Regex("([가-힣A-Za-z0-9]+점)에서").find(normalized)?.groupValues?.get(1)?.let { requested ->
        if(catalog.none { entry -> entry.aliases.any { it == requested } }) {
            question=SpeechQuestion("${requested}의 좌표를 찾지 못했어요. 해당 장소를 화면에서 선택해 주세요.",plan.visits[focused].id)
        }
    }
    if (!correctionOnly) {
        for ((_, kind, _) in activities.drop(1)) {
            if (plan.visits.none { it.activity == kind }) {
                if (plan.visits.size < 3) {
                    plan = plan.copy(visits = plan.visits + Visit(plan.visits.maxOf { it.id } + 1, kind,
                        stayMinutes = if (kind == Activity.ARRIVE) 0 else 20))
                    applied += "${kind.label} 추가"
                } else if (question == null) {
                    question = SpeechQuestion("방문지는 최대 세 곳입니다. 방문 순서를 먼저 정리해 주세요.")
                }
            }
        }
    }
    if (remotePlaces && !correctionOnly) {
        val assigned = mutableSetOf<Long>()
        for (mention in spoken) {
            val kind = if (mention.destination) { if (pickup) Activity.PICKUP else Activity.ARRIVE }
                else activities.lastOrNull { it.first <= mention.position }?.second
                    ?: activities.firstOrNull()?.second ?: plan.visits[focused].activity
            var target = plan.visits.indexOfFirst { it.activity == kind && it.id !in assigned }
            if (target < 0 && plan.visits.size < 3) {
                plan = plan.copy(visits = plan.visits + Visit(plan.visits.maxOf { it.id } + 1, kind))
                target = plan.visits.lastIndex
            }
            if (target >= 0) {
                assigned += plan.visits[target].id
                val saved = catalog.filter { entry -> entry.aliases.any { it.replace(" ", "") == mention.text.replace(" ", "") } }
                    .map { it.place }.distinctBy { it.id }.singleOrNull()
                plan = plan.copy(visits = plan.visits.mapIndexed { i, v -> if (i == target)
                    v.copy(place = saved, spokenPlace = mention.text, placeholder = false) else v })
            }
        }
    }
    // A single clock minute is not a stay. Keep the range to associate a stay with its nearby visit.
    val allDurations = spokenDurations(normalized)
    // "2시간 안에 집 가야 해": 머무는 시간이 아니라 지금부터 잰 도착 마감이다.
    val withinPattern = Regex("^\\s*(?:안에|안으로|이내|내에|내로)")
    val within = allDurations.filter { withinPattern.containsMatchIn(normalized.substring(it.range.last + 1)) }
    // "30분, 아니 40분": 정정 뒤의 값만 남기고, 가까운 방문은 처음 말한 자리로 찾는다.
    val durationMatches = mutableListOf<SpokenDuration>()
    for (d in allDurations - within.toSet()) {
        val previous = durationMatches.lastOrNull()
        if (previous != null && Regex("아니|말고|정정").containsMatchIn(normalized.substring(previous.range.last + 1, d.range.first))) {
            durationMatches[durationMatches.lastIndex] = d.copy(anchor = previous.anchor)
        } else durationMatches += d
    }
    if (durationMatches.size > 1) {
        return SpeechUpdate(plan, applied, question ?: SpeechQuestion("체류 시간이 여러 개 들렸어요. 각 방문의 시간을 화면에서 확인해 주세요."))
    }
    val durationMatch = durationMatches.singleOrNull()
    val duration = durationMatch?.minutes
    if (duration != null && durationMatch != null) {
        val suffix = normalized.substring(durationMatch.range.last + 1).trimStart()
        if (Regex("^(뒤|후|전|뒤에|후에|전에)").containsMatchIn(suffix)) {
            return SpeechUpdate(plan, applied, question ?: SpeechQuestion("말씀하신 시간이 체류 시간인지 확인해 주세요."))
        }
        // Mention anchors are mapped to existing visit IDs. Equidistant or duplicate targets are not guessed.
        val anchors = mutableListOf<Pair<IntRange, Int>>()
        for ((position, kind, word) in activities) {
            plan.visits.forEachIndexed { index, visit ->
                if (visit.activity == kind) anchors += (position until position + word.length) to index
            }
        }
        for ((position, entries) in mentions) {
            for ((_, alias, entry) in entries) {
                plan.visits.forEachIndexed { index, visit ->
                    if (visit.place?.id == entry.place.id) anchors += (position until position + alias.length) to index
                }
            }
        }
        for (mention in spoken) {
            plan.visits.forEachIndexed { index, visit ->
                if (visit.spokenPlace == mention.text)
                    anchors += (mention.position until mention.position + mention.text.length) to index
            }
        }
        val spokenAt = durationMatch.anchor
        fun gap(range: IntRange): Int = when {
            range.last < spokenAt.first -> spokenAt.first - range.last - 1
            range.first > spokenAt.last -> range.first - spokenAt.last - 1
            else -> 0
        }
        val nearestGap = anchors.minOfOrNull { gap(it.first) }
        val nearest = anchors.filter { gap(it.first) == nearestGap }.map { it.second }.distinct()
        val explicitFocus = plan.visits.indexOfFirst { it.id == focusedVisitId }.takeIf { it >= 0 }
        val target = when {
            nearestGap != null && nearestGap <= 5 && nearest.size == 1 -> nearest.single()
            nearestGap != null && nearestGap <= 5 && nearest.size > 1 -> null
            explicitFocus != null -> explicitFocus
            plan.visits.size == 1 -> 0
            else -> null
        }
        if (target == null) {
            return SpeechUpdate(plan, applied, question ?: SpeechQuestion("어느 방문의 체류 시간인지 화면에서 선택해 주세요."))
        }
        if (duration !in 0..720) question = SpeechQuestion("머무는 시간은 0분부터 12시간 사이로 정해 주세요.")
        else {
            val visits = plan.visits.toMutableList()
            visits[target] = visits[target].copy(stayMinutes = duration, placeholder = false)
            plan = plan.copy(visits = visits)
            applied += "${visits[target].label} ${duration}분"
        }
    }
    val allTimeMentions = KoreanTimeParser.mentions(normalized)
    val timeMention = allTimeMentions.lastOrNull()
    val departureRole = Regex("출발").containsMatchIn(normalized)
    // "오후 세 시 전에"도 그 시각까지 도착한다는 뜻이다.
    val explicitArrivalRole = Regex("까지|도착|가야|시\\s*(?:반\\s*)?이?전(?:에|엔|으로)").containsMatchIn(normalized)
    val arrivalRole = explicitArrivalRole || correctionOnly && current.visits.any { it.deadline != null }
    // Until role-to-clock association is reliable, never offer mixed-role clocks as one role's choices.
    if (departureRole && explicitArrivalRole && allTimeMentions.size > 1) {
        return SpeechUpdate(plan, applied, SpeechQuestion("출발 시각과 도착 마감을 각각 확인해 주세요."))
    }
    if (departureRole && allTimeMentions.size > 1) {
        // Multiple clocks may share a departure role only when the entire remaining
        // sentence consists of departure, particles, correction words and punctuation.
        val remaining = KoreanTimeParser.maskTimeExpressions(normalized)
        val onlyDepartureSyntax = Regex("^(?:\\s|[,.!?~]|출발|아니|정정|말고|으로|은|는|에|로)*$").matches(remaining)
        if (!onlyDepartureSyntax) {
            return SpeechUpdate(plan, applied, SpeechQuestion("출발 시각과 다른 시각의 역할을 각각 확인해 주세요."))
        }
    }
    val explicitTimeTargets = plan.visits.withIndex().filter { indexed ->
        indexed.value.id == focusedVisitId || indexed.value.place?.name?.let { name -> normalized.contains(name) } == true
    }
    val deadlineTargets = plan.visits.withIndex().filter { it.value.deadline != null }
    if (timeMention != null && !departureRole && correctionOnly && explicitTimeTargets.size != 1 && deadlineTargets.size > 1) {
        return SpeechUpdate(plan, applied, SpeechQuestion("어느 방문의 도착 마감인지 화면에서 선택해 주세요."))
    }
    // "…○○까지 △△ 가야 해": 마감은 도착지(△△)의 것이다. 문장에 이름이 나온 다른 방문(저장된 장소로 묶인 마트 등)에 붙이지 않는다.
    val destinationTarget = destination?.takeUnless { correctionOnly }
        ?.let { d -> plan.visits.indexOfLast { it.spokenPlace == d.text }.takeIf { it >= 0 }
            ?: plan.visits.indexOfLast { it.activity == (if (pickup) Activity.PICKUP else Activity.ARRIVE) }.takeIf { it >= 0 } }
    val timeTarget = destinationTarget ?: explicitTimeTargets.singleOrNull()?.index
        ?: deadlineTargets.singleOrNull()?.index?.takeIf { correctionOnly }
        ?: plan.visits.lastIndex
    if (timeMention != null) {
        if (!departureRole && !arrivalRole) question = SpeechQuestion("말씀하신 시각은 출발 시각인가요, 도착 마감인가요?", plan.visits[timeTarget].id, timeRole = true)
        else {
            val previous = if (departureRole) current.departure else current.visits.getOrNull(timeTarget)?.deadline
            val reference = if (correctionOnly && previous != null) previous.hour * 60 - 1 else now.hour * 60 + now.minute
            val alternatives=allTimeMentions.mapIndexedNotNull { i, mention ->
                val part=normalized.substring(mention.position,allTimeMentions.getOrNull(i+1)?.position ?: normalized.length)
                KoreanTimeParser.parse(part,reference)
            }.distinct()
            val betweenCorrection=allTimeMentions.size>1 && Regex("아니|정정|말고").containsMatchIn(normalized.substring(allTimeMentions.first().position+1,allTimeMentions.last().position))
            if(alternatives.size>1 && !betweenCorrection) {
                val referenceDate = if (departureRole) current.departure?.toLocalDate() else previous?.toLocalDate()
                val date=when {
                    normalized.contains("내일") -> now.toLocalDate().plusDays(1)
                    normalized.contains("오늘") -> now.toLocalDate()
                    correctionOnly && referenceDate != null -> referenceDate
                    else -> now.toLocalDate()
                }
                question=SpeechQuestion(if (departureRole) "서로 다른 시각이 들렸어요. 출발 시각만 골라 주세요." else "서로 다른 시각이 들렸어요. 도착 마감만 골라 주세요.",
                    if (departureRole) null else plan.visits[timeTarget].id,
                    timeChoices=alternatives.map { date.atStartOfDay().plusMinutes(it.toLong()) })
                return SpeechUpdate(plan,applied.distinct(),question)
            }
            val clock = KoreanTimeParser.parse(normalized, reference)
            if (clock != null) {
                val date = when {
                    normalized.contains("내일") -> now.toLocalDate().plusDays(1)
                    normalized.contains("오늘") -> now.toLocalDate()
                    correctionOnly && previous != null -> previous.toLocalDate()
                    else -> now.toLocalDate()
                }
                val at = date.atStartOfDay().plusMinutes(clock.toLong())
                if (departureRole) { plan = plan.copy(departure = at); applied += "출발 ${itineraryClock(at)}" }
                else {
                    val visits = plan.visits.toMutableList()
                    visits[timeTarget] = visits[timeTarget].copy(deadline = at, placeholder = false)
                    plan = plan.copy(visits = visits)
                    applied += "${visits[timeTarget].place?.name ?: visits[timeTarget].label} 도착 마감 ${itineraryClock(at)}"
                }
            }
        }
    }
    if (within.isNotEmpty()) {
        if (timeMention != null || within.size > 1 || departureRole) {
            if (question == null) question = SpeechQuestion("도착 마감 시각을 한 번만 말씀해 주세요.", plan.visits[timeTarget].id)
        } else {
            val at = now.withSecond(0).withNano(0).plusMinutes(within.single().minutes.toLong())
            val visits = plan.visits.toMutableList()
            visits[timeTarget] = visits[timeTarget].copy(deadline = at, placeholder = false)
            plan = plan.copy(visits = visits)
            applied += "${visits[timeTarget].place?.name ?: visits[timeTarget].label} 도착 마감 ${itineraryClock(at)}"
        }
    }
    // 강한 표현(꼭·해야)을 먼저 보고, '안'·'조금'은 '충전' 바로 곁에 있을 때만 충전 방식으로 읽는다.
    when {
        Regex("필요한\\s*양|충분히\\s*충전|꼭\\s*충전|충전\\s*[은을는]?\\s*꼭|충전\\s*[은을는]?\\s*해야|충전\\s*[은을]?\\s*안\\s*하면\\s*안").containsMatchIn(normalized) ->
            { plan = plan.copy(chargeMode = ChargeMode.REQUIRED); applied += "필요한 양까지 충전" }
        Regex("충전(?:소)?\\s*[은을는도]?\\s*(?:이번엔\\s*|이번에는\\s*)?(?:안\\s*(?:해|할|하|들|가)|없이|빼|하지\\s*마|필요\\s*없|생략)").containsMatchIn(normalized) ->
            { plan = plan.copy(chargeMode = ChargeMode.NONE); applied += "충전 안 함" }
        Regex("보충|조금\\s*(?:만\\s*)?충전|충전\\s*[은을]?\\s*조금|충전도|충전.*(하면서|동안)").containsMatchIn(normalized) ->
            { plan = plan.copy(chargeMode = ChargeMode.TOP_UP); applied += "볼일 중 충전" }
    }
    return SpeechUpdate(plan, applied.distinct(), question)
}
