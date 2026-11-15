package kr.gakim.charge.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gakim.charge.domain.TimingStatus
import kr.gakim.charge.ui.state.AssistantUi
import kr.gakim.charge.ui.theme.GakimColors
import kr.gakim.charge.ui.theme.GakimType

val CardShape = RoundedCornerShape(28.dp)
val ButtonShape = RoundedCornerShape(24.dp)
val PillShape = RoundedCornerShape(50)

/** 화면마다 하나뿐인 주 동작. 켜져 있으면 브랜드 그라데이션, [progress] 가 있으면 아래에 자동 진행 막대를 그린다. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    progress: Float? = null,
) {
    Box(
        modifier
            .height(88.dp)
            .clip(ButtonShape)
            .background(if (enabled) GakimColors.brandH() else SolidColor(GakimColors.SurfaceHigh))
            .border(2.dp, GakimColors.Outline, ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            Box(
                Modifier.align(Alignment.CenterStart).fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(GakimColors.OnAccent.copy(alpha = 0.22f)),
            )
        }
        Text(
            text,
            color = if (enabled) GakimColors.OnAccent else GakimColors.TextDim,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 76.dp)
            .clip(ButtonShape)
            .border(2.dp, GakimColors.Outline, ButtonShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = GakimType.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = if (text == "−" || text == "+") 36.sp else 22.sp),
            color = if (enabled) GakimColors.Text else GakimColors.TextDim,
            textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

/** 편집기·분할 선택에 쓰는 큰 선택 버튼. */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .background(if (selected) GakimColors.SurfaceHigh else GakimColors.Surface)
            .then(if (selected) Modifier.border(3.dp, GakimColors.brandH(), shape) else Modifier.border(2.dp, GakimColors.Outline, shape))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            (if (selected) "✓ " else "") + text, style = GakimType.Body.copy(fontWeight = FontWeight.SemiBold),
            color = if (!enabled) GakimColors.TextDim else if (selected) GakimColors.Accent else GakimColors.Text,
            textAlign = TextAlign.Center, maxLines = 2,
        )
        if (sub != null) Text(sub, style = GakimType.Caption, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 상태 알약. [outline] 이면 테두리만. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = GakimColors.Text,
    icon: Icon? = null,
    outline: Boolean = false,
) {
    val base = modifier.clip(PillShape)
    Row(
        (if (outline) base.border(2.dp, color, PillShape) else base.background(GakimColors.SurfaceHigh))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Glyph(icon, color, 24.dp)
        Text(text, style = GakimType.CaptionBold, color = color, maxLines = 1)
    }
}

/** 여유/촉박/늦음 칩: 색만이 아니라 아이콘과 낱말로도 알려 준다. */
@Composable
fun SlackChip(status: TimingStatus, slack: Int?, modifier: Modifier = Modifier) {
    val (text, color, icon) = when (status) {
        TimingStatus.OK -> Triple("${slack ?: 0}분 여유", GakimColors.Accent, Icon.CHECK)
        TimingStatus.TIGHT -> Triple("${slack ?: 0}분 여유 · 촉박", GakimColors.Tight, Icon.WARN)
        TimingStatus.LATE -> Triple("${-(slack ?: 0)}분 늦음", GakimColors.Late, Icon.CROSS)
        TimingStatus.NO_DEADLINE -> Triple("시간 제한 없음", GakimColors.Accent, Icon.CLOCK)
        TimingStatus.UNKNOWN -> Triple("시간 미확인", GakimColors.Unknown, Icon.WARN)
    }
    Row(
        modifier.clip(PillShape).background(color).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(icon, GakimColors.OnAccent, 24.dp)
        Text(text, style = GakimType.CaptionBold, color = GakimColors.OnAccent, maxLines = 1)
    }
}

/** 비서 한 문장 + 보조 한 줄. 문장이 바뀌면 부드럽게 바뀐다. */
@Composable
fun AssistantBlock(assistant: AssistantUi, modifier: Modifier = Modifier, height: Dp = 144.dp) {
    AnimatedContent(
        targetState = assistant,
        modifier = modifier.fillMaxWidth().height(height),
        transitionSpec = {
            (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 12 }) togetherWith fadeOut(tween(150))
        },
        label = "assistant",
    ) { a ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
            Text(a.line, style = GakimType.Assistant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (a.sub != null) Text(a.sub, style = GakimType.Body.copy(color = GakimColors.TextDim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun Dot(color: Color, size: Dp = 16.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** 작은 마이크(88dp) + 마지막으로 들은 말. 2·3 화면 아래 줄에서 같이 쓴다. */
@Composable
fun CompactMicWithHeard(
    listening: Boolean,
    level: Float,
    heard: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CompactMic(listening, level, onClick, Modifier.alpha(if (enabled) 1f else 0.4f), enabled = enabled)
        Text(
            when {
                listening -> "듣고 있어요…"
                heard.isNotBlank() -> "“$heard”"
                else -> placeholder
            },
            style = GakimType.Body.copy(color = if (heard.isNotBlank()) GakimColors.Text else GakimColors.TextDim),
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 주행 중 잠금 카드. 1·2·3 화면이 같은 문구·같은 모양을 쓴다(§8 주행 잠금).
 * 잠금 중에는 편집·비교·확인 동작을 화면에서 끈다.
 */
@Composable
fun DriveLockCard(message: String = "멈춘 뒤에 이 화면에서 이어서 안내할 수 있어요. 지금은 확인만 할 수 있어요.") {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(GakimColors.Surface).border(2.dp, GakimColors.Tight, shape).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Glyph(Icon.LOCK, GakimColors.Tight, 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("주행 중에는 일정을 바꿀 수 없어요", style = GakimType.Title)
            Text(message, style = GakimType.Body.copy(color = GakimColors.TextDim))
        }
    }
}

/** 어두운 면 위의 카드. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.clip(CardShape).background(GakimColors.Surface).border(2.dp, GakimColors.Outline.copy(alpha = 0.6f), CardShape)) { content() }
