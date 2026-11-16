package kr.gakim.charge.remote

import kr.gakim.charge.data.remote.JsonDecoder

/** Test-only JSON reader for the framework-independent parser seam. Android uses org.json. */
object FixtureJson : JsonDecoder {
    override fun decode(raw: String): Any? = Reader(raw).read()
    fun fixture(name: String): String = javaClass.getResource("/remote/$name")!!.readText()
    private class Reader(private val raw: String) {
        private var index = 0
        fun read(): Any? {
            val result = value()
            whitespace()
            require(index == raw.length)
            return result
        }
        private fun whitespace() { while (index < raw.length && raw[index].isWhitespace()) index++ }
        private fun take(char: Char): Boolean {
            whitespace()
            if (index >= raw.length || raw[index] != char) return false
            index++
            return true
        }
        private fun value(): Any? {
            whitespace()
            return when (raw[index]) {
                '{' -> {
                    index++
                    val result = mutableMapOf<String, Any?>()
                    if (!take('}')) {
                        do { val key = string(); require(take(':')); result[key] = value() } while (take(','))
                        require(take('}'))
                    }
                    result
                }
                '[' -> {
                    index++
                    val result = mutableListOf<Any?>()
                    if (!take(']')) { do { result += value() } while (take(',')); require(take(']')) }
                    result
                }
                '"' -> string()
                else -> {
                    val start = index
                    while (index < raw.length && raw[index] !in ",]} \r\n\t") index++
                    when (val token = raw.substring(start, index)) {
                        "null" -> null
                        "true" -> true
                        "false" -> false
                        else -> token.toLongOrNull() ?: token.toDouble()
                    }
                }
            }
        }
        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            while (raw[index] != '"') {
                val char = raw[index++]
                if (char != '\\') result.append(char) else {
                    result.append(when (val escaped = raw[index++]) {
                        'u' -> raw.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        else -> escaped
                    })
                }
            }
            index++
            return result.toString()
        }
    }
}
