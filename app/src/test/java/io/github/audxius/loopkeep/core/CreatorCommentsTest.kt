package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fixture shaped like TikTok's api/comment/list response as seen live on 2026-10-29 (values synthetic). */
class CreatorCommentsTest {

    private val response = """
        {"status_code":0,"total":11,"has_more":1,"cursor":50,"comments":[
          {"cid":"1","text":"i love bonobos","user":{"uid":"111","unique_id":"fan.one","nickname":"Fan"},"label_list":null,
           "reply_comment":[{"cid":"2","text":"Us too 😍","user":{"uid":"999","unique_id":"natgeo","nickname":"National Geographic"},
                             "label_list":[{"text":"Creator","type":1}]}]},
          {"cid":"3","text":"Where was this filmed?","user":{"uid":"222","unique_id":"curious"},
           "reply_comment":[{"cid":"4","text":"Also wondering","user":{"uid":"333","unique_id":"someone"},"label_list":null}]},
          {"cid":"5","text":"Full documentary link in my bio!","user":{"uid":"999","unique_id":"natgeo"},"label_list":null},
          {"cid":"6","text":"no labels, matched by handle","user":{"uid":"","unique_id":"NatGeo"}}
        ]}
    """.trimIndent()

    @Test fun picksCreatorCommentsAndReplies() {
        val page = CommentParser.parse(response, authorUid = "999", authorHandle = "natgeo")!!
        assertEquals(
            listOf(
                CreatorComment("Us too 😍", "fan.one", "i love bonobos"),
                CreatorComment("Full documentary link in my bio!"),
                CreatorComment("no labels, matched by handle"),
            ),
            page.creatorComments,
        )
        assertTrue(page.hasMore)
        assertEquals(50L, page.cursor)
    }

    @Test fun labelAloneIsEnough() {
        val page = CommentParser.parse(response, authorUid = null, authorHandle = null)!!
        assertEquals(listOf(CreatorComment("Us too 😍", "fan.one", "i love bonobos")), page.creatorComments)
    }

    @Test fun garbage() {
        assertNull(CommentParser.parse("<html>blocked</html>", "1", "x"))
        val empty = CommentParser.parse("""{"status_code":0,"comments":null,"has_more":0}""", "1", "x")!!
        assertTrue(empty.creatorComments.isEmpty())
        assertFalse(empty.hasMore)
    }

    @Test fun markdownRoundTrip() {
        val comments = listOf(
            CreatorComment("Us too 😍", "fan.one", "i love bonobos"),
            CreatorComment("Link in bio!\nSecond line"),
        )
        val md = CreatorCommentsMarkdown.render("natgeo", comments)
        assertEquals("> @fan.one: i love bonobos\n\n**@natgeo:** Us too 😍\n\n**@natgeo:** Link in bio! Second line", md)
        assertEquals(
            listOf(CreatorComment("Us too 😍", "fan.one", "i love bonobos"), CreatorComment("Link in bio! Second line")),
            CreatorCommentsMarkdown.parse(md),
        )
    }

    @Test fun noteSectionSitsAboveNotesAndLeavesCaptionAlone() {
        val meta = VideoMeta(
            tiktokId = "1", sourceUrl = "https://www.tiktok.com/@natgeo/video/1", authorHandle = "natgeo", author = "National Geographic",
            saved = LocalDate.of(2026, 10, 29), status = NoteStatus.FULL, videoFile = "1.mp4", caption = "Bonobos!",
            creatorComments = listOf(CreatorComment("Us too", "fan", "love them")),
        )
        val text = NoteWriter.newNote(meta)
        assertTrue(text.contains("author_url: https://www.tiktok.com/@natgeo\n"))
        assertTrue(text.contains("Bonobos!\n\n## Creator comments\n\n> @fan: love them\n\n**@natgeo:** Us too\n\n## Notes\n"))
        val doc = NoteDocument.parse(text)
        assertEquals("Bonobos!", doc.caption())
        assertEquals("> @fan: love them\n\n**@natgeo:** Us too", doc.creatorComments())

        // Notes stay put when comments are refreshed; caption untouched.
        doc.setNotes("mine")
        val merged = NoteDocument.parse(NoteWriter.merge(doc.render(), meta.copy(creatorComments = listOf(CreatorComment("Newer reply")))))
        assertEquals("**@natgeo:** Newer reply", merged.creatorComments())
        assertEquals("mine", merged.notes())
        assertEquals("Bonobos!", merged.caption())

        // An empty or missing result never wipes comments already saved.
        val kept = NoteDocument.parse(NoteWriter.merge(merged.render(), meta.copy(creatorComments = emptyList())))
        assertEquals("**@natgeo:** Newer reply", kept.creatorComments())
        val keptNull = NoteDocument.parse(NoteWriter.merge(merged.render(), meta.copy(creatorComments = null)))
        assertEquals("**@natgeo:** Newer reply", keptNull.creatorComments())
    }

    @Test fun profilePage() {
        val html = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">{"__DEFAULT_SCOPE__":{"webapp.user-detail":{"statusCode":0,
            "userInfo":{"user":{"id":"999","uniqueId":"natgeo","nickname":"National Geographic","signature":"Explore 🌎","bioLink":{"link":"spr.ly/natgeotiktok","risk":3}}}}}}</script>"""
        assertEquals(ProfileInfo("999", "natgeo", "National Geographic", "Explore 🌎", "https://spr.ly/natgeotiktok"), ProfileParser.parse(html))
        assertNull(ProfileParser.parse("<html></html>"))
    }

    @Test fun autoTagDetection() {
        assertTrue(TagSuggester.allFromHashtags(listOf("recipe", "pasta"), "Best pasta #Recipe #pasta #fyp"))
        assertFalse(TagSuggester.allFromHashtags(listOf("recipe", "dinner"), "Best pasta #Recipe #pasta"))
        assertFalse(TagSuggester.allFromHashtags(emptyList(), "#a"))
    }
}
