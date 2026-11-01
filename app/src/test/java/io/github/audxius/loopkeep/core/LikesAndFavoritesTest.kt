package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Newer exports group both lists under one "Likes and Favorites" section (structure reported by a user, 2026-11). */
class LikesAndFavoritesTest {

    private val json = """
        {"Likes and Favorites":{
           "Favorite Videos":{"FavoriteVideoList":[
              {"Date":"2025-01-01 10:00:00","Link":"https://www.tiktokv.com/share/video/7300000000000000701/"},
              {"Date":"2025-01-02 10:00:00","Link":"https://www.tiktokv.com/share/video/7300000000000000702/"}]},
           "Like List":{"ItemFavoriteList":[
              {"date":"2025-01-03 10:00:00","link":"https://www.tiktokv.com/share/video/7300000000000000703/"}]},
           "Favorite Sounds":{"FavoriteSoundList":[{"Date":"2025-01-01","Link":"https://www.tiktok.com/music/x-123"}]}}}
    """.trimIndent()

    @Test fun favoritesAreSavedNotLiked() {
        val scan = ExportParser.parse(json.toByteArray(), "user_data_tiktok.json")
        assertEquals(2, scan.count(ExportSource.SAVED))
        assertEquals(1, scan.count(ExportSource.LIKED))
    }

    @Test fun ambiguousSectionNamesAreSkipped() {
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("Likes and Favorites", "Favorite Videos", "FavoriteVideoList")))
        assertEquals(ExportSource.LIKED, ExportParser.classify(listOf("Likes and Favorites", "Like List", "ItemFavoriteList")))
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("Your Activity", "Likes and Favorites", "Favorite Videos")))
        // Zip layout: folder names are path parts too.
        assertEquals(ExportSource.SAVED, ExportParser.classify(listOf("TikTok_Data", "Likes and Favorites", "Favorite Videos")))
    }
}
