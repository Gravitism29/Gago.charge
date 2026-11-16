package kr.gakim.charge.data.pleos

import kr.gakim.charge.domain.itinerary.Evidence
import kr.gakim.charge.domain.itinerary.Measured
import java.time.LocalDateTime

internal fun rangeFromMeters(meters: Double?, source: String): Measured<Double>? =
    meters?.takeIf { it.isFinite() && it > 0 }?.let { Measured(it / 1000.0, Evidence(source, LocalDateTime.now())) }

internal fun selectRange(android: Measured<Double>?, pleos: Measured<Double>?): Measured<Double>? =
    android?.takeIf { it.value.isFinite() && it.value > 0 }
        ?: pleos?.takeIf { it.value.isFinite() && it.value > 0 }
