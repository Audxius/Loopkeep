package io.github.audxius.loopkeep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipInputStream

enum class ExportSource { SAVED, LIKED, OTHER }

data class ExportEntry(
    val url: String,
    /** Null when the export only has a short link; resolved later in the pipeline. */
    val videoId: String?,
    val date: LocalDate?,
    val source: ExportSource,
)

data class ExportScan(val entries: List<ExportEntry>) {
    fun count(source: ExportSource) = entries.count { it.source == source }
}

/**
 * Tolerant parser for TikTok's "Download your data" export.
 *
 * TikTok changes the export structure regularly, so nothing here depends on an exact schema.
 * Instead it looks for TikTok post links anywhere in the file, picks up a date found near each
 * link, and classifies the link by the section names around it ("Favorite Videos" → saved,
 * "Like List" → liked). Handles JSON, TXT, HTML and ZIP archives of those.
 */
object ExportParser {

    private const val MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024

    fun parse(bytes: ByteArray, fileName: String = ""): ExportScan {
        val raw = when {
            isZip(bytes) -> parseZip(bytes)
            else -> parseText(bytes.toString(Charsets.UTF_8), fileName)
        }
        return ExportScan(dedupe(raw))
    }

    fun parseText(text: String, context: String = ""): List<ExportEntry> {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val json = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
            if (json != null) return parseJson(json, context)
        }
        val looksHtml = Regex("""<(html|body|div|table|p|br|a)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed.take(20_000))
        return parseLines(if (looksHtml) htmlToText(trimmed) else trimmed, context)
    }

    /** Keeps one entry per video. A saved entry beats a liked one; an earlier date wins ties. */
    fun dedupe(entries: List<ExportEntry>): List<ExportEntry> {
        val byKey = LinkedHashMap<String, ExportEntry>()
        for (e in entries) {
            val key = e.videoId ?: e.url.trimEnd('/')
            val existing = byKey[key]
            byKey[key] = if (existing == null) e else better(existing, e)
        }
        return byKey.values.toList()
    }

    private fun better(a: ExportEntry, b: ExportEntry): ExportEntry {
        if (a.source != b.source) return if (a.source.ordinal <= b.source.ordinal) a.withDate(b) else b.withDate(a)
        val date = listOfNotNull(a.date, b.date).minOrNull()
        return a.copy(date = date)
    }

    private fun ExportEntry.withDate(other: ExportEntry) = if (date == null) copy(date = other.date) else this

    // --- classification ---------------------------------------------------------------

    /**
     * Classifies by the outermost section name that says something, so
     * "Like List" → "ItemFavoriteList" is a like, not a favourite.
     */
    fun classify(path: List<String>): ExportSource {
        for (segment in path) {
            val s = segment.lowercase().replace('_', ' ')
            if (Regex("""(^|[^a-z])like|likelist|liked""").containsMatchIn(s) && !s.contains("favorite video")) return ExportSource.LIKED
            if (Regex("""favou?rite|collect|saved|bookmark""").containsMatchIn(s)) return ExportSource.SAVED
            if (Regex("""brows|watch|history|share|post|upload|comment|message|chat|sound|effect|hashtag""").containsMatchIn(s)) return ExportSource.OTHER
        }
        return ExportSource.OTHER
    }

    // --- JSON -----------------------------------------------------------------------------

    private fun parseJson(root: JsonElement, context: String): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        walk(root, if (context.isBlank()) emptyList() else listOf(context), out)
        return out
    }

    private fun walk(el: JsonElement, path: List<String>, out: MutableList<ExportEntry>) {
        when (el) {
            is JsonObject -> {
                val urls = el.entries.mapNotNull { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }
                    .flatMap { TikTokUrls.findUrls(it) }
                    .filter { TikTokUrls.isPostUrl(it) }
                if (urls.isNotEmpty()) {
                    val date = el.entries.firstNotNullOfOrNull { (k, v) ->
                        if (Regex("date|time|created|saved", RegexOption.IGNORE_CASE).containsMatchIn(k)) primitiveDate(v) else null
                    } ?: el.values.firstNotNullOfOrNull { primitiveDate(it) }
                    val source = classify(path)
                    urls.forEach { out += ExportEntry(it, TikTokUrls.extractVideoId(it), date, source) }
                }
                for ((k, v) in el) if (v !is JsonPrimitive) walk(v, path + k, out)
            }
            is JsonArray -> el.forEach { item ->
                if (item is JsonPrimitive && item.isString) {
                    // A bare list of links.
                    TikTokUrls.findUrls(item.content).filter { TikTokUrls.isPostUrl(it) }.forEach {
                        out += ExportEntry(it, TikTokUrls.extractVideoId(it), null, classify(path))
                    }
                } else walk(item, path, out)
            }
            is JsonPrimitive -> Unit
        }
    }

    private fun primitiveDate(v: JsonElement): LocalDate? {
        val p = v as? JsonPrimitive ?: return null
        if (p.isString) return findDate(p.content)
        val n = p.longOrNull ?: return null
        return epochToDate(n)
    }

    // --- TXT / HTML -------------------------------------------------------------------------

    private fun parseLines(text: String, context: String): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        val lines = text.lines()
        var heading = ""
        var lastDate: LocalDate? = null
        var lastDateLine = -10
        for ((i, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val urls = TikTokUrls.findUrls(line).filter { TikTokUrls.isPostUrl(it) }
            val lineDate = findDate(line)
            if (urls.isNotEmpty()) {
                // Dates usually precede the link ("Date: …" then "Link: …"), occasionally share the line.
                val date = lineDate ?: lastDate.takeIf { i - lastDateLine <= 3 }
                    ?: lines.getOrNull(i + 1)?.let { findDate(it) }
                val source = classify(listOfNotNull(context.ifBlank { null }, heading.ifBlank { null }))
                urls.forEach { out += ExportEntry(it, TikTokUrls.extractVideoId(it), date, source) }
                lastDate = null
            } else if (lineDate != null) {
                lastDate = lineDate
                lastDateLine = i
            } else if (line.length <= 80 && line.any { it.isLetter() } && !line.contains("://")) {
                heading = line
            }
        }
        return out
    }

    fun htmlToText(html: String): String = html
        .replace(Regex("""(?is)<(script|style)\b.*?</\1>"""), " ")
        // Keep link targets visible once tags are stripped.
        .replace(Regex("""(?i)<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>"""), " $1 ")
        .replace(Regex("""(?i)<br\s*/?>|</(p|div|li|tr|h[1-6]|td|th|section|table)>"""), "\n")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    // --- ZIP ----------------------------------------------------------------------------------

    private fun isZip(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2].toInt() == 3 && bytes[3].toInt() == 4

    private fun parseZip(bytes: ByteArray): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && name.substringAfterLast('.').lowercase() in setOf("json", "txt", "html", "htm")) {
                        val content = zip.readUpTo(MAX_ZIP_ENTRY_BYTES.toInt())
                        // Folder and file names carry the section ("Activity/Favorite Videos.txt").
                        val context = name.substringBeforeLast('.').replace('/', ' ')
                        out += parseText(content.toString(Charsets.UTF_8), context)
                    }
                }
            }
        }
        return out
    }

    // --- dates ----------------------------------------------------------------------------------

    private val isoDate = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")

    fun findDate(text: String): LocalDate? {
        isoDate.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.takeIf { it.year in 2014..2100 }
        }
        return null
    }

    private fun epochToDate(n: Long): LocalDate? {
        val seconds = when {
            n in 1_400_000_000L..4_200_000_000L -> n
            n in 1_400_000_000_000L..4_200_000_000_000L -> n / 1000
            else -> return null
        }
        return Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate()
    }
}

/** `InputStream.readNBytes` is API 33+ on Android; this works everywhere. */
fun java.io.InputStream.readUpTo(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (total < max) {
        val n = read(buf, 0, minOf(buf.size, max - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}
