package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Fixtures are synthetic approximations of yt-dlp and oEmbed output; see TESTING.md. */
class RemoteMetaTest {

    private val info = """
        {"id": "7300000000000000001", "title": "Best pasta", "description": "Best pasta ever #recipe",
         "uploader": "chef.anna", "uploader_id": "6812345678901234567", "channel": "Chef Anna",
         "timestamp": 1709251200, "upload_date": "20240301", "duration": 33.6, "ext": "mp4",
         "webpage_url": "https://www.tiktok.com/@chef.anna/video/7300000000000000001",
         "thumbnail": "https://p16.example/fallback.jpeg",
         "thumbnails": [
           {"id": "dynamicCover", "url": "https://p16.example/dynamic.webp", "preference": -2},
           {"id": "cover", "url": "https://p16.example/cover.jpeg", "preference": -1},
           {"id": "originCover", "url": "https://p16.example/origin.jpeg", "preference": -1}
         ],
         "formats": [{"format_id": "a", "vcodec": "h264"}, {"format_id": "b", "vcodec": "bytevc1"}],
         "unknown": {"nested": [1,2,3]}}
    """.trimIndent()

    @Test fun parsesYtDlpInfo() {
        val i = YtDlpInfoParser.parse(info)!!
        assertEquals("7300000000000000001", i.id)
        assertEquals("Best pasta ever #recipe", i.caption)
        assertEquals("Chef Anna", i.author)
        assertEquals("chef.anna", i.authorHandle)
        assertEquals(LocalDate.of(2024, 3, 1), i.posted)
        assertEquals(34, i.durationSeconds)
        assertEquals(
            listOf("https://p16.example/cover.jpeg", "https://p16.example/origin.jpeg", "https://p16.example/dynamic.webp", "https://p16.example/fallback.jpeg"),
            i.thumbnails,
        )
        assertTrue(i.hasVideo)
    }

    @Test fun audioOnlyFormatsMeanNoVideo() {
        val i = YtDlpInfoParser.parse("""{"id":"1","formats":[{"vcodec":"none","acodec":"aac"}],"ext":"m4a"}""")!!
        assertFalse(i.hasVideo)
    }

    @Test fun minimalAndBrokenInfo() {
        val i = YtDlpInfoParser.parse("""{"id": 7300000000000000001, "uploader_id": "123", "webpage_url": "https://www.tiktok.com/@x.y/video/1"}""")!!
        assertEquals("7300000000000000001", i.id)
        assertEquals("x.y", i.authorHandle)
        assertNull(i.posted)
        assertNull(YtDlpInfoParser.parse("not json"))
        assertNull(YtDlpInfoParser.parse("[]"))
    }

    @Test fun parsesOEmbed() {
        val json = """{"version":"1.0","type":"video","title":"Best pasta #recipe","author_url":"https://www.tiktok.com/@chef.anna",
            "author_name":"Chef Anna","thumbnail_url":"https://p16.example/t.jpeg","embed_product_id":"7300000000000000001",
            "html":"<blockquote data-video-id=\"7300000000000000001\"></blockquote>"}"""
        val o = OEmbedParser.parse(json)!!
        assertEquals("Best pasta #recipe", o.caption)
        assertEquals("Chef Anna", o.author)
        assertEquals("chef.anna", o.authorHandle)
        assertEquals("https://p16.example/t.jpeg", o.thumbnailUrl)
        assertEquals("7300000000000000001", o.videoId)
        assertNull(OEmbedParser.parse("<html>error</html>"))
    }

    @Test fun slideshowImagesFromPageState() {
        val html = """<html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{"id":"1","imagePost":{"images":[
              {"imageURL":{"urlList":["https://img.example/1.jpeg","https://img.example/1b.jpeg"]}},
              {"imageURL":{"urlList":["https://img.example/2.jpeg"]}}]}}}}}}</script></html>"""
        assertEquals(listOf("https://img.example/1.jpeg", "https://img.example/2.jpeg"), SlideshowParser.imageUrls(html))
        assertTrue(SlideshowParser.imageUrls("<html>nothing</html>").isEmpty())
    }
}
