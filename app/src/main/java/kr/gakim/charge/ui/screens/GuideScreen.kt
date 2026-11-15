package kr.gakim.charge.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.domain.StopMode
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.domain.VerifyResult
import kr.gakim.charge.ui.state.AssistantUi
import kr.gakim.charge.ui.state.GuideCta
import kr.gakim.charge.ui.state.StepState
import kr.gakim.charge.ui.state.UiState
import kr.gakim.charge.ui.state.canConfirm
import kr.gakim.charge.ui.state.clockText
import kr.gakim.charge.ui.state.guideCta
import kr.gakim.charge.ui.state.needsParkToggle
import kr.gakim.charge.ui.state.shortName
import kr.gakim.charge.ui.state.showTracker
import kr.gakim.charge.ui.state.slackMinutes
import kr.gakim.charge.ui.state.trackerSteps
import kr.gakim.charge.ui.state.withGa
import kr.gakim.charge.ui.state.withNeun
import kr.gakim.charge.ui.theme.GakimColors
import kr.gakim.charge.ui.theme.GakimTheme
import kr.gakim.charge.ui.theme.GakimType

/** 3. 안내 — 경로를 한눈에 보여 주고, 확인한 뒤에야 내비를 바꾸며, 진행 상황은 실제 상태만 보여 준다. */
@Composable
fun GuideScreen(state: UiState, actions: Actions) {
    val selected = state.selected
    val g = state.guide
    val cta = guideCta(g)
    val applied = g.verify == VerifyResult.APPLIED
    val moving = g.parkState == ParkState.MOVING && !applied && !g.sent

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AssistantBlock(state.assistant)
        if (selected != null) RouteStrip(state, applied)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                showTracker(g) -> Tracker(state, applied)
                moving -> LockCard()
                selected != null -> SummaryCard(state)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!applied) {
                SecondaryButton("← 다른 곳 고르기", actions.back, Modifier.height(88.dp), enabled = !g.busy)
            }
            SecondaryButton("처음으로", actions.restart, Modifier.height(88.dp), enabled = !g.busy && !applied)
            // 2화면과 같은 작은 마이크 + 마지막으로 들은 말. 주행 중에는 누를 수 없다.
            CompactMicWithHeard(
                listening = state.speech.listening,
                level = state.speech.level,
                heard = state.speech.heard,
                placeholder = "“그래, 안내해 줘” 처럼 말해도 돼요",
                onClick = actions.toggleListening,
                modifier = Modifier.weight(1f),
                enabled = !moving,
            )
            if (needsParkToggle(g) && !moving) ParkToggle(g.parkedConfirmed, { actions.setParkedConfirmed(!g.parkedConfirmed) })
            if (!moving) {
                PrimaryButton(
                    when (cta) {
                        GuideCta.DONE -> "완료"
                        GuideCta.BUSY -> "확인하는 중…"
                        GuideCta.RECHECK -> "다시 확인"
                        GuideCta.RETRY -> "다시 시도"
                        GuideCta.CONFIRM -> "이대로 안내"
                    },
                    if (cta == GuideCta.DONE) actions.restart else actions.confirmGuidance,
                    Modifier.width(380.dp),
                    enabled = cta == GuideCta.DONE || (cta != GuideCta.BUSY && selected != null && canConfirm(g)),
                )
            }
        }
    }
}

@Composable
private fun ParkToggle(checked: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.height(88.dp).clip(shape)
            .background(if (checked) GakimColors.SurfaceHigh else GakimColors.Surface)
            .border(if (checked) 3.dp else 2.dp, if (checked) GakimColors.Accent else GakimColors.Outline, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .then(if (checked) Modifier.background(GakimColors.Accent) else Modifier.border(3.dp, GakimColors.Outline, RoundedCornerShape(10.dp))),
            contentAlignment = Alignment.Center,
        ) { if (checked) Glyph(Icon.CHECK, GakimColors.OnAccent, 30.dp) }
        Text("주차 중이에요", style = GakimType.Body.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp))
    }
}

@Composable
private fun LockCard() = DriveLockCard()

@Composable
private fun SummaryCard(state: UiState) {
    val o = state.selected?.option ?: return
    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(o.arrival?.let { clockText(it) } ?: "–:––", style = GakimType.Big)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${state.destination?.name?.let(::shortName) ?: "목적지"} 도착 예상", style = GakimType.Body.copy(color = GakimColors.TextDim))
                SlackChip(o.status, slackMinutes(o, state.form.deadline))
            }
        }
    }
}

// ---------- 경로 띠 ----------

private data class StripNode(val icon: Icon, val name: String, val tag: String?, val kind: Kind) {
    enum class Kind { START, NEW, WAYPOINT, DEST }
}

private fun stripNodes(state: UiState): List<StripNode> {
    val sel = state.selected ?: return emptyList()
    val o = sel.option
    val dest = state.destination?.name?.let(::shortName) ?: "목적지"
    val slack = slackMinutes(o, state.form.deadline)
    val arrivalTag = when {
        o.arrival == null -> "도착 시각 미확인"
        else -> "${clockText(o.arrival!!)} 도착 예상" + when (o.status) {
            TimingStatus.OK -> " · ${slack}분 여유"
            TimingStatus.TIGHT -> " · ${slack}분 여유 · 촉박"
            TimingStatus.LATE -> " · ${-(slack ?: 0)}분 늦음"
            else -> ""
        }
    }
    val stop = if (o.mode == StopMode.SHOP_AND_CHARGE) "경유 · 장보기+충전" else "경유 · 장보기 ${state.form.activityMinutes}분"
    return buildList {
        add(StripNode(Icon.CAR, "현재 위치", null, StripNode.Kind.START))
        add(StripNode(Icon.CART, shortName(sel.place.name), stop, StripNode.Kind.NEW))
        // 고른 마트가 이미 경유지에 있으면 두 번 그리지 않는다.
        state.route?.waypoints.orEmpty().filterNot { kr.gakim.charge.data.PlaceMatcher.samePlace(it, sel.place) }.forEach { add(StripNode(Icon.PIN, shortName(it.name), "기존 경유지", StripNode.Kind.WAYPOINT)) }
        add(StripNode(Icon.FLAG, dest, arrivalTag, StripNode.Kind.DEST))
    }
}

@Composable
private fun RouteStrip(state: UiState, locked: Boolean) {
    val nodes = stripNodes(state)
    var go by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { go = true }
    val draw by animateFloatAsState(if (go) 1f else 0f, tween(600), label = "strip")
    val glow = rememberInfiniteTransition(label = "glow").animateFloat(
        0.25f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glowValue",
    ).value

    SurfaceCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.Top) {
            nodes.forEachIndexed { i, n ->
                StripNodeView(n, locked, glow, Modifier.width(220.dp))
                if (i < nodes.lastIndex) {
                    Box(Modifier.weight(1f).padding(top = 29.dp)) {
                        Box(Modifier.fillMaxWidth(draw).height(6.dp).clip(RoundedCornerShape(3.dp)).background(GakimColors.brandH()))
                    }
                }
            }
        }
    }
}

@Composable
private fun StripNodeView(n: StripNode, locked: Boolean, glow: Float, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            val isNew = n.kind == StripNode.Kind.NEW
            if (isNew && !locked) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(GakimColors.Accent.copy(alpha = 0.25f * glow)))
            }
            val filled = isNew || locked && n.kind != StripNode.Kind.START
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .then(if (filled) Modifier.background(GakimColors.brandH()) else Modifier.background(GakimColors.SurfaceHigh).border(3.dp, GakimColors.Outline, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (locked && n.kind != StripNode.Kind.START) Glyph(Icon.CHECK, GakimColors.OnAccent, 34.dp)
                else Glyph(n.icon, if (filled) GakimColors.OnAccent else GakimColors.Text, 32.dp)
            }
        }
        Text(n.name, style = GakimType.BodyBold, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (n.tag != null) Text(n.tag, style = GakimType.Caption.copy(color = if (n.kind == StripNode.Kind.NEW) GakimColors.Accent else GakimColors.TextDim), maxLines = 2, textAlign = TextAlign.Center)
    }
}

// ---------- 진행 상황 ----------

private val StepTitles = listOf("요청 보냄", "내비가 경로 계산 중", "반영 확인")

@Composable
private fun Tracker(state: UiState, applied: Boolean) {
    val steps = trackerSteps(state.guide)
    val sel = state.selected
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            steps.forEachIndexed { i, st ->
                StepCard(i + 1, StepTitles[i], st, stepNote(i, st), Modifier.weight(1f))
            }
        }
        if (applied && sel != null) {
            val dest = state.destination?.name?.let(::shortName) ?: "목적지"
            val mart = shortName(sel.place.name)
            Text("${withGa(mart)} 추가됐어요. ${withNeun(dest)} 그대로예요.", style = GakimType.Title.copy(color = GakimColors.Accent))
        }
    }
}

private fun stepNote(i: Int, st: StepState): String = when (i) {
    0 -> when (st) { StepState.DONE -> "내비에 전달했어요"; StepState.FAILED -> "전달하지 못했어요"; StepState.ACTIVE -> "보내는 중…"; else -> "기다리는 중" }
    1 -> when (st) { StepState.DONE -> "경로를 받았어요"; StepState.ACTIVE -> "잠시만 기다려 주세요"; else -> "기다리는 중" }
    else -> when (st) {
        StepState.DONE -> "계획과 같아요"
        StepState.FAILED -> "계획과 달라요"
        StepState.WARN -> "아직 확인하지 못했어요"
        else -> "기다리는 중"
    }
}

@Composable
private fun StepCard(number: Int, title: String, st: StepState, note: String, modifier: Modifier) {
    val color = when (st) {
        StepState.DONE -> GakimColors.Accent
        StepState.ACTIVE -> GakimColors.Voice
        StepState.WARN -> GakimColors.Tight
        StepState.FAILED -> GakimColors.Late
        StepState.WAITING -> GakimColors.TextDim
    }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.height(112.dp).clip(shape).background(GakimColors.Surface)
            .border(if (st == StepState.WAITING) 2.dp else 3.dp, if (st == StepState.WAITING) GakimColors.Outline else color, shape)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            when (st) {
                StepState.DONE -> Box(Modifier.size(56.dp).clip(CircleShape).background(GakimColors.brandH()), contentAlignment = Alignment.Center) {
                    Glyph(Icon.CHECK, GakimColors.OnAccent, 36.dp)
                }
                StepState.ACTIVE -> Spinner(color)
                StepState.WARN -> Glyph(Icon.WARN, color, 44.dp)
                StepState.FAILED -> Glyph(Icon.CROSS, color, 44.dp)
                StepState.WAITING -> Box(Modifier.size(48.dp).clip(CircleShape).border(3.dp, GakimColors.Outline, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$number", style = GakimType.BodyBold.copy(color = GakimColors.TextDim))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GakimType.BodyBold.copy(fontSize = 24.sp, color = if (st == StepState.WAITING) GakimColors.TextDim else GakimColors.Text), maxLines = 2)
            Text(note, style = GakimType.Caption.copy(color = if (st == StepState.WAITING) GakimColors.TextDim else color), maxLines = 1)
        }
    }
}

@Composable
private fun Spinner(color: Color) {
    val angle = rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle",
    ).value
    Canvas(Modifier.size(48.dp)) {
        val w = 5.dp.toPx()
        drawCircle(GakimColors.Outline, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
        drawArc(color, angle, 100f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

// ---------- 미리보기 ----------

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun GuidePreviewReady() = GakimTheme { GakimApp(PreviewData.guide(PreviewData.GuideKind.READY), Actions.None, showDevPanel = false) }

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun GuidePreviewVerifying() = GakimTheme { GakimApp(PreviewData.guide(PreviewData.GuideKind.VERIFYING), Actions.None, showDevPanel = false) }

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun GuidePreviewApplied() = GakimTheme { GakimApp(PreviewData.guide(PreviewData.GuideKind.APPLIED), Actions.None, showDevPanel = false) }

@Preview(widthDp = 1280, heightDp = 910, showBackground = true, backgroundColor = 0xFF0B1016)
@Composable
private fun GuidePreviewMoving() = GakimTheme { GakimApp(PreviewData.guide(PreviewData.GuideKind.MOVING), Actions.None, showDevPanel = false) }
