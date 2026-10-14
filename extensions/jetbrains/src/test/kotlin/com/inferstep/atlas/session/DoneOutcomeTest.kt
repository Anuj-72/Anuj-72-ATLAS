// The done event's status and reason reach the panel (#236). Both clients
// showed only the summary, so a stopped or failed run looked finished.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.DonePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DoneOutcomeTest {
    @Test
    fun `carries the status, the reason and the summary`() {
        val outcome =
            doneOutcome(
                DonePayload(
                    summary = "Stopped: mod.py still does not parse.",
                    status = "incomplete",
                    reason = "repair_unfinished",
                ),
            )

        assertEquals(
            DoneOutcome(
                text = "Stopped: mod.py still does not parse.",
                status = "incomplete",
                label = "incomplete",
                reason = "repair_unfinished",
                cls = "incomplete",
            ),
            outcome,
        )
    }

    @Test
    fun `reads a missing status as incomplete, as the API doc says`() {
        val outcome = doneOutcome(DonePayload(summary = "from an older proxy"))

        assertEquals("incomplete", outcome.status)
        assertEquals("incomplete", outcome.cls)
        assertEquals("", outcome.reason)
    }

    @Test
    fun `gives each known status its own class`() {
        val classes =
            listOf("completed", "incomplete", "stopped", "timed_out", "failed").map { status ->
                doneOutcome(DonePayload(status = status)).cls
            }

        assertEquals(5, classes.toSet().size)
        assertEquals("timed out", doneOutcome(DonePayload(status = "timed_out")).label)
    }

    @Test
    fun `never turns an unknown status into a style name`() {
        val outcome = doneOutcome(DonePayload(status = """x" onclick="y"""))

        assertEquals("other", outcome.cls)
        assertEquals("""x" onclick="y""", outcome.label)
    }

    @Test
    fun `handles a stream that ended with no done event at all`() {
        assertEquals(
            DoneOutcome(
                text = "",
                status = "incomplete",
                label = "incomplete",
                reason = "",
                cls = "incomplete",
            ),
            doneOutcome(null),
        )
    }
}
