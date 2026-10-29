package io.github.audxius.loopkeep.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontmatterTest {

    private val obsidianEdited = """
        ---
        tiktok_id: "7300000000000000001"
        source_url: https://www.tiktok.com/@chef/video/7300000000000000001
        author: Chef Anna   # display name
        aliases:
          - pasta night
          - "the: good one"
        rating: 5
        tags:
          - recipe
          - pasta
        cssclasses: [wide]
        # my own comment
        status: full
        nested:
          key: value
          list: [1, 2]
        ---

        ![[7300000000000000001.mp4]]

        Caption with #recipe #pasta 🍝
        """.trimIndent() + "\n"

    @Test fun unchangedRoundTripIsByteExact() {
        assertEquals(obsidianEdited, Frontmatter.parse(obsidianEdited).render())
        val crlf = obsidianEdited.replace("\n", "\r\n")
        assertEquals(crlf, Frontmatter.parse(crlf).render())
        val bom = "﻿" + obsidianEdited
        assertEquals(bom, Frontmatter.parse(bom).render())
    }

    @Test fun readsValues() {
        val fm = Frontmatter.parse(obsidianEdited)
        assertEquals("7300000000000000001", fm.getString("tiktok_id"))
        assertEquals("Chef Anna", fm.getString("author"))
        assertEquals(listOf("recipe", "pasta"), fm.getList("tags"))
        assertEquals(listOf("pasta night", "the: good one"), fm.getList("aliases"))
        assertEquals(listOf("wide"), fm.getList("cssclasses"))
        assertEquals("5", fm.getString("rating"))
        assertNull(fm.getString("missing"))
    }

    @Test fun settingAManagedKeyLeavesEverythingElseAlone() {
        val fm = Frontmatter.parse(obsidianEdited)
        fm.setList("tags", listOf("recipe", "pasta", "weeknight"))
        fm.set("status", "text-only")
        val out = fm.render()
        val expected = obsidianEdited
            .replace("tags:\n  - recipe\n  - pasta\n", "tags: [recipe, pasta, weeknight]\n")
            .replace("status: full", "status: text-only")
        assertEquals(expected, out)
        assertEquals(listOf("tiktok_id", "source_url", "author", "aliases", "rating", "tags", "cssclasses", "status", "nested"), fm.keys)
    }

    @Test fun newKeysAreAppendedAfterTheLastKey() {
        val fm = Frontmatter.parse("---\na: 1\n# trailing comment\n---\nbody\n")
        fm.set("b", "2", forceQuote = false)
        assertEquals("---\na: 1\nb: \"2\"\n# trailing comment\n---\nbody\n", fm.render())
    }

    @Test fun removeKey() {
        val fm = Frontmatter.parse("---\na: 1\nb:\n  - x\nc: 3\n---\n")
        fm.remove("b")
        assertEquals("---\na: 1\nc: 3\n---\n", fm.render())
    }

    @Test fun quotingRoundTrips() {
        val tricky = listOf(
            "", "plain", "123", "true", "null", "[[x.mp4]]", "a: b", "ends with:", "#tag", "has \"quotes\"",
            "back\\slash", "multi\nline", "emoji 🍝✨", "- dash", "  padded  ", "@handle", "x # not comment", "1e5",
        )
        for (value in tricky) {
            val fm = Frontmatter.parse("---\n---\n")
            fm.set("k", value)
            val reparsed = Frontmatter.parse(fm.render())
            assertEquals("value <$value>", value, reparsed.getString("k") ?: "")
        }
    }

    @Test fun listQuotingRoundTrips() {
        val fm = Frontmatter.parse("---\n---\n")
        val values = listOf("a", "b, c", "[[x.jpg]]", "d'e", "true")
        fm.setList("k", values)
        assertEquals(values, Frontmatter.parse(fm.render()).getList("k"))
    }

    @Test fun noFrontmatter() {
        val fm = Frontmatter.parse("# Just a note\n\ntext\n")
        assertFalse(fm.hasFrontmatter)
        assertEquals("# Just a note\n\ntext\n", fm.render())
        fm.set("tiktok_id", "1", forceQuote = true)
        assertEquals("---\ntiktok_id: \"1\"\n---\n# Just a note\n\ntext\n", fm.render())
    }

    @Test fun unterminatedFrontmatterIsTreatedAsBody() {
        val text = "---\na: 1\nno closing fence\n"
        val fm = Frontmatter.parse(text)
        assertFalse(fm.hasFrontmatter)
        assertEquals(text, fm.render())
    }

    @Test fun commaSeparatedTagStringIsReadAsList() {
        assertEquals(listOf("a", "b"), Frontmatter.parse("---\ntags: a, b\n---\n").getList("tags"))
        assertEquals(listOf("solo"), Frontmatter.parse("---\ntags: solo\n---\n").getList("tags"))
        assertTrue(Frontmatter.parse("---\ntags:\n---\n").getList("tags").isEmpty())
    }
}
