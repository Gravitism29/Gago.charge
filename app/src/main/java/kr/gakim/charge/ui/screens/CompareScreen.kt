package kr.gakim.charge.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.MartOption
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.domain.hasUsableUnit
import kr.gakim.charge.ui.state.comparisonFacts
import kr.gakim.charge.ui.state.MartCard
import kr.gakim.charge.ui.state.SegmentKind
import kr.gakim.charge.ui.state.UiState
import kr.gakim.charge.ui.state.clockText
import kr.gakim.charge.ui.state.selectionIndex
import kr.gakim.charge.ui.state.martCards
import kr.gakim.charge.ui.state.slackMinutes
import kr.gakim.charge.ui.state.timelineSegments
import kr.gakim.charge.ui.state.shortName
import kr.gakim.charge.ui.state.withRo
import kr.gakim.charge.ui.theme.GakimColors
import kr.gakim.charge.ui.theme.GakimTheme
import kr.gakim.charge.ui.theme.GakimType

/** 2. 고르기 — 마트마다 카드 한 장, 두 카드가 같은 시간축 위에서 마감선과 비교된다. */
@Composable
fun CompareScreen(state: UiState, actions: Actions) {
    val cards = martCards(state)
    val moving = state.parkState == ParkState.MOVING
    val deadline = (state.form.deadline as? Deadline.At)?.minutes
    val depart = state.nowMinutes
    // 두 카드가 같은 시간축을 쓰도록 축의 끝은 마감과 모든 도착 시각 중 가장 늦은 때에 여유를 더해 정한다.
    val axisEnd = (listOfNotNull(deadline) + state.options.mapNotNull { it.option.arrival }).maxOrNull()
        ?.let { it + 6 } ?: (depart + 60)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AssistantBlock(state.assistant, height = 120.dp)
        if (moving) DriveLockCard()
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            cards.forEach { card ->
                val indices = listOfNotNull(card.shopOnly, card.charge)
                val shown = selectionIndex(state, card) ?: indices.first()
                MartCardView(
                    state = state, card = card, shownIndex = shown,
                    selected = state.selectedIndex in indices,
                    recommended = state.recommendedIndex in indices,
                    depart = depart, deadline = deadline, axisEnd = axisEnd,
                    locked = moving,
                    onTap = { if (!moving) actions.select(shown) },
                    onMode = { i -> if (!moving) { actions.select(i) } },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
        BottomRow(state, actions, moving)
    }
}

@Composable
private fun BottomRow(state: UiState, actions: Actions, moving: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CompactMicWithHeard(
            listening = state.speech.listening,
            level = state.speech.level,
            heard = if (state.speech.listening) state.speech.heard else "",
            placeholder = "“${withRo(state.selected?.place?.name?.let(::shortName) ?: "B마트")} 갈래”라고 말해도 돼요",
            onClick = actions.toggleListening,
            modifier = Modifier.weight(1f),
            enabled = !moving,
        )
        SecondaryButton("← 다시 말하기", actions.back, Modifier.height(88.dp), enabled = !moving)
        val selected = state.selected
        PrimaryButton(
            if (selected != null) "${withRo(shortName(selected.place.name))} 가기 →" else "한 곳을 골라 주세요",
            actions.openGuide, Modifier.width(420.dp), enabled = !moving && selected != null,
        )
    }
}

@Composable
private fun MartCardView(
    state: UiState,
    card: MartCard,
    shownIndex: Int,
    selected: Boolean,
    recommended: Boolean,
    depart: Int,
    deadline: Int?,
    axisEnd: Int,
    onTap: () -> Unit,
    onMode: (Int) -> Unit,
    locked: Boolean,
    modifier: Modifier,
) {
    val option = state.options[shownIndex].option
    val other = listOfNotNull(card.shopOnly, card.charge).firstOrNull { it != shownIndex }?.let { state.options[it].option }
    val scale by animateFloatAsState(if (selected) 1.015f else 1f, tween(250), label = "cardScale")
    val shape = CardShape
    Column(
        modifier.scale(scale).clip(shape)
            .background(if (selected) GakimColors.SurfaceHigh else GakimColors.Surface)
            .then(if (selected) Modifier.border(3.dp, GakimColors.brandH(), shape) else Modifier.border(2.dp, GakimColors.Outline, shape))
            .clickable(enabled = option.selectable && !locked, role = Role.RadioButton, onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 머리줄: 마트 이름 · 추천 · 선택 표시
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(card.name, style = GakimType.Title, maxLines = 1)
            if (recommended) {
                Row(
                    Modifier.clip(PillShape).border(2.dp, GakimColors.Voice, PillShape).padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("★ 추천", style = GakimType.CaptionBold, color = GakimColors.Voice)
                }
            }
            Box(Modifier.weight(1f))
            if (selected) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(GakimColors.brandH()), contentAlignment = Alignment.Center) {
                    Glyph(Icon.CHECK, GakimColors.OnAccent, 28.dp)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(148.dp)) {
                Text(option.arrival?.let { clockText(it) } ?: "시간 미확인", style = GakimType.Big, maxLines = 1)
                Text("목적지 도착 예상", style = GakimType.Caption)
            }
            if (card.shopOnly != null && card.charge != null) {
                ChoiceChip("장보기만", shownIndex == card.shopOnly, { onMode(card.shopOnly) }, Modifier.weight(1f),
                    enabled = !locked && state.options[card.shopOnly].option.selectable)
                ChoiceChip("함께 충전", shownIndex == card.charge, { onMode(card.charge) }, Modifier.weight(1f),
                    enabled = !locked && state.options[card.charge].option.selectable)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (option.selectable) SlackChip(option.status, slackMinutes(option, state.form.deadline))
            Text(otherLine(option, other, deadline), style = GakimType.Caption, maxLines = 2, modifier = Modifier.weight(1f))
        }
        // 시간 막대는 시계 줄 바로 아래(24dp)에 둔다. 예전에는 다른 방식 줄과의 사이가 비어 있었다.
        Box(Modifier.height(2.dp))
        DeadlineTimeline(shownIndex, state, depart, deadline, axisEnd)

        Text("장보기 ${state.form.activityMinutes}분" + (option.candidate.accessMinutes?.let { " + 주차 ${it}분" } ?: ""), style = GakimType.Caption)

        comparisonFacts(state, state.options[shownIndex]).take(3).forEach { fact ->
            Text(fact, style = GakimType.Caption, maxLines = 2)
        }
        Box(Modifier.weight(1f))
    }
}

private fun otherLine(shown: MartOption, other: MartOption?, deadline: Int?): String {
    if (other == null) return " "
    if (!other.selectable) return "충전은 이 마트에서 할 수 없어요"
    val arrival = other.arrival ?: return "도착 시간을 알 수 없어요"
    val tail = when (other.status) {
        TimingStatus.LATE -> deadline?.let { " · ${arrival - it}분 늦음" } ?: ""
        TimingStatus.TIGHT -> deadline?.let { " · ${it - arrival}분 여유 · 촉박" } ?: ""
        TimingStatus.OK -> deadline?.let { " · ${it - arrival}분 여유" } ?: ""
        else -> ""
    }
    return (if (other.mode == StopMode.SHOP_AND_CHARGE) "충전까지 하면 " else "장보기만 하면 ") + clockText(arrival) + tail
}

/**
 * 출발 → 마감 시간축 위의 막대. 두 카드가 같은 [axisEnd] 를 쓰므로 막대 길이와 마감선이 서로 비교된다.
 * 마감선을 넘는 구간은 산호색으로 칠한다.
 */
@Composable
private fun DeadlineTimeline(shownIndex: Int, state: UiState, depart: Int, deadline: Int?, axisEnd: Int) {
    val ui = state.options[shownIndex]
    val option = ui.option
    val segments = timelineSegments(ui, depart)
    val span = (axisEnd - depart).toFloat().coerceAtLeast(1f)
    var go by remember(option) { mutableStateOf(false) }
    LaunchedEffect(option) { go = true }
    val grow by animateFloatAsState(if (go) 1f else 0f, tween(if (state.parkState == ParkState.PARKED) 350 else 0), label = "grow")
    val base = when (option.status) {
        TimingStatus.TIGHT -> GakimColors.Tight
        TimingStatus.UNKNOWN -> GakimColors.Unknown
        else -> GakimColors.Accent
    }
    val trackColor = GakimColors.Outline.copy(alpha = 0.45f)

    BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp).alpha(if (option.selectable) 1f else 0.4f)) {
        val w = maxWidth
        fun x(t: Int): Dp = w * ((t - depart) / span).coerceIn(0f, 1f)
        val barTop = 30.dp
        val barH = 28.dp

        Canvas(Modifier.fillMaxSize()) {
            val top = barTop.toPx(); val h = barH.toPx()
            val totalPx = size.width
            drawRoundRect(trackColor, Offset(0f, top), Size(totalPx, h), CornerRadius(12.dp.toPx()))
            if (segments.isNotEmpty()) {
                val end = segments.sumOf { it.minutes } / span * totalPx * grow
                val path = Path().apply { addRoundRect(RoundRect(0f, top, end, top + h, CornerRadius(12.dp.toPx()))) }
                clipPath(path) {
                    var s = 0f
                    segments.forEach { seg ->
                        val e = s + seg.minutes / span * totalPx * grow
                        val right = (e - 2.dp.toPx()).coerceAtLeast(s)
                        val opacity = if (seg.kind == SegmentKind.DRIVE) 0.5f else 1f
                        val dlX = deadline?.let { ((it - depart) / span * totalPx).coerceIn(3.dp.toPx(), totalPx - 3.dp.toPx()) } ?: right
                        val onTimeEnd = minOf(right, dlX)
                        if (onTimeEnd > s) drawRect(base.copy(alpha = opacity), Offset(s, top), Size(onTimeEnd - s, h))
                        val lateStart = maxOf(s, dlX)
                        if (right > lateStart) drawRect(GakimColors.Late.copy(alpha = opacity), Offset(lateStart, top), Size(right - lateStart, h))
                        s = e
                    }
                }
            }
            if (deadline != null) {
                val dlX = ((deadline - depart) / span * totalPx).coerceIn(3.dp.toPx(), totalPx - 3.dp.toPx())
                drawRect(GakimColors.Text, Offset(dlX - 1.5.dp.toPx(), top - 8.dp.toPx()), Size(3.dp.toPx(), h + 16.dp.toPx()))
            }
        }

        // Labels have equal-width columns below the proportional bar so short drives remain readable.
        if (segments.size == 3) Row(Modifier.fillMaxWidth().offset(y = 66.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = listOf("마트까지 ${segments[0].minutes}분",
                if (option.mode == StopMode.SHOP_ONLY) "장보기 ${state.form.activityMinutes}분"
                else "장보기+충전 ${segments[1].minutes}분",
                "${state.destination?.name?.let(::shortName) ?: "목적지"}까지 ${segments[2].minutes}분")
            labels.forEachIndexed { i, label ->
                Text(label, style = GakimType.CaptionBold, color = if (i == 1) GakimColors.Accent else GakimColors.TextDim,
                    modifier = Modifier.weight(1f), maxLines = 2, textAlign = TextAlign.Center)
            }
        }

        // 마감 이름표(막대 위)
        if (deadline != null) {
            val labelW = 112.dp
            val cx = x(deadline)
            Text(
                "${clockText(deadline)}까지", style = GakimType.CaptionBold.copy(color = GakimColors.Text), textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.offset((cx - labelW / 2).coerceIn(0.dp, w - labelW), 0.dp).width(labelW),
            )
        }

    }
}


// ---------- 미리보기 ----------

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun ComparePreview() = GakimTheme { GakimApp(PreviewData.compare(selected = 3), Actions.None, showDevPanel = false) }

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun CompareLatePreview() = GakimTheme { GakimApp(PreviewData.compare(selected = null, deadline = 15 * 60 + 45), Actions.None, showDevPanel = false) }
