package io.github.audxius.loopkeep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One of the user's TikTok collections and the post IDs in it, as read by `assets/collection_sync.js`. */
data class SyncedCollection(val name: String, val ids: List<String>)

/**
 * One of the signed-in user's own posts. [videoUrl] is TikTok's (expiring) file link; blank for slideshows.
 * [fileError]: why the in-page download failed (null if it worked or wasn't tried).
 */
data class SyncedOwnPost(
    val id: String, val caption: String, val createTime: Long, val videoUrl: String?, val private: Boolean,
    val fileError: String? = null,
)

data class SyncResult(
    val handle: String?,
    val collections: List<SyncedCollection>,
    val own: List<SyncedOwnPost>,
    /** Why the own posts couldn't be listed, if they couldn't; collections may still be fine. */
    val ownError: String?,
    /** How many posts the profile says it has (0 if unknown). */
    val expected: Int = 0,
)

object CollectionSync {
    private val numericId = Regex("""^\d{6,25}$""")
    private val handleChars = Regex("""^[A-Za-z0-9._]{1,40}$""")

    /** `[{"name": "Car", "ids": ["7300…", …]}, …]` → collections; blank names and odd IDs are dropped. */
    fun parse(json: String): List<SyncedCollection> = parseResult(json).collections

    /**
     * `{"handle": "me", "collections": [...], "own": [{"id", "caption", "createTime", "videoUrl", "private"}], "ownError": ""}`,
     * or the older bare collections array.
     */
    fun parseResult(json: String): SyncResult {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull()
        if (root is JsonArray) return SyncResult(null, collections(root), emptyList(), null)
        val obj = root as? JsonObject ?: return SyncResult(null, emptyList(), emptyList(), null)
        return SyncResult(
            handle = obj.string("handle")?.trim()?.removePrefix("@")?.takeIf { handleChars.matches(it) },
            collections = collections(obj["collections"] as? JsonArray),
            own = (obj["own"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.string("id")?.trim()?.takeIf { numericId.matches(it) } ?: return@mapNotNull null
                SyncedOwnPost(
                    id = id,
                    caption = o.string("caption").orEmpty().trim(),
                    createTime = (o["createTime"] as? JsonPrimitive)?.longOrNull ?: 0,
                    videoUrl = o.string("videoUrl")?.trim()?.takeIf { it.startsWith("https://") },
                    private = (o["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    fileError = o.string("file")?.takeIf { it != "ok" }?.take(200),
                )
            }.distinctBy { it.id },
            ownError = obj.string("ownError")?.ifBlank { null },
            expected = ((obj["expected"] as? JsonPrimitive)?.longOrNull ?: 0).toInt().coerceIn(0, 100_000),
        )
    }

    private fun collections(root: JsonArray?): List<SyncedCollection> = root.orEmpty().mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val name = obj.string("name")?.trim()?.ifEmpty { null } ?: return@mapNotNull null
        val ids = (obj["ids"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { id -> numericId.matches(id) } }
            .distinct()
        SyncedCollection(name, ids)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
