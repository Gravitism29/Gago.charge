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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.gakim.charge.ui.theme.GakimColors

/**
 * 말하기 구슬. 평소에는 천천히 숨 쉬고, 듣는 동안에는 입력 크기([level], 0~1)에 따라 물결이 퍼진다.
 * 전부 Canvas 로 그린다.
 */
@Composable
fun MicOrb(
    listening: Boolean,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    orbSize: Dp = 220.dp,
    dimmed: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breathe by transition.animateFloat(
        initialValue = 0.97f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe",
    )
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple",
    )
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "level")
    val scale = if (listening) 1f + 0.08f * smooth else breathe
    val boxSize = orbSize * 1.4f

    Box(
        modifier.size(boxSize).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(boxSize)) {
            val c = center
            val r = orbSize.toPx() / 2f
            // 은은한 바깥 빛
            drawCircle(
                Brush.radialGradient(
                    listOf(GakimColors.Voice.copy(alpha = if (listening) 0.30f else 0.14f), GakimColors.Accent.copy(alpha = 0f)),
                    center = c, radius = r * 1.4f,
                ),
                radius = r * 1.4f, center = c,
            )
            if (listening) {
                for (i in 0 until 3) {
                    val p = (phase + i / 3f) % 1f
                    val ringR = r * (1.05f + 0.3f * p + 0.25f * smooth * p)
                    drawCircle(
                        Brush.horizontalGradient(listOf(GakimColors.Voice, GakimColors.Accent)),
                        radius = ringR, center = c,
                        alpha = (1f - p) * (0.25f + 0.6f * smooth),
                        style = Stroke(width = 4.dp.toPx()),
                    )
                }
            }
            val orbR = r * scale
            drawCircle(
                Brush.linearGradient(
                    listOf(GakimColors.Voice, GakimColors.Accent),
                    start = Offset(c.x - orbR, c.y - orbR), end = Offset(c.x + orbR, c.y + orbR),
                ),
                radius = orbR, center = c, alpha = if (dimmed) 0.35f else 1f,
            )
            drawCircle(
                Brush.radialGradient(
                    listOf(GakimColors.Text.copy(alpha = 0.35f), GakimColors.Text.copy(alpha = 0f)),
                    center = Offset(c.x - orbR * 0.35f, c.y - orbR * 0.4f), radius = orbR * 0.9f,
                ),
                radius = orbR, center = c,
            )
            val iconSize = orbR * 0.8f
            translate(c.x - iconSize / 2f, c.y - iconSize / 2f) {
                drawIcon(Icon.MIC, GakimColors.OnAccent, iconSize)
            }
        }
    }
}

/** 작은 마이크(비교·안내 화면용). */
@Composable
fun CompactMic(
    listening: Boolean,
    level: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val smooth by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "level")
    Box(modifier.size(88.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                Modifier.size(88.dp * (1f + 0.12f * smooth)).clip(CircleShape)
                    .background(GakimColors.Voice.copy(alpha = 0.25f + 0.3f * smooth)),
            )
        }
        Box(Modifier.size(76.dp).clip(CircleShape).background(GakimColors.brandH()), contentAlignment = Alignment.Center) {
            Glyph(Icon.MIC, GakimColors.OnAccent, 40.dp)
        }
    }
}
