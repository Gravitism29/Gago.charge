package kr.gakim.charge.domain.speech

/**
 * 음성 인식 결과 문자열에서 시각을 뽑는 규칙 기반 파서. LLM·네트워크를 쓰지 않는다.
 *
 * - "네 시", "4시", "세 시 반", "네 시 삼십 분", "4시 30분", "16:30", "네 시 십 분 전"
 * - 정정 표현은 마지막 값 우선: "네 시, 아니 네 시 반" → 네 시 반
 * - 분만 고쳐 말한 경우: "네 시, 아니 반" → 네 시 반
 * - 오전/오후가 없으면 현재 시각([parse] 의 nowMinutes) 이후에 처음 오는 쪽을 고른다.
 * - "한 시간", "두 시간 뒤" 같은 길이 표현은 시각으로 보지 않는다.
 */
object KoreanTimeParser {

    /** 문장 안에서 찾은 시각 하나. */
    data class Mention(
        val position: Int,
        /** 말한 그대로의 시(0~24). */
        val hour: Int,
        val minute: Int,
        /** true=오후, false=오전, null=말하지 않음. */
        val pm: Boolean?,
        /** "밤 열두 시"처럼 자정을 뜻하는 표현인가. */
        val night: Boolean = false,
        val subtractMinutes: Int = 0,
    )

    private val nativeHours = linkedMapOf(
        "열여덟" to 18, "열일곱" to 17, "열여섯" to 16, "열다섯" to 15, "열네" to 14, "열세" to 13, "열아홉" to 19, "스무" to 20, "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7,
        "여섯" to 6, "다섯" to 5, "네" to 4, "세" to 3, "두" to 2, "한" to 1,
    )
    private val sinoDigits = mapOf(
        '일' to 1, '이' to 2, '삼' to 3, '사' to 4, '오' to 5, '육' to 6, '칠' to 7, '팔' to 8, '구' to 9,
    )

    private const val MERIDIEM = "(오전|오후|아침|새벽|낮|저녁|밤)"
    private const val HOUR = "(\\d{1,2}|열여덟|열일곱|열여섯|열다섯|열아홉|열네|열세|스무|열\\s?두|열\\s?한|열|아홉|여덟|일곱|여섯|다섯|네|세|두|한)"
    private const val HALF = "반(?=\\s|\$|[,.!?~]|까지|까정|쯤|에|으로|이|인디|정도|요|은|엔)"
    private const val MINUTE = "(?:($HALF)|(\\d{1,2})\\s*분|([일이삼사오육칠팔구십]{1,3})\\s*분)"

    // 1: 오전/오후, 2: 시, 3: 반, 4: 숫자 분, 5: 한자어 분, 6: 전
    private val spoken = Regex("(?:$MERIDIEM\\s*)?$HOUR\\s*시(?!간)(?:\\s*$MINUTE(\\s*전)?)?")

    // 1: 오전/오후, 2: 시, 3: 분
    private val colon = Regex("(?:$MERIDIEM\\s*)?(\\d{1,2}):(\\d{2})")

    // 정정 표시 바로 뒤에 분만 말한 경우. 1: 반, 2: 숫자 분, 3: 한자어 분
    private val minuteOnlyCorrection = Regex("(?:아니[야다고]?|말고|정정)[,\\s]*$MINUTE")

    /** Mask only time expressions accepted by [mentions], retaining all other words for role checks. */
    fun maskTimeExpressions(text: String): String {
        val acceptedPositions = mentions(text).map { it.position }.toSet()
        val masked = text.toCharArray()
        for (pattern in listOf(spoken, colon, minuteOnlyCorrection)) {
            for (match in pattern.findAll(text)) {
                if (match.range.first in acceptedPositions) {
                    for (index in match.range) masked[index] = ' '
                }
            }
        }
        return String(masked)
    }

    /**
     * @param nowMinutes 현재 시각(자정부터 분). 오전/오후를 말하지 않았을 때 기준으로 쓴다.
     * @return 자정부터 센 분(0~1439). 시각이 없으면 null.
     */
    fun parse(text: String, nowMinutes: Int? = null): Int? {
        val last = mentions(text).lastOrNull() ?: return null
        return resolve(last, nowMinutes)
    }

    /** 문장에 나온 시각을 말한 순서대로 돌려준다(정정 반영 후). */
    fun mentions(text: String): List<Mention> {
        val found = mutableListOf<Mention>()
        for (m in spoken.findAll(text)) {
            val hour = hourOf(m.groupValues[2]) ?: continue
            if (hour > 24 || (m.range.first > 0 && text[m.range.first - 1].isDigit())) continue
            var minute = when {
                m.groupValues[3].isNotEmpty() -> 30
                m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
                m.groupValues[5].isNotEmpty() -> sinoNumber(m.groupValues[5]) ?: continue
                else -> 0
            }
            if (minute > 59) continue
            if (hour == 24 && minute != 0) continue
            val subtract = if (m.groupValues[6].isNotEmpty()) minute else 0
            found += mention(m.range.first, hour, if (subtract > 0) 0 else minute, m.groupValues[1])
                .copy(subtractMinutes = subtract)
        }
        for (m in colon.findAll(text)) {
            val hour = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toInt()
            if (hour > 24 || minute > 59 || (hour == 24 && minute != 0) ||
                (m.range.first > 0 && text[m.range.first - 1].isDigit())) continue
            found += mention(m.range.first, hour, minute, m.groupValues[1])
        }
        found.sortBy { it.position }

        // "네 시, 아니 반": 앞에서 말한 시는 그대로 두고 분만 바꾼다.
        for (m in minuteOnlyCorrection.findAll(text)) {
            val base = found.lastOrNull { it.position < m.range.first } ?: continue
            val minute = when {
                m.groupValues[1].isNotEmpty() -> 30
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
                else -> sinoNumber(m.groupValues[3]) ?: continue
            }
            if (minute > 59) continue
            found += base.copy(position = m.range.first, minute = minute)
        }
        found.sortBy { it.position }

        // 오전/오후를 다시 말하지 않은 정정은 앞에서 말한 것을 이어받는다.
        var carriedPm: Boolean? = null
        var carriedNight = false
        return found.map {
            if (it.pm != null) {
                carriedPm = it.pm
                carriedNight = it.night
                it
            } else {
                it.copy(pm = carriedPm, night = carriedNight)
            }
        }
    }

    /** 말한 시각을 자정 기준 분으로 바꾼다. */
    fun resolve(mention: Mention, nowMinutes: Int? = null): Int {
        if (mention.subtractMinutes > 0) return resolve(mention.copy(subtractMinutes = 0), nowMinutes) - mention.subtractMinutes
        val h = mention.hour
        val min = mention.minute
        if (h == 0 || h == 24) return min
        if (h > 12) return h * 60 + min
        val am = (h % 12) * 60 + min
        val pm = am + 720
        return when (mention.pm) {
            true -> if (h == 12 && mention.night) am else pm
            false -> am
            null ->
                if (nowMinutes != null) {
                    listOf(am, pm).firstOrNull { it >= nowMinutes } ?: pm
                } else {
                    // 기준 시각이 없으면 1~6시와 12시는 오후, 7~11시는 오전으로 본다.
                    if (h in 7..11) am else pm
                }
        }
    }

    private fun mention(position: Int, hour: Int, minute: Int, meridiem: String): Mention {
        val pm = when (meridiem) {
            "오전", "아침", "새벽" -> false
            "오후", "낮", "저녁", "밤" -> true
            else -> null
        }
        return Mention(position, hour, minute, pm, night = meridiem == "밤")
    }

    private fun hourOf(token: String): Int? {
        val t = token.replace(" ", "")
        return t.toIntOrNull() ?: nativeHours[t]
    }

    /** "십", "십오", "이십", "삼십오", "오" 같은 한자어 수(1~99). */
    internal fun sinoNumber(token: String): Int? {
        if (token.isEmpty()) return null
        val tenAt = token.indexOf('십')
        if (tenAt < 0) {
            return if (token.length == 1) sinoDigits[token[0]] else null
        }
        if (token.indexOf('십', tenAt + 1) >= 0) return null
        val tens = when (tenAt) {
            0 -> 1
            1 -> sinoDigits[token[0]] ?: return null
            else -> return null
        }
        val rest = token.substring(tenAt + 1)
        val ones = when (rest.length) {
            0 -> 0
            1 -> sinoDigits[rest[0]] ?: return null
            else -> return null
        }
        return tens * 10 + ones
    }
}
