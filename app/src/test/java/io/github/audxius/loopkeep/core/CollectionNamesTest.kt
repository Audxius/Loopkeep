package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TikTok's 2026 export lists collection names but not their videos (structure from a real export, 2026-11). */
class CollectionNamesTest {

    private val json = """
        {"Likes and Favorites":{
           "Collection":{},
           "Favorite Collection":{"FavoriteCollectionList":[
              {"Date":"2026-10-29 17:45:42","FavoriteCollection":"Minecraft"},
              {"Date":"2026-10-29 15:02:40","FavoriteCollection":"Home"}]},
           "Favorite Videos":{"FavoriteVideoList":[
              {"Date":"2025-01-01 10:00:00","Link":"https://www.tiktokv.com/share/video/7300000000000000701/"}]}}}
    """.trimIndent()

    @Test fun listedCollectionsAreNamesOnly() {
        val scan = ExportParser.parse(json.toByteArray(), "user_data_tiktok.json")
        assertEquals(listOf("Minecraft", "Home"), scan.collectionNames)
        assertEquals(1, scan.count(ExportSource.SAVED))
        assertTrue(scan.entries.all { it.collections.isEmpty() })
    }

    @Test fun collectionsWithVideosAreNamedToo() {
        val withVideos = """{"Collections":[{"CollectionName":"Recipes","Videos":[{"Link":"https://www.tiktok.com/@a/video/7300000000000000801"}]}]}"""
        val scan = ExportParser.parse(withVideos.toByteArray())
        assertEquals(listOf("Recipes"), scan.collectionNames)
        assertEquals(listOf("Recipes"), scan.entries.single().collections)
    }

    @Test fun textExport() {
        val txt = "Favorite Collection:\nDate: 2026-10-29 17:45:42\nFavorite Collection: Car\n\nDate: 2026-10-29 15:01:33\nFavorite Collection: Gifts\n"
        assertEquals(listOf("Car", "Gifts"), ExportParser.parse(txt.toByteArray(), "Likes and Favorites.txt").collectionNames)
    }
}
