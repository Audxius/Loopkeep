package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The fixtures are synthetic: they mimic the publicly documented shapes of TikTok's export
 * but have not been checked against a real export (see TESTING.md).
 */
class ExportParserTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("export/$name")!!.readBytes()

    @Test fun jsonExportSeparatesSavedAndLiked() {
        val scan = ExportParser.parse(resource("user_data.json"), "user_data.json")
        assertEquals(3, scan.count(ExportSource.SAVED))
        assertEquals(2, scan.count(ExportSource.LIKED))
        val first = scan.entries.first { it.videoId == "7300000000000000001" }
        assertEquals(ExportSource.SAVED, first.source)
        assertEquals(LocalDate.of(2024, 3, 14), first.date)
        // Browsing history and the user's own posts are not imported as saves.
        assertTrue(scan.entries.none { it.videoId == "7300000000000000099" && it.source != ExportSource.OTHER })
    }

    @Test fun savedBeatsLikedWhenTheSameVideoAppearsInBoth() {
        val scan = ExportParser.parse(resource("user_data.json"))
        val dup = scan.entries.filter { it.videoId == "7300000000000000002" }
        assertEquals(1, dup.size)
        assertEquals(ExportSource.SAVED, dup.single().source)
    }

    @Test fun txtExportUsesFileNameAndDateLines() {
        val scan = ExportParser.parse(resource("Favorite Videos.txt"), "Favorite Videos.txt")
        assertEquals(2, scan.entries.size)
        assertTrue(scan.entries.all { it.source == ExportSource.SAVED })
        assertEquals(LocalDate.of(2023, 11, 2), scan.entries[0].date)
        assertEquals(LocalDate.of(2023, 10, 30), scan.entries[1].date)
    }

    @Test fun htmlExportUsesHeadings() {
        val scan = ExportParser.parse(resource("export.html"), "export.html")
        assertEquals(listOf("7300000000000000011"), scan.entries.filter { it.source == ExportSource.SAVED }.map { it.videoId })
        assertEquals(listOf("7300000000000000012"), scan.entries.filter { it.source == ExportSource.LIKED }.map { it.videoId })
    }

    @Test fun zipOfTxtFiles() {
        val bytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zip ->
                zip.putNextEntry(ZipEntry("TikTok_Data/Activity/Favorite Videos.txt"))
                zip.write(resource("Favorite Videos.txt"))
                zip.putNextEntry(ZipEntry("TikTok_Data/Activity/Like List.txt"))
                zip.write("Date: 2023-01-01 10:00:00\nLink: https://www.tiktokv.com/share/video/7300000000000000050/\n".toByteArray())
                zip.putNextEntry(ZipEntry("TikTok_Data/Activity/Browsing History.txt"))
                zip.write("Date: 2023-01-01 10:00:00\nLink: https://www.tiktokv.com/share/video/7300000000000000051/\n".toByteArray())
            }
        }.toByteArray()
        val scan = ExportParser.parse(bytes, "export.zip")
        assertEquals(2, scan.count(ExportSource.SAVED))
        assertEquals(1, scan.count(ExportSource.LIKED))
        assertEquals(1, scan.count(ExportSource.OTHER))
    }

    @Test fun shortLinksAreKeptWithoutId() {
        val scan = ExportParser.parse("""{"Favorite Videos":{"List":[{"Date":"2024-01-01","Link":"https://vm.tiktok.com/ZMabc/"}]}}""".toByteArray())
        assertEquals(1, scan.entries.size)
        assertEquals(null, scan.entries[0].videoId)
        assertEquals(ExportSource.SAVED, scan.entries[0].source)
    }

    @Test fun epochDatesAreUnderstood() {
        val scan = ExportParser.parse("""{"Collections":[{"created":1710374400,"url":"https://www.tiktok.com/@a/video/7300000000000000060"}]}""".toByteArray())
        assertEquals(LocalDate.of(2024, 3, 14), scan.entries.single().date)
    }

    @Test fun garbageInputYieldsNothingAndDoesNotThrow() {
        listOf(
            ByteArray(0),
            byteArrayOf(0, 1, 2, 3, -1, -2, 80, 75, 3, 4, 9, 9),
            "{not json at all".toByteArray(),
            "[1, 2, null, {\"a\": [[[[]]]]}]".toByteArray(),
            "<html><body><p>no links</p></body></html>".toByteArray(),
            "https://www.tiktok.com/@someone".toByteArray(),
            ByteArray(10_000) { (it % 251).toByte() },
        ).forEach { bytes ->
            assertTrue(ExportParser.parse(bytes).entries.isEmpty())
        }
    }

    @Test fun malformedJsonFallsBackToLineScan() {
        val text = "{ \"Favorite Videos\": broken, Date: 2024-02-02 Link: https://www.tiktokv.com/share/video/7300000000000000070/ "
        val scan = ExportParser.parse(text.toByteArray(), "Favorite Videos.json")
        assertEquals("7300000000000000070", scan.entries.single().videoId)
        assertEquals(LocalDate.of(2024, 2, 2), scan.entries.single().date)
    }

    @Test fun dedupeKeepsEarliestDateWithinSameSource() {
        val a = ExportEntry("u1", "1", LocalDate.of(2024, 5, 1), ExportSource.SAVED)
        val b = ExportEntry("u2", "1", LocalDate.of(2024, 4, 1), ExportSource.SAVED)
        val c = ExportEntry("u3", "2", null, ExportSource.LIKED)
        val d = ExportEntry("u3/", null, null, ExportSource.LIKED)
        val e = ExportEntry("u3", null, null, ExportSource.LIKED)
        val out = ExportParser.dedupe(listOf(a, b, c, d, e))
        assertEquals(3, out.size)
        assertEquals(LocalDate.of(2024, 4, 1), out.first { it.videoId == "1" }.date)
    }

    @Test fun classification() {
        assertEquals(ExportSource.LIKED, ExportParser.classify(listOf("Activity", "Like List", "ItemFavoriteList")))
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("Activity", "Favorite Videos", "FavoriteVideoList")))
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("Your Activity", "Favourite Videos")))
        assertEquals(ExportSource.OTHER, ExportParser.classify(listOf("Activity", "Video Browsing History")))
        assertEquals(ExportSource.OTHER, ExportParser.classify(emptyList()))
    }
}
