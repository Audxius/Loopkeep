package io.github.audxius.loopkeep.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One of the user's TikTok collections and the post IDs in it, as read by `assets/collection_sync.js`. */
data class SyncedCollection(val name: String, val ids: List<String>)

object CollectionSync {
    private val numericId = Regex("""^\d{6,25}$""")

    /** `[{"name": "Car", "ids": ["7300…", …]}, …]` → collections; blank names and odd IDs are dropped. */
    fun parse(json: String): List<SyncedCollection> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = (obj["name"] as? JsonPrimitive)?.content?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            val ids = (obj["ids"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { id -> numericId.matches(id) } }
                .distinct()
            SyncedCollection(name, ids)
        }
    }
}
