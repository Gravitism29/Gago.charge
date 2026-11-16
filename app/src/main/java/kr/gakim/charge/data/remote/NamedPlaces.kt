package kr.gakim.charge.data.remote

import kr.gakim.charge.domain.itinerary.*

internal val martPrefixes = listOf("이마트", "홈플러스", "롯데마트", "코스트코", "트레이더스")
private fun compact(text: String) = text.replace(Regex("\\s+"), "")
fun isGenericPlace(text: String) = compact(text) in setOf("학교", "병원", "집", "회사", "마트")
fun isBareMartBranch(text: String) = compact(text).endsWith("점") && martPrefixes.none { compact(text).startsWith(it) }
fun exactMartBranch(name: String, text: String): Boolean = martIdentity(name)?.branch?.let { compact(it) == compact(text) } == true

data class PlaceResolution(val place: Place? = null, val choices: List<Place> = emptyList(), val missing: Boolean = false)

/** Only an exact, unique name or a sole plausible result is safe to bind without asking. */
fun resolveNamedPlace(text: String, results: List<Place>): PlaceResolution {
    if (isGenericPlace(text)) return PlaceResolution()
    val real = results.filter { !it.demo && it.validCoordinates }.distinctBy { it.id }
    // "판교역" → "판교역 신분당선", "판교역 경강선"처럼 첫 어절이 같은 결과가 본체다(에뮬레이터 확인: 거리순이면 '○○ 판교역점' 가게가 먼저 나왔다).
    val exact = real.filter { compact(it.name) == compact(text) || compact(it.name.trim().substringBefore(' ')) == compact(text) }
    val sameSpot = exact.size > 1 && exact.all { distanceMeters(exact.first(), it) <= 400 }
    return when {
        exact.size == 1 || sameSpot -> PlaceResolution(place = exact.first())
        real.size == 1 -> PlaceResolution(place = real.single())
        real.isEmpty() -> PlaceResolution(missing = true)
        else -> PlaceResolution(choices = (exact.ifEmpty { real }).take(4))
    }
}
