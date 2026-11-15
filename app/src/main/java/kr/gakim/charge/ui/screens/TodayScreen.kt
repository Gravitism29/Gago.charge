package kr.gakim.charge.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.ChargePreference
import kr.gakim.charge.domain.Deadline
import kr.gakim.charge.domain.formatClock
import kr.gakim.charge.ui.state.deadlineMessage
import kr.gakim.charge.ui.state.Slot
import kr.gakim.charge.ui.state.UiState
import kr.gakim.charge.ui.state.clockText
import kr.gakim.charge.ui.state.deadlineChoices
import kr.gakim.charge.ui.state.needsDeadlineEditor
import kr.gakim.charge.ui.state.shortName
import kr.gakim.charge.ui.theme.GakimColors
import kr.gakim.charge.ui.theme.GakimTheme
import kr.gakim.charge.ui.theme.GakimType

private val Examples = listOf(
    "장 보고 4시까지 애 데리러 가야 해",
    "장 보고 애 데리러 가야 해",
    "충전도 하고 네 시 반까지",
)

/** 1. 말하기 — 앱이 먼저 말하고, 이해한 내용을 네 칸으로 보여 주며, 틀린 칸만 눌러서 고친다. */
@Composable
fun TodayScreen(state: UiState, actions: Actions) {
    val moving = state.parkState == ParkState.MOVING
    var open by remember { mutableStateOf<Slot?>(null) }
    val needDeadline = needsDeadlineEditor(state) && !moving
    LaunchedEffect(needDeadline) { if (needDeadline) open = Slot.DEADLINE }
    LaunchedEffect(moving) { if (moving) open = null }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        // ---- 왼쪽: 비서 문장 · 들은 말 · 이해한 칸 · 편집기 ----
        Column(Modifier.weight(0.62f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AssistantBlock(state.assistant)
            if (moving) DriveLockCard()
            Transcript(state)
            SlotRow(state, open, onToggle = { slot ->
                if (!moving) {
                    actions.beginTouchEdit()
                    open = if (open == slot) null else slot
                }
            }, enabled = !moving)
            AnimatedVisibility(
                visible = open != null && !moving,
                enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                EditorPanel(state, actions, open)
            }
            if (open == null) SurfaceCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("현재 위치", style = GakimType.BodyBold)
                    Text("─", style = GakimType.Body, color = GakimColors.Voice)
                    Text(state.selected?.place?.name?.let(::shortName) ?: "?", style = GakimType.Title, color = GakimColors.Accent)
                    Text("─", style = GakimType.Body, color = GakimColors.Voice)
                    Text(state.destination?.name?.let(::shortName) ?: "목적지 없음", style = GakimType.BodyBold, maxLines = 2)
                }
            }
            val naviProblem = state.navi is NaviRead.Unknown || (state.navi is NaviRead.Known && state.destination == null)
            if (naviProblem) SecondaryButton("다시 확인", actions.refresh, enabled = !state.loading && !moving)
            if (state.autoAdvanceAtMs != null && open == null) {
                Text("잠시 뒤 자동으로 넘어가요 · 화면을 누르면 멈춰요", style = GakimType.Caption.copy(color = GakimColors.Accent))
            }
        }

        // ---- 오른쪽: 마이크 · 예시 · 주 동작 ----
        Column(
            Modifier.weight(0.38f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.alpha(if (moving) 0.4f else 1f)) {
                MicOrb(
                    listening = state.speech.listening, level = state.speech.level,
                    onClick = { if (!moving) actions.toggleListening() }, orbSize = 200.dp, dimmed = !state.speech.available,
                )
            }
            Text(
                when {
                    moving -> "주행 중에는 말로 바꿀 수 없어요"
                    state.speech.listening -> "듣고 있어요 · 누르면 멈춰요"
                    !state.speech.available -> "마이크를 쓸 수 없어요"
                    else -> "눌러서 말하기"
                },
                style = GakimType.BodyBold.copy(color = if (state.speech.listening) GakimColors.Voice else GakimColors.TextDim),
            )
            Box(Modifier.height(24.dp))
            Text("이렇게 말해 보세요", style = GakimType.Caption, modifier = Modifier.align(Alignment.CenterHorizontally))
            Box(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Examples.forEach { sentence ->
                    ExampleChip(sentence, enabled = !moving) { actions.onSpokenSentence(sentence) }
                }
            }
            Box(Modifier.weight(1f))
            CompareCta(state, actions, enabled = !moving)
        }
    }
}

@Composable
private fun CompareCta(state: UiState, actions: Actions, enabled: Boolean = true) {
    val due = state.autoAdvanceAtMs
    var fraction by remember(due) { mutableFloatStateOf(0f) }
    var seconds by remember(due) { mutableStateOf(2) }
    LaunchedEffect(due) {
        if (due != null) {
            while (true) {
                val remaining = due - System.currentTimeMillis()
                fraction = (1f - remaining.toFloat() / state.autoAdvanceDurationMs).coerceIn(0f, 1f)
                seconds = (remaining / 1000).toInt().coerceAtLeast(1)
                if (remaining <= 0) break
                delay(30)
            }
        }
    }
    PrimaryButton(
        if (due != null) "${seconds}초 뒤 비교 화면으로 →" else if (state.blocker != null) "도착 시각을 정하면 비교해요" else "두 곳 비교하기 →",
        { if (due != null) actions.cancelAutoAdvance() else actions.openCompare() }, Modifier.fillMaxWidth(),
        enabled = enabled && state.blocker == null && !state.loading,
        progress = if (due != null) fraction else null,
    )
}

@Composable
private fun ExampleChip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.fillMaxWidth().height(76.dp).clip(shape)
            .background(GakimColors.Surface)
            .border(2.dp, GakimColors.Outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("“$text”", style = GakimType.Body.copy(color = GakimColors.Voice), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Transcript(state: UiState) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(GakimColors.Surface)
            .border(2.dp, if (state.speech.listening) GakimColors.Voice else GakimColors.Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Glyph(Icon.MIC, if (state.speech.listening) GakimColors.Voice else GakimColors.TextDim, 30.dp)
        val heard = state.speech.heard
        Text(
            if (heard.isNotBlank()) "“$heard”" else "말씀하시면 여기에 그대로 적혀요",
            style = GakimType.Body.copy(color = if (heard.isNotBlank()) GakimColors.Text else GakimColors.TextDim),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- 이해한 칸 ----------

private class SlotView(val slot: Slot, val icon: Icon, val label: String, val value: String, val required: Boolean, val enabled: Boolean = true)

private fun slotViews(s: UiState): List<SlotView> {
    val f = s.form
    val dest = s.destination?.name?.let(::shortName)
    return listOf(
        SlotView(Slot.ACTIVITY, Icon.CART, "할 일", "장보기 ${f.activityMinutes}분", false),
        SlotView(
            Slot.PICKUP, Icon.PIN, "픽업",
            if (!f.pickup) "없음" else if (dest != null) "${dest}에서" else "목적지 없음", false, enabled = dest != null,
        ),
        SlotView(
            Slot.DEADLINE, Icon.CLOCK, "도착",
            when (val d = f.deadline) {
                is Deadline.At -> "${formatClock(d.minutes)}까지"
                Deadline.Anytime -> "몇 시든"
                Deadline.Unset -> if (f.pickup) "언제까지?" else "몇 시든"
            },
            required = f.pickup && f.deadline == Deadline.Unset,
        ),
        SlotView(
            Slot.CHARGE, Icon.BOLT, "충전",
            when (f.charge) { ChargePreference.AUTO -> "알아서"; ChargePreference.WANT -> "꼭 하기"; ChargePreference.DECLINE -> "안 하기" },
            false,
        ),
    )
}

@Composable
private fun SlotRow(state: UiState, open: Slot?, onToggle: (Slot) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        slotViews(state).forEachIndexed { index, v ->
            SlotPill(
                v, index, filled = v.slot in state.spokenSlots, selected = open == v.slot,
                onClick = { onToggle(v.slot) }, modifier = Modifier.weight(1f), enabled = enabled,
            )
        }
    }
}

@Composable
private fun SlotPill(v: SlotView, index: Int, filled: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier, enabled: Boolean = true) {
    // 말로 채워진 칸은 순서대로(170ms 간격) 민트로 물든다.
    val fill by animateFloatAsState(if (filled) 1f else 0f, tween(450, delayMillis = if (filled) index * 170 else 0), label = "fill")
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseValue",
    ).value
    val shape = RoundedCornerShape(24.dp)
    val borderColor = when {
        selected -> GakimColors.Voice
        v.required -> GakimColors.Tight.copy(alpha = pulse)
        else -> GakimColors.Outline
    }
    Column(
        modifier.height(96.dp)
            .scale(1f + 0.02f * fill)
            .clip(shape)
            .background(lerp(GakimColors.Surface, Color(0xFF173A33), fill))
            .border(if (selected || v.required) 3.dp else 2.dp, borderColor, shape)
            .clickable(enabled = enabled && v.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(v.icon, if (v.required) GakimColors.Tight else GakimColors.TextDim, 24.dp)
            Text(v.label, style = GakimType.Caption, maxLines = 1)
        }
        Text(
            v.value,
            style = GakimType.Body.copy(
                fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold,
                color = if (!v.enabled) GakimColors.TextDim else if (v.required) GakimColors.Tight else GakimColors.Text,
            ),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- 편집기 ----------

@Composable
private fun EditorPanel(state: UiState, actions: Actions, slot: Slot?) {
    val f = state.form
    SurfaceCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (slot) {
                Slot.ACTIVITY -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(10, 20, 30).forEach { m ->
                        ChoiceChip("${m}분", f.activityMinutes == m, { actions.setActivityMinutes(m) }, Modifier.weight(1f))
                    }
                }
                Slot.PICKUP -> {
                    val dest = state.destination?.name?.let(::shortName)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChoiceChip(
                            "${dest ?: "목적지"}에서 픽업", f.pickup, { actions.setPickup(true) },
                            Modifier.weight(1f), enabled = dest != null,
                        )
                        ChoiceChip("픽업 없음 · 장만 보기", !f.pickup, { actions.setPickup(false) }, Modifier.weight(1f))
                    }
                }
                Slot.DEADLINE -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("−", { actions.nudgeDeadline(-10) }, Modifier.width(76.dp),
                            enabled = (f.deadline as? Deadline.At)?.minutes?.let { it > state.nowMinutes + 10 } == true)
                        Text(
                            (f.deadline as? Deadline.At)?.let { (if (it.minutes % 1440 >= 720) "오후 " else "오전 ") + clockText(it.minutes) } ?: "시각 선택",
                            style = GakimType.Big.copy(fontSize = 28.sp, color = if (f.deadline is Deadline.At) GakimColors.Text else GakimColors.TextDim),
                            textAlign = TextAlign.Center, modifier = Modifier.width(170.dp),
                        )
                        SecondaryButton("+", { actions.nudgeDeadline(10) }, Modifier.width(76.dp))
                        Box(Modifier.weight(1f))
                        ChoiceChip("몇 시든 괜찮아요", f.deadline == Deadline.Anytime, { actions.setDeadline(Deadline.Anytime) })
                    }
                    Text(deadlineMessage(state.nowMinutes), style = GakimType.Caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        deadlineChoices(state.nowMinutes).forEach { m ->
                            ChoiceChip(formatClock(m), f.deadline == Deadline.At(m), { actions.setDeadline(Deadline.At(m)) }, Modifier.weight(1f))
                        }
                    }
                }
                Slot.CHARGE -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("도착할 때 ${state.thresholdPercent}% 아래면 권해요", style = GakimType.Caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChoiceChip("알아서", f.charge == ChargePreference.AUTO, { actions.setCharge(ChargePreference.AUTO) }, Modifier.weight(1f))
                    ChoiceChip("꼭 하기", f.charge == ChargePreference.WANT, { actions.setCharge(ChargePreference.WANT) }, Modifier.weight(1f))
                    ChoiceChip("안 하기", f.charge == ChargePreference.DECLINE, { actions.setCharge(ChargePreference.DECLINE) }, Modifier.weight(1f))
                }
                }
                null -> Unit
            }
        }
    }
}

// ---------- 미리보기 ----------

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun TodayPreviewIdle() = GakimTheme { GakimApp(PreviewData.today(), Actions.None, showDevPanel = false) }

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun TodayPreviewDeadlineMissing() = GakimTheme { GakimApp(PreviewData.todayDeadlineMissing(), Actions.None, showDevPanel = false) }
