package io.github.audxius.loopkeep.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small app preferences. Nothing here is data: losing it only means re-picking the folder. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _folder = MutableStateFlow(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse))
    val folder: StateFlow<Uri?> = _folder.asStateFlow()

    fun setFolder(uri: Uri?) {
        prefs.edit { putString(KEY_FOLDER, uri?.toString()) }
        _folder.value = uri
    }

    var feedOrder: FeedOrder
        get() = runCatching { FeedOrder.valueOf(prefs.getString(KEY_ORDER, null)!!) }.getOrDefault(FeedOrder.NEWEST)
        set(value) = prefs.edit { putString(KEY_ORDER, value.name) }

    var muted: Boolean
        get() = prefs.getBoolean(KEY_MUTED, false)
        set(value) = prefs.edit { putBoolean(KEY_MUTED, value) }

    var lastYtDlpUpdateCheck: Long
        get() = prefs.getLong(KEY_YTDLP_CHECK, 0)
        set(value) = prefs.edit { putLong(KEY_YTDLP_CHECK, value) }

    /** One-time removal of the hashtag tags v0.1–v0.2 wrote automatically. */
    var autoTagsCleaned: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TAGS_CLEANED, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_TAGS_CLEANED, value) }

    var autoUpdateYtDlp: Boolean
        get() = prefs.getBoolean(KEY_YTDLP_AUTO, true)
        set(value) = prefs.edit { putBoolean(KEY_YTDLP_AUTO, value) }

    private companion object {
        const val KEY_FOLDER = "folder"
        const val KEY_ORDER = "feed_order"
        const val KEY_MUTED = "muted"
        const val KEY_YTDLP_CHECK = "ytdlp_check"
        const val KEY_YTDLP_AUTO = "ytdlp_auto"
        const val KEY_AUTO_TAGS_CLEANED = "auto_tags_cleaned"
    }
}
