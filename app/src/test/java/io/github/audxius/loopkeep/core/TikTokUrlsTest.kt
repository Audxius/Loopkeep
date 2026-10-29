package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokUrlsTest {

    @Test fun extractsIdFromCommonShapes() {
        val id = "7345678901234567890"
        listOf(
            "https://www.tiktok.com/@some.user/video/$id",
            "https://www.tiktok.com/@some.user/video/$id?is_from_webapp=1&sender_device=pc",
            "https://m.tiktok.com/v/$id.html",
            "https://www.tiktokv.com/share/video/$id/",
            "https://www.tiktok.com/@u/photo/$id",
            "https://www.tiktok.com/embed/v2/$id",
            "https://www.tiktok.com/share?item_id=$id",
        ).forEach { assertEquals(it, id, TikTokUrls.extractVideoId(it)) }
    }

    @Test fun noIdForProfilesAndShortLinks() {
        assertNull(TikTokUrls.extractVideoId("https://www.tiktok.com/@some.user"))
        assertNull(TikTokUrls.extractVideoId("https://vm.tiktok.com/ZMabc123/"))
        assertNull(TikTokUrls.extractVideoId("https://www.tiktok.com/music/song-123"))
    }

    @Test fun detectsShortLinks() {
        assertTrue(TikTokUrls.isShortLink("https://vm.tiktok.com/ZMabc123/"))
        assertTrue(TikTokUrls.isShortLink("https://vt.tiktok.com/ZSabc/"))
        assertTrue(TikTokUrls.isShortLink("https://www.tiktok.com/t/ZTabc/"))
        assertFalse(TikTokUrls.isShortLink("https://www.tiktok.com/@a/video/7345678901234567890"))
    }

    @Test fun findsUrlsInSharedText() {
        val text = "Check this out! https://vm.tiktok.com/ZMabc123/ (via app), and https://www.tiktok.com/@x/video/7345678901234567890."
        assertEquals(
            listOf("https://vm.tiktok.com/ZMabc123/", "https://www.tiktok.com/@x/video/7345678901234567890"),
            TikTokUrls.findUrls(text),
        )
    }

    @Test fun ignoresNonTikTokLinks() {
        assertTrue(TikTokUrls.findUrls("https://example.com/tiktok.com/video/1 https://nottiktok.com/x").isEmpty())
    }

    @Test fun canonicalUrl() {
        assertEquals("https://www.tiktok.com/@bob/video/1", TikTokUrls.canonicalUrl("1", "bob"))
        assertEquals("https://www.tiktok.com/@/video/1", TikTokUrls.canonicalUrl("1"))
        assertEquals("https://www.tiktok.com/@bob/photo/1", TikTokUrls.canonicalUrl("1", "bob", photo = true))
    }

    @Test fun extractsHandle() {
        assertEquals("some.user", TikTokUrls.extractHandle("https://www.tiktok.com/@some.user/video/1"))
        assertNull(TikTokUrls.extractHandle("https://www.tiktok.com/@/video/1"))
    }
}
