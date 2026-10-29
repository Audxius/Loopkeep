package io.github.audxius.loopkeep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A comment the post's creator wrote, with the comment it replies to (if any). */
data class CreatorComment(
    val text: String,
    val replyToUser: String? = null,
    val replyToText: String? = null,
)

data class CommentPage(val creatorComments: List<CreatorComment>, val hasMore: Boolean, val cursor: Long)

/**
 * Picks the creator's comments out of TikTok's `api/comment/list` response. The creator is
 * recognised by TikTok's "Creator" label, their numeric user id, or their handle. Their replies
 * usually appear in each comment's `reply_comment` preview (checked live 2026-10-29).
 */
object CommentParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, authorUid: String?, authorHandle: String?): CommentPage? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val comments = root["comments"] as? JsonArray ?: JsonArray(emptyList())
        val out = mutableListOf<CreatorComment>()
        for (c in comments) {
            val o = c as? JsonObject ?: continue
            val text = o.str("text") ?: continue
            if (isCreator(o, authorUid, authorHandle)) out += CreatorComment(text)
            for (r in (o["reply_comment"] as? JsonArray).orEmpty()) {
                val ro = r as? JsonObject ?: continue
                val replyText = ro.str("text") ?: continue
                if (isCreator(ro, authorUid, authorHandle)) {
                    out += CreatorComment(replyText, userHandle(o), text)
                }
            }
        }
        val hasMore = (root["has_more"] as? JsonPrimitive)?.let { it.intOrNull == 1 || it.contentOrNull == "true" } ?: false
        val cursor = (root["cursor"] as? JsonPrimitive)?.longOrNull ?: 0
        return CommentPage(out, hasMore, cursor)
    }

    private fun isCreator(comment: JsonObject, authorUid: String?, authorHandle: String?): Boolean {
        val labels = (comment["label_list"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }
        if (labels.any { it.equals("Creator", ignoreCase = true) }) return true
        val user = comment["user"] as? JsonObject ?: return false
        if (authorUid != null && user.str("uid") == authorUid) return true
        return authorHandle != null && user.str("unique_id")?.equals(authorHandle, ignoreCase = true) == true
    }

    private fun userHandle(comment: JsonObject): String? = (comment["user"] as? JsonObject)?.str("unique_id")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** Markdown for the `## Creator comments` section, and back. */
object CreatorCommentsMarkdown {

    fun render(handle: String?, comments: List<CreatorComment>): String {
        val who = handle?.let { "@$it" } ?: "Creator"
        return comments.joinToString("\n\n") { c ->
            val reply = "**$who:** ${oneLine(c.text)}"
            if (c.replyToText != null) "> ${c.replyToUser?.let { "@$it: " } ?: ""}${oneLine(c.replyToText)}\n\n$reply" else reply
        }
    }

    fun parse(markdown: String): List<CreatorComment> {
        val out = mutableListOf<CreatorComment>()
        var quoteUser: String? = null
        var quoteText: String? = null
        for (line in markdown.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (line.startsWith(">")) {
                val q = line.removePrefix(">").trim()
                val m = Regex("""^@([A-Za-z0-9._-]+):\s*(.*)$""").find(q)
                quoteUser = m?.groupValues?.get(1)
                quoteText = m?.groupValues?.get(2) ?: q
            } else {
                val text = Regex("""^\*\*[^*]+:\*\*\s*(.*)$""").find(line)?.groupValues?.get(1) ?: line
                out += CreatorComment(text, quoteUser, quoteText)
                quoteUser = null
                quoteText = null
            }
        }
        return out
    }

    private fun oneLine(s: String) = s.replace(Regex("""\s*\n\s*"""), " ").trim()
}
