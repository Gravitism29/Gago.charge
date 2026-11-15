package kr.gakim.charge.domain.speech
import kr.gakim.charge.domain.ChargePreference
import org.junit.Assert.assertEquals
import org.junit.Test
class SpeechRegressionTest {
    @Test fun minuteOnlyCorrectionAfterUntil() = assertEquals(SpokenDeadline.At(990), SpeechIntentParser.parse("네 시까지, 아니 반", 900).deadline)
    @Test fun regression1() = assertEquals(SpokenDeadline.At(930), SpeechIntentParser.parse("삼십 분 뒤", 900).deadline)
    @Test fun regression2() = assertEquals(SpokenDeadline.At(930), SpeechIntentParser.parse("삼십 분 후", 900).deadline)
    @Test fun regression3() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("한 시간 뒤", 900).deadline)
    @Test fun regression4() = assertEquals(SpokenDeadline.At(990), SpeechIntentParser.parse("한 시간 반 뒤", 900).deadline)
    @Test fun regression5() = assertEquals(SpokenDeadline.At(990), SpeechIntentParser.parse("네 시 반인디", 900).deadline)
    @Test fun regression6() = assertEquals(SpokenDeadline.At(990), SpeechIntentParser.parse("네 시 반까정", 900).deadline)
    @Test fun regression7() = assertEquals(SpokenDeadline.At(990), SpeechIntentParser.parse("네 시 반쯤", 900).deadline)
    @Test fun regression8() = assertEquals(SpokenDeadline.At(770), SpeechIntentParser.parse("오후 한 시 십 분 전", 900).deadline)
    @Test fun regression9() = assertEquals(SpokenDeadline.At(1019), SpeechIntentParser.parse("다섯 시 전에", 900).deadline)
    @Test fun regression10() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("몇 시든 괜찮아, 아니 네 시까지", 900).deadline)
    @Test fun regression11() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("몇 시든 괜찮아, 아니다 네 시까지", 900).deadline)
    @Test fun regression12() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("몇 시든 괜찮아, 아니야 네 시까지", 900).deadline)
    @Test fun regression13() = assertEquals(ChargePreference.DECLINE, SpeechIntentParser.parse("충전해 줘, 아니 하지 마", 900).charge)
    @Test fun regression14() = assertEquals(ChargePreference.WANT, SpeechIntentParser.parse("충전 안 해도 돼, 아니 해 줘", 900).charge)
    @Test fun regression15() = assertEquals(ChargePreference.DECLINE, SpeechIntentParser.parse("충전 좀 하지 마", 900).charge)
    @Test fun regression16() = assertEquals(ChargePreference.WANT, SpeechIntentParser.parse("충전은 안 하면 안 돼", 900).charge)
    @Test fun regression17() = assertEquals(false, SpeechIntentParser.parse("애 안 데리러 가", 900).pickup)
    @Test fun regression18() = assertEquals(true, SpeechIntentParser.parse("장만 보고 애 데리러", 900).pickup)
    @Test fun regression19() = assertEquals(false, SpeechIntentParser.parse("장만 보고 갈게", 900).pickup)
    @Test fun regression20() = assertEquals(null, SpeechIntentParser.parse("장 보지 마", 900).activity)
    @Test fun regression21() = assertEquals(null, SpeechIntentParser.parse("장 안 봐", 900).activity)
    @Test fun regression22() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("네 시까지 애 데리러 가고 다섯 시에 장 봐", 900).deadline)
    @Test fun regression23() = assertEquals(SpokenDeadline.At(960), SpeechIntentParser.parse("네 시에 애 데리러 가고 다섯 시에 장 봐", 900).deadline)
}
