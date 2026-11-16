package kr.gakim.charge.data.pleos

import ai.pleos.playground.vehicle.Vehicle
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
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

    private suspend fun readPleosRange(): kr.gakim.charge.domain.itinerary.Measured<Double>? = withTimeoutOrNull(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val v = vehicle
            if (v == null) { cont.resume(null); return@suspendCancellableCoroutine }
            try {
                v.getOdometer().getRangeRemainingDistance(
                    onComplete = { if (cont.isActive) cont.resume(rangeFromMeters(it?.toDouble(), "Pleos Vehicle SDK 주행 가능 거리")) },
                    onFailed = { if (cont.isActive) cont.resume(null) })
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
    override suspend fun readRangeKm() = AndroidRangeReader.read(appContext) ?: readPleosRange()

    private var stopUpdates: (() -> Unit)? = null
    override val energyUpdates: kotlinx.coroutines.flow.Flow<kr.gakim.charge.data.VehicleEnergyUpdate> = kotlinx.coroutines.flow.callbackFlow {
        val v = vehicle
        var androidRange: kr.gakim.charge.domain.itinerary.Measured<Double>? = null
        var pleosRange: kr.gakim.charge.domain.itinerary.Measured<Double>? = null
        val lock = Any()
        var androidVersion = 0
        var pleosVersion = 0
        fun publish() { trySend(kr.gakim.charge.data.VehicleEnergyUpdate.Range(selectRange(androidRange, pleosRange))) }
        val socListener = object : ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                trySend(kr.gakim.charge.data.VehicleEnergyUpdate.Soc(
                    value?.takeIf { it in 0..100 }?.let { BatteryReading.Known(it) } ?: BatteryReading.Unknown("SOC unavailable")))
            }
            override fun onFailed(e: Exception) { onEvBatteryStateOfChargeUpdated(null) }
        }
        val rangeListener = object : ai.pleos.playground.vehicle.listener.RangeRemainingListener {
            override fun onRangeRemainingChanged(rangeRemaining: Float?) { synchronized(lock) {
                pleosVersion++
                pleosRange = rangeFromMeters(rangeRemaining?.toDouble(), "Pleos Vehicle SDK 주행 가능 거리"); publish()
            } }
            override fun onFailed(e: Exception) { onRangeRemainingChanged(null) }
        }
        try { v?.getEvBattery()?.registerEvBatteryStateOfCharge(socListener) } catch (e: Exception) { socListener.onFailed(e) }
        try { v?.getOdometer()?.registerRangeRemainingListener(rangeListener) } catch (e: Exception) { rangeListener.onFailed(e) }
        val stopAndroid = AndroidRangeReader.subscribe(appContext) { synchronized(lock) { androidVersion++; androidRange = it; publish() } }
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val cleanup = {
            if (closed.compareAndSet(false, true)) {
            stopAndroid()
            try { v?.getEvBattery()?.unregisterEvBatteryStateOfCharge(socListener) } catch (_: Exception) { }
            try { v?.getOdometer()?.unregisterRangeRemainingListener(rangeListener) } catch (_: Exception) { }
            }
            Unit
        }
        stopUpdates = cleanup
        launch {
            // 주행 가능 거리는 값이 바뀔 때만 이벤트가 온다. 값이 그대로여도 2분 뒤 '오래된 정보'가 되지 않게 30초마다 다시 읽는다.
            while (true) {
                val av = synchronized(lock) { androidVersion }
                val a = AndroidRangeReader.read(appContext)
                synchronized(lock) { if (av == androidVersion) { androidRange = a; publish() } }
                val pv = synchronized(lock) { pleosVersion }
                val p = readPleosRange()
                synchronized(lock) { if (pv == pleosVersion) { pleosRange = p; publish() } }
                kotlinx.coroutines.delay(30_000)
            }
        }
        awaitClose { if (stopUpdates === cleanup) stopUpdates = null; cleanup() }
    }

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
        stopUpdates?.invoke(); stopUpdates = null
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
