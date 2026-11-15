package kr.gakim.charge.domain.speech

sealed interface SpeechCommand {
    data class SelectIndex(val index: Int) : SpeechCommand
    data class SelectByName(val name: String) : SpeechCommand
    data object SelectFastest : SpeechCommand
    data object SelectRecommended : SpeechCommand
    data class PreferCharging(val charging: Boolean) : SpeechCommand
    data object Confirm : SpeechCommand
    data object Recheck : SpeechCommand
    data object Parked : SpeechCommand
    data object Back : SpeechCommand
    data object Restart : SpeechCommand
    data object None : SpeechCommand
}

/** Commands are deliberately conservative: a negated instruction never authorizes navigation. */
object SpeechCommandParser {
    private fun compact(text: String) = text.lowercase().replace(Regex("[\\s,.!?~]"), "")
    private fun alias(text: String) = compact(text).replace("에이", "a").replace("비마트", "b마트")

    fun parse(text: String, martNames: List<String> = emptyList()): SpeechCommand {
        if (text.contains('?') || text.contains('？')) return SpeechCommand.None
        val raw = compact(text)
        val correction = Regex("^\\s*아니요\\s*,\\s*(.+)$").matchEntire(text)?.groupValues?.get(1)?.let(::compact)
        val explicitConfirm = setOf("안내해줘", "이대로안내", "출발", "확정", "추가해줘")
        val t = correction?.takeIf { it in explicitConfirm } ?: raw
        if (Regex("줄까|해도돼|할까|아직|글쎄|잠깐").containsMatchIn(t)) return SpeechCommand.None
        if (t.startsWith("음")) return SpeechCommand.None
        if (t == "다시확인") return SpeechCommand.Recheck
        if (t.isEmpty()) return SpeechCommand.None
        if (t in listOf("처음으로", "다시", "다시시작", "처음부터")) return SpeechCommand.Restart
        if (t in listOf("아니", "아니요", "취소", "뒤로", "뒤로가", "돌아가")) return SpeechCommand.Back
        if (Regex("하지마|하지않|안내하지|안갈|싫|아니|안해|취소").containsMatchIn(t)) return SpeechCommand.None
        if (t in listOf("주차중이야", "주차중이에요", "주차했어", "주차했어요", "주차중입니다")) return SpeechCommand.Parked
        if (t in listOf("응", "네", "그래", "좋아", "안내해줘", "이대로안내", "출발", "확정", "추가해줘")) return SpeechCommand.Confirm
        if (t in listOf("충전없이", "충전없이가", "충전안하는걸로", "장보기만", "충전빼고", "충전은빼고")) return SpeechCommand.PreferCharging(false)
        if (t in listOf("충전하는걸로", "충전하는곳", "충전도", "충전포함", "함께충전")) return SpeechCommand.PreferCharging(true)
        if (t in listOf("첫번째", "첫째", "1번", "왼쪽", "왼쪽으로")) return SpeechCommand.SelectIndex(0)
        if (t in listOf("두번째로가자", "두번째", "둘째", "2번", "오른쪽", "오른쪽으로")) return SpeechCommand.SelectIndex(1)
        if (t in listOf("빠른데로가줘", "빠른데로", "빠른곳", "가장빠른곳", "제일빠른곳", "빠른걸로")) return SpeechCommand.SelectFastest
        if (t in listOf("추천대로", "추천", "추천하는곳", "그걸로", "추천한곳")) return SpeechCommand.SelectRecommended
        val normalized = alias(t.substringAfterLast("말고"))
        val matches = martNames.filter { name ->
            val key = alias(name.substringBefore("("))
            key.isNotEmpty() && normalized in listOf(key, "${key}로", "${key}으로", "${key}로가", "${key}으로가", "${key}로가자")
        }
        return matches.singleOrNull()?.let { SpeechCommand.SelectByName(it) } ?: SpeechCommand.None
    }
}
