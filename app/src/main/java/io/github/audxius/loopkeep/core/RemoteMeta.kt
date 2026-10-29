package io.github.audxius.loopkeep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Metadata pulled from yt-dlp's info JSON. Every field is optional: yt-dlp output varies by version. */
data class YtDlpInfo(
    val id: String?,
    val webpageUrl: String?,
    val caption: String?,
    val author: String?,
    val authorHandle: String?,
    val posted: LocalDate?,
    val durationSeconds: Int?,
    /** Cover candidates, best first. */
    val thumbnails: List<String>,
    val hasVideo: Boolean,
    val ext: String?,
)

/** Metadata from TikTok's oEmbed endpoint (https://www.tiktok.com/oembed?url=…). */
data class OEmbedInfo(
    val caption: String?,
    val author: String?,
    val authorHandle: String?,
    val thumbnailUrl: String?,
    val videoId: String?,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
    (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JsonObject.num(vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
    (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
}

object YtDlpInfoParser {

    fun parse(json: String): YtDlpInfo? {
        val obj = runCatching { lenientJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        return parse(obj)
    }

    fun parse(obj: JsonObject): YtDlpInfo {
        val posted = obj.str("upload_date")?.let { d ->
            runCatching { LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        } ?: obj.num("timestamp", "release_timestamp")?.let {
            Instant.ofEpochSecond(it.toLong()).atZone(ZoneOffset.UTC).toLocalDate()
        }

        val formats = obj["formats"] as? JsonArray
        val hasVideo = when {
            formats != null && formats.isNotEmpty() -> formats.any { f ->
                val vc = (f as? JsonObject)?.str("vcodec")
                vc != null && vc != "none"
            }
            else -> obj.str("vcodec")?.let { it != "none" } ?: (obj.num("width") != null)
        }

        // On TikTok, yt-dlp sets `uploader` to the @handle and `channel`/`creator` to the display name.
        val handle = obj.str("uploader", "uploader_id")?.removePrefix("@")?.takeIf { !it.all(Char::isDigit) }
            ?: obj.str("webpage_url", "original_url")?.let { TikTokUrls.extractHandle(it) }
        val author = obj.str("channel", "creator", "artist")
            ?: (obj["creators"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: handle

        return YtDlpInfo(
            id = obj.str("id", "display_id"),
            webpageUrl = obj.str("webpage_url", "original_url"),
            caption = obj.str("description", "title", "fulltitle"),
            author = author,
            authorHandle = handle,
            posted = posted,
            durationSeconds = obj.num("duration")?.let { kotlin.math.round(it).toInt() }?.takeIf { it > 0 },
            thumbnails = thumbnails(obj),
            hasVideo = hasVideo,
            ext = obj.str("ext"),
        )
    }

    /**
     * Static covers first ("cover", "origin_cover", "originCover"), animated ones
     * ("dynamic_cover") last, then the top-level `thumbnail`.
     */
    private fun thumbnails(obj: JsonObject): List<String> {
        val list = (obj["thumbnails"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun rank(t: JsonObject): Int {
            val id = t.str("id")?.lowercase().orEmpty()
            return when {
                id.contains("dynamic") || id.contains("animated") -> 50
                id == "cover" -> 0
                id.contains("origin") -> 1
                id.contains("cover") -> 2
                else -> 10 - (t.num("preference")?.toInt() ?: 0).coerceIn(-5, 5)
            }
        }
        val urls = list.sortedBy { rank(it) }.mapNotNull { it.str("url") }
        return (urls + listOfNotNull(obj.str("thumbnail"))).distinct()
    }
}

object OEmbedParser {
    fun parse(json: String): OEmbedInfo? {
        val obj = runCatching { lenientJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val authorUrl = obj.str("author_url")
        val handle = obj.str("author_unique_id")?.removePrefix("@") ?: authorUrl?.let { TikTokUrls.extractHandle(it) }
        val idFromHtml = obj.str("html")?.let { Regex("""data-video-id="(\d+)"""").find(it)?.groupValues?.get(1) }
        return OEmbedInfo(
            caption = obj.str("title"),
            author = obj.str("author_name") ?: handle,
            authorHandle = handle,
            thumbnailUrl = obj.str("thumbnail_url"),
            videoId = obj.str("embed_product_id") ?: idFromHtml,
        )
    }
}

/**
 * Extracts slideshow image URLs from a TikTok post page. TikTok embeds its state as JSON in a
 * `<script>` tag (`__UNIVERSAL_DATA_FOR_REHYDRATION__` or the older `SIGI_STATE`); this finds any
 * `imagePost.images[*]` there without depending on the exact nesting.
 */
object SlideshowParser {

    private val scriptRegex = Regex(
        """<script[^>]*id="(?:__UNIVERSAL_DATA_FOR_REHYDRATION__|SIGI_STATE|__NEXT_DATA__)"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun imageUrls(html: String): List<String> {
        val json = scriptRegex.findAll(html).mapNotNull { runCatching { lenientJson.parseToJsonElement(it.groupValues[1]) }.getOrNull() }
        for (root in json) {
            val imagePost = findKey(root, "imagePost") as? JsonObject ?: continue
            val images = imagePost["images"] as? JsonArray ?: continue
            val urls = images.mapNotNull { img ->
                val o = img as? JsonObject ?: return@mapNotNull null
                val holder = (o["imageURL"] ?: o["display_image"] ?: o["displayImage"] ?: o["owner_watermark_image"]) as? JsonObject
                ((holder?.get("urlList") ?: holder?.get("url_list")) as? JsonArray)?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
            }
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private fun findKey(el: JsonElement, key: String, depth: Int = 0): JsonElement? {
        if (depth > 30) return null
        return when (el) {
            is JsonObject -> el[key] ?: el.values.firstNotNullOfOrNull { findKey(it, key, depth + 1) }
            is JsonArray -> el.firstNotNullOfOrNull { findKey(it, key, depth + 1) }
            else -> null
        }
    }
}

