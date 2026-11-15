package kr.gakim.charge.ui.state

import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.MartOption
import kr.gakim.charge.domain.RouteAction
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.domain.VerifyResult

/* 화면이 쓰는 읽기 전용 표시 계산. 판단 규칙은 :domain 에 있고, 여기서는 이미 계산된 값을 화면용으로 정리만 한다. */

/** 3:52 형태(12시간제). */
fun clockText(minutes: Int): String {
    val m = ((minutes % 1440) + 1440) % 1440
    return "${((m / 60 + 11) % 12) + 1}:${(m % 60).toString().padStart(2, '0')}"
}

/** 화면에 쓰는 짧은 장소 이름: '(시연)' 꼬리표는 상단 출처 표시가 대신한다. */
fun shortName(name: String): String = name.replace(Regex("\\s*\\(시연\\)"), "").trim()

/** 마트 한 곳의 카드. [shopOnly]/[charge] 는 [UiState.options] 안의 위치. */
data class MartCard(val martId: String, val name: String, val shopOnly: Int?, val charge: Int?)

fun martCards(s: UiState): List<MartCard> =
    s.options.withIndex().groupBy { it.value.option.candidate.id }.values.map { entries ->
        MartCard(
            martId = entries.first().value.option.candidate.id,
            name = shortName(entries.first().value.place.name),
            shopOnly = entries.firstOrNull { it.value.option.mode == StopMode.SHOP_ONLY }?.index,
            charge = entries.firstOrNull { it.value.option.mode == StopMode.SHOP_AND_CHARGE }?.index,
        )
    }

/** 카드가 처음 보여 줄 방식. 추천 선택지가 이 카드에 있으면 그것, 아니면 늦지 않으면서 가장 빠른 쪽, 그것도 없으면 장보기만. */
fun defaultIndex(s: UiState, card: MartCard): Int? {
    // 충전을 꼭 하겠다고 했으면 충전 방식을 먼저 보여 준다.
    val needsCharge = s.prompt == kr.gakim.charge.domain.ChargingPrompt.COMPARE_CHARGING
    if (s.form.charge == kr.gakim.charge.domain.ChargePreference.WANT || needsCharge) {
        card.charge?.let {
            val o = s.options[it].option
            // 잔량이 부족해서 권하는 경우에는 늦지 않는 충전만 먼저 보여 준다.
            if (o.selectable && (!needsCharge || s.form.charge == kr.gakim.charge.domain.ChargePreference.WANT || o.status != TimingStatus.LATE)) return it
        }
    }
    s.recommendedIndex?.let { if (it == card.shopOnly || it == card.charge) return it }
    val usable = listOfNotNull(card.shopOnly, card.charge).filter {
        val o = s.options[it].option
        o.selectable && o.status != TimingStatus.LATE && o.arrival != null
    }
    return usable.minByOrNull { s.options[it].option.arrival!! } ?: card.shopOnly ?: card.charge
}

/** 마감까지 남은 분(음수면 늦은 분). 마감이나 도착을 모르면 null. */
fun slackMinutes(o: MartOption, deadline: Deadline): Int? {
    val d = (deadline as? Deadline.At)?.minutes ?: return null
    val a = o.arrival ?: return null
    return d - a
}

enum class SegmentKind { DRIVE, STOP }
data class Segment(val kind: SegmentKind, val minutes: Int)

/** Read-only timeline: never infer a drive split when the data source has none. */
fun timelineSegments(ui: OptionUi, depart: Int): List<Segment> {
    val o = ui.option
    val arrival = o.arrival ?: return emptyList()
    val drive = o.candidate.driveMinutes ?: return emptyList()
    val first = ui.toMartMinutes ?: return emptyList()
    val last = ui.martToDestinationMinutes ?: return emptyList()
    val stop = arrival - depart - drive
    if (first < 0 || last < 0 || first + last != drive || stop < 0) return emptyList()
    return listOf(Segment(SegmentKind.DRIVE, first), Segment(SegmentKind.STOP, stop), Segment(SegmentKind.DRIVE, last))
}

/** Facts are sourced solely from the current comparison and its candidate. */
fun comparisonFacts(s: UiState, ui: OptionUi): List<String> = buildList {
    s.arrivalSoc?.let { add("목적지 도착 때 잔량 ${it}% · 충전 전 기준") }
    val c = ui.option.candidate
    if (c.hasChargingFacility) {
        val available = c.units.count { it.compatible && it.available && it.current }
        add("충전기 ${c.units.size}대 중 ${available}대 사용 가능")
        add(listOfNotNull(c.waitMinutes?.let { "대기 ${it}분" }, c.chargeMinutes?.let { "충전 ${it}분" }).joinToString(" · "))
    } else slackMinutes(ui.option, s.form.deadline)?.let { add(if (it >= 0) "도착까지 ${it}분 여유" else "약속보다 ${-it}분 늦음") }
}.filter { it.isNotBlank() }.take(3)

// ---------- 안내 화면 ----------

enum class StepState { WAITING, ACTIVE, DONE, WARN, FAILED }

/** 요청 보냄 → 내비가 경로 계산 중 → 반영 확인. 실제 상태에서만 계산한다. */
fun trackerSteps(g: GuideUi): List<StepState> {
    if (g.verify == VerifyResult.APPLIED) return listOf(StepState.DONE, StepState.DONE, StepState.DONE)
    val failedRequest = failedRequest(g)
    val s1 = when {
        failedRequest -> StepState.FAILED
        g.sent -> StepState.DONE
        g.busy -> StepState.ACTIVE
        else -> StepState.WAITING
    }
    val s2 = when {
        !g.sent -> StepState.WAITING
        g.verify == VerifyResult.MISMATCH -> StepState.DONE
        g.busy -> StepState.ACTIVE
        else -> StepState.DONE
    }
    val s3 = when {
        g.verify == VerifyResult.MISMATCH -> StepState.FAILED
        g.verify == VerifyResult.PENDING && !g.busy -> StepState.WARN
        else -> StepState.WAITING
    }
    return listOf(s1, s2, s3)
}

private fun failedRequest(g: GuideUi) = !g.sent && !g.busy && g.action == RouteAction.REQUEST_ONCE && g.message != null

fun showTracker(g: GuideUi): Boolean = g.busy || g.sent || g.verify != null || failedRequest(g)

enum class GuideCta { CONFIRM, RETRY, RECHECK, DONE, BUSY }

fun guideCta(g: GuideUi): GuideCta = when {
    g.busy -> GuideCta.BUSY
    g.verify == VerifyResult.APPLIED -> GuideCta.DONE
    g.sent -> GuideCta.RECHECK
    g.message != null -> GuideCta.RETRY
    else -> GuideCta.CONFIRM
}

/** 확인 버튼을 누를 수 있는가: 정차(또는 직접 확인) 상태일 때만. */
fun canConfirm(g: GuideUi): Boolean =
    !g.busy && g.parkState != ParkState.MOVING && (g.parkState == ParkState.PARKED || g.parkedConfirmed)

/** 주차 상태를 읽을 수 없어 사용자의 직접 확인이 필요한가. */
fun needsParkToggle(g: GuideUi): Boolean =
    g.parkState == ParkState.UNKNOWN && guideCta(g).let { it == GuideCta.CONFIRM || it == GuideCta.RETRY } && g.verify == null

/** 비서가 "일정을 다 이해했지만 마감만 비었다"고 말하는 상태(마감 편집기를 자동으로 연다). */
fun needsDeadlineEditor(s: UiState): Boolean =
    s.spokenSlots.isNotEmpty() && s.form.pickup && s.form.deadline == Deadline.Unset

/** 이름 뒤에 붙는 '로/으로'. 받침이 없거나 ㄹ 받침이면 '로'. */
fun withRo(name: String): String {
    val c = name.lastOrNull() ?: return "로"
    if (c !in '가'..'힣') return name + "로"
    val jong = (c - '가') % 28
    return name + if (jong == 0 || jong == 8) "로" else "으로"
}

private fun hasBatchim(name: String): Boolean {
    val c = name.lastOrNull() ?: return false
    return c in '가'..'힣' && (c - '가') % 28 != 0
}

/** 이름 뒤에 붙는 '이/가'. */
fun withGa(name: String): String = name + if (hasBatchim(name)) "이" else "가"

/** 이름 뒤에 붙는 '은/는'. */
fun withNeun(name: String): String = name + if (hasBatchim(name)) "은" else "는"

fun withIeyo(name: String): String = name + if (hasBatchim(name)) "이에요" else "예요"
fun withReul(name: String): String = name + if (hasBatchim(name)) "을" else "를"
fun withIgunyo(name: String): String = name + if (hasBatchim(name)) "이군요" else "군요"

/** One mode resolver for card rendering, taps, and spoken place selection. */
fun selectionIndex(s: UiState, card: MartCard): Int? {
    val indices = listOfNotNull(card.shopOnly, card.charge)
    return s.selectedIndex?.takeIf { it in indices }
        ?: s.cardModeIndices[card.martId]?.takeIf { it in indices }
        ?: defaultIndex(s, card)
}
