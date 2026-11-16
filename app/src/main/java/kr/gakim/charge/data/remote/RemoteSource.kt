package kr.gakim.charge.data.remote

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

sealed interface RemoteResult<out T> {
    data class Available<out T>(val value: T, val fetchedAt: LocalDateTime) : RemoteResult<T>
    data class Unavailable(val reason: String) : RemoteResult<Nothing>
}
data class SourceStatus(val source: String, val at: LocalDateTime?, val status: String)
data class HttpReply(val code: Int, val body: String)
fun interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>, readTimeout: Int): HttpReply
}
class UrlConnectionTransport : HttpTransport {
    companion object {
        private val requests = java.util.concurrent.Executors.newFixedThreadPool(3)
    }
    override suspend fun get(url: String, headers: Map<String, String>, readTimeout: Int): HttpReply = suspendCancellableCoroutine { continuation ->
        val connection = URL(url).openConnection() as HttpURLConnection
        val task = requests.submit {
          try {
            connection.connectTimeout = 4_000
            connection.readTimeout = readTimeout
            connection.instanceFollowRedirects = false
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val reply = HttpReply(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            if (continuation.isActive) continuation.resume(reply)
          } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
          } finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { task.cancel(true); connection.disconnect() }
    }
}
internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** JSON decoding is replaceable so JVM fixtures need no additional Android/runtime library. */
fun interface JsonDecoder { fun decode(raw: String): Any? }
object AndroidJsonDecoder : JsonDecoder {
    override fun decode(raw: String): Any? = unwrap(JSONTokener(raw).nextValue())
    private fun unwrap(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { unwrap(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { unwrap(value.get(it)) }
        else -> value
    }
}
internal fun Any?.obj(): Map<*, *> = this as? Map<*, *> ?: error("객체 형식 오류")
internal fun Map<*, *>.text(key: String): String = this[key]?.toString().orEmpty()
internal fun Any?.array(): List<*> = this as? List<*> ?: error("목록 형식 오류")
internal fun Map<*, *>.number(key: String): Double? = text(key).toDoubleOrNull()?.takeIf { it.isFinite() }

/** Per-key single flight allows different districts/candidates to load concurrently. */
class RemoteSession(
    val name: String, private val key: String, private val transport: HttpTransport,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
    private val debug: (String) -> Unit = {},
) {
    private val mutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, RemoteResult.Available<Any>>()
    companion object { private val logged = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
    var status = SourceStatus(name, null, if (key.isBlank()) "키 없음 · 비활성" else "아직 조회하지 않음")
        private set
    val enabled get() = key.isNotBlank()

    suspend fun <T : Any> load(cacheKey: String, seconds: Long, block: suspend () -> T): RemoteResult<T> = mutexes.getOrPut(cacheKey) { Mutex() }.withLock {
        if (!enabled) return@withLock RemoteResult.Unavailable("키 없음 · 비활성")
        val cached = cache[cacheKey]
        if (cached != null && Duration.between(cached.fetchedAt, clock()).seconds in 0..seconds) {
            @Suppress("UNCHECKED_CAST")
            return@withLock cached as RemoteResult.Available<T>
        }
        try {
            val value = withContext(Dispatchers.IO) { block() }
            val result = RemoteResult.Available(value, clock())
            cache[cacheKey] = result
            status = SourceStatus(name, result.fetchedAt, "조회 성공")
            result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Exception messages from network stacks can contain credential-bearing URLs.
            val reason = when (e) {
                is SourceFailure -> e.reason
                is java.net.SocketTimeoutException -> "응답 시간 초과"
                is java.io.IOException -> "네트워크 연결 실패"
                else -> "응답 형식 오류"
            }
            status = SourceStatus(name, clock(), reason)
            RemoteResult.Unavailable(reason)
        }
    }
    suspend fun request(url: String, kakao: Boolean = false, timeout: Int = 6_000, endpoint: String = name): String {
        val response = transport.get(url, if (kakao) mapOf("Authorization" to "KakaoAK $key") else emptyMap(), timeout)
        if (logged.add(name)) {
            // Some API errors echo request parameters. Redact all key representations before logging.
            val secrets = listOf(key, encode(key), runCatching { java.net.URLDecoder.decode(key, "UTF-8") }.getOrDefault(key))
            debug(secrets.filter { it.isNotBlank() }.fold(response.body) { body, secret -> body.replace(secret, "[redacted]", ignoreCase = true) }.take(1500))
        }
        if (response.code !in 200..299) {
            val reason = if (response.code == 403 && response.body.contains("disabled OPEN_MAP_AND_LOCAL service"))
                "카카오맵(로컬) 사용 설정이 꺼져 있어요" else "HTTP ${response.code}"
            throw SourceFailure(reason)
        }
        return response.body
    }
}
internal class SourceFailure(val reason: String) : Exception(reason)
