package kr.gakim.charge.domain.speech

import kr.gakim.charge.domain.ChargePreference

/** 말에서 알아낸 마감. */
sealed interface SpokenDeadline {
    data class At(val minutes: Int) : SpokenDeadline

    /** "몇 시든 괜찮아" */
    data object Anytime : SpokenDeadline
}

/**
 * 한 문장에서 알아낸 것. null 은 '말하지 않음'이며 화면의 기존 선택을 바꾸지 않는다.
 */
data class SpeechIntent(
    val activity: String? = null,
    /** true=픽업 있음, false=장만 보는 날. */
    val pickup: Boolean? = null,
    val deadline: SpokenDeadline? = null,
    val charge: ChargePreference? = null,
    val activityMinutes: Int? = null,
    val clarification: String? = null,
) {
    val isEmpty: Boolean get() = activity == null && pickup == null && deadline == null && charge == null
}

/**
 * "장 보고 4시까지 애 데리러 가야 해" 같은 문장을 규칙으로 해석한다. LLM·네트워크를 쓰지 않는다.
 * 알아듣지 못한 부분은 채우지 않으며, 사용자는 버튼으로 언제든 고칠 수 있다.
 */
object SpeechIntentParser {

    const val ACTIVITY_SHOPPING = "장보기"

    private val shopping = Regex("장만\\s?보|장\\s?을?\\s?보|장\\s?봐|마트")
    private val noPickup = Regex("장만\\s?보|장보기만|장\\s?보기만|픽업\\s?(은|이)?\\s?없|데리러\\s?안|데리러\\s?갈\\s?필요\\s?없")
    private val pickup = Regex("데리러|데리고|데려|픽업|태우러|마중")
    private val anytime = Regex("몇\\s?시든|아무\\s?때나|언제든|시간(은|이)?\\s?상관\\s?없|시간(은|이)?\\s?괜찮")
    private val chargeDecline = Regex(
        "충전(은|도|을|이)?\\s*(안|하지\\s?않|하지\\s?마|필요\\s?없|말고|됐|괜찮|빼|없이)"
    )

    private val correction = Regex("아니(?:다|야)?(?=[,\\s]|$)")

    fun parse(text: String, nowMinutes: Int? = null): SpeechIntent {
        if (Regex("내일|모레").containsMatchIn(text)) return SpeechIntent(clarification = "오늘 일정의 시각을 말해 주세요.")
        if (Regex("[가-힣]+에서.*(데리러|픽업)").containsMatchIn(text))
            return SpeechIntent(clarification = "픽업 장소를 내비 목적지로 설정한 뒤 다시 입력해 주세요.")
        if (Regex("분\\s*반\\s*(뒤|후)").containsMatchIn(text))
            return SpeechIntent(clarification = "분 단위로 시간을 말해 주세요.")
        val corrected = correction.findAll(text).lastOrNull()?.let { text.substring(it.range.last + 1) } ?: text
        val activity = if (shopping.containsMatchIn(corrected) &&
            !Regex("장\\s*(보지\\s*(마|말)|안\\s*봐)|마트는?\\s*안").containsMatchIn(corrected)) ACTIVITY_SHOPPING else null
        val explicitPickup = pickup.containsMatchIn(corrected)
        val pickupIntent = when {
            Regex("안\\s*데리러|데리러\\s*안|데리러\\s*갈\\s*필요\\s*없|픽업\\s*(은|이)?\\s*(없|안)|데리러\\s*가지\\s*마").containsMatchIn(corrected) -> false
            explicitPickup -> true
            noPickup.containsMatchIn(corrected) -> false
            else -> null
        }
        val deadline = when {
            anytime.containsMatchIn(corrected) -> SpokenDeadline.Anytime
            else -> (relativeTime(corrected, nowMinutes) ?: deadlineTime(text, nowMinutes))?.let { SpokenDeadline.At(it) }
        }
        val duration = Regex("(?:장보기|장\\s*보고)\\s*(\\d+|[일이삼사오육칠팔구십]+)\\s*분").find(corrected)
            ?.groupValues?.get(1)?.let(::number)
        return SpeechIntent(activity = activity, activityMinutes = duration, pickup = pickupIntent, deadline = deadline, charge = chargeOf(text))
    }

    // Relative durations belong to sentence intent; the clock parser still rejects durations.
    private fun number(text: String): Int? = text.toIntOrNull()
        ?: mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4)[text]
        ?: KoreanTimeParser.sinoNumber(text)

    private fun relativeTime(text: String, now: Int?): Int? {
        if (now == null) return null
        val amount = "(\\d+|[일이삼사오육칠팔구십]+|한|두|세|네)"
        val m = Regex("(?:$amount\\s*시간\\s*)?(?:$amount\\s*분\\s*)?(반\\s*(?:시간)?)?\\s*(뒤|후)").find(text) ?: return null
        val hours = m.groupValues[1].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
        val minutes = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::number) ?: 0
        val half = if (m.groupValues[3].isNotEmpty()) 30 else 0
        if (hours + minutes + half == 0) return null
        val base = if (text.contains("출발")) KoreanTimeParser.parse(text.substringBefore("출발"), now) ?: now else now
        return base + hours * 60 + minutes + half
    }

    private fun deadlineTime(text: String, now: Int?): Int? {
        val timeText = text.substringAfterLast("말고")
        val mentions = KoreanTimeParser.mentions(timeText)
        val correctionAt = correction.findAll(text).lastOrNull()?.range?.first ?: -1
        val candidates = mentions.filter { it.position >= correctionAt }.ifEmpty { mentions }
        val chosen = candidates.firstOrNull { mention ->
            val end = mentions.firstOrNull { it.position > mention.position }?.position ?: text.length
            val clause = timeText.substring(mention.position, end.coerceAtMost(timeText.length))
            clause.contains("까지") || pickup.containsMatchIn(clause)
        } ?: candidates.lastOrNull() ?: return null
        return KoreanTimeParser.resolve(chosen, now) - if (Regex("시\\s*전에").containsMatchIn(timeText)) 1 else 0
    }

    /** Carry the charge topic into an elliptical correction. */
    private fun chargeOf(text: String): ChargePreference? {
        if (text.contains("충전소")) return null
        val at = text.lastIndexOf("충전")
        if (at < 0) return null
        val correctionAt = correction.findAll(text).lastOrNull()?.range?.last ?: -1
        val tail = if (correctionAt > at) "충전 " + text.substring(correctionAt + 1) else text.substring(at)
        val resolvedTail = tail.substringAfterLast("대신").substringAfterLast("말고")
        val compact = resolvedTail.replace(Regex("\\s+"), "")
        return when {
            compact.contains("안하면안돼") || compact.contains("안해도안돼") -> ChargePreference.WANT
            Regex("그만|하지마|하지않|안해|안하|필요없|말고|됐|괜찮|빼|없이").containsMatchIn(compact) -> ChargePreference.DECLINE
            chargeDecline.containsMatchIn(resolvedTail) -> ChargePreference.DECLINE
            Regex("자동|알아서").containsMatchIn(compact) -> ChargePreference.AUTO
            else -> ChargePreference.WANT
        }
    }
}
