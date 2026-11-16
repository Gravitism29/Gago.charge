package kr.gakim.charge.domain.itinerary

import java.time.LocalDateTime

/** Shared clock wording for screens, accessibility and speech echoes. */
fun itineraryClock(time: LocalDateTime): String {
    val period = if (time.hour < 12) "오전" else "오후"
    val hour = (time.hour + 11) % 12 + 1
    return "$period $hour:${time.minute.toString().padStart(2, '0')}"
}
