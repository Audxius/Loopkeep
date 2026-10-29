package io.github.audxius.loopkeep.core

/**
 * A line-preserving YAML frontmatter editor.
 *
 * Not a YAML parser: it understands top-level `key: value` lines, inline lists and block lists,
 * which is everything this app writes. Every other line (unknown keys, comments, nested maps,
 * odd formatting from Obsidian or the user) is kept byte-for-byte, and keys keep their order.
 * Only keys explicitly [set] or [remove]d are rewritten.
 */
class Frontmatter private constructor(
    private val entries: MutableList<Entry>,
    /** Text after the closing `---` line, untouched. */
    var body: String,
    val hasFrontmatter: Boolean,
    private val newline: String,
    private val openLine: String,
    private val closeLine: String,
) {
    /** [key] is null for comments, blank lines, and anything before the first key. */
    private class Entry(val key: String?, val lines: List<String>)

    val keys: List<String> get() = entries.mapNotNull { it.key }

    operator fun contains(key: String) = entries.any { it.key == key }

    /** The scalar value of [key], unquoted. Null when missing or when the value is a block. */
    fun getString(key: String): String? {
        val entry = entries.firstOrNull { it.key == key } ?: return null
        val raw = valuePart(entry.lines.first())
        if (raw.isEmpty()) return null
        if (raw.startsWith("[") || raw.startsWith("{")) return raw
        return parseScalar(raw)
    }

    /** List values in any of: `[a, b]`, block `- a`, or a single scalar / comma-separated string. */
    fun getList(key: String): List<String> {
        val entry = entries.firstOrNull { it.key == key } ?: return emptyList()
        val raw = valuePart(entry.lines.first())
        if (raw.startsWith("[")) return splitInlineList(raw)
        if (raw.isNotEmpty()) return parseScalar(raw).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return entry.lines.drop(1).mapNotNull { line ->
            val t = line.trim()
            if (t.startsWith("- ") || t == "-") parseScalar(t.removePrefix("-").trim()).takeIf { it.isNotEmpty() } else null
        }
    }

    /** Sets [key] to a string scalar, quoting when YAML would otherwise mis-read it. */
    fun set(key: String, value: String?, forceQuote: Boolean = false) {
        if (value == null) return remove(key)
        replace(key, listOf("$key: ${renderScalar(value, forceQuote)}"))
    }

    fun set(key: String, value: Int?) {
        if (value == null) return remove(key)
        replace(key, listOf("$key: $value"))
    }

    /** Lists are written inline (`[a, b]`), the style Obsidian's property editor reads well. */
    fun setList(key: String, values: List<String>) {
        replace(key, listOf("$key: [" + values.joinToString(", ") { renderListItem(it) } + "]"))
    }

    fun remove(key: String) {
        entries.removeAll { it.key == key }
    }

    private fun replace(key: String, lines: List<String>) {
        val idx = entries.indexOfFirst { it.key == key }
        val entry = Entry(key, lines)
        if (idx >= 0) entries[idx] = entry else {
            // Append after the last key, before trailing blank lines/comments.
            val lastKey = entries.indexOfLast { it.key != null }
            entries.add(lastKey + 1, entry)
        }
    }

    fun render(): String {
        if (!hasFrontmatter && entries.isEmpty()) return body
        val sb = StringBuilder()
        sb.append(openLine).append(newline)
        entries.forEach { e -> e.lines.forEach { sb.append(it).append(newline) } }
        sb.append(closeLine).append(newline)
        sb.append(body)
        return sb.toString()
    }

    companion object {
        private val keyLine = Regex("""^([^\s#\-:][^:]*?|"[^"]*"|'[^']*')\s*:(\s.*|)$""")

        fun parse(text: String): Frontmatter {
            val newline = if (text.contains("\r\n")) "\r\n" else "\n"
            val bom = if (text.startsWith("\uFEFF")) "\uFEFF" else ""
            val content = text.removePrefix(bom)
            val lines = content.split(newline)
            if (lines.isEmpty() || lines[0].trimEnd() != "---") {
                return Frontmatter(mutableListOf(), text, false, newline, "$bom---", "---")
            }
            val close = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" || lines[it].trimEnd() == "..." }
                ?: return Frontmatter(mutableListOf(), text, false, newline, "$bom---", "---")

            val entries = mutableListOf<Entry>()
            var currentKey: String? = null
            var current = mutableListOf<String>()
            fun flush() {
                if (current.isNotEmpty() || currentKey != null) entries += Entry(currentKey, current.toList())
                current = mutableListOf()
                currentKey = null
            }
            for (line in lines.subList(1, close)) {
                val m = keyLine.find(line)
                if (m != null) {
                    flush()
                    currentKey = m.groupValues[1].trim().trim('"', '\'')
                    current += line
                } else if (currentKey != null && (line.startsWith(" ") || line.startsWith("\t") || line.trimStart().startsWith("- "))) {
                    current += line
                } else {
                    // Comment or blank line: its own keyless entry, so it stays in place.
                    flush()
                    entries += Entry(null, listOf(line))
                }
            }
            flush()
            val body = lines.subList(close + 1, lines.size).joinToString(newline)
            return Frontmatter(entries, body, true, newline, bom + lines[0], lines[close])
        }

        private fun valuePart(line: String): String {
            val afterColon = line.substring(line.indexOf(':') + 1).trim()
            return stripComment(afterColon)
        }

        private fun stripComment(value: String): String {
            if (value.startsWith("\"") || value.startsWith("'")) return value
            val idx = value.indexOf(" #")
            return if (idx >= 0) value.substring(0, idx).trimEnd() else value
        }

        fun parseScalar(raw: String): String {
            val v = raw.trim()
            if (v.length >= 2 && v.startsWith("\"")) {
                val end = closingQuote(v)
                return unescapeDouble(v.substring(1, if (end > 0) end else v.length))
            }
            if (v.length >= 2 && v.startsWith("'")) {
                val end = v.lastIndexOf('\'')
                return v.substring(1, if (end > 0) end else v.length).replace("''", "'")
            }
            return stripComment(v)
        }

        private fun closingQuote(v: String): Int {
            var i = 1
            while (i < v.length) {
                if (v[i] == '\\') i += 2 else if (v[i] == '"') return i else i++
            }
            return -1
        }

        private fun unescapeDouble(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); '"' -> sb.append('"'); '\\' -> sb.append('\\')
                        'u' -> if (i + 5 < s.length) {
                            s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                        else -> sb.append('\\').append(n)
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }

        private fun splitInlineList(raw: String): List<String> {
            val inner = raw.removePrefix("[").let { it.substring(0, it.lastIndexOf(']').takeIf { i -> i >= 0 } ?: it.length) }
            val items = mutableListOf<String>()
            val sb = StringBuilder()
            var quote: Char? = null
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                when {
                    quote != null -> {
                        sb.append(c)
                        if (c == '\\' && quote == '"' && i + 1 < inner.length) { sb.append(inner[i + 1]); i++ }
                        else if (c == quote) quote = null
                    }
                    (c == '"' || c == '\'') && sb.isBlank() -> { quote = c; sb.append(c) }
                    c == ',' -> { items += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
                i++
            }
            items += sb.toString()
            return items.map { parseScalar(it.trim()) }.filter { it.isNotEmpty() }
        }

        private val plainUnsafeStart = "[]{}&*!|>'\"%@`#,?:-<=".toSet()
        private val reserved = Regex("""^(true|false|yes|no|on|off|null|~|[-+]?(\d[\d_]*(\.\d*)?|\.\d+)([eE][-+]?\d+)?|0x[0-9a-fA-F]+|\.inf|\.nan)$""", RegexOption.IGNORE_CASE)

        fun renderScalar(value: String, forceQuote: Boolean): String {
            val needsQuote = forceQuote || value.isEmpty() || value.first() in plainUnsafeStart ||
                value.first().isWhitespace() || value.last().isWhitespace() ||
                value.contains(": ") || value.contains(" #") || value.endsWith(":") ||
                value.any { it == '\n' || it == '\r' || it == '\t' || it.isISOControl() } ||
                reserved.matches(value)
            if (!needsQuote) return value
            val escaped = buildString {
                for (c in value) when (c) {
                    '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    else -> if (c.isISOControl()) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            return "\"$escaped\""
        }

        /** Renders list items, quoting those that would break inline list syntax. */
        fun renderListItem(value: String): String =
            if (value.any { it in ",[]{}" }) renderScalar(value, true) else renderScalar(value, false)
    }
}
