package kr.gakim.charge.data.pleos

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.NavigationScreenRequest
import kr.gakim.charge.data.TripPreview
import java.time.LocalDateTime
import kotlin.math.ceil
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kr.gakim.charge.data.NaviGateway
import kr.gakim.charge.data.NaviPlace
import kr.gakim.charge.data.NaviRead
import kr.gakim.charge.data.NaviRequest
import kr.gakim.charge.data.NaviRoute
import kotlin.coroutines.resume

/**
 * Pleos NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5)로 목적지·경유지를 읽고 경유지를 추가한다.
 *
 * 사용 API (pleos-sdk-src 에서 확인한 시그니처):
 *  - NaviHelper(context), initialize(onConnected: () -> Unit, onFailed: (Throwable) -> Unit), release()
 *  - addListener(NaviHelperEventListener) / removeListener(...)
 *  - getDestinationInfo() → NaviHelperEventListener.onDestinationInfo(DestinationInfo)
 *  - getWaypointInfo()    → NaviHelperEventListener.onWayPointInfo(List<WaypointInfo>)
 *  - addWaypoint(RequestWaypointInfo(latitude, longitude, poiName, waypointIndex, poiId, address, poiSubId))
 * 권한: NAVI_ROUTE(조회), NAVI_ROUTE_SEARCH(경유지 변경)
 *
 * 결과는 모두 비동기 콜백으로 온다. 응답이 없거나 실패하면 [NaviRead.Unknown] 이다.
 * 목적지가 없을 때 내비가 콜백을 주는지는 미검증이라, 응답이 없으면 '목적지 없음'이 아니라 '알 수 없음'으로 본다.
 */
@SuppressLint("MissingPermission") // Pleos 커스텀 권한은 매니페스트에 선언하고, 거부되면 Unknown/Failed 로 처리한다.
class PleosNaviGateway(context: Context) : NaviGateway {

    private val helper: NaviHelper? = try {
        NaviHelper(context.applicationContext)
    } catch (t: Throwable) {
            if (t is CancellationException) throw t
        Log.w(TAG, "NaviHelper 생성 실패", t)
        null
    }

    private val mutex = Mutex()

    @Volatile
    private var connected = false

    @Volatile
    private var destinationWaiter: CompletableDeferred<DestinationInfo>? = null

    @Volatile
    private var waypointWaiter: CompletableDeferred<List<WaypointInfo>>? = null

    @Volatile
    private var lastError: NaviErrorCode? = null
    private var bookmarkWaiter: CompletableDeferred<List<BookmarkInfo>>? = null
    private var recentWaiter: CompletableDeferred<List<RecentDestinationInfo>>? = null
    private var tripWaiter: CompletableDeferred<TripInfo>? = null
    private var tripTimedOut = false

    private val listener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) { bookmarkWaiter?.complete(bookmarkInfoList) }
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) { recentWaiter?.complete(recentDestinationInfoList) }
        override fun onTripInfo(tripInfo: TripInfo) { tripWaiter?.complete(tripInfo) }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            destinationWaiter?.complete(destinationInfo)
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            waypointWaiter?.complete(waypointInfoList)
        }

        override fun onError(errorCode: NaviErrorCode) {
            lastError = errorCode
            Log.w(TAG, "내비 오류: $errorCode")
        }
    }

    private suspend fun ensureConnected(h: NaviHelper): Boolean {
        if (connected) return true
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    h.initialize(
                        onConnected = { if (cont.isActive) cont.resume(true) },
                        onFailed = { t: Throwable ->
                            if (t is CancellationException) { cont.cancel(t); return@initialize }
                            Log.w(TAG, "내비 서비스 연결 실패", t)
                            if (cont.isActive) cont.resume(false)
                        },
                    )
                } catch (t: Throwable) {
            if (t is CancellationException) throw t
                    Log.w(TAG, "내비 초기화 실패", t)
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
        if (ok) {
            h.addListener(listener)
            connected = true
        }
        return ok
    }

    override suspend fun readRoute(): NaviRead = mutex.withLock {
        val h = helper ?: return@withLock NaviRead.Unknown("내비 서비스를 쓸 수 없음")
        if (!ensureConnected(h)) return@withLock NaviRead.Unknown("내비 서비스에 연결하지 못함")
        try {
            lastError = null
            val destination = CompletableDeferred<DestinationInfo>().also { destinationWaiter = it }
            val waypoints = CompletableDeferred<List<WaypointInfo>>().also { waypointWaiter = it }
            h.getDestinationInfo()
            h.getWaypointInfo()
            val d = withTimeoutOrNull(READ_TIMEOUT_MS) { destination.await() }
                ?: return@withLock NaviRead.Unknown("목적지 응답 없음" + errorSuffix())
            val w = withTimeoutOrNull(READ_TIMEOUT_MS) { waypoints.await() }
                ?: return@withLock NaviRead.Unknown("경유지 응답 없음" + errorSuffix())
            Log.d(TAG, "readRoute destination=$d waypoints=$w")
            NaviRead.Known(NaviRoute.fromSdk(d.toPlace(), w.map { it.toPlace() }))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // 권한 거부(SecurityException) 등
            NaviRead.Unknown(t.message ?: t.javaClass.simpleName)
        } finally {
            destinationWaiter = null
            waypointWaiter = null
        }
    }

    override suspend fun addFirstWaypoint(place: NaviPlace): NaviRequest = mutex.withLock {
        val h = helper ?: return@withLock NaviRequest.Failed("내비 서비스를 쓸 수 없음")
        if (!ensureConnected(h)) return@withLock NaviRequest.Failed("내비 서비스에 연결하지 못함")
        try {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    poiName = place.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = place.poiId,
                    address = place.address,
                    poiSubId = place.poiSubId,
                ),
            )
            NaviRequest.Sent
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            NaviRequest.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    override suspend fun setDestinationForDemo(place: NaviPlace): NaviRequest = mutex.withLock {
        val h = helper ?: return@withLock NaviRequest.Failed("내비 서비스를 쓸 수 없음")
        if (!ensureConnected(h)) return@withLock NaviRequest.Failed("내비 서비스에 연결하지 못함")
        try {
            h.requestRoute(
                RouteInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    poiName = place.name,
                    poiId = place.poiId,
                    address = place.address,
                    poiSubId = place.poiSubId,
                ),
            )
            NaviRequest.Sent
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            NaviRequest.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    override suspend fun applyRoute(route: NaviRoute): NaviRequest = mutex.withLock {
        val h = helper ?: return@withLock NaviRequest.Failed("내비 서비스를 쓸 수 없음")
        val destination = route.destination ?: return@withLock NaviRequest.Failed("목적지 없음")
        if (route.waypoints.size > 2) return@withLock NaviRequest.Failed("경유지는 최대 두 곳입니다")
        if (!ensureConnected(h)) return@withLock NaviRequest.Failed("내비 서비스 연결 실패")
        try {
            h.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                destination = RouteInfo(latitude = destination.latitude, longitude = destination.longitude,
                    poiName = destination.name, poiId = destination.poiId, address = destination.address, poiSubId = destination.poiSubId),
                waypoints = route.waypoints.mapIndexed { i, p -> RequestWaypointInfo(latitude = p.latitude, longitude = p.longitude,
                    poiName = p.name, waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId = p.poiId, address = p.address, poiSubId = p.poiSubId) },
            ))
            NaviRequest.Sent
        } catch (e: Exception) { if (e is CancellationException) throw e; NaviRequest.Failed(e.message ?: "내비 요청 실패") }
    }

    override suspend fun readSavedPlaces(): List<NaviPlace> = mutex.withLock {
        val h = helper ?: return@withLock emptyList()
        if (!ensureConnected(h)) return@withLock emptyList()
        try {
            val b = CompletableDeferred<List<BookmarkInfo>>().also { bookmarkWaiter = it }
            val r = CompletableDeferred<List<RecentDestinationInfo>>().also { recentWaiter = it }
            h.getBookmarkInfo(); h.getRecentDestinationInfo()
            val bookmarks = withTimeoutOrNull(READ_TIMEOUT_MS) { b.await() }.orEmpty()
            val recent = withTimeoutOrNull(READ_TIMEOUT_MS) { r.await() }.orEmpty()
            bookmarks.map { NaviPlace(it.name, it.address, it.latitude, it.longitude, it.poiId, it.poiSubId ?: "") } +
                recent.map { NaviPlace(it.name, it.address, it.latitude, it.longitude, it.poiId, it.poiSubId ?: "") }
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
        finally { bookmarkWaiter = null; recentWaiter = null }
    }

    override suspend fun openPlaceSearch(keyword: String): NaviRequest = mutex.withLock {
        val h=helper ?: return@withLock NaviRequest.Failed("내비 서비스 없음")
        if(!ensureConnected(h)) return@withLock NaviRequest.Failed("내비 연결 실패")
        try { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword));NaviRequest.Sent }
        catch(e:Exception) { if(e is CancellationException) throw e;NaviRequest.Failed(e.message ?: "검색 화면 요청 실패") }
    }

    override suspend fun previewTrip(place: NaviPlace): TripPreview? = mutex.withLock {
        val h = helper ?: return@withLock null
        // No callback request ID: after timeout do not bind a late response to another target.
        if (tripTimedOut || !ensureConnected(h)) return@withLock null
        try {
            val reply = CompletableDeferred<TripInfo>().also { tripWaiter = it }
            h.requestTrip(TripRequest(place.latitude, place.longitude, place.poiId, place.name, place.poiSubId))
            val info = withTimeoutOrNull(READ_TIMEOUT_MS) { reply.await() }
            if (info == null) { tripTimedOut = true; return@withLock null }
            if (info.distance < 0 || info.eta < 0) return@withLock null
            if (info.summary.routePoints.none { p ->
                if(place.poiId.isNotBlank() && p.poiId.isNotBlank()) place.poiId == p.poiId
                else kotlin.math.abs(p.latitude-place.latitude) < 0.0005 && kotlin.math.abs(p.longitude-place.longitude) < 0.0005
            }) return@withLock null
            Log.d(TAG, "trip ${place.name}: ${info.distance}m ${info.eta}s SOC=${info.estimatedSocAtDest}")
            TripPreview(info.distance / 1000.0, ceil(info.eta / 60.0).toInt(), info.estimatedSocAtDest.takeIf { it in 0..100 }, LocalDateTime.now())
        } catch (e: Exception) { if (e is CancellationException) throw e; null }
        finally { tripWaiter = null }
    }

    override fun release() {
        bookmarkWaiter?.cancel(); recentWaiter?.cancel(); tripWaiter?.cancel()
        destinationWaiter?.cancel()
        waypointWaiter?.cancel()
        try {
            helper?.removeListener(listener)
            helper?.release()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "NaviHelper 해제 실패", t)
        }
        connected = false
    }

    private fun errorSuffix(): String = lastError?.let { " ($it)" } ?: ""

    private fun DestinationInfo.toPlace() = NaviPlace(
        name = name, address = address, latitude = latitude, longitude = longitude,
        poiId = poiId, poiSubId = poiSubId ?: "",
    )

    private fun WaypointInfo.toPlace() = NaviPlace(
        name = name, address = address, latitude = latitude, longitude = longitude,
        poiId = poiId, poiSubId = poiSubId ?: "",
    )

    private companion object {
        const val TAG = "PleosNaviGateway"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 3_000L
    }
}
