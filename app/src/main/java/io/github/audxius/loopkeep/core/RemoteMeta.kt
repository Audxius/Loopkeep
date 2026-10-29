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
    /** TikTok's numeric user id of the creator. */
    val authorUid: String? = null,
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
        val author = obj.str("channel", "creator")
            ?: (obj["creators"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: handle

        return YtDlpInfo(
            id = obj.str("id", "display_id"),
            webpageUrl = obj.str("webpage_url", "original_url"),
            caption = obj.str("description", "title", "fulltitle"),
            author = author,
            authorHandle = handle,
            authorUid = obj.str("uploader_id")?.takeIf { id -> id.all(Char::isDigit) },
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

/** What a TikTok post page says about the post. */
data class PageInfo(
    /** TikTok's own status: 0 = OK, 10204 = doesn't exist, 10216/10222 = private, etc. */
    val statusCode: Int?,
    val statusMessage: String?,
    val id: String?,
    val caption: String?,
    val author: String?,
    val authorHandle: String?,
    val posted: LocalDate?,
    val durationSeconds: Int?,
    val covers: List<String>,
    /** Slideshow images, in order. Empty for regular videos. */
    val imageUrls: List<String>,
    val authorUid: String? = null,
    /** Profile bio and the link in it, as shown on the post page. */
    val authorBio: String? = null,
    val authorLink: String? = null,
) {
    val isAvailable: Boolean get() = statusCode == null || statusCode == 0
}

/**
 * Parses the JSON state TikTok embeds in post pages (`__UNIVERSAL_DATA_FOR_REHYDRATION__`, or the
 * older `SIGI_STATE`). Used as the fallback when yt-dlp can't download a post, and for slideshows.
 * Finds `itemStruct` / `imagePost` / `statusCode` wherever they are nested.
 */
object PageParser {

    private val scriptRegex = Regex(
        """<script[^>]*id="(?:__UNIVERSAL_DATA_FOR_REHYDRATION__|SIGI_STATE|__NEXT_DATA__)"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(html: String): PageInfo? {
        val roots = scriptRegex.findAll(html)
            .mapNotNull { runCatching { lenientJson.parseToJsonElement(it.groupValues[1]) }.getOrNull() }
            .toList()
        if (roots.isEmpty()) return null
        for (root in roots) {
            // Only the post-detail section counts. Other sections (app-context etc.) also carry a
            // statusCode, and a page without post data (e.g. /photo/ URLs) must not look like a result.
            val detail = findObjectWithKey(root, "itemInfo") ?: findDetailSection(root)
            val item = (findKey(root, "itemStruct") as? JsonObject)
                ?: ((findKey(root, "ItemModule") as? JsonObject)?.values?.firstOrNull() as? JsonObject)
            val status = (detail?.get("statusCode") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (item == null && status == null) continue
            val statusMsg = (detail?.get("statusMsg") as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
            if (item == null) return PageInfo(status, statusMsg, null, null, null, null, null, null, emptyList(), emptyList())

            val author = item["author"]
            val authorObj = author as? JsonObject
            val handle = authorObj?.str("uniqueId", "unique_id") ?: (author as? JsonPrimitive)?.contentOrNull
            val video = item["video"] as? JsonObject
            return PageInfo(
                statusCode = status ?: 0,
                statusMessage = statusMsg,
                id = item.str("id"),
                caption = item.str("desc"),
                author = authorObj?.str("nickname") ?: item.str("nickname") ?: handle,
                authorHandle = handle,
                posted = item.num("createTime")?.toLong()?.takeIf { it > 0 }?.let {
                    Instant.ofEpochSecond(it).atZone(ZoneOffset.UTC).toLocalDate()
                },
                durationSeconds = video?.num("duration")?.toInt()?.takeIf { it > 0 },
                covers = listOfNotNull(video?.str("cover"), video?.str("originCover")).distinct(),
                imageUrls = imageUrls(item),
                authorUid = authorObj?.str("id"),
                authorBio = authorObj?.str("signature"),
                authorLink = (authorObj?.get("bioLink") as? JsonObject)?.str("link")
                    ?.let { if (it.startsWith("http")) it else "https://$it" },
            )
        }
        return null
    }

    private fun imageUrls(item: JsonObject): List<String> {
        val imagePost = findKey(item, "imagePost") as? JsonObject ?: return emptyList()
        val images = imagePost["images"] as? JsonArray ?: return emptyList()
        return images.mapNotNull { img ->
            val o = img as? JsonObject ?: return@mapNotNull null
            val holder = (o["imageURL"] ?: o["display_image"] ?: o["displayImage"]) as? JsonObject
            ((holder?.get("urlList") ?: holder?.get("url_list")) as? JsonArray)?.firstOrNull()
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }
    }

    /** A `"webapp.video-detail"`-style section (any key ending in `-detail`) that has a statusCode. */
    private fun findDetailSection(el: JsonElement, depth: Int = 0): JsonObject? {
        if (depth > 10 || el !is JsonObject) return null
        for ((k, v) in el) {
            if (k.endsWith("-detail") && v is JsonObject && "statusCode" in v) return v
        }
        return el.values.firstNotNullOfOrNull { findDetailSection(it, depth + 1) }
    }

    private fun findKey(el: JsonElement, key: String, depth: Int = 0): JsonElement? {
        if (depth > 30) return null
        return when (el) {
            is JsonObject -> el[key] ?: el.values.firstNotNullOfOrNull { findKey(it, key, depth + 1) }
            is JsonArray -> el.firstNotNullOfOrNull { findKey(it, key, depth + 1) }
            else -> null
        }
    }

    private fun findObjectWithKey(el: JsonElement, key: String, depth: Int = 0): JsonObject? {
        if (depth > 30) return null
        return when (el) {
            is JsonObject -> if (key in el) el else el.values.firstNotNullOfOrNull { findObjectWithKey(it, key, depth + 1) }
            is JsonArray -> el.firstNotNullOfOrNull { findObjectWithKey(it, key, depth + 1) }
            else -> null
        }
    }
}

/** Reads the video links out of `yt-dlp --flat-playlist -J <collection url>` output. */
object PlaylistParser {
    fun videoUrls(json: String): List<String> {
        val obj = runCatching { lenientJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
        val entries = obj["entries"] as? JsonArray ?: return emptyList()
        return entries.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.str("url", "webpage_url")
            val id = o.str("id")
            when {
                url != null && TikTokUrls.extractVideoId(url) != null -> url
                id != null && id.all { it.isDigit() } -> TikTokUrls.canonicalUrl(id)
                else -> null
            }
        }.distinct()
    }
}

data class ProfileInfo(val uid: String?, val handle: String?, val nickname: String?, val bio: String?, val link: String?)

/**
 * Reads a creator's profile page (`tiktok.com/@handle`): bio and the link in it. The post page
 * doesn't include the bio link (checked live 2026-10-29), the profile page does (`bioLink.link`).
 */
object ProfileParser {
    private val scriptRegex = Regex(
        """<script[^>]*id="(?:__UNIVERSAL_DATA_FOR_REHYDRATION__|SIGI_STATE)"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun parse(html: String): ProfileInfo? {
        for (m in scriptRegex.findAll(html)) {
            val root = runCatching { lenientJson.parseToJsonElement(m.groupValues[1]) }.getOrNull() ?: continue
            val userInfo = findObject(root, "userInfo") ?: continue
            val user = userInfo["user"] as? JsonObject ?: continue
            val link = (user["bioLink"] as? JsonObject)?.str("link")
            return ProfileInfo(
                uid = user.str("id"),
                handle = user.str("uniqueId"),
                nickname = user.str("nickname"),
                bio = user.str("signature"),
                link = link?.let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" },
            )
        }
        return null
    }

    private fun findObject(el: JsonElement, key: String, depth: Int = 0): JsonObject? {
        if (depth > 12) return null
        return when (el) {
            is JsonObject -> (el[key] as? JsonObject) ?: el.values.firstNotNullOfOrNull { findObject(it, key, depth + 1) }
            is JsonArray -> el.firstNotNullOfOrNull { findObject(it, key, depth + 1) }
            else -> null
        }
    }
}
