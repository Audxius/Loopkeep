package io.github.audxius.loopkeep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    @Test fun myVideosMatchHandleOrLocalIds() {
        val q = LibraryFilter(mine = true, ownHandle = "me").toQuery()
        assertTrue(q.sql.contains("(videos.tiktokId LIKE 'own-%' OR videos.authorHandle = ?)"))
        assertEquals(1, q.argCount)
    }

    @Test fun uncategorizedLeavesOutOwnVideos() {
        val q = LibraryFilter(uncategorized = true, ownHandle = "me").toQuery()
        assertTrue(q.sql.contains("NOT (videos.tiktokId LIKE 'own-%' OR videos.authorHandle = ?)"))
        assertTrue(q.sql.contains("REPLACE(IFNULL(videos.collections, ''), char(10), '') = ''"))
    }

    @Test fun withoutHandleOnlyLocalIdsAreMine() {
        val q = LibraryFilter(mine = true).toQuery()
        assertTrue(q.sql.contains("videos.tiktokId LIKE 'own-%'"))
        assertEquals(0, q.argCount)
    }
}
