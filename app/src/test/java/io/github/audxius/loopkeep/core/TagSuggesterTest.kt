package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TagSuggesterTest {
    @Test fun hashtagsCleanedAndDeNoised() {
        assertEquals(
            listOf("recipe", "pasta", "weeknightdinner"),
            TagSuggester.suggest("So good #Recipe #fyp #pasta #foryoupage #viral #WeeknightDinner #2024 #recipe"),
        )
    }

    @Test fun libraryTagsMatchedAsWords() {
        assertEquals(listOf("garlic", "travel"), TagSuggester.suggest("#garlic bread for travel days", listOf("travel", "tokyo")))
    }

    @Test fun unicodeHashtags() {
        assertEquals(listOf("receptai", "日本"), TagSuggester.suggest("#Receptai #日本"))
    }
}
