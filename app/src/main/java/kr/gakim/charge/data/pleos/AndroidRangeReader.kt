package kr.gakim.charge.data.pleos

import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.gakim.charge.domain.itinerary.Evidence
import kr.gakim.charge.domain.itinerary.Measured
import java.time.LocalDateTime

/** Public Android RANGE_REMAINING, meters. */
object AndroidRangeReader {
    @SuppressLint("MissingPermission")
    fun subscribe(context: Context, changed: (Measured<Double>?) -> Unit): () -> Unit {
        var car: Car? = null
        var manager: CarPropertyManager? = null
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.propertyId == VehiclePropertyIds.RANGE_REMAINING) changed(
                    if (value.status == CarPropertyValue.STATUS_AVAILABLE)
                        rangeFromMeters((value.value as? Number)?.toDouble(), "Android RANGE_REMAINING") else null)
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) { changed(null) }
        }
        try {
            car = Car.createCar(context)
            manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            manager?.registerCallback(callback, VehiclePropertyIds.RANGE_REMAINING, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (e: Exception) { changed(null) }
        catch (e: LinkageError) { changed(null) }
        return {
            try { manager?.unregisterCallback(callback) } catch (_: Exception) { }
            finally { car?.disconnect() }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun read(context: Context): Measured<Double>? = withContext(Dispatchers.IO) {
        var car: Car? = null
        try {
            car = Car.createCar(context)
            val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return@withContext null
            val value = manager.getProperty(Float::class.javaObjectType, VehiclePropertyIds.RANGE_REMAINING, 0)
            val meters = value?.value?.toDouble() ?: return@withContext null
            if (value.status != CarPropertyValue.STATUS_AVAILABLE || !meters.isFinite() || meters <= 0 || value.timestamp <= 0) return@withContext null
            // timestamp 는 값이 마지막으로 '바뀐' 시각이다(ONCHANGE 속성). 방금 읽은 현재 값이므로 읽은 시각을 근거로 남긴다.
            Measured(meters / 1000.0, Evidence("Android RANGE_REMAINING", LocalDateTime.now()))
        } catch (e: Exception) {
            Log.w("GakimRange", "RANGE_REMAINING unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) { null }
        finally { car?.disconnect() }
    }

    /** Public Android EV_BATTERY_LEVEL(Wh, android.car.permission.CAR_ENERGY) → kWh. 전비 추정에만 쓴다. */
    @SuppressLint("MissingPermission")
    suspend fun readBatteryKwh(context: Context): Measured<Double>? = withContext(Dispatchers.IO) {
        var car: Car? = null
        try {
            car = Car.createCar(context)
            val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return@withContext null
            val value = manager.getProperty(Float::class.javaObjectType, VehiclePropertyIds.EV_BATTERY_LEVEL, 0)
            val wh = value?.value?.toDouble() ?: return@withContext null
            if (value.status != CarPropertyValue.STATUS_AVAILABLE || !wh.isFinite() || wh <= 0) return@withContext null
            Measured(wh / 1000.0, Evidence("Android EV_BATTERY_LEVEL", LocalDateTime.now()))
        } catch (e: Exception) {
            Log.w("GakimRange", "EV_BATTERY_LEVEL unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        } catch (e: LinkageError) { null }
        finally { car?.disconnect() }
    }
}
