package io.github.audxius.loopkeep.pipeline

import io.github.audxius.loopkeep.core.readUpTo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code for $url")

/** Minimal HTTP over HttpURLConnection; the app only needs a handful of GETs. */
object Http {
    // TikTok serves the full page state to ordinary desktop browsers.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 30_000
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Referer", "https://www.tiktok.com/")
        }

    /** Follows redirects by hand (cross-host redirects aren't followed automatically) until [done] is true. */
    fun resolveRedirects(start: String, maxHops: Int = 8, done: (String) -> Boolean): String {
        var url = start
        repeat(maxHops) {
            if (done(url)) return url
            val conn = open(url).apply { instanceFollowRedirects = false; requestMethod = "GET" }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    url = URL(URL(url), location).toString()
                } else {
                    return url
                }
            } finally {
                conn.disconnect()
            }
        }
        return url
    }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            return conn.inputStream.use { it.readUpTo(MAX_TEXT_BYTES) }.toString(Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, target: File, maxBytes: Long = 50L * 1024 * 1024) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpStatusException(code, url)
            target.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("Download too large: $url")
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
