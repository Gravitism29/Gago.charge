package kr.gakim.charge.ui.planner

import java.time.LocalDateTime
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.roundToInt

internal enum class TimeDialMode { Duration, Clock }

internal const val DIAL_DETENT = 5
internal const val MINUTES_PER_DAY = 24 * 60

/** Unwrapped degrees, clockwise from twelve. One revolution always means sixty minutes. */
internal fun valueToDialAngle(value: Float): Float = value * 6f

internal fun normalizeDialAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

/** The signed short path preserves direction when a pointer crosses twelve. */
internal fun dialAngleDelta(previous: Float, current: Float): Float =
    ((current - previous + 540f) % 360f + 360f) % 360f - 180f

internal fun snapDialValue(value: Float, mode: TimeDialMode): Int {
    val snapped = (value / DIAL_DETENT).roundToInt() * DIAL_DETENT
    return when (mode) {
        TimeDialMode.Duration -> snapped.coerceIn(5, 180)
        TimeDialMode.Clock -> snapped.coerceIn(0, MINUTES_PER_DAY * 2 - DIAL_DETENT)
    }
}

/** A tap selects the closest detent in the current lap/hour, including seam rollover. */
internal fun dialAngleToValue(angle: Float, reference: Int, mode: TimeDialMode): Int =
    dialDragValue(reference, dialAngleDelta(normalizeDialAngle(valueToDialAngle(reference.toFloat())), angle), mode)

/** Deltas accumulate from the grabbed angle, never from the thumb's initial position. */
internal fun dialDragValue(start: Int, accumulatedDegrees: Float, mode: TimeDialMode): Int =
    snapDialValue(start + accumulatedDegrees / 6f, mode)

internal data class DialLaps(val completed: Int, val remainder: Float)

internal fun dialLaps(value: Float): DialLaps {
    val minutes = value.coerceAtLeast(0f)
    return DialLaps(floor(minutes / 60f).toInt(), minutes % 60f)
}

/** Absolute minutes retain noon/midnight and the selected today/tomorrow date. */
internal fun dialClockTime(value: Int, now: LocalDateTime): LocalDateTime =
    now.toLocalDate().atStartOfDay().plusMinutes(value.toLong())

internal fun clockDialValue(time: LocalDateTime, now: LocalDateTime): Int =
    (java.time.Duration.between(now.toLocalDate().atStartOfDay(), time).toMinutes()).toInt()

internal fun minimumDialClockValue(now: LocalDateTime): Int {
    val nanos = java.time.Duration.between(now.toLocalDate().atStartOfDay(), now.plusMinutes(10)).toNanos()
    return ceil(nanos / 300_000_000_000.0).toInt() * DIAL_DETENT
}

internal fun dialClockAllowed(value: Int, now: LocalDateTime): Boolean =
    value in 0 until MINUTES_PER_DAY * 2 && !dialClockTime(value, now).isBefore(now.plusMinutes(10))

internal fun dialMeridiem(value: Int, afternoon: Boolean): Int {
    val day = value / MINUTES_PER_DAY
    val halfDay = value % (12 * 60)
    return day * MINUTES_PER_DAY + halfDay + if (afternoon) 12 * 60 else 0
}

internal fun nextDialHalfHours(now: LocalDateTime): List<Int> {
    val first = now.withSecond(0).withNano(0).plusMinutes((30 - now.minute % 30).toLong())
    return (0..3).map { clockDialValue(first.plusMinutes(it * 30L), now) }
}

internal fun dialClockLabel(value: Int, spoken: Boolean = false): String {
    val minutes = Math.floorMod(value, MINUTES_PER_DAY)
    val hour = minutes / 60
    val h = (hour + 11) % 12 + 1
    val m = minutes % 60
    val period = if (hour < 12) "오전" else "오후"
    return if (spoken) "$period ${h}시 ${m}분" else "$period $h:${m.toString().padStart(2, '0')}"
}
