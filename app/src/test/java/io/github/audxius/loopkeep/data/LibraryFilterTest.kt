package io.github.audxius.loopkeep.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {

    @Test fun myVideosMatchHandleOrLocalIds() {
        val q = LibraryFilter(mine = true, ownHandle = "me").toQuery()
        assertTrue(q.sql.contains("(videos.tiktokId LIKE 'own-%' OR videos.authorHandle = ? COLLATE NOCASE)"))
        assertEquals(1, q.argCount)
    }

    @Test fun uncategorizedLeavesOutOwnVideos() {
        val q = LibraryFilter(uncategorized = true, ownHandle = "me").toQuery()
        assertTrue(q.sql.contains("NOT (videos.tiktokId LIKE 'own-%' OR videos.authorHandle = ? COLLATE NOCASE)"))
        assertTrue(q.sql.contains("REPLACE(IFNULL(videos.collections, ''), char(10), '') = ''"))
    }

    @Test fun postsListedOnTheProfileAreMine() {
        val q = LibraryFilter(mine = true, ownIds = setOf("7300000000000000001", "x' OR 1=1")).toQuery()
        assertTrue(q.sql.contains("videos.tiktokId IN ('7300000000000000001')"))
        assertTrue(!q.sql.contains("1=1"))
    }

    @Test fun withoutHandleOnlyLocalIdsAreMine() {
        val q = LibraryFilter(mine = true).toQuery()
        assertTrue(q.sql.contains("videos.tiktokId LIKE 'own-%'"))
        assertEquals(0, q.argCount)
    }
}
