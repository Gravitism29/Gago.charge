package kr.gakim.charge.ui.planner

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import kotlin.math.*
import kr.gakim.charge.ui.theme.*

// A critically damped spring curve with a fixed 200 ms settling window.
private val DialSpring = Easing { t ->
    val decay = (1f + 8f * t) * exp(-8f * t)
    ((1f - decay) / (1f - 9f * exp(-8f))).coerceIn(0f, 1f)
}

/** A sixty-minute rotary control, shared by stays, arrival deadlines and departure. */
@Composable
internal fun TimeDial(
    value: Int,
    mode: TimeDialMode,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = true,
    now: LocalDateTime = LocalDateTime.now(),
    clockRole: String = "도착 시각",
    onInvalidTime: () -> Unit = {},
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val invalid by rememberUpdatedState(onInvalidTime)
    val currentNow by rememberUpdatedState(now)
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val animated = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value, dragging) {
        if (dragging) animated.snapTo(value.toFloat())
        else animated.animateTo(value.toFloat(), tween(200, easing = DialSpring))
    }
    val thumbSize by animateDpAsState(if (pressed) 52.dp else 44.dp, tween(100), label = "dial thumb")
    val interactive = enabled && active
    val publish: (Int) -> Boolean = { requested ->
        val next = snapDialValue(requested.toFloat(), mode)
        if (!interactive) false
        else if (mode == TimeDialMode.Clock && !dialClockAllowed(next, currentNow)) {
            invalid(); false
        } else {
            if (next != current) {
                // LocalHapticFeedback is a no-op on devices without a vibrator.
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                change(next)
            }
            true
        }
    }
    val publishLatest by rememberUpdatedState(publish)
    val description = if (mode == TimeDialMode.Duration) "머무는 시간 ${value}분"
        else "$clockRole ${dialClockLabel(value, spoken = true)}"
    val picks = if (mode == TimeDialMode.Duration) listOf(15, 30, 45, 60, 90) else nextDialHalfHours(now)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mode == TimeDialMode.Clock) {
            Row(Modifier.width(320.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("오전", "오후").forEachIndexed { index, label ->
                    PlannerPill(label, { publish(dialMeridiem(value, index == 1)) }, Modifier.weight(1f),
                        interactive, (value / 60) % 24 / 12 == index)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(320.dp).alpha(if (interactive) 1f else .35f)
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(),
                        if (mode == TimeDialMode.Duration) 5f..180f else 0f..2875f,
                        if (mode == TimeDialMode.Duration) 34 else 574)
                    if (!interactive) disabled()
                    else setProgress { target -> publish(snapDialValue(target, mode)) }
                }
                .pointerInput(mode, interactive) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.width / 2f - 40.dp.toPx()
                        // The generous annulus includes the thumb and bezel, but leaves the centre alone.
                        if (abs((down.position - center).getDistance() - radius) > 44.dp.toPx()) return@awaitEachGesture
                        down.consume()
                        pressed = true
                        val startValue = current
                        fun angle(point: Offset) = normalizeDialAngle(
                            Math.toDegrees(atan2((point.y - center.y).toDouble(), (point.x - center.x).toDouble())).toFloat() + 90f)
                        var previous = angle(down.position)
                        var accumulated = 0f
                        var lastRequested = startValue
                        var moved = false
                        var cancelled = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                if (pointer == null) { cancelled = true; break }
                                if (pointer.isConsumed) { cancelled = true; break }
                                val nextAngle = angle(pointer.position)
                                accumulated += dialAngleDelta(previous, nextAngle)
                                previous = nextAngle
                                if (!moved && (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    moved = true
                                    dragging = true
                                }
                                if (moved) {
                                    val requested = dialDragValue(startValue, accumulated, mode)
                                    if (requested != lastRequested) {
                                        publishLatest(requested)
                                        lastRequested = requested
                                    }
                                }
                                pointer.consume() // Own the gesture, including its up event, so a sheet cannot scroll.
                                if (!pointer.pressed) break
                            }
                            if (!moved && !cancelled) publishLatest(dialAngleToValue(previous, startValue, mode))
                        } finally {
                            pressed = false
                            dragging = false
                        }
                    }
                }, contentAlignment = Alignment.Center) {
                val rendered = if (dragging) value.toFloat() else animated.value
                Canvas(Modifier.fillMaxSize()) {
                    val radius = size.minDimension / 2f - 40.dp.toPx()
                    val topLeft = center - Offset(radius, radius)
                    val arcSize = Size(radius * 2, radius * 2)
                    val laps = dialLaps(rendered)
                    val sweep = if (mode == TimeDialMode.Duration && laps.completed > 0) 360f else laps.remainder * 6f
                    val gradient = Brush.sweepGradient(GakimColors.BrandStops, center)
                    val stroke = 26.dp.toPx()
                    drawCircle(GakimColors.Outline, radius, style = Stroke(stroke))
                    if (pressed) drawIntoCanvas { canvas ->
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = stroke + 10.dp.toPx()
                            strokeCap = Paint.Cap.ROUND
                            shader = SweepGradient(center.x, center.y,
                                intArrayOf(GakimColors.Voice.toArgb(), GakimColors.Accent.toArgb()), null)
                            alpha = 77
                            maskFilter = BlurMaskFilter(10.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.save()
                        canvas.nativeCanvas.rotate(-90f, center.x, center.y)
                        canvas.nativeCanvas.drawArc(RectF(topLeft.x, topLeft.y, topLeft.x + arcSize.width, topLeft.y + arcSize.height), 0f, sweep, false, paint)
                        canvas.nativeCanvas.restore()
                    }
                    rotate(-90f) {
                        drawArc(gradient, 0f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        if (mode == TimeDialMode.Duration) {
                            // Extra hour laps remain visible even at exact multiples of sixty.
                            for (lap in 1..2) {
                                val extra = (rendered - lap * 60f).coerceIn(0f, 60f)
                                if (extra > 0) {
                                    val r = radius - (20 + (lap - 1) * 8).dp.toPx()
                                    drawArc(gradient, 0f, extra * 6f, false, center - Offset(r, r), Size(r * 2, r * 2), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                                }
                            }
                        }
                    }
                    val bezel = size.minDimension / 2f - 8.dp.toPx()
                    for (tick in 0 until 60) {
                        // Keep all twelve long major ticks; their labels sit just clockwise of them.
                        if (tick % 15 in 1..2) continue
                        val a = (tick * 6f - 90f) * PI.toFloat() / 180f
                        val major = tick % 5 == 0
                        val unit = Offset(cos(a), sin(a))
                        drawLine(if (major) GakimColors.Text else GakimColors.TextDim,
                            center + unit * (bezel - if (major) 12.dp.toPx() else 6.dp.toPx()), center + unit * bezel,
                            if (major) 3.dp.toPx() else 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = GakimColors.TextDim.toArgb(); textSize = 20.sp.toPx(); textAlign = Paint.Align.CENTER
                    }
                    drawIntoCanvas { canvas ->
                        val labels = if (mode == TimeDialMode.Duration) listOf("0", "15", "30", "45") else listOf("12", "3", "6", "9")
                        labels.forEachIndexed { i, label ->
                            val a = (i * 90f - 82.5f) * PI.toFloat() / 180f
                            val p = center + Offset(cos(a), sin(a)) * (bezel - 7.dp.toPx())
                            canvas.nativeCanvas.drawText(label, p.x, p.y - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
                        }
                    }
                    if (mode == TimeDialMode.Clock) {
                        val r = radius + 21.dp.toPx()
                        drawCircle(GakimColors.Voice.copy(alpha = .3f), r, style = Stroke(1.dp.toPx()))
                        val a = (valueToDialAngle(now.minute + now.second / 60f) - 90) * PI.toFloat() / 180f
                        drawCircle(GakimColors.Voice, 4.dp.toPx(), center + Offset(cos(a), sin(a)) * r)
                    }
                    val a = (valueToDialAngle(rendered) - 90) * PI.toFloat() / 180f
                    val thumb = center + Offset(cos(a), sin(a)) * radius
                    drawIntoCanvas { canvas ->
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.argb(110, 0, 0, 0)
                            maskFilter = BlurMaskFilter(7.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                        }
                        canvas.nativeCanvas.drawCircle(thumb.x, thumb.y + 3.dp.toPx(), thumbSize.toPx() / 2, paint)
                    }
                    drawCircle(Color.White, thumbSize.toPx() / 2, thumb)
                    drawCircle(Brush.linearGradient(GakimColors.BrandStops, thumb - Offset(20.dp.toPx(), 20.dp.toPx()), thumb + Offset(20.dp.toPx(), 20.dp.toPx())),
                        thumbSize.toPx() / 2 - 4.dp.toPx(), thumb, style = Stroke(4.dp.toPx()))
                }
                Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mode == TimeDialMode.Duration) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$value", style = GakimType.Big)
                            Text("분", style = GakimType.Body, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        if (value >= 60) Text("${value / 60}시간 ${value % 60}분", style = GakimType.Caption)
                    } else {
                        // 오전/오후는 작은 라벨, 시각만 크게: 두 줄이 같은 크기면 링 안이 답답해 보인다(에뮬레이터 확인).
                        val parts = dialClockLabel(value).split(' ', limit = 2)
                        Text(parts.first(), style = GakimType.BodyBold, color = GakimColors.TextDim)
                        Text(parts.last(), style = GakimType.Big, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                DialStepButton("+", "$description · 5분 늘리기", interactive && value < if (mode == TimeDialMode.Duration) 180 else 2875) { publish(value + 5) }
                DialStepButton("−", "$description · 5분 줄이기", interactive && value > if (mode == TimeDialMode.Duration) 5 else 0) { publish(value - 5) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (mode == TimeDialMode.Duration) "빠르게 고르기" else "가까운 시각", style = GakimType.Caption)
                picks.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { pick ->
                            val label = if (mode == TimeDialMode.Duration) "${pick}분"
                                else (if (pick >= MINUTES_PER_DAY) "내일 " else "") + clock(dialClockTime(pick, now))
                            PlannerPill(label, { publish(pick) }, Modifier.weight(1f), interactive, value == pick)
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DialStepButton(label: String, description: String, enabled: Boolean, click: () -> Unit) {
    Box(Modifier.size(96.dp).clip(CircleShape).background(GakimColors.SurfaceHigh)
        .border(2.dp, if (enabled) GakimColors.Voice else GakimColors.Outline, CircleShape)
        .semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = click), contentAlignment = Alignment.Center) {
        Text(label, style = GakimType.Big, color = if (enabled) GakimColors.Text else GakimColors.TextDim)
    }
}
