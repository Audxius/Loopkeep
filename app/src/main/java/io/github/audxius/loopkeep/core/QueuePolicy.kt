package io.github.audxius.loopkeep.core

import kotlin.math.min

/** Retry and dedupe rules for the download queue, kept free of Android types so they're testable. */
object QueuePolicy {

    const val MAX_ATTEMPTS = 6
    private const val BASE_DELAY_MS = 60_000L
    private const val MAX_DELAY_MS = 6 * 60 * 60_000L

    /** Exponential backoff: 1, 2, 4, 8 … minutes, capped at 6 h, with ±20 % jitter from [random] (0..1). */
    fun backoffMs(attempts: Int, random: Double = 0.5): Long {
        val exp = BASE_DELAY_MS shl (attempts - 1).coerceIn(0, 20)
        val capped = min(exp, MAX_DELAY_MS)
        return (capped * (0.8 + 0.4 * random.coerceIn(0.0, 1.0))).toLong()
    }

    enum class Decision { ADD, SKIP_ALREADY_SAVED, SKIP_IN_QUEUE, SKIP_ALREADY_TRIED, REQUEUE }

    /**
     * @param noteStatus status of an existing note for this video, if any
     * @param queueState state of the latest queue item for this video/URL, if any
     * @param force the user explicitly asked to (re)download
     */
    fun decide(noteStatus: NoteStatus?, queueState: String?, force: Boolean): Decision = when {
        queueState == "pending" || queueState == "running" -> Decision.SKIP_IN_QUEUE
        force -> if (queueState != null) Decision.REQUEUE else Decision.ADD
        noteStatus == NoteStatus.FULL -> Decision.SKIP_ALREADY_SAVED
        queueState == "failed" -> Decision.REQUEUE
        // Text-only/unavailable results stay as they are until the user asks for a retry.
        noteStatus != null || queueState == "done" -> Decision.SKIP_ALREADY_TRIED
        else -> Decision.ADD
    }
}
