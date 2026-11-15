package kr.gakim.charge.domain.speech

import kr.gakim.charge.domain.ChargePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanTimeParserTest {

    private val at15 = 15 * 60
    private fun hm(h: Int, m: Int = 0) = h * 60 + m
    private fun parse(text: String, now: Int? = at15) = KoreanTimeParser.parse(text, now)

    // ---- 지시서에 나온 예 ----

    @Test fun correction_lastValueWins() = assertEquals(hm(16, 30), parse("네 시, 아니 네 시 반"))
    @Test fun digitsWithUntil() = assertEquals(hm(16), parse("4시까지"))
    @Test fun nativeHourAndHalf() = assertEquals(hm(15, 30), parse("세 시 반"))
    @Test fun fullSentence() = assertEquals(hm(16), parse("장 보고 4시까지 애 데리러 가야 해"))

    // ---- 표기 변형 ----

    @Test fun noSpaces() = assertEquals(hm(16, 30), parse("네시반까지"))
    @Test fun sinoMinutes() = assertEquals(hm(16, 30), parse("네 시 삼십 분"))
    @Test fun sinoMinutesFifteen() = assertEquals(hm(16, 15), parse("네 시 십오 분까지"))
    @Test fun sinoMinutesFortyFive() = assertEquals(hm(15, 45), parse("세 시 사십오 분"))
    @Test fun digitMinutes() = assertEquals(hm(16, 20), parse("4시 20분"))
    @Test fun colonForm() = assertEquals(hm(16, 30), parse("16:30까지"))
    @Test fun colonFormTwelveHour() = assertEquals(hm(16, 30), parse("4:30"))
    @Test fun twentyFourHour() = assertEquals(hm(16), parse("16시"))
    @Test fun elevenNotOne() = assertEquals(hm(11), parse("열한 시", now = hm(9)))
    @Test fun twelve() = assertEquals(hm(12), parse("열두 시", now = hm(9)))
    @Test fun spacedEleven() = assertEquals(hm(11), parse("열 한 시", now = hm(9)))
    @Test fun five() = assertEquals(hm(17), parse("다섯 시까지"))
    @Test fun minutesBefore() = assertEquals(hm(15, 50), parse("네 시 십 분 전"))
    @Test fun beforeWithoutMinutesIsTheHour() = assertEquals(hm(16), parse("네 시 전까지"))

    // ---- 오전/오후 ----

    @Test fun explicitPm() = assertEquals(hm(16), parse("오후 네 시", now = hm(9)))
    @Test fun explicitAm() = assertEquals(hm(9, 30), parse("오전 아홉 시 반", now = hm(8)))
    @Test fun evening() = assertEquals(hm(19), parse("저녁 일곱 시", now = hm(9)))
    @Test fun midnight() = assertEquals(0, parse("밤 열두 시"))
    @Test fun noon() = assertEquals(hm(12), parse("낮 열두 시", now = hm(9)))
    @Test fun afternoonContextPicksNextOccurrence() = assertEquals(hm(16), parse("네 시"))
    @Test fun morningContextPicksMorning() = assertEquals(hm(10), parse("열 시", now = hm(8)))
    @Test fun morningContextAfternoonHour() = assertEquals(hm(16), parse("네 시", now = hm(8)))
    @Test fun alreadyPassedStaysAfternoon() = assertEquals(hm(14), parse("두 시"))
    @Test fun noContextDefaultsAfternoonForSmallHours() = assertEquals(hm(16), parse("네 시", now = null))
    @Test fun noContextDefaultsMorningForNine() = assertEquals(hm(9), parse("아홉 시", now = null))

    // ---- 정정 ----

    @Test fun correctionWithAniya() = assertEquals(hm(17), parse("네 시 아니야 다섯 시"))
    @Test fun correctionMinuteOnlyHalf() = assertEquals(hm(16, 30), parse("네 시, 아니 반"))
    @Test fun correctionMinuteOnlySino() = assertEquals(hm(16, 20), parse("네 시까지, 아니 이십 분까지"))
    @Test fun correctionBackToEarlier() = assertEquals(hm(16), parse("네 시 반, 아니다 네 시"))
    @Test fun correctionKeepsMeridiem() = assertEquals(hm(16, 30), parse("오후 네 시, 아니 네 시 반", now = hm(9)))
    @Test fun correctionCanChangeMeridiem() = assertEquals(hm(9), parse("오후 네 시, 아니 오전 아홉 시", now = hm(8)))
    @Test fun threeMentionsLastWins() = assertEquals(hm(17, 30), parse("세 시, 아니 네 시, 아니 다섯 시 반"))

    @Test
    fun mentionsAreKeptInOrder() {
        val all = KoreanTimeParser.mentions("네 시, 아니 네 시 반").map { it.hour to it.minute }
        assertEquals(listOf(4 to 0, 4 to 30), all)
    }

    // ---- 시각이 아닌 것 ----

    @Test fun durationIsNotATime() = assertNull(parse("한 시간 뒤에 가자"))
    @Test fun durationDigits() = assertNull(parse("2시간 걸려"))
    @Test fun noTime() = assertNull(parse("장 보고 애 데리러 가야 해"))
    @Test fun emptyText() = assertNull(parse(""))
    @Test fun sideDishIsNotHalf() = assertEquals(hm(16), parse("네 시 반찬 사야 해"))
    @Test fun invalidHour() = assertNull(parse("30시"))
    @Test fun againIsNotATime() = assertNull(parse("다시 말해 줘"))

    @Test
    fun sinoNumbers() {
        assertEquals(10, KoreanTimeParser.sinoNumber("십"))
        assertEquals(15, KoreanTimeParser.sinoNumber("십오"))
        assertEquals(20, KoreanTimeParser.sinoNumber("이십"))
        assertEquals(55, KoreanTimeParser.sinoNumber("오십오"))
        assertEquals(5, KoreanTimeParser.sinoNumber("오"))
        assertNull(KoreanTimeParser.sinoNumber("십십"))
    }
}

class SpeechIntentParserTest {

    private val at15 = 15 * 60
    private fun parse(text: String) = SpeechIntentParser.parse(text, at15)

    @Test
    fun briefExample() {
        val i = parse("장 보고 4시까지 애 데리러 가야 해")
        assertEquals("장보기", i.activity)
        assertEquals(true, i.pickup)
        assertEquals(SpokenDeadline.At(16 * 60), i.deadline)
        assertNull(i.charge)
    }

    @Test
    fun correctedTime() {
        val i = parse("장 보고 네 시, 아니 네 시 반까지 애 데리러 가야 해")
        assertEquals(SpokenDeadline.At(16 * 60 + 30), i.deadline)
    }

    @Test
    fun shoppingOnly() {
        val i = parse("오늘은 장만 보면 돼")
        assertEquals("장보기", i.activity)
        assertEquals(false, i.pickup)
        assertNull(i.deadline)
    }

    @Test
    fun anytime() {
        val i = parse("장 보고 학교 가는데 몇 시든 괜찮아")
        assertEquals(SpokenDeadline.Anytime, i.deadline)
    }

    @Test fun chargeWanted() = assertEquals(ChargePreference.WANT, parse("가는 김에 충전도 하고 싶어").charge)
    @Test fun chargeDeclined() = assertEquals(ChargePreference.DECLINE, parse("충전은 안 해도 돼").charge)
    @Test fun chargeNotNeeded() = assertEquals(ChargePreference.DECLINE, parse("오늘 충전 필요 없어").charge)
    @Test fun chargeAuto() = assertEquals(ChargePreference.AUTO, parse("충전은 알아서 해 줘").charge)
    @Test fun chargeCorrectionLastWins() = assertEquals(ChargePreference.DECLINE, parse("충전해 줘, 아니 충전 안 해도 돼").charge)

    @Test
    fun nothingUnderstood() {
        assertTrue(parse("음 그러니까").isEmpty)
    }
}
