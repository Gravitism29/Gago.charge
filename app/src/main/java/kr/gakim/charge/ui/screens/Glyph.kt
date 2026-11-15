package kr.gakim.charge.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 외부 아이콘 라이브러리 없이 Canvas 로 그리는 단순한 아이콘 모음. */
enum class Icon { CHECK, CROSS, BOLT, CART, PIN, CLOCK, MIC, SPEAKER, SPEAKER_OFF, BATTERY, WARN, LOCK, FLAG, DOT, CAR }

@Composable
fun Glyph(icon: Icon, color: Color, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIcon(icon, color) }
}

fun DrawScope.drawIcon(icon: Icon, color: Color, s: Float = size.minDimension) {
    val w = s * 0.1f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (icon) {
        Icon.CHECK -> drawPath(Path().apply { moveTo(.18f * s, .54f * s); lineTo(.42f * s, .76f * s); lineTo(.84f * s, .26f * s) }, color, style = Stroke(w * 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        Icon.CROSS -> {
            drawLine(color, p(.24f, .24f), p(.76f, .76f), w * 1.3f, StrokeCap.Round)
            drawLine(color, p(.76f, .24f), p(.24f, .76f), w * 1.3f, StrokeCap.Round)
        }
        Icon.BOLT -> drawPath(Path().apply {
            moveTo(.58f * s, .04f * s); lineTo(.2f * s, .56f * s); lineTo(.47f * s, .56f * s)
            lineTo(.4f * s, .96f * s); lineTo(.8f * s, .4f * s); lineTo(.53f * s, .4f * s); close()
        }, color)
        Icon.CART -> {
            drawPath(Path().apply { moveTo(.06f * s, .14f * s); lineTo(.2f * s, .14f * s); lineTo(.3f * s, .62f * s); lineTo(.82f * s, .62f * s); lineTo(.9f * s, .28f * s); lineTo(.24f * s, .28f * s) }, color, style = stroke)
            drawCircle(color, s * .07f, p(.38f, .82f)); drawCircle(color, s * .07f, p(.74f, .82f))
        }
        Icon.PIN -> {
            drawPath(Path().apply {
                moveTo(.5f * s, .94f * s); lineTo(.24f * s, .52f * s)
                arcTo(androidx.compose.ui.geometry.Rect(.2f * s, .06f * s, .8f * s, .66f * s), 150f, 240f, false)
                close()
            }, color, style = stroke)
            drawCircle(color, s * .09f, p(.5f, .36f))
        }
        Icon.CLOCK -> {
            drawCircle(color, s * .4f, p(.5f, .5f), style = stroke)
            drawLine(color, p(.5f, .5f), p(.5f, .27f), w, StrokeCap.Round)
            drawLine(color, p(.5f, .5f), p(.66f, .6f), w, StrokeCap.Round)
        }
        Icon.MIC -> {
            drawRoundRect(color, p(.36f, .06f), Size(.28f * s, .5f * s), androidx.compose.ui.geometry.CornerRadius(.14f * s))
            drawArc(color, 0f, 180f, false, p(.22f, .26f), Size(.56f * s, .5f * s), style = stroke)
            drawLine(color, p(.5f, .76f), p(.5f, .94f), w, StrokeCap.Round)
            drawLine(color, p(.34f, .94f), p(.66f, .94f), w, StrokeCap.Round)
        }
        Icon.SPEAKER, Icon.SPEAKER_OFF -> {
            drawPath(Path().apply {
                moveTo(.1f * s, .38f * s); lineTo(.28f * s, .38f * s); lineTo(.5f * s, .18f * s)
                lineTo(.5f * s, .82f * s); lineTo(.28f * s, .62f * s); lineTo(.1f * s, .62f * s); close()
            }, color)
            if (icon == Icon.SPEAKER) {
                drawArc(color, -45f, 90f, false, p(.4f, .32f), Size(.3f * s, .36f * s), style = stroke)
                drawArc(color, -50f, 100f, false, p(.34f, .2f), Size(.5f * s, .6f * s), style = stroke)
            } else {
                drawLine(color, p(.64f, .36f), p(.9f, .64f), w, StrokeCap.Round)
                drawLine(color, p(.9f, .36f), p(.64f, .64f), w, StrokeCap.Round)
            }
        }
        Icon.BATTERY -> {
            drawRoundRect(color, p(.04f, .26f), Size(.8f * s, .48f * s), androidx.compose.ui.geometry.CornerRadius(.08f * s), style = stroke)
            drawRoundRect(color, p(.14f, .36f), Size(.5f * s, .28f * s), androidx.compose.ui.geometry.CornerRadius(.03f * s))
            drawRoundRect(color, p(.88f, .42f), Size(.08f * s, .16f * s), androidx.compose.ui.geometry.CornerRadius(.02f * s))
        }
        Icon.WARN -> {
            drawPath(Path().apply { moveTo(.5f * s, .1f * s); lineTo(.92f * s, .84f * s); lineTo(.08f * s, .84f * s); close() }, color, style = stroke)
            drawLine(color, p(.5f, .38f), p(.5f, .6f), w, StrokeCap.Round)
            drawCircle(color, s * .05f, p(.5f, .73f))
        }
        Icon.LOCK -> {
            drawRoundRect(color, p(.2f, .44f), Size(.6f * s, .46f * s), androidx.compose.ui.geometry.CornerRadius(.08f * s))
            drawArc(color, 180f, 180f, false, p(.3f, .1f), Size(.4f * s, .7f * s), style = stroke)
        }
        Icon.FLAG -> {
            drawLine(color, p(.22f, .08f), p(.22f, .94f), w, StrokeCap.Round)
            drawPath(Path().apply { moveTo(.22f * s, .12f * s); lineTo(.84f * s, .12f * s); lineTo(.66f * s, .34f * s); lineTo(.84f * s, .56f * s); lineTo(.22f * s, .56f * s); close() }, color)
        }
        Icon.DOT -> drawCircle(color, s * .22f, p(.5f, .5f))
        Icon.CAR -> {
            drawPath(Path().apply { moveTo(.5f * s, .06f * s); lineTo(.86f * s, .9f * s); lineTo(.5f * s, .7f * s); lineTo(.14f * s, .9f * s); close() }, color)
        }
    }
}
