package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RemoveVideoTest {
    private val meta = VideoMeta(
        tiktokId = "7300000000000000001",
        sourceUrl = "https://www.tiktok.com/@me/video/7300000000000000001",
        author = "me",
        authorHandle = "me",
        posted = LocalDate.of(2024, 1, 2),
        saved = LocalDate.of(2024, 1, 3),
        collections = listOf("Car"),
        status = NoteStatus.FULL,
        type = PostType.VIDEO,
        durationSeconds = 12,
        videoFile = "7300000000000000001.mp4",
        coverFile = "7300000000000000001.jpg",
        caption = "my caption",
    )

    @Test fun removingTheVideoMakesThePostPartialAndKeepsTheRest() {
        val note = NoteWriter.newNote(meta).replace("## Notes\n", "## Notes\n\nmy own words\n")
        val (text, video) = NoteWriter.removeVideo(note)
        assertEquals("7300000000000000001.mp4", video)
        assertTrue(text.contains("status: text-only"))
        assertFalse(text.contains("7300000000000000001.mp4"))
        assertFalse(text.contains("duration"))
        assertTrue(text.contains("![[7300000000000000001.jpg]]"))
        assertTrue(text.contains("my caption"))
        assertTrue(text.contains("my own words"))
        assertEquals(listOf("Car"), Frontmatter.parse(text).getList(NoteKeys.COLLECTIONS))
    }
}
