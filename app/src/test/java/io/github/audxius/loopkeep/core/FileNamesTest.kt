package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test fun readableName() {
        assertEquals(
            "chef.anna - Best pasta ever with garlic and lemon - 567890.md",
            FileNames.noteFileName("chef.anna", "Best pasta ever, with garlic and lemon! #recipe", "7300001234567890"),
        )
    }

    @Test fun stripsEmojiAndForbiddenCharacters() {
        val name = FileNames.noteFileName("we/ird:name", "🍝 Wow?? <this> is* \"great\" | [[link]] #tag ^x", "1234567")
        assertEquals("we ird name - Wow this is great link x - 234567.md", name)
        assertTrue(name.none { it in "\\/:*?\"<>|#^[]" })
    }

    @Test fun keepsNonLatinLetters() {
        assertEquals("ąžuolas - Labas rytas Ūkininke - 999999.md", FileNames.noteFileName("ąžuolas", "Labas rytas, Ūkininke!", "999999"))
        assertEquals("u - 東京の夜 - 111111.md", FileNames.noteFileName("u", "東京の夜", "111111"))
    }

    @Test fun hashtagOnlyCaptionUsesTagWords() {
        assertEquals("u - fyp cooking - 111111.md", FileNames.noteFileName("u", "#fyp #cooking", "111111"))
    }

    @Test fun emptyEverything() {
        assertEquals("111111.md", FileNames.noteFileName(null, null, "111111"))
        assertEquals("111111.md", FileNames.noteFileName("", "🔥🔥🔥", "111111"))
    }

    @Test fun lengthIsCappedInBytes() {
        val name = FileNames.noteFileName("h", "ž".repeat(500), "123456")
        assertTrue(name.toByteArray().size <= 125)
    }

    @Test fun noLeadingDotOrDash() {
        assertTrue(!FileNames.noteFileName(".hidden", null, "1").startsWith("."))
        assertTrue(!FileNames.noteFileName("-", "-", "1").startsWith("-"))
    }

    @Test fun uniqueSuffix() {
        val taken = setOf("a.md", "a (2).md")
        assertEquals("a (3).md", FileNames.unique("a.md") { it in taken })
        assertEquals("b.md", FileNames.unique("b.md") { it in taken })
    }
}
