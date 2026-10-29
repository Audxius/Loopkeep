package io.github.audxius.loopkeep.core

import java.text.Normalizer

/** Readable, portable note filenames: `handle - first words of caption - 123456.md`. */
object FileNames {

    private const val MAX_WORDS = 7
    private const val MAX_BYTES = 120

    fun noteFileName(authorHandle: String?, caption: String?, tiktokId: String): String {
        val handle = sanitize(authorHandle.orEmpty()).take(30)
        val words = captionWords(caption.orEmpty())
        val shortId = tiktokId.filter { it.isDigit() }.takeLast(6).ifEmpty { sanitize(tiktokId).take(12) }
        // Without author or caption, a bare number is a poor name; say what it is.
        val parts = listOf(handle.ifBlank { if (words.isBlank()) "tiktok" else "" }, words, shortId).filter { it.isNotBlank() }
        val base = truncateBytes(parts.joinToString(" - "), MAX_BYTES).trim(' ', '-', '.')
        return (base.ifBlank { "tiktok $shortId" }) + ".md"
    }

    /** Appends " (2)", " (3)" … until [taken] says the name is free. */
    fun unique(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    private fun captionWords(caption: String): String {
        val tokens = caption.split(Regex("""\s+""")).filter { it.isNotBlank() }
        // Prefer real words; fall back to hashtags (without '#') when the caption is only tags.
        val words = tokens.filterNot { it.startsWith("#") || it.startsWith("@") }.map { sanitize(it) }.filter { it.isNotBlank() }
        val chosen = words.ifEmpty { tokens.map { sanitize(it.trimStart('#', '@')) }.filter { it.isNotBlank() } }
        return chosen.take(MAX_WORDS).joinToString(" ")
    }

    /**
     * Keeps letters (any script), digits, spaces, and `-_'`. Drops emoji, punctuation, and
     * everything that Windows, Android, or Obsidian links choke on (`\ / : * ? " < > | # ^ [ ]`).
     */
    fun sanitize(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC).replace('\u2019', '\'').replace('\u02BC', '\'')
        val sb = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            when {
                Character.isLetterOrDigit(cp) -> sb.appendCodePoint(cp)
                Character.getType(cp) == Character.NON_SPACING_MARK.toInt() -> sb.appendCodePoint(cp)
                cp == '-'.code || cp == '_'.code || cp == '.'.code || cp == '\''.code -> sb.appendCodePoint(cp)
                Character.isWhitespace(cp) -> sb.append(' ')
                else -> sb.append(' ')
            }
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex(" +"), " ").trim().trimStart('.', '-')
    }

    private fun truncateBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += len
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun videoFile(id: String, ext: String = "mp4") = "$id.$ext"
    fun coverFile(id: String) = "$id.jpg"
    fun imageFile(id: String, index: Int) = "${id}_${index + 1}.jpg"
}
