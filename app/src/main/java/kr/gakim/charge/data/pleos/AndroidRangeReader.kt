package kr.gakim.charge.data.pleos

import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.gakim.charge.domain.itinerary.Evidence
import kr.gakim.charge.domain.itinerary.Measured
import java.time.LocalDateTime

/** Public Android Automotive RANGE_REMAINING, meters. No deprecated hidden Pleos range API. */
object AndroidRangeReader {
    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): Measured<Double>? = withContext(Dispatchers.IO) {
        var car: Car? = null
        try {
            car = Car.createCar(context)
            val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return@withContext null
            val value = manager.getProperty(Float::class.javaObjectType, VehiclePropertyIds.RANGE_REMAINING, 0)
            val meters = value?.value?.toDouble() ?: return@withContext null
            if (value.status != CarPropertyValue.STATUS_AVAILABLE || !meters.isFinite() || meters < 0 || value.timestamp <= 0) return@withContext null
            val ageNanos = SystemClock.elapsedRealtimeNanos() - value.timestamp
            if (ageNanos < 0) return@withContext null
            Measured(meters / 1000.0, Evidence("Android Automotive RANGE_REMAINING", LocalDateTime.now().minusNanos(ageNanos)))
        } catch (e: Exception) {
            Log.w("GakimRange", "RANGE_REMAINING unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) { null }
        finally { car?.disconnect() }
    }
}
