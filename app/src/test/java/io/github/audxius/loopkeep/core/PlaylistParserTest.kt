package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParserTest {
    @Test fun readsEntries() {
        val json = """{"_type":"playlist","id":"1","title":"me-recipes","entries":[
            {"_type":"url","url":"https://www.tiktok.com/@a/video/7300000000000000001","id":"7300000000000000001"},
            {"_type":"url","id":"7300000000000000002"},
            {"_type":"url","url":"https://www.tiktok.com/@a/video/7300000000000000001"},
            {"_type":"url","url":"https://example.com/x"}]}"""
        assertEquals(
            listOf("https://www.tiktok.com/@a/video/7300000000000000001", "https://www.tiktok.com/@/video/7300000000000000002"),
            PlaylistParser.videoUrls(json),
        )
    }

    @Test fun emptyOrBroken() {
        assertTrue(PlaylistParser.videoUrls("""{"_type":"playlist","entries":[]}""").isEmpty())
        assertTrue(PlaylistParser.videoUrls("garbage").isEmpty())
    }
}
