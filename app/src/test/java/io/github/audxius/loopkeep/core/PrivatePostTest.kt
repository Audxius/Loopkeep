package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrivatePostTest {

    @Test fun postedDateFromId() {
        // Real IDs with the dates TikTok reports for them.
        assertEquals(LocalDate.of(2019, 7, 27), TikTokUrls.postedFromId("6718335390845095173"))
        assertEquals(LocalDate.of(2026, 10, 25), TikTokUrls.postedFromId("7700680369246358815"))
        assertEquals(LocalDate.of(2026, 2, 14), TikTokUrls.postedFromId("7606831942381718797"))
    }

    @Test fun nonsenseIdsGiveNoDate() {
        assertNull(TikTokUrls.postedFromId("123"))
        assertNull(TikTokUrls.postedFromId("not a number"))
        assertNull(TikTokUrls.postedFromId("18446744073709551615")) // far future
    }

    @Test fun privateStatusCodes() {
        fun page(code: Int) = PageInfo(code, null, null, null, null, null, null, null, emptyList(), emptyList())
        assertTrue(page(10216).isPrivate)
        assertTrue(page(10222).isPrivate)
        assertFalse(page(10204).isPrivate)
        assertFalse(page(0).isPrivate)
    }

    @Test fun attachingAFileUpgradesAPrivateNoteAndKeepsTheDate() {
        val partial = NoteWriter.newNote(
            VideoMeta(
                tiktokId = "7606831942381718797", sourceUrl = "https://www.tiktok.com/@me/video/7606831942381718797",
                authorHandle = "me", posted = TikTokUrls.postedFromId("7606831942381718797"),
                saved = LocalDate.of(2026, 10, 31), status = NoteStatus.TEXT_ONLY,
            ),
        )
        val doc0 = NoteDocument.parse(partial)
        doc0.fillCaption("My caption, typed by hand")
        doc0.setNotes("funny sound")
        val full = NoteDocument.parse(
            NoteWriter.merge(
                doc0.render(),
                VideoMeta(
                    tiktokId = "7606831942381718797", sourceUrl = "", saved = LocalDate.of(2026, 11, 1),
                    status = NoteStatus.FULL, videoFile = "7606831942381718797.mp4", coverFile = "7606831942381718797.jpg", durationSeconds = 12,
                ),
            ),
        )
        assertEquals("full", full.frontmatter.getString(NoteKeys.STATUS))
        assertEquals("2026-02-14", full.frontmatter.getString(NoteKeys.POSTED))
        assertEquals("2026-10-31", full.frontmatter.getString(NoteKeys.SAVED))
        assertEquals("[[7606831942381718797.mp4]]", full.frontmatter.getString(NoteKeys.VIDEO))
        assertEquals("My caption, typed by hand", full.caption())
        assertEquals("funny sound", full.notes())
        assertTrue(full.render().contains("\n![[7606831942381718797.mp4]]\n"))
    }
}
