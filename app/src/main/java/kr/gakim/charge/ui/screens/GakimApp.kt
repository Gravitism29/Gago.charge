package kr.gakim.charge.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gakim.charge.BuildConfig
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.ui.state.Screen
import kr.gakim.charge.ui.state.UiState
import kr.gakim.charge.ui.state.clockText
import kr.gakim.charge.ui.theme.GakimColors
import kr.gakim.charge.ui.theme.GakimType

/** 앱 전체 틀: 상단 바 + 화면 전환 + (디버그 전용) 개발자 패널. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GakimApp(state: UiState, actions: Actions, showDevPanel: Boolean = BuildConfig.DEBUG) {
    var devOpen by rememberSaveable { mutableStateOf(false) }
    val slide = with(LocalDensity.current) { 24.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(GakimColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                state, actions,
                onWordmarkLongPress = { if (showDevPanel) devOpen = !devOpen },
            )
            AnimatedContent(
                targetState = state.screen,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (fadeIn(tween(300)) + slideInHorizontally(tween(300)) { dir * slide }) togetherWith
                        (fadeOut(tween(160)) + slideOutHorizontally(tween(300)) { -dir * slide })
                },
                label = "screen",
            ) { screen ->
                Box(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 24.dp)) {
                    when (screen) {
                        Screen.TODAY -> TodayScreen(state, actions)
                        Screen.COMPARE -> CompareScreen(state, actions)
                        Screen.GUIDE -> GuideScreen(state, actions)
                    }
                }
            }
        }
        if (showDevPanel && devOpen) DevPanel(state, actions, onClose = { devOpen = false }, modifier = Modifier.align(Alignment.TopStart))
    }
}

private val StepLabels = listOf("말하기", "고르기", "안내")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(state: UiState, actions: Actions, onWordmarkLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 왼쪽: 워드마크(디버그 빌드에서는 길게 누르면 개발자 패널)
        Row(
            Modifier.height(76.dp).padding(end = 12.dp).combinedClickable(onClick = {}, onLongClick = onWordmarkLongPress),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Glyph(Icon.BOLT, GakimColors.Accent, 30.dp)
            Text("가는 김에 충전", style = GakimType.Title.copy(fontSize = 22.sp), maxLines = 1)
        }

        // 가운데: 단계 점
        val current = state.screen.ordinal
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepLabels.forEachIndexed { i, label ->
                val reachable = i < current && !state.guide.busy
                Row(
                    Modifier.height(76.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = reachable, role = Role.Button) { repeat(current - i) { actions.back() } }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val color = if (i == current) GakimColors.Accent else if (i < current) GakimColors.Text else GakimColors.TextDim
                    Box(
                        Modifier.size(if (i == current) 18.dp else 14.dp).clip(CircleShape)
                            .then(if (i <= current) Modifier.background(if (i == current) GakimColors.brandH() else androidx.compose.ui.graphics.SolidColor(color)) else Modifier.border(2.dp, GakimColors.Outline, CircleShape)),
                    )
                    Text(label, style = GakimType.Caption.copy(fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal), color = color, maxLines = 1)
                }
                if (i < StepLabels.lastIndex) {
                    Box(Modifier.width(12.dp).height(2.dp).background(if (i < current) GakimColors.Accent.copy(alpha = 0.6f) else GakimColors.Outline))
                }
            }
        }

        // 오른쪽: 상태 알약
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val battery = when (val b = state.battery) {
                is BatteryReading.Known -> b.percent.let { p -> if (state.demoBatteryOverride != null) "${p}% 시연" else "${p}%" }
                is BatteryReading.Unknown -> "배터리 ?"
                null -> "…"
            }
            Pill(battery, icon = Icon.BATTERY, color = if (state.battery is BatteryReading.Unknown) GakimColors.Tight else GakimColors.Text)
            Pill("${clockText(state.nowMinutes)}", color = GakimColors.Demo, outline = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(if (state.usingFakes) "차량 모의" else "차량 연결됨", icon = Icon.DOT, color = GakimColors.Accent)
                Pill("시연 값", color = GakimColors.Demo, outline = true)
            }
            Box(
                Modifier.size(76.dp).clip(CircleShape).clickable(role = Role.Switch) { actions.setTtsEnabled(!state.ttsEnabled) },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(if (state.ttsEnabled) Icon.SPEAKER else Icon.SPEAKER_OFF, if (state.ttsEnabled) GakimColors.Text else GakimColors.TextDim, 36.dp)
            }
        }
    }
}

/** 개발자 패널(디버그 빌드 전용): Fake/실제 SDK, 시연 목적지, 다시 읽기. 일반 화면에는 나오지 않는다. */
@Composable
private fun DevPanel(state: UiState, actions: Actions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    SurfaceCard(modifier.padding(start = 32.dp, top = 76.dp).width(520.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("개발자 패널", style = GakimType.Title, modifier = Modifier.weight(1f))
                SecondaryButton("닫기", onClose)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (state.usingFakes) "Fake 게이트웨이" else "실제 SDK", style = GakimType.Body, modifier = Modifier.weight(1f))
                Switch(checked = !state.usingFakes, onCheckedChange = { actions.setUseFakes(!it) })
            }
            if (!state.usingFakes) {
                SecondaryButton("시연: 학교를 목적지로", actions.setDemoDestination, Modifier.fillMaxWidth(), enabled = !state.loading)
            }
            SecondaryButton("다시 읽기", actions.refresh, Modifier.fillMaxWidth(), enabled = !state.loading)
            // 시연 잔량: 차에서 읽은 값 대신 비교·표시에 쓸 배터리를 덮어쓴다(디버그 빌드 전용).
            Text("시연 잔량", style = GakimType.Body, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val current = state.demoBatteryOverride
                ChoiceChip("실제 값", current == null, { actions.setDemoBatteryOverride(null) }, Modifier.weight(1f))
                ChoiceChip("25%", current == 25, { actions.setDemoBatteryOverride(25) }, Modifier.weight(1f))
                ChoiceChip("15%", current == 15, { actions.setDemoBatteryOverride(15) }, Modifier.weight(1f))
            }
        }
    }
}
