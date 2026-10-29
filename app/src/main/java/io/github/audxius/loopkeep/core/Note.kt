package io.github.audxius.loopkeep.core

import java.time.LocalDate

enum class NoteStatus(val value: String) {
    FULL("full"), TEXT_ONLY("text-only"), UNAVAILABLE("unavailable");

    companion object {
        fun from(value: String?): NoteStatus = entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: FULL
    }
}

enum class PostType(val value: String) {
    VIDEO("video"), SLIDESHOW("slideshow");

    companion object {
        fun from(value: String?): PostType = entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: VIDEO
    }
}

/** Frontmatter keys the app owns. Everything else in a note belongs to the user. */
object NoteKeys {
    const val TIKTOK_ID = "tiktok_id"
    const val SOURCE_URL = "source_url"
    const val AUTHOR = "author"
    const val AUTHOR_HANDLE = "author_handle"
    const val POSTED = "posted"
    const val SAVED = "saved"
    const val TAGS = "tags"
    const val COLLECTIONS = "collections"
    const val STATUS = "status"
    const val TYPE = "type"
    const val DURATION = "duration"
    const val VIDEO = "video"
    const val COVER = "cover"
    const val AUDIO = "audio"
    const val IMAGES = "images"

    const val AUTHOR_URL = "author_url"
    const val AUTHOR_LINK = "author_link"
    const val AUTHOR_BIO = "author_bio"

    const val NOTES_HEADING = "## Notes"
    const val CREATOR_COMMENTS_HEADING = "## Creator comments"
}

/** Everything known about a post when its note is written. */
data class VideoMeta(
    val tiktokId: String,
    val sourceUrl: String,
    val author: String? = null,
    val authorHandle: String? = null,
    val posted: LocalDate? = null,
    val saved: LocalDate,
    val tags: List<String> = emptyList(),
    /** TikTok collections (or the user's own groupings) this post belongs to. */
    val collections: List<String> = emptyList(),
    val status: NoteStatus,
    val type: PostType = PostType.VIDEO,
    val durationSeconds: Int? = null,
    val videoFile: String? = null,
    val coverFile: String? = null,
    val audioFile: String? = null,
    val imageFiles: List<String> = emptyList(),
    val caption: String = "",
    /** Link in the creator's profile bio, if any. */
    val authorLink: String? = null,
    val authorBio: String? = null,
    /** Null when comments weren't fetched (keeps whatever the note has); empty when there are none. */
    val creatorComments: List<CreatorComment>? = null,
)

fun wikiLink(fileName: String) = "[[$fileName]]"

fun unwrapWikiLink(value: String?): String? =
    value?.trim()?.removePrefix("!")?.removePrefix("[[")?.removeSuffix("]]")?.substringBefore('|')?.trim()?.ifEmpty { null }

/**
 * A note: frontmatter plus a markdown body made of
 * `[media embeds] [caption, verbatim] [## Notes section] [anything else the user added]`.
 */
class NoteDocument private constructor(val frontmatter: Frontmatter) {

    private val newline: String get() = if (frontmatter.body.contains("\r\n")) "\r\n" else "\n"

    val tiktokId: String? get() = frontmatter.getString(NoteKeys.TIKTOK_ID)?.trim()?.ifEmpty { null }

    private fun bodyLines(): MutableList<String> = frontmatter.body.split("\n").map { it.removeSuffix("\r") }.toMutableList()

    private fun setBodyLines(lines: List<String>) {
        frontmatter.body = lines.joinToString(newline)
    }

    /** Index of the `## Notes` heading. The last one wins in case a caption contains the text. */
    private fun notesHeadingIndex(lines: List<String>) =
        lines.indexOfLast { it.trim().matches(Regex("""##\s+Notes\s*""")) }

    private fun sectionEnd(lines: List<String>, start: Int): Int {
        for (i in start + 1 until lines.size) if (lines[i].matches(Regex("""#{1,2}\s.*"""))) return i
        return lines.size
    }

    fun notes(): String {
        val lines = bodyLines()
        val h = notesHeadingIndex(lines)
        if (h < 0) return ""
        return lines.subList(h + 1, sectionEnd(lines, h)).joinToString("\n").trim('\n', ' ', '\r')
    }

    /** Replaces only the content of `## Notes`, adding the section at the end if it is missing. */
    fun setNotes(text: String) {
        val lines = bodyLines()
        val content = text.replace("\r\n", "\n").trim('\n').let { if (it.isBlank()) emptyList() else it.split("\n") }
        val h = notesHeadingIndex(lines)
        if (h < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += ""
            lines += NoteKeys.NOTES_HEADING
            lines += ""
            lines += content
            lines += ""
        } else {
            val end = sectionEnd(lines, h)
            val replacement = (listOf("") + content + "").toMutableList()
            if (content.isEmpty()) replacement.removeAt(replacement.lastIndex)
            lines.subList(h + 1, end).clear()
            lines.addAll(h + 1, replacement)
        }
        setBodyLines(lines)
    }

    /** Range of the leading block of embeds (`![[…]]`) and blank lines the app writes above the caption. */
    private fun leadingEmbedRange(lines: List<String>): IntRange {
        var i = 0
        var lastEmbed = -1
        while (i < lines.size && (lines[i].isBlank() || isEmbed(lines[i]))) {
            if (isEmbed(lines[i])) lastEmbed = i
            i++
        }
        return 0..lastEmbed
    }

    private fun isEmbed(line: String) = line.trim().matches(Regex("""!\[\[[^\]]+]]"""))

    /** Replaces the leading embed block (written by the app) with [embeds], leaving the caption alone. */
    fun setEmbeds(embeds: List<String>) {
        val lines = bodyLines()
        val range = leadingEmbedRange(lines)
        if (!range.isEmpty()) lines.subList(range.first, range.last + 1).clear()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        val block = mutableListOf("")
        embeds.forEach { block += "![[$it]]" }
        if (embeds.isNotEmpty()) block += ""
        lines.addAll(0, block)
        setBodyLines(lines)
    }

    /** The caption text for search and display: body minus embeds and the notes section. */
    fun caption(): String {
        var lines: List<String> = bodyLines()
        for (heading in listOf(NoteKeys.NOTES_HEADING, NoteKeys.CREATOR_COMMENTS_HEADING)) {
            val h = headingIndex(lines, heading)
            if (h >= 0) lines = lines.subList(0, h) + lines.subList(sectionEnd(lines, h), lines.size)
        }
        val withoutNotes = lines
        val range = leadingEmbedRange(withoutNotes)
        val rest = if (range.isEmpty()) withoutNotes else withoutNotes.subList(range.last + 1, withoutNotes.size)
        return rest.joinToString("\n").trim()
    }

    /** Inserts [caption] above the notes section. Only used when the note has no caption yet. */
    fun fillCaption(caption: String) {
        val lines = bodyLines()
        val h = notesHeadingIndex(lines).let { if (it < 0) lines.size else it }
        val insert = listOf("") + caption.replace("\r\n", "\n").trim().split("\n") + ""
        var at = h
        while (at > 0 && lines[at - 1].isBlank()) { lines.removeAt(at - 1); at-- }
        lines.addAll(at, insert)
        setBodyLines(lines)
    }

    private fun headingIndex(lines: List<String>, heading: String): Int {
        val title = heading.removePrefix("## ")
        return lines.indexOfLast { it.trim().matches(Regex("""##\s+""" + Regex.escape(title) + """\s*""")) }
    }

    /** Markdown inside `## Creator comments`, or "" when the note has none. */
    fun creatorComments(): String {
        val lines = bodyLines()
        val h = headingIndex(lines, NoteKeys.CREATOR_COMMENTS_HEADING)
        if (h < 0) return ""
        return lines.subList(h + 1, sectionEnd(lines, h)).joinToString("\n").trim('\n', ' ', '\r')
    }

    /**
     * Replaces the content of `## Creator comments` (an app-managed section), creating it just above
     * `## Notes` when missing. Never touches the caption or the user's notes.
     */
    fun setCreatorComments(markdown: String) {
        val lines = bodyLines()
        val content = listOf("") + markdown.replace("\r\n", "\n").trim('\n').split("\n") + ""
        val h = headingIndex(lines, NoteKeys.CREATOR_COMMENTS_HEADING)
        if (h >= 0) {
            val end = sectionEnd(lines, h)
            lines.subList(h + 1, end).clear()
            lines.addAll(h + 1, content)
        } else {
            var at = notesHeadingIndex(lines).let { if (it < 0) lines.size else it }
            while (at > 0 && lines[at - 1].isBlank()) { lines.removeAt(at - 1); at-- }
            lines.addAll(at, listOf("", NoteKeys.CREATOR_COMMENTS_HEADING) + content)
        }
        setBodyLines(lines)
    }

    fun render(): String = frontmatter.render()

    companion object {
        fun parse(text: String) = NoteDocument(Frontmatter.parse(text))
    }
}
