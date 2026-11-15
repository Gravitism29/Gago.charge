package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime
import kr.gakim.charge.domain.speech.KoreanTimeParser

data class CatalogEntry(val place: Place, val activities: Set<Activity>, val aliases: List<String> = listOf(place.name))
data class SpeechQuestion(val message: String, val visitId: Long? = null, val places: List<Place> = emptyList(), val timeRole: Boolean = false, val timeChoices: List<LocalDateTime> = emptyList())
data class SpeechUpdate(val plan: Itinerary, val applied: List<String>, val question: SpeechQuestion? = null)

/** A final transcript produces a patch over the existing plan. Missing fields never erase user input. */
fun interpretItinerary(text: String, current: Itinerary, catalog: List<CatalogEntry>, now: LocalDateTime,
                       focusedVisitId: Long? = null): SpeechUpdate {
    var plan = current
    val applied = mutableListOf<String>()
    var question: SpeechQuestion? = null
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val correctionOnly = Regex("^(아니|정정|시간은|도착은)").containsMatchIn(normalized)
    val activityPatterns = listOf(Activity.SHOP to Regex("장\\s*보|장\\s*봐|마트"), Activity.MEAL to Regex("식사|밥\\s*먹|식당"),
        Activity.COLLECT to Regex("물건|수령|택배"), Activity.HOSPITAL to Regex("병원"), Activity.PICKUP to Regex("학교|픽업|데리러|태우러"),
        Activity.CHARGE to Regex("충전소만|충전\\s*할\\s*곳|충전소\\s*찾"))
    val activities = activityPatterns
        .mapNotNull { (kind, regex) -> regex.find(normalized)?.takeUnless { match ->
            val tail = normalized.substring(match.range.last+1)
            Regex("^\\s*(지\\s*(말|않)|는?\\s*말고)").containsMatchIn(tail)
        }?.let { Triple(it.range.first, kind, it.value) } }.sortedBy { it.first }

    // A deadline belongs to one visit. Do not interpret this correction as a new activity or place.
    val noDeadline = Regex("시간\\s*(제약|제한)\\s*없|몇\\s*시든|아무\\s*때나|시간은?\\s*상관\\s*없").containsMatchIn(normalized)
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
            applied += "모든 방문 시간 제약 없음"
        } else if (target != null) {
            plan = plan.copy(visits = plan.visits.map { if (it.id == target.id) it.copy(deadline = null) else it })
            applied += "${target.place?.name ?: target.label} 시간 제약 없음"
        } else if (plan.visits.size == 1) {
            applied += "시간 제약 없음"
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
                visits[focused] = visits[focused].copy(activity = kind, customLabel = "", placeholder = false)
                plan = plan.copy(visits = visits)
            }
            plan.visits.size < 3 -> {
                plan = plan.copy(visits = plan.visits + Visit((plan.visits.maxOfOrNull { it.id } ?: 0) + 1, activity = kind))
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
    val mentions = catalog.mapNotNull { entry ->
        entry.aliases.filter { it.length >= 2 }.mapNotNull { alias ->
            normalized.indexOf(alias).takeIf { it >= 0 }?.let { Pair(it, alias) }
        }.minByOrNull { it.first }?.let { Triple(it.first, it.second, entry) }
    }.groupBy { it.first }.toSortedMap()
    for ((_, samePosition) in mentions) {
        val longest = samePosition.maxOf { it.second.length }
        val entries = samePosition.filter { it.second.length == longest }.map { it.third }.distinctBy { it.place.id }
        val existingPlace = plan.visits.mapNotNull { it.place }.firstOrNull { p -> entries.any { it.place.id == p.id } }
        val entry = if (entries.size == 1) entries.first() else entries.firstOrNull { it.place.id == existingPlace?.id }
        val kind = (entry ?: entries.first()).activities.firstOrNull() ?: plan.visits[focused].activity
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
            plan = plan.copy(visits = plan.visits + Visit((plan.visits.maxOfOrNull { it.id } ?: 0) + 1, activity = kind))
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
    Regex("([가-힣A-Za-z0-9]+점)에서").find(normalized)?.groupValues?.get(1)?.let { requested ->
        if(catalog.none { entry -> entry.aliases.any { it == requested } }) {
            question=SpeechQuestion("${requested}의 좌표를 찾지 못했어요. 해당 장소를 화면에서 선택해 주세요.",plan.visits[focused].id)
        }
    }
    if (!correctionOnly) {
        for ((_, kind, _) in activities.drop(1)) {
            if (plan.visits.none { it.activity == kind }) {
                if (plan.visits.size < 3) {
                    plan = plan.copy(visits = plan.visits + Visit(plan.visits.maxOf { it.id } + 1, kind))
                    applied += "${kind.label} 추가"
                } else if (question == null) {
                    question = SpeechQuestion("방문지는 최대 세 곳입니다. 방문 순서를 먼저 정리해 주세요.")
                }
            }
        }
    }
    // A single clock minute is not a stay. Keep the range to associate a stay with its nearby visit.
    val hourDurations = Regex("(\\d+)\\s*시간(?:\\s*(\\d+)\\s*분|\\s*(반))?").findAll(normalized).toList()
    val minuteDurations = Regex("(\\d+)\\s*분").findAll(normalized).filter { m ->
        val before = normalized.substring(0, m.range.first).trimEnd()
        !before.endsWith("시") && !normalized.substring(m.range.last + 1).trimStart().startsWith("까지") &&
            hourDurations.none { m.range.first in it.range }
    }.toList()
    val durationMatches = (hourDurations + minuteDurations).sortedBy { it.range.first }
    if (durationMatches.size > 1) {
        return SpeechUpdate(plan, applied, question ?: SpeechQuestion("체류 시간이 여러 개 들렸어요. 각 방문의 시간을 화면에서 확인해 주세요."))
    }
    val durationMatch = durationMatches.singleOrNull()
    val duration = durationMatch?.let { m ->
        if (m.groupValues.size > 2) m.groupValues[1].toIntOrNull()?.times(60)?.plus(
            if (m.groupValues[3].isNotEmpty()) 30 else m.groupValues[2].toIntOrNull() ?: 0)
        else m.groupValues[1].toIntOrNull()
    }
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
        fun gap(range: IntRange): Int = when {
            range.last < durationMatch.range.first -> durationMatch.range.first - range.last - 1
            range.first > durationMatch.range.last -> range.first - durationMatch.range.last - 1
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
    val explicitArrivalRole = Regex("까지|도착|가야").containsMatchIn(normalized)
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
    val timeTarget = explicitTimeTargets.singleOrNull()?.index
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
                if (departureRole) { plan = plan.copy(departure = at); applied += "출발 ${at.toLocalTime()}" }
                else {
                    val visits = plan.visits.toMutableList()
                    visits[timeTarget] = visits[timeTarget].copy(deadline = at, placeholder = false)
                    plan = plan.copy(visits = visits)
                    applied += "${visits[timeTarget].place?.name ?: visits[timeTarget].label} 도착 마감 ${at.toLocalTime()}"
                }
            }
        }
    }
    when {
        Regex("충전.*(안|없이|빼|하지\\s*마)").containsMatchIn(normalized) -> { plan = plan.copy(chargeMode = ChargeMode.NONE); applied += "충전 안 함" }
        Regex("필요한\\s*양|충분히\\s*충전|꼭\\s*충전").containsMatchIn(normalized) -> { plan = plan.copy(chargeMode = ChargeMode.REQUIRED); applied += "필요한 양까지 충전" }
        Regex("보충|조금|충전도|충전.*(하면서|동안)").containsMatchIn(normalized) -> { plan = plan.copy(chargeMode = ChargeMode.TOP_UP); applied += "볼일 중 충전" }
    }
    return SpeechUpdate(plan, applied.distinct(), question)
}
