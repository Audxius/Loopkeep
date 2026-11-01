package io.github.audxius.loopkeep.pipeline

import android.webkit.CookieManager
import java.io.File

/**
 * The TikTok login the user made on the Sync from TikTok screen lives in the WebView's cookie store.
 * Private ("Only me") posts can only be fetched with it, so downloads of the user's own posts borrow
 * it: as a Cookie header for direct file links, or as a cookies file for yt-dlp. Never stored elsewhere.
 */
object TikTokSession {
    /** The sign-in WebView's user agent; downloads made with its cookies present the same browser. */
    const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val HOME = "https://www.tiktok.com/"

    /** Cookies for [url] (only those that belong to its host), or null if there are none. */
    fun cookieHeader(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.ifBlank { null }

    val isSignedIn: Boolean
        get() = cookieHeader(HOME).orEmpty().let { it.contains("sessionid=") || it.contains("sid_tt=") }

    /** Writes the TikTok cookies as a Netscape cookies file for yt-dlp. Returns null when not signed in. */
    fun writeCookiesFile(target: File): File? {
        if (!isSignedIn) return null
        val pairs = cookieHeader(HOME).orEmpty().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
        if (pairs.isEmpty()) return null
        val expires = System.currentTimeMillis() / 1000 + 7 * 24 * 3600
        target.parentFile?.mkdirs()
        target.writeText(
            buildString {
                append("# Netscape HTTP Cookie File\n")
                for ((name, value) in pairs) append(".tiktok.com\tTRUE\t/\tTRUE\t$expires\t$name\t$value\n")
            },
        )
        return target
    }
}
