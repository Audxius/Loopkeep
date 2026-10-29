package io.github.audxius.loopkeep.ui

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Three players for the previous, current and next page. The neighbours are prepared but paused,
 * so swiping starts playback instantly. Page `p` always uses slot `p % 3`.
 */
class PlayerPool(context: Context, initiallyMuted: Boolean) {

    private class Slot(val player: ExoPlayer) {
        var uri: String? = null
    }

    private val slots = List(SIZE) {
        Slot(
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (initiallyMuted) 0f else 1f
            },
        )
    }

    var muted: Boolean = initiallyMuted
        set(value) {
            field = value
            slots.forEach { it.player.volume = if (value) 0f else 1f }
        }

    fun playerFor(page: Int): ExoPlayer = slots[page.mod(SIZE)].player

    /** Loads [uri] into the page's slot if it isn't already there. Cheap to call repeatedly. */
    fun prepare(page: Int, uri: String) {
        val slot = slots[page.mod(SIZE)]
        if (slot.uri == uri) return
        slot.uri = uri
        slot.player.apply {
            playWhenReady = false
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }

    /**
     * Plays the current page from the start and pauses everything else. [uri] must match what the
     * page loaded, so a text-only page never plays a stale video left in its slot.
     */
    fun activate(page: Int?, uri: String?) {
        slots.forEachIndexed { i, slot ->
            if (page != null && uri != null && i == page.mod(SIZE) && slot.uri == uri) {
                slot.player.seekTo(0)
                slot.player.playWhenReady = true
            } else {
                slot.player.playWhenReady = false
            }
        }
    }

    fun pauseAll() = slots.forEach { it.player.playWhenReady = false }

    fun release() = slots.forEach { it.player.release() }

    private companion object {
        const val SIZE = 3
    }
}
