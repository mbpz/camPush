package com.campush.lan

/**
 * lan-core 内部使用的极小 JSON 读写器。
 *
 * 仅用于 JWT claims、HTTP 请求体与响应体（体积 <= 8 KB）。不追求完整 JSON 规范，
 * 但保证：字符串转义、UTF-8、int/double/boolean/null、对象与数组嵌套。
 * 不依赖任何第三方库。
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing characters at ${parser.pos}" }
        return value
    }

    fun write(value: Any?): String {
        val sb = StringBuilder(64)
        writeValue(sb, value)
        return sb.toString()
    }

    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        writeString(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> sb.append(value.toString())
            is Float -> writeValue(sb, value.toDouble())
            is Double -> {
                if (value.isFinite() && value == value.toLong().toDouble()) {
                    sb.append(value.toLong())
                } else {
                    sb.append(value)
                }
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, value.toList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0
            private set

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            check(pos < text.length) { "unexpected end of JSON" }
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expect("true")
                    true
                }
                'f' -> {
                    expect("false")
                    false
                }
                'n' -> {
                    expect("null")
                    null
                }
                else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun expect(literal: String) {
            check(text.startsWith(literal, pos)) { "expected $literal at $pos" }
            pos += literal.length
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                check(pos < text.length && text[pos] == ':') { "expected ':' at $pos" }
                pos++
                map[key] = parseValue()
                skipWhitespace()
                check(pos < text.length) { "unterminated object" }
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                check(pos < text.length) { "unterminated array" }
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            check(pos < text.length && text[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                check(pos < text.length) { "unterminated string" }
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        check(pos < text.length) { "unterminated escape" }
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                check(pos + 4 <= text.length) { "bad unicode escape" }
                                sb.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            return if (isDouble) raw.toDouble() else raw.toLong()
        }
    }
}
