package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Own posts in the export. The older shape (Video → Videos → VideoList with Date + VideoLink) is
 * what open-source export tools read; the newer shape (Post → Posts → VideoList with Link) is a
 * guess. Both synthetic, unverified against a real export.
 */
class OwnPostsExportTest {

    private val older = """
        {"Profile":{"Profile Information":{"ProfileMap":{"userName":"audxius","bioDescription":"hi"}}},
         "Activity":{"Following List":{"Following":[{"Date":"2023-01-01 10:00:00","UserName":"someone.else"}]},
                     "Favorite Videos":{"FavoriteVideoList":[{"Date":"2024-03-14 18:22:05","Link":"https://www.tiktokv.com/share/video/7300000000000000001/"}]}},
         "Video":{"Videos":{"VideoList":[
            {"Date":"2023-05-04 18:22:10","VideoLink":"https://v16m-default.tiktokcdn.com/abc/video/tos/useast2a/x.mp4?x-expires=1&sig=2","Likes":"3"},
            {"Date":"2022-12-24 09:05:00","VideoLink":"https://v77.tiktokcdn.com/def/video/tos/maliva/y/?a=1988","Likes":"0"}
         ]}}}
    """.trimIndent()

    @Test fun olderExportOwnVideos() {
        val scan = ExportParser.parse(older.toByteArray(), "user_data.json")
        assertEquals("audxius", scan.ownerHandle)
        val own = scan.entries.filter { it.source == ExportSource.OWN }
        assertEquals(2, own.size)
        val first = own.first { it.timestamp == "20230504182210" }
        assertNull(first.videoId)
        assertTrue(first.mediaUrl!!.startsWith("https://v16m-default.tiktokcdn.com/"))
        assertEquals(LocalDate.of(2023, 5, 4), first.date)
        assertEquals("20221224090500", own.first { it.date == LocalDate.of(2022, 12, 24) }.timestamp)
        assertEquals(1, scan.count(ExportSource.SAVED))
    }

    @Test fun newerExportPostsWithLinksAndCaptions() {
        val json = """{"Post":{"Posts":{"VideoList":[
            {"Date":"2024-02-01 12:00:00","Link":"https://www.tiktokv.com/share/video/7300000000000000500/","Title":"my private one","WhoCanView":"Only me",
             "VideoLink":"https://v19.tiktokcdn-us.com/zz/video/tos/useast5/z.mp4"}]}}}"""
        val e = ExportParser.parse(json.toByteArray()).entries.single()
        assertEquals(ExportSource.OWN, e.source)
        assertEquals("7300000000000000500", e.videoId)
        assertEquals("my private one", e.caption)
        assertEquals("https://v19.tiktokcdn-us.com/zz/video/tos/useast5/z.mp4", e.mediaUrl)
    }

    @Test fun zipOfTxtOwnVideos() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zip ->
                zip.putNextEntry(ZipEntry("TikTok_Data/Video/Videos.txt"))
                zip.write("Date: 2023-05-04 18:22:10\nVideo Link: https://v16m-default.tiktokcdn.com/abc/video/tos/x.mp4?e=1\nLikes: 3\n".toByteArray())
                zip.putNextEntry(ZipEntry("TikTok_Data/Profile/Profile Info.txt"))
                zip.write("Username: audxius\nBirthdate: 2000-01-01\n".toByteArray())
                zip.putNextEntry(ZipEntry("TikTok_Data/Activity/Favorite Videos.txt"))
                zip.write("Date: 2024-01-01 10:00:00\nLink: https://www.tiktokv.com/share/video/7300000000000000600/\n".toByteArray())
            }
        }.toByteArray()
        val scan = ExportParser.parse(bytes, "export.zip")
        assertEquals("audxius", scan.ownerHandle)
        val own = scan.entries.single { it.source == ExportSource.OWN }
        assertEquals("20230504182210", own.timestamp)
        assertTrue(own.mediaUrl!!.contains("tiktokcdn.com"))
        assertEquals(1, scan.count(ExportSource.SAVED))
    }

    @Test fun watchHistoryIsNotOwn() {
        assertEquals(ExportSource.OTHER, ExportParser.classify(listOf("Activity", "Video Browsing History", "VideoList")))
        assertEquals(ExportSource.OWN, ExportParser.classify(listOf("Video", "Videos", "VideoList")))
        assertEquals(ExportSource.OWN, ExportParser.classify(listOf("Post", "Posts", "VideoList")))
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("Activity", "Favorite Collections", "CollectionList", "Videos")))
    }
}
