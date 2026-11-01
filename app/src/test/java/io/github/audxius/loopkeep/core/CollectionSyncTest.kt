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
}
