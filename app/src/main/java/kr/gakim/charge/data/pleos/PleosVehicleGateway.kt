package kr.gakim.charge.data.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kr.gakim.charge.data.BatteryReading
import kr.gakim.charge.data.ParkState
import kr.gakim.charge.data.VehicleGateway
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK(ai.pleos.playground:Vehicle:2.3.2)로 배터리 잔량을 읽는다.
 * 사용 API: Vehicle(context), initialize(), getEvBattery().getEvBatteryStateOfCharge(onComplete, onFailed), release()
 * 권한: pleos.car.permission.CAR_ENERGY
 *
 * 실패·null·권한 거부·시간 초과는 모두 [BatteryReading.Unknown] 이다. 0%는 유효한 값이다.
 */
@SuppressLint("MissingPermission") // Pleos 커스텀 권한은 매니페스트에 선언하고, 거부되면 Unknown 으로 처리한다.
class PleosVehicleGateway(context: Context) : VehicleGateway {
    private val appContext = context.applicationContext

    override suspend fun readRangeKm() = AndroidRangeReader.read(appContext)

    override suspend fun readEfficiencyKmPerKwh(): kr.gakim.charge.domain.itinerary.Measured<Double>? {
        val v = vehicle ?: return null
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    v.getEvBattery().getCurrentEvEfficiency(onComplete = { value ->
                        val reading = value?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.let {
                            kr.gakim.charge.domain.itinerary.Measured(it, kr.gakim.charge.domain.itinerary.Evidence("Vehicle current EV efficiency km/kWh", java.time.LocalDateTime.now()))
                        }
                        if (cont.isActive) cont.resume(reading)
                    }, onFailed = { if (cont.isActive) cont.resume(null) })
                } catch (e: Exception) { if (e is CancellationException) throw e; if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private val vehicle: Vehicle? = try {
        Vehicle(context.applicationContext).also { it.initialize() }
    } catch (t: Throwable) {
            if (t is CancellationException) throw t
        Log.w(TAG, "Vehicle 초기화 실패", t)
        null
    }

    override suspend fun readBatterySoc(): BatteryReading {
        val v = vehicle ?: return BatteryReading.Unknown("차량 서비스에 연결하지 못함")
        var last: BatteryReading = BatteryReading.Unknown("응답 없음")
        // initialize() 직후에는 서비스 연결이 끝나지 않았을 수 있어 짧게 몇 번 다시 시도한다.
        repeat(ATTEMPTS) { attempt ->
            last = readOnce(v)
            if (last is BatteryReading.Known) return last
            if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return last
    }

    private suspend fun readOnce(v: Vehicle): BatteryReading =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    v.getEvBattery().getEvBatteryStateOfCharge(
                        onComplete = { soc: Int? ->
                            val reading =
                                if (soc != null && soc in 0..100) BatteryReading.Known(soc)
                                else BatteryReading.Unknown("값 없음 또는 범위 밖: $soc")
                            if (cont.isActive) cont.resume(reading)
                        },
                        onFailed = { e: Exception ->
                            if (e is CancellationException) { cont.cancel(e); return@getEvBatteryStateOfCharge }
                            if (cont.isActive) cont.resume(BatteryReading.Unknown(e.message ?: e.javaClass.simpleName))
                        },
                    )
                } catch (t: Throwable) {
            if (t is CancellationException) throw t
                    // 권한 거부(SecurityException) 등
                    if (cont.isActive) cont.resume(BatteryReading.Unknown(t.message ?: t.javaClass.simpleName))
                }
            }
        } ?: BatteryReading.Unknown("응답 시간 초과")

    /** CAR_DRIVE gear selection: only P is parked; N/D/R must not authorize edits. */
    override suspend fun readParkState(): ParkState {
        val v = vehicle ?: return ParkState.UNKNOWN
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    v.getGear().getCurrentGearSelectionState(
                        onComplete = { gear ->
                            val state = parkStateForGear(gear)
                            if (cont.isActive) cont.resume(state)
                        },
                        onFailed = { error ->
                            if (error is CancellationException) cont.cancel(error)
                            else {
                                Log.w(TAG, "Gear read failed", error)
                                if (cont.isActive) cont.resume(ParkState.UNKNOWN)
                            }
                        },
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Gear read failed", t)
                    if (cont.isActive) cont.resume(ParkState.UNKNOWN)
                }
            }
        } ?: ParkState.UNKNOWN
    }

    override fun release() {
        try {
            vehicle?.release()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Vehicle 해제 실패", t)
        }
    }

    private companion object {
        const val TAG = "PleosVehicleGateway"
        const val TIMEOUT_MS = 3_000L
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 500L
    }
}

internal fun parkStateForGear(gear: ai.pleos.playground.vehicle.constant.control.VehicleGear?): ParkState = when (gear) {
    null, ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_UNKNOWN -> ParkState.UNKNOWN
    ai.pleos.playground.vehicle.constant.control.VehicleGear.GEAR_PARK -> ParkState.PARKED
    else -> ParkState.MOVING
}
