package kr.gakim.charge.data.remote

import android.content.Context
import android.util.Log
import kr.gakim.charge.BuildConfig

fun remoteRepository(context: Context): CompositeItineraryRepository {
    val calendar = MartClosingAssets.load({ name -> runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull() })
    val transport = UrlConnectionTransport()
    fun session(name: String, key: String) = RemoteSession(name, key, transport, debug = { raw ->
        if (BuildConfig.DEBUG) Log.d("Remote:$name", raw.take(1500))
    })
    return CompositeItineraryRepository(
        WebKakaoPlaceSearch(session("카카오 장소 검색", BuildConfig.KAKAO_REST_API_KEY), closed = { calendar.permanentlyClosed(it.name) }),
        WebKakaoDirections(session("카카오모빌리티 길찾기", BuildConfig.KAKAO_REST_API_KEY)),
        WebKecoChargers(session("한국환경공단", BuildConfig.DATA_GO_KR_SERVICE_KEY), BuildConfig.DATA_GO_KR_SERVICE_KEY,
            regions = KakaoRegionCodes(session("카카오 지역 코드", BuildConfig.KAKAO_REST_API_KEY))), calendar,
    )
}
