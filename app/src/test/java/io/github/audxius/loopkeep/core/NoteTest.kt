package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NoteTest {

    private val meta = VideoMeta(
        tiktokId = "7300000000000000001",
        sourceUrl = "https://www.tiktok.com/@chef/video/7300000000000000001",
        author = "Chef Anna",
        authorHandle = "chef",
        posted = LocalDate.of(2025, 3, 1),
        saved = LocalDate.of(2025, 3, 14),
        tags = listOf("recipe", "pasta"),
        status = NoteStatus.FULL,
        durationSeconds = 34,
        videoFile = "7300000000000000001.mp4",
        coverFile = "7300000000000000001.jpg",
        caption = "Best pasta ever 🍝\nSecond line #recipe #pasta",
    )

    @Test fun newNoteMatchesTheDocumentedFormat() {
        val expected = """
            ---
            tiktok_id: "7300000000000000001"
            source_url: https://www.tiktok.com/@chef/video/7300000000000000001
            author: Chef Anna
            author_handle: chef
            posted: 2025-03-01
            saved: 2025-03-14
            tags: [recipe, pasta]
            status: full
            duration: 34
            video: "[[7300000000000000001.mp4]]"
            cover: "[[7300000000000000001.jpg]]"
            ---

            ![[7300000000000000001.mp4]]

            Best pasta ever 🍝
            Second line #recipe #pasta

            ## Notes

            """.trimIndent() + "\n"
        assertEquals(expected, NoteWriter.newNote(meta))
    }

    @Test fun parsingANewNoteGivesBackTheParts() {
        val doc = NoteDocument.parse(NoteWriter.newNote(meta))
        assertEquals("7300000000000000001", doc.tiktokId)
        assertEquals(meta.caption, doc.caption())
        assertEquals("", doc.notes())
    }

    @Test fun setNotesOnlyTouchesTheNotesSection() {
        val original = NoteWriter.newNote(meta) + "## My section\n\nkeep me\n"
        val doc = NoteDocument.parse(original)
        doc.setNotes("Made this on Sunday.\n\n- add more garlic")
        val out = doc.render()
        assertTrue(out.contains("## Notes\n\nMade this on Sunday.\n\n- add more garlic\n\n## My section\n\nkeep me\n"))
        assertEquals(meta.caption, NoteDocument.parse(out).caption().substringBefore("\n## My section").trim())
        assertEquals("Made this on Sunday.\n\n- add more garlic", NoteDocument.parse(out).notes())

        // Editing again is stable.
        val again = NoteDocument.parse(out).apply { setNotes("Made this on Sunday.\n\n- add more garlic") }.render()
        assertEquals(out, again)
    }

    @Test fun clearingNotesKeepsTheHeading() {
        val doc = NoteDocument.parse(NoteWriter.newNote(meta))
        doc.setNotes("something")
        doc.setNotes("")
        assertEquals("", doc.notes())
        assertTrue(doc.render().contains("## Notes"))
    }

    @Test fun setNotesAddsSectionWhenMissing() {
        val doc = NoteDocument.parse("---\ntiktok_id: \"1\"\n---\n\ncaption only\n")
        doc.setNotes("hello")
        assertEquals("---\ntiktok_id: \"1\"\n---\n\ncaption only\n\n## Notes\n\nhello\n", doc.render())
    }

    @Test fun obsidianEditsSurviveATagChange() {
        val edited = NoteWriter.newNote(meta)
            .replace("Best pasta ever", "Best pasta EVER (edited in Obsidian)")
            .replace("status: full\n", "status: full\nrating: 5\n")
            .replace("## Notes\n\n", "## Notes\n\nmy thoughts\n")
        val doc = NoteDocument.parse(edited)
        doc.frontmatter.setList(NoteKeys.TAGS, listOf("recipe", "pasta", "dinner"))
        val out = doc.render()
        assertEquals(edited.replace("tags: [recipe, pasta]", "tags: [recipe, pasta, dinner]"), out)
    }

    @Test fun mergeUpgradesTextOnlyWithoutTouchingUserContent() {
        val textOnly = NoteWriter.newNote(
            meta.copy(status = NoteStatus.TEXT_ONLY, videoFile = null, durationSeconds = null, tags = listOf("recipe")),
        )
        assertTrue(textOnly.contains("![[7300000000000000001.jpg]]"))
        val userEdited = NoteDocument.parse(textOnly).apply {
            setNotes("try this")
            frontmatter.set("rating", 4)
        }.render()

        val merged = NoteWriter.merge(userEdited, meta)
        val doc = NoteDocument.parse(merged)
        assertEquals("full", doc.frontmatter.getString(NoteKeys.STATUS))
        assertEquals("[[7300000000000000001.mp4]]", doc.frontmatter.getString(NoteKeys.VIDEO))
        assertEquals("4", doc.frontmatter.getString("rating"))
        assertEquals(listOf("recipe", "pasta"), doc.frontmatter.getList(NoteKeys.TAGS))
        assertEquals("try this", doc.notes())
        assertEquals(meta.caption, doc.caption())
        assertTrue(merged.contains("\n![[7300000000000000001.mp4]]\n"))
        assertTrue(!merged.contains("![[7300000000000000001.jpg]]"))
    }

    @Test fun mergeFillsMissingCaptionAboveNotes() {
        val bare = NoteWriter.newNote(meta.copy(status = NoteStatus.UNAVAILABLE, videoFile = null, coverFile = null, caption = ""))
        val withNotes = NoteDocument.parse(bare).apply { setNotes("n") }.render()
        val merged = NoteDocument.parse(NoteWriter.merge(withNotes, meta))
        assertEquals(meta.caption, merged.caption())
        assertEquals("n", merged.notes())
    }

    @Test fun mergeDoesNotOverwriteUserEditedAuthor() {
        val edited = NoteWriter.newNote(meta).replace("author: Chef Anna", "author: Anna (my aunt)")
        val merged = NoteDocument.parse(NoteWriter.merge(edited, meta.copy(author = "Someone Else")))
        assertEquals("Anna (my aunt)", merged.frontmatter.getString(NoteKeys.AUTHOR))
    }

    @Test fun slideshowNote() {
        val s = meta.copy(
            type = PostType.SLIDESHOW, videoFile = null, durationSeconds = null,
            imageFiles = listOf("1_1.jpg", "1_2.jpg"), audioFile = "1.m4a",
        )
        val doc = NoteDocument.parse(NoteWriter.newNote(s))
        assertEquals("slideshow", doc.frontmatter.getString(NoteKeys.TYPE))
        assertEquals(listOf("[[1_1.jpg]]", "[[1_2.jpg]]"), doc.frontmatter.getList(NoteKeys.IMAGES))
        assertTrue(doc.render().contains("![[1_1.jpg]]\n![[1_2.jpg]]\n![[1.m4a]]"))
        assertEquals(meta.caption, doc.caption())
    }

    @Test fun veryLongCaptionIsKeptVerbatim() {
        val long = (1..4000).joinToString(" ") { "word$it" } + " 🎉 ${"​"} \"quoted\" --- *md*"
        val doc = NoteDocument.parse(NoteWriter.newNote(meta.copy(caption = long)))
        assertEquals(long, doc.caption())
    }

    @Test fun captionStartingWithYamlFenceDoesNotBreakParsing() {
        val doc = NoteDocument.parse(NoteWriter.newNote(meta.copy(caption = "---\nnot: frontmatter\n---")))
        assertEquals("7300000000000000001", doc.tiktokId)
        assertEquals("---\nnot: frontmatter\n---", doc.caption())
    }

    @Test fun wikiLinks() {
        assertEquals("a.mp4", unwrapWikiLink("[[a.mp4]]"))
        assertEquals("a.mp4", unwrapWikiLink("![[a.mp4|300]]"))
        assertEquals(null, unwrapWikiLink(""))
    }
}
