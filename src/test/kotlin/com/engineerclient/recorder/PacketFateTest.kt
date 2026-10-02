package com.engineerclient.recorder

import com.engineerclient.recorder.PacketFate.STAGE_BODY
import com.engineerclient.recorder.PacketFate.STAGE_DISPATCHED
import com.engineerclient.recorder.PacketFate.STAGE_TAPPED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of packet fate tracking: the seq list encoding and what one channelRead0 means. */
class PacketFateTest {

    @Test
    fun seqRangesCollapsesRunsOfThreeOrMore() {
        assertEquals("[]", PacketFate.seqRanges(longArrayOf()))
        assertEquals("[5]", PacketFate.seqRanges(longArrayOf(5)))
        assertEquals("[5,6]", PacketFate.seqRanges(longArrayOf(5, 6)))
        assertEquals("[[5,7]]", PacketFate.seqRanges(longArrayOf(5, 6, 7)))
        assertEquals("[1,[3,6],9,10,[20,22]]", PacketFate.seqRanges(longArrayOf(1, 3, 4, 5, 6, 9, 10, 20, 21, 22)))
        // Out of order stays in application order, nothing merged across a gap or a step back.
        assertEquals("[7,5,6]", PacketFate.seqRanges(longArrayOf(7, 5, 6)))
    }

    private fun outcome(stage: Int, queued: Boolean = false, odin: Boolean = false, atReturn: Boolean = true,
                        dispatchSeen: Boolean = true, returnSeen: Boolean = true) =
        PacketFate.readOutcome(stage, queued, odin, atReturn, dispatchSeen, returnSeen)

    @Test
    fun queuedPacketsWaitForTheGameThread() {
        assertEquals("wait", outcome(STAGE_DISPATCHED, queued = true))
        assertEquals("wait", outcome(STAGE_DISPATCHED, queued = true, atReturn = false))
    }

    @Test
    fun dispatchedButNotQueuedRanOnTheNetworkThread() {
        assertEquals("netty", outcome(STAGE_DISPATCHED))
        // RETURN never reached although it works: the handler threw out of channelRead0.
        assertEquals("error", outcome(STAGE_DISPATCHED, atReturn = false))
        // RETURN hook not known to work: do not call it an error.
        assertEquals("netty", outcome(STAGE_DISPATCHED, atReturn = false, returnSeen = false))
    }

    @Test
    fun bodyWithoutDispatchIsRejected() {
        assertEquals("rejected", outcome(STAGE_BODY))
        assertEquals("rejected", outcome(STAGE_BODY, atReturn = false))
    }

    @Test
    fun neverReachingTheBodyIsACancel() {
        assertEquals("cancelled_read0", outcome(STAGE_TAPPED, atReturn = false))
        // Whether or not a cancellable callback's early return counts as RETURN, the answer is the same.
        assertEquals("cancelled_read0", outcome(STAGE_TAPPED, atReturn = true))
        // Already written as an Odin cancel.
        assertNull(outcome(STAGE_TAPPED, odin = true))
        // The in-method marks never fired yet: a target that failed to apply must not read as a cancel.
        assertNull(outcome(STAGE_TAPPED, dispatchSeen = false))
    }
}
