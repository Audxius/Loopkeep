package io.github.audxius.loopkeep.core

/** Suggests tags from a caption's hashtags with plain heuristics. */
object TagSuggester {

    private val hashtag = Regex("""#([\p{L}\p{N}_]+)""")

    /** Engagement-bait and platform tags that say nothing about the content. */
    private val noise = setOf(
        "fyp", "fy", "foryou", "foryoupage", "fypage", "fypシ", "fypシ゚viral", "fyppppppppppppppppppppppp",
        "viral", "viralvideo", "trending", "trend", "tiktok", "tiktoker", "explore", "explorepage",
        "xyzbca", "foru", "4u", "4you", "parati", "pourtoi", "fürdich", "fy p", "capcut", "duet", "stitch",
        "greenscreen", "foryourpage", "blowthisup", "goviral", "fyptiktok", "tiktokviral", "viraltiktok",
    )

    fun normalize(tag: String): String? {
        val t = tag.trim().trimStart('#').lowercase().replace(Regex("""[^\p{L}\p{N}_\-/]"""), "")
        if (t.length < 2 || t.length > 40) return null
        if (t.all { it.isDigit() }) return null // Obsidian rejects all-digit tags
        if (t in noise || t.startsWith("fyp") || t.startsWith("foryou")) return null
        return t
    }

    /**
     * True if every tag looks auto-generated from the caption's hashtags (what Loopkeep up to v0.2
     * wrote into new notes). Used once to clean those up without touching tags the user added.
     */
    fun allFromHashtags(tags: List<String>, caption: String): Boolean {
        if (tags.isEmpty()) return false
        val fromCaption = hashtag.findAll(caption).mapNotNull { normalize(it.groupValues[1]) }.toSet()
        return tags.all { normalize(it) in fromCaption }
    }

    /**
     * Hashtags in caption order, cleaned and de-noised, followed by any existing library tags
     * that appear as whole words in the caption.
     */
    fun suggest(caption: String, existingTags: Collection<String> = emptyList(), limit: Int = 8): List<String> {
        val fromHashtags = hashtag.findAll(caption).mapNotNull { normalize(it.groupValues[1]) }
        val words = caption.lowercase().split(Regex("""[^\p{L}\p{N}_]+""")).toSet()
        val fromLibrary = existingTags.mapNotNull { normalize(it) }.filter { it in words }
        return (fromHashtags + fromLibrary).distinct().take(limit).toList()
    }
}
