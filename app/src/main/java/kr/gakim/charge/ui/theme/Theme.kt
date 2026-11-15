package kr.gakim.charge.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/* 차량 화면용: 어두운 배경, 큰 글씨, 가로형(≈1280x910dp 기준). 색·글자 크기는 docs/REDESIGN_SPEC.md §1. */

object GakimColors {
    val Background = Color(0xFF0B1016)
    val Surface = Color(0xFF141C24)
    val SurfaceHigh = Color(0xFF1D2833)
    /** 배경 대비 3:1 이상이 되도록 고른 테두리색(§8). */
    val Outline = Color(0xFF3A4A5A)
    val Accent = Color(0xFF3DF5B0)
    val OnAccent = Color(0xFF00241A)
    val Voice = Color(0xFF5CC8FF)
    val Text = Color(0xFFF4F7FA)
    val TextDim = Color(0xFF93A3B1)
    val Ok = Accent
    val Tight = Color(0xFFFFC857)
    val Late = Color(0xFFFF6B6B)
    val Unknown = Color(0xFF8FA0AE)
    val Demo = Color(0xFFFFC857)

    /** 브랜드 그라데이션: 하늘색(음성) → 민트(충전). */
    val BrandStops = listOf(Voice, Accent)
    fun brandH(): Brush = Brush.horizontalGradient(BrandStops)
}

/** 글자 크기 규칙: 비서 문장 40, 큰 숫자 60, 제목 28, 본문 22, 보조 20(최소). */
object GakimType {
    val Assistant = TextStyle(fontSize = 40.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, color = GakimColors.Text)
    val Big = TextStyle(fontSize = 60.sp, lineHeight = 64.sp, fontWeight = FontWeight.Bold, color = GakimColors.Text)
    val Title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = GakimColors.Text)
    val Body = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, color = GakimColors.Text)
    val BodyBold = Body.copy(fontWeight = FontWeight.SemiBold)
    val Caption = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, color = GakimColors.TextDim)
    val CaptionBold = Caption.copy(fontWeight = FontWeight.SemiBold)
}

private val colors = darkColorScheme(
    primary = GakimColors.Accent,
    onPrimary = GakimColors.OnAccent,
    background = GakimColors.Background,
    onBackground = GakimColors.Text,
    surface = GakimColors.Surface,
    onSurface = GakimColors.Text,
    surfaceVariant = GakimColors.SurfaceHigh,
    onSurfaceVariant = GakimColors.TextDim,
    error = GakimColors.Late,
)

private val typography = Typography(
    bodyLarge = GakimType.Body,
    bodyMedium = GakimType.Caption.copy(color = GakimColors.Text),
    titleLarge = GakimType.Title,
)

/** 화면 설계 기준 너비(dp). */
private const val DESIGN_WIDTH_DP = 1280f

/**
 * Pleos Connect 에뮬레이터 창은 약 1700x1210px(mdpi)이다. 너비를 1280dp 기준으로 환산해 전체를 같은 비율로 키우면
 * 1280x910dp 로 설계한 화면이 그대로 꽉 찬다. 1280dp 보다 좁은 화면에서는 키우지 않는다.
 */
@Composable
fun GakimTheme(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scale = (LocalConfiguration.current.screenWidthDp / DESIGN_WIDTH_DP).coerceIn(1f, 3f)
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
        MaterialTheme(colorScheme = colors, typography = typography) {
            Surface(modifier = Modifier.fillMaxSize(), color = GakimColors.Background, content = content)
        }
    }
}
