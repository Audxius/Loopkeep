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

/** OWN: the account owner's own posts (export section "Video"/"Videos"/"Posts"). */
enum class ExportSource { SAVED, LIKED, OWN, OTHER }

data class ExportEntry(
    val url: String,
    /** Null when the export only has a short link; resolved later in the pipeline. */
    val videoId: String?,
    val date: LocalDate?,
    val source: ExportSource,
    /** Collection names, if the export says which collection the video is in. */
    val collections: List<String> = emptyList(),
    /** Own posts: direct download link to the video file (export `VideoLink`), works for private posts. */
    val mediaUrl: String? = null,
    /** Own posts: the caption, if the export includes it. */
    val caption: String? = null,
    /** Own posts: post time as yyyyMMddHHmmss, used to key posts that have no TikTok link. */
    val timestamp: String? = null,
)

data class ExportScan(val entries: List<ExportEntry>, /** The account's own @handle, from the profile section. */ val ownerHandle: String? = null) {
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
        owner = null
        val raw = when {
            isZip(bytes) -> parseZip(bytes)
            else -> parseText(bytes.toString(Charsets.UTF_8), fileName)
        }
        return ExportScan(dedupe(raw), owner)
    }

    // Set while parsing; the parser is single-threaded per call.
    @Volatile private var owner: String? = null
    private val ownerKey = Regex("""^(user ?name|unique ?id)$""", RegexOption.IGNORE_CASE)
    private val handleValue = Regex("""^@?([A-Za-z0-9._]{2,24})$""")
    private val captionKey = Regex("""^(title|caption|description|desc|text|video ?description)$""", RegexOption.IGNORE_CASE)
    private val mediaKey = Regex("""(video|download|media) ?(link|url)""", RegexOption.IGNORE_CASE)
    private val anyUrl = Regex("""https?://[^\s"'<>]+""")
    private val dateTime = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** "2023-05-04 18:22:10" → "20230504182210". */
    fun timestampOf(text: String): String? = dateTime.find(text)?.destructured?.let { (y, mo, d, h, mi, s) ->
        "%s%02d%02d%02d%02d%02d".format(y, mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.ifEmpty { "0" }.toInt())
    }

    /** A direct file link (TikTok CDN), not a post page. */
    private fun isMediaUrl(url: String): Boolean =
        !TikTokUrls.isPostUrl(url) && (url.contains(".mp4") || url.contains("tiktokcdn") || url.contains("/video/tos/") || Regex("""//v\d+[-.]""").containsMatchIn(url))

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
        val collections = (a.collections + b.collections).distinct()
        // Never lose an own post's file link or caption when another section lists the same video.
        fun ExportEntry.keepExtras() = copy(
            mediaUrl = mediaUrl ?: a.mediaUrl ?: b.mediaUrl, caption = caption ?: a.caption ?: b.caption, timestamp = timestamp ?: a.timestamp ?: b.timestamp,
        )
        if (a.source != b.source) {
            val winner = if (a.source.ordinal <= b.source.ordinal) a.withDate(b) else b.withDate(a)
            return winner.copy(collections = collections).keepExtras()
        }
        val date = listOfNotNull(a.date, b.date).minOrNull()
        return a.copy(date = date, collections = collections).keepExtras()
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
            // The owner's own posts: "Video" → "Videos" → "VideoList", or "Post" → "Posts".
            if (Regex("""^(videos?|posts?|video ?list|my videos|your videos|your posts|uploads?)$""").matches(s.trim())) return ExportSource.OWN
            val isLike = Regex("""(^|[^a-z])like|likelist|liked""").containsMatchIn(s) && !s.contains("favorite video")
            val isSaved = Regex("""favou?rite|collect|saved|bookmark""").containsMatchIn(s)
            // "Likes and Favorites" (newer exports) names both lists; the section inside decides.
            if (isLike && isSaved) continue
            if (isLike) return ExportSource.LIKED
            if (isSaved) return ExportSource.SAVED
            if (Regex("""brows|watch|history|share|post|upload|comment|message|chat|sound|effect|hashtag""").containsMatchIn(s)) return ExportSource.OTHER
        }
        return ExportSource.OTHER
    }

    // --- JSON -----------------------------------------------------------------------------

    private fun parseJson(root: JsonElement, context: String): List<ExportEntry> {
        val out = mutableListOf<ExportEntry>()
        walk(root, context.split('/').filter { it.isNotBlank() }, out, null)
        return out
    }

    private val collectionNameKey = Regex("""^(collection[ _]?)?(name|title)$""", RegexOption.IGNORE_CASE)
    private val genericKey = Regex("""list|items|videos|data|favou?rite|collection""", RegexOption.IGNORE_CASE)

    private fun walk(el: JsonElement, path: List<String>, out: MutableList<ExportEntry>, collection: String?) {
        val inCollections = path.any { it.contains("collection", ignoreCase = true) }
        when (el) {
            is JsonObject -> {
                // {"CollectionName": "Recipes", "Videos": […]} inside a collections section.
                val named = if (inCollections) el.entries.firstNotNullOfOrNull { (k, v) ->
                    if (collectionNameKey.matches(k)) (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null } else null
                } else null
                val current = named ?: collection
                val strings = el.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }
                if (owner == null && path.any { it.contains("profile", ignoreCase = true) }) {
                    strings.firstOrNull { (k, _) -> ownerKey.matches(k) }?.second?.trim()?.let { handleValue.find(it)?.groupValues?.get(1) }?.let { owner = it }
                }
                val urls = strings.map { it.second }
                    .flatMap { TikTokUrls.findUrls(it) }
                    .filter { TikTokUrls.isPostUrl(it) }
                val source = classify(path)
                if (source == ExportSource.OWN) {
                    ownPost(strings, urls)?.let { out += it }
                } else if (urls.isNotEmpty()) {
                    val date = el.entries.firstNotNullOfOrNull { (k, v) ->
                        if (Regex("date|time|created|saved", RegexOption.IGNORE_CASE).containsMatchIn(k)) primitiveDate(v) else null
                    } ?: el.values.firstNotNullOfOrNull { primitiveDate(it) }
                    urls.forEach { out += ExportEntry(it, TikTokUrls.extractVideoId(it), date, source, listOfNotNull(current)) }
                }
                for ((k, v) in el) {
                    if (v is JsonPrimitive) continue
                    // {"Collections": {"Recipes": […]}}: a non-generic key under a collections section is the name.
                    val childCollection = if (current == null && path.lastOrNull()?.contains("collection", true) == true && !genericKey.containsMatchIn(k)) k else current
                    walk(v, path + k, out, childCollection)
                }
            }
            is JsonArray -> el.forEach { item ->
                if (item is JsonPrimitive && item.isString) {
                    // A bare list of links.
                    TikTokUrls.findUrls(item.content).filter { TikTokUrls.isPostUrl(it) }.forEach {
                        out += ExportEntry(it, TikTokUrls.extractVideoId(it), null, classify(path), listOfNotNull(collection))
                    }
                } else walk(item, path, out, collection)
            }
            is JsonPrimitive -> Unit
        }
    }

    /**
     * One of the owner's own posts. Older exports give `Date` + `VideoLink` (a direct, expiring file
     * link that works for private posts too); newer ones may give a post `Link`. Both are kept.
     */
    private fun ownPost(fields: List<Pair<String, String>>, postUrls: List<String>): ExportEntry? {
        val media = fields.firstNotNullOfOrNull { (k, v) -> v.trim().takeIf { mediaKey.containsMatchIn(k) && it.startsWith("http") && !TikTokUrls.isPostUrl(it) } }
            ?: fields.flatMap { anyUrl.findAll(it.second).map { m -> m.value } }.firstOrNull { isMediaUrl(it) }
        val post = postUrls.firstOrNull()
        if (media == null && post == null) return null
        val dateText = fields.firstOrNull { (k, _) -> Regex("date|time|created", RegexOption.IGNORE_CASE).containsMatchIn(k) }?.second
            ?: fields.firstOrNull { findDate(it.second) != null }?.second
        val caption = fields.firstOrNull { (k, _) -> captionKey.matches(k.trim()) }?.second?.trim()?.ifEmpty { null }
        return ExportEntry(
            url = post ?: media!!,
            videoId = post?.let { TikTokUrls.extractVideoId(it) },
            date = dateText?.let { findDate(it) },
            source = ExportSource.OWN,
            mediaUrl = media,
            caption = caption,
            timestamp = dateText?.let { timestampOf(it) },
        )
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
        var collection: String? = null
        val collectionLine = Regex("""^collection(?: name)?\s*[:\-]\s*(.+)$""", RegexOption.IGNORE_CASE)
        var lastDate: LocalDate? = null
        var lastDateText: String? = null
        var lastDateLine = -10
        val contextPath = context.split('/').filter { it.isNotBlank() }
        val profileFile = context.contains("profile", ignoreCase = true)
        for ((i, rawLine) in lines.withIndex()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            collectionLine.find(line)?.let { collection = it.groupValues[1].trim(); continue }
            if (owner == null && (profileFile || heading.contains("profile", ignoreCase = true))) {
                Regex("""^user ?name\s*:\s*@?([A-Za-z0-9._]{2,24})\s*$""", RegexOption.IGNORE_CASE).find(line)?.let { owner = it.groupValues[1]; continue }
            }
            val source = classify(contextPath + listOfNotNull(heading.ifBlank { null }))
            if (source == ExportSource.OWN) {
                // "Date: …" then "Video Link: https://…" (a direct file link) or a post link.
                val links = anyUrl.findAll(line).map { it.value.trimEnd('.', ',') }.filter { TikTokUrls.isPostUrl(it) || isMediaUrl(it) }.toList()
                if (links.isNotEmpty()) {
                    val dateText = lastDateText.takeIf { i - lastDateLine <= 3 } ?: line
                    val post = links.firstOrNull { TikTokUrls.isPostUrl(it) }
                    val media = links.firstOrNull { isMediaUrl(it) }
                    out += ExportEntry(
                        post ?: media!!, post?.let { TikTokUrls.extractVideoId(it) }, findDate(dateText), ExportSource.OWN,
                        mediaUrl = media, timestamp = timestampOf(dateText),
                    )
                    lastDate = null
                    lastDateText = null
                    continue
                }
            }
            val urls = TikTokUrls.findUrls(line).filter { TikTokUrls.isPostUrl(it) }
            val lineDate = findDate(line)
            if (urls.isNotEmpty()) {
                // Dates usually precede the link ("Date: …" then "Link: …"), occasionally share the line.
                val date = lineDate ?: lastDate.takeIf { i - lastDateLine <= 3 }
                    ?: lines.getOrNull(i + 1)?.let { findDate(it) }
                urls.forEach { out += ExportEntry(it, TikTokUrls.extractVideoId(it), date, source, listOfNotNull(collection)) }
                lastDate = null
            } else if (lineDate != null) {
                lastDate = lineDate
                lastDateText = line
                lastDateLine = i
            } else if (line.length <= 80 && line.any { it.isLetter() } && !line.contains("://")) {
                heading = line
                collection = null
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
                        val context = name.substringBeforeLast('.')
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
