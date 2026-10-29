package io.github.audxius.loopkeep.core

/** Builds new notes and merges fresh download results into existing ones. */
object NoteWriter {

    fun newNote(meta: VideoMeta): String {
        val doc = NoteDocument.parse("---\n---\n")
        val fm = doc.frontmatter
        fm.set(NoteKeys.TIKTOK_ID, meta.tiktokId, forceQuote = true)
        fm.set(NoteKeys.SOURCE_URL, meta.sourceUrl)
        fm.set(NoteKeys.AUTHOR, meta.author ?: meta.authorHandle ?: "")
        fm.set(NoteKeys.AUTHOR_HANDLE, meta.authorHandle ?: "")
        meta.authorHandle?.takeIf { it.isNotBlank() }?.let { fm.set(NoteKeys.AUTHOR_URL, TikTokUrls.profileUrl(it)) }
        meta.authorLink?.let { fm.set(NoteKeys.AUTHOR_LINK, it) }
        meta.authorBio?.let { fm.set(NoteKeys.AUTHOR_BIO, it) }
        meta.posted?.let { fm.set(NoteKeys.POSTED, it.toString()) }
        fm.set(NoteKeys.SAVED, meta.saved.toString())
        fm.setList(NoteKeys.TAGS, meta.tags)
        if (meta.collections.isNotEmpty()) fm.setList(NoteKeys.COLLECTIONS, meta.collections)
        applyMedia(doc, meta)

        val body = StringBuilder()
        body.append(meta.caption.replace("\r\n", "\n").trim())
        body.append("\n\n").append(NoteKeys.NOTES_HEADING).append("\n\n")
        fm.body = "\n" + body.toString()
        applyCreatorComments(doc, meta)
        doc.setEmbeds(embedsFor(meta))
        return doc.render()
    }

    /**
     * Merges a newer result (e.g. a successful retry of a text-only item) into an existing note.
     * Media keys and status are updated; descriptive keys only fill gaps; tags are unioned.
     * The caption, the notes section, and unknown keys are never touched.
     */
    fun merge(existingText: String, meta: VideoMeta): String {
        val doc = NoteDocument.parse(existingText)
        val fm = doc.frontmatter
        if (fm.getString(NoteKeys.TIKTOK_ID).isNullOrBlank()) fm.set(NoteKeys.TIKTOK_ID, meta.tiktokId, forceQuote = true)
        fillGap(fm, NoteKeys.SOURCE_URL, meta.sourceUrl)
        fillGap(fm, NoteKeys.AUTHOR, meta.author)
        fillGap(fm, NoteKeys.AUTHOR_HANDLE, meta.authorHandle)
        fillGap(fm, NoteKeys.POSTED, meta.posted?.toString())
        meta.authorHandle?.takeIf { it.isNotBlank() }?.let { fillGap(fm, NoteKeys.AUTHOR_URL, TikTokUrls.profileUrl(it)) }
        // Bio and link reflect the profile now; refresh them when we have newer ones.
        meta.authorLink?.let { fm.set(NoteKeys.AUTHOR_LINK, it) }
        meta.authorBio?.let { fm.set(NoteKeys.AUTHOR_BIO, it) }
        val tags = (fm.getList(NoteKeys.TAGS) + meta.tags).distinct()
        if (tags != fm.getList(NoteKeys.TAGS) || NoteKeys.TAGS !in fm) fm.setList(NoteKeys.TAGS, tags)
        addCollections(fm, meta.collections)
        applyMedia(doc, meta)
        // Only rewrite the embeds if we now have something better to show.
        if (meta.status != NoteStatus.UNAVAILABLE) doc.setEmbeds(embedsFor(meta))
        if (doc.caption().isBlank() && meta.caption.isNotBlank()) doc.fillCaption(meta.caption)
        applyCreatorComments(doc, meta)
        return doc.render()
    }

    /** Adds [collections] to a note's `collections` list, keeping existing ones. Returns true if it changed. */
    fun addCollections(fm: Frontmatter, collections: List<String>): Boolean {
        val current = fm.getList(NoteKeys.COLLECTIONS)
        val merged = (current + collections.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        if (merged == current) return false
        fm.setList(NoteKeys.COLLECTIONS, merged)
        return true
    }

    /**
     * Updates only the creator's profile link/bio and the creator comments section of a note.
     * Media, status, caption and everything else stay exactly as they are.
     */
    fun updateCreatorInfo(existingText: String, handle: String?, link: String?, bio: String?, comments: List<CreatorComment>?): String {
        val doc = NoteDocument.parse(existingText)
        val fm = doc.frontmatter
        handle?.takeIf { it.isNotBlank() }?.let { fillGap(fm, NoteKeys.AUTHOR_URL, TikTokUrls.profileUrl(it)) }
        link?.let { fm.set(NoteKeys.AUTHOR_LINK, it) }
        bio?.let { fm.set(NoteKeys.AUTHOR_BIO, it) }
        if (!comments.isNullOrEmpty()) doc.setCreatorComments(CreatorCommentsMarkdown.render(handle, comments))
        return doc.render()
    }

    /** Writes the creator comments section when we found some. Never removes existing ones. */
    private fun applyCreatorComments(doc: NoteDocument, meta: VideoMeta) {
        val comments = meta.creatorComments ?: return
        if (comments.isEmpty()) return
        doc.setCreatorComments(CreatorCommentsMarkdown.render(meta.authorHandle, comments))
    }

    private fun fillGap(fm: Frontmatter, key: String, value: String?) {
        if (value.isNullOrBlank()) return
        if (fm.getString(key).isNullOrBlank()) fm.set(key, value)
    }

    private fun applyMedia(doc: NoteDocument, meta: VideoMeta) {
        val fm = doc.frontmatter
        fm.set(NoteKeys.STATUS, meta.status.value)
        if (meta.type != PostType.VIDEO || NoteKeys.TYPE in fm) fm.set(NoteKeys.TYPE, meta.type.value)
        meta.durationSeconds?.let { fm.set(NoteKeys.DURATION, it) }
        meta.videoFile?.let { fm.set(NoteKeys.VIDEO, wikiLink(it), forceQuote = true) }
        meta.coverFile?.let { fm.set(NoteKeys.COVER, wikiLink(it), forceQuote = true) }
        meta.audioFile?.let { fm.set(NoteKeys.AUDIO, wikiLink(it), forceQuote = true) }
        if (meta.imageFiles.isNotEmpty()) fm.setList(NoteKeys.IMAGES, meta.imageFiles.map { wikiLink(it) })
    }

    private fun embedsFor(meta: VideoMeta): List<String> = when {
        meta.videoFile != null -> listOf(meta.videoFile)
        meta.imageFiles.isNotEmpty() -> meta.imageFiles + listOfNotNull(meta.audioFile)
        meta.coverFile != null -> listOf(meta.coverFile)
        else -> emptyList()
    }
}
