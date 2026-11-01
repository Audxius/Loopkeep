package io.github.audxius.loopkeep.core

/**
 * Recognises TikTok links in their many shapes and turns them into a stable video ID
 * and a canonical URL that yt-dlp understands.
 */
object TikTokUrls {

    private val linkRegex = Regex(
        """https?://(?:[a-z0-9-]+\.)*(?:tiktok\.com|tiktokv\.com|tiktokv\.eu|tiktokcdn\.com)/[^\s"'<>\\)\]}，]*""",
        RegexOption.IGNORE_CASE,
    )

    // Video and photo IDs are 64-bit snowflakes: 18-19 digits today, allow some slack.
    private val idPatterns = listOf(
        Regex("""/(?:video|photo|v)/(\d{15,22})"""),
        Regex("""[?&](?:item_id|share_item_id|aweme_id|video_id)=(\d{15,22})"""),
        Regex("""/embed(?:/v2)?/(\d{15,22})"""),
    )

    private val handleRegex = Regex("""/@([A-Za-z0-9._-]+)""")

    /** All TikTok-looking URLs in [text], with trailing punctuation trimmed. */
    fun findUrls(text: String): List<String> =
        linkRegex.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', '!', '?') }.toList()

    fun isTikTokUrl(url: String): Boolean = linkRegex.matchesAt(url.trim(), 0)

    /** Short links (vm./vt./tiktok.com/t/) hide the ID and must be resolved over the network. */
    fun isShortLink(url: String): Boolean {
        val u = url.lowercase()
        return Regex("""https?://(vm|vt)\.tiktok\.com/""").containsMatchIn(u) ||
            Regex("""https?://(www\.|m\.)?tiktok\.com/t/""").containsMatchIn(u)
    }

    fun extractVideoId(url: String): String? =
        idPatterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }

    fun extractHandle(url: String): String? =
        handleRegex.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    fun isPhotoUrl(url: String): Boolean = url.contains("/photo/")

    /**
     * Canonical web URL. yt-dlp accepts an empty handle (`/@/video/<id>`), so the ID alone
     * is enough when the author is unknown.
     */
    fun canonicalUrl(id: String, handle: String? = null, photo: Boolean = false): String =
        "https://www.tiktok.com/@${handle.orEmpty()}/${if (photo) "photo" else "video"}/$id"

    private val collectionRegex = Regex("""tiktok\.com/@([A-Za-z0-9._-]+)/collection/([^/?#]+)-(\d+)""")

    fun isCollectionUrl(url: String): Boolean = collectionRegex.containsMatchIn(url)

    /** Collection name from a collection URL (`/collection/Pasta%20night-123` → "Pasta night"). */
    fun collectionName(url: String): String? {
        val slug = collectionRegex.find(url)?.groupValues?.get(2) ?: return null
        val decoded = runCatching { java.net.URLDecoder.decode(slug.replace("+", "%2B"), "UTF-8") }.getOrDefault(slug)
        return decoded.replace('-', ' ').trim().ifEmpty { null }
    }

    /**
     * Upload date encoded in a TikTok post ID: the top 32 bits are the Unix time the post was
     * created (checked against TikTok's own dates, 2026-10-31). Works for private and deleted
     * posts too, since it needs nothing but the ID.
     */
    fun postedFromId(id: String): java.time.LocalDate? {
        val n = id.toULongOrNull() ?: return null
        val seconds = (n shr 32).toLong()
        // TikTok (2016+) posts only; older musical.ly-era IDs don't follow this scheme.
        if (seconds < 1_451_606_400L || seconds > System.currentTimeMillis() / 1000 + 86_400) return null
        return java.time.Instant.ofEpochSecond(seconds).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    }

    fun profileUrl(handle: String): String = "https://www.tiktok.com/@${handle.removePrefix("@")}"

    /** True if this is a link to a video/photo post (not a profile, sound, or hashtag page). */
    fun isPostUrl(url: String): Boolean = isShortLink(url) || extractVideoId(url) != null
}
