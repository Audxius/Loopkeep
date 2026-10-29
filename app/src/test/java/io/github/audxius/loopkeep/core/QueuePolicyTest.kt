package io.github.audxius.loopkeep.core

import io.github.audxius.loopkeep.core.QueuePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePolicyTest {

    @Test fun backoffGrowsAndCaps() {
        assertEquals(60_000L, QueuePolicy.backoffMs(1))
        assertEquals(120_000L, QueuePolicy.backoffMs(2))
        assertEquals(240_000L, QueuePolicy.backoffMs(3))
        assertEquals(6 * 3_600_000L, QueuePolicy.backoffMs(30))
        assertTrue(QueuePolicy.backoffMs(1, 0.0) < QueuePolicy.backoffMs(1, 1.0))
    }

    @Test fun dedupe() {
        assertEquals(Decision.ADD, QueuePolicy.decide(null, null, false))
        assertEquals(Decision.SKIP_ALREADY_SAVED, QueuePolicy.decide(NoteStatus.FULL, null, false))
        assertEquals(Decision.SKIP_ALREADY_SAVED, QueuePolicy.decide(NoteStatus.FULL, "done", false))
        assertEquals(Decision.SKIP_IN_QUEUE, QueuePolicy.decide(null, "pending", false))
        assertEquals(Decision.SKIP_IN_QUEUE, QueuePolicy.decide(NoteStatus.TEXT_ONLY, "running", true))
        assertEquals(Decision.REQUEUE, QueuePolicy.decide(null, "failed", false))
        assertEquals(Decision.SKIP_ALREADY_TRIED, QueuePolicy.decide(NoteStatus.TEXT_ONLY, "done", false))
        assertEquals(Decision.SKIP_ALREADY_TRIED, QueuePolicy.decide(NoteStatus.UNAVAILABLE, null, false))
        assertEquals(Decision.REQUEUE, QueuePolicy.decide(NoteStatus.TEXT_ONLY, "done", true))
        assertEquals(Decision.ADD, QueuePolicy.decide(NoteStatus.FULL, null, true))
    }
}
