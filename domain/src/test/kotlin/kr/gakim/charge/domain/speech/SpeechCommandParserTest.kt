package kr.gakim.charge.domain.speech
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kr.gakim.charge.domain.speech.SpeechCommand.*

@RunWith(Parameterized::class)
class SpeechCommandParserTest(private val text: String, private val expected: SpeechCommand) {
    @Test fun command() = assertEquals(expected, SpeechCommandParser.parse(text, listOf("A마트", "B마트")))
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("첫 번째", SelectIndex(0)),
            arrayOf("첫째", SelectIndex(0)),
            arrayOf("1번", SelectIndex(0)),
            arrayOf("왼쪽", SelectIndex(0)),
            arrayOf("왼쪽으로", SelectIndex(0)),
            arrayOf("두 번째", SelectIndex(1)),
            arrayOf("둘째", SelectIndex(1)),
            arrayOf("2번", SelectIndex(1)),
            arrayOf("오른쪽", SelectIndex(1)),
            arrayOf("오른쪽으로", SelectIndex(1)),
            arrayOf("A마트", SelectByName("A마트")),
            arrayOf("에이마트", SelectByName("A마트")),
            arrayOf("에이 마트로", SelectByName("A마트")),
            arrayOf("a마트로 가", SelectByName("A마트")),
            arrayOf("A마트로 가자", SelectByName("A마트")),
            arrayOf("B마트", SelectByName("B마트")),
            arrayOf("비마트", SelectByName("B마트")),
            arrayOf("비 마트로", SelectByName("B마트")),
            arrayOf("b마트로 가", SelectByName("B마트")),
            arrayOf("빠른 데로", SelectFastest),
            arrayOf("빠른 곳", SelectFastest),
            arrayOf("가장 빠른 곳", SelectFastest),
            arrayOf("제일 빠른 곳", SelectFastest),
            arrayOf("빠른 걸로", SelectFastest),
            arrayOf("추천대로", SelectRecommended),
            arrayOf("그걸로", SelectRecommended),
            arrayOf("추천", SelectRecommended),
            arrayOf("추천하는 곳", SelectRecommended),
            arrayOf("충전하는 걸로", PreferCharging(true)),
            arrayOf("충전하는 곳", PreferCharging(true)),
            arrayOf("함께 충전", PreferCharging(true)),
            arrayOf("충전 없이", PreferCharging(false)),
            arrayOf("충전 없이 가", PreferCharging(false)),
            arrayOf("충전 안 하는 걸로", PreferCharging(false)),
            arrayOf("장보기만", PreferCharging(false)),
            arrayOf("응", Confirm),
            arrayOf("네", Confirm),
            arrayOf("그래", Confirm),
            arrayOf("좋아", Confirm),
            arrayOf("안내해 줘", Confirm),
            arrayOf("출발", Confirm),
            arrayOf("이대로 안내", Confirm),
            arrayOf("주차 중이야", Parked),
            arrayOf("주차 중이에요", Parked),
            arrayOf("주차했어", Parked),
            arrayOf("아니", Back),
            arrayOf("취소", Back),
            arrayOf("뒤로", Back),
            arrayOf("처음으로", Restart),
            arrayOf("다시", Restart),
            arrayOf("다시 시작", Restart),
            arrayOf("", None),
            arrayOf("음", None),
            arrayOf("아니 안내하지 마", None),
            arrayOf("안내하지 마", None),
            arrayOf("출발하지 마", None),
            arrayOf("주차 안 했어", None),
            arrayOf("네 시까지", None),
            arrayOf("오늘 날씨", None),
            arrayOf("C마트", None),
            arrayOf("A마트 말고", None),
            arrayOf("안내해 줘? 아니야", None),
            arrayOf("A마트 B마트", None),
            arrayOf("충전하지 마", None),
            arrayOf("안내하지 않아", None),
            arrayOf("주차 중 아니야", None),
        )
    }
}
