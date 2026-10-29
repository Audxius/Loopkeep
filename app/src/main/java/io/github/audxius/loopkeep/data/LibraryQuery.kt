package io.github.audxius.loopkeep.data

import androidx.sqlite.db.SimpleSQLiteQuery

enum class FeedOrder { NEWEST, OLDEST, SHUFFLE }

/** What the library grid and the feed show. Both use the same query so the feed respects filters. */
data class LibraryFilter(
    val search: String = "",
    val status: String? = null,
    val tag: String? = null,
    val authorHandle: String? = null,
    val order: FeedOrder = FeedOrder.NEWEST,
    val shuffleSeed: Long = 1,
) {
    val isFiltered: Boolean get() = search.isNotBlank() || status != null || tag != null || authorHandle != null

    fun toQuery(): SimpleSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        val fts = ftsExpression(search)
        val from = if (fts != null) {
            args += fts
            "videos INNER JOIN videos_fts ON videos.id = videos_fts.rowid AND videos_fts MATCH ?"
        } else "videos"
        status?.let { where += "videos.status = ?"; args += it }
        tag?.let { where += "videos.tags LIKE ?"; args += "% $it %" }
        authorHandle?.let { where += "videos.authorHandle = ?"; args += it }
        val whereSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val orderSql = when (order) {
            FeedOrder.NEWEST -> " ORDER BY videos.savedSort DESC, videos.id DESC"
            FeedOrder.OLDEST -> " ORDER BY videos.savedSort ASC, videos.id ASC"
            // Stable pseudo-random order for a given seed, so the feed doesn't reshuffle on every DB change.
            FeedOrder.SHUFFLE -> " ORDER BY ((videos.id * ${shuffleSeed % 100_003 + 7919}) % 100003), videos.id"
        }
        return SimpleSQLiteQuery("SELECT videos.* FROM $from$whereSql$orderSql", args.toTypedArray())
    }

    companion object {
        /** Each word becomes a prefix match; all words must match. FTS syntax characters are dropped. */
        fun ftsExpression(search: String): String? {
            val tokens = search.split(Regex("""\s+"""))
                .map { it.replace(Regex("""["*():^\-+#@]"""), "") }
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "\"$it\"*" }
        }
    }
}
