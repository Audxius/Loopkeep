package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSyncTest {

    @Test fun parsesCollections() {
        val json = """[{"name":" Car ","ids":["7300000000000000701","7300000000000000702","7300000000000000701"]},{"name":"Home","ids":[]}]"""
        assertEquals(
            listOf(SyncedCollection("Car", listOf("7300000000000000701", "7300000000000000702")), SyncedCollection("Home", emptyList())),
            CollectionSync.parse(json),
        )
    }

    @Test fun dropsJunk() {
        assertEquals(listOf(SyncedCollection("A", listOf("7300000000000000701"))), CollectionSync.parse("""[{"name":"","ids":["1"]},{"name":"A","ids":["x","7300000000000000701",5]},3]"""))
        assertTrue(CollectionSync.parse("not json").isEmpty())
        assertTrue(CollectionSync.parse("""{"name":"A"}""").isEmpty())
    }

    @Test fun parsesOwnPostsAndHandle() {
        val json = """{"handle":"@audxius","collections":[{"name":"Car","ids":["7300000000000000701"]}],
            "own":[{"id":"7400000000000000001","caption":" hi ","createTime":1717000000,"videoUrl":"https://v16-webapp.tiktok.com/x.mp4","private":true},
                   {"id":"7400000000000000002","caption":"","createTime":1717000001,"videoUrl":"","private":false},
                   {"id":"7400000000000000001","caption":"dupe"},{"id":"nope"}],
            "ownError":""}"""
        val r = CollectionSync.parseResult(json)
        assertEquals("audxius", r.handle)
        assertEquals(listOf(SyncedCollection("Car", listOf("7300000000000000701"))), r.collections)
        assertEquals(
            listOf(
                SyncedOwnPost("7400000000000000001", "hi", 1717000000, "https://v16-webapp.tiktok.com/x.mp4", true),
                SyncedOwnPost("7400000000000000002", "", 1717000001, null, false),
            ),
            r.own,
        )
        assertEquals(null, r.ownError)
    }

    @Test fun keepsOwnErrorAndRejectsOddHandles() {
        val r = CollectionSync.parseResult("""{"handle":"<script>","collections":[],"own":[],"ownError":"TikTok refused /api/post/item_list/"}""")
        assertEquals(null, r.handle)
        assertEquals("TikTok refused /api/post/item_list/", r.ownError)
    }
}
