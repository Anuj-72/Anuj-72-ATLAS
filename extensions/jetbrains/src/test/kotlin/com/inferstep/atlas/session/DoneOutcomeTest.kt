// The done event's status and reason reach the panel (#236). Both clients
// showed only the summary, so a stopped or failed run looked finished.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.DonePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("banners")
    fun `names the banner each status gets, and invents no style of its own`(
        name: String,
        status: String,
        label: String,
        cls: String,
    ) {
        val outcome = doneOutcome(DonePayload(status = status))

        assertEquals(label, outcome.label, name)
        assertEquals(cls, outcome.cls, name)
    }

    @Test
    fun `reads a missing status as incomplete, as the API doc says`() {
        val outcome = doneOutcome(DonePayload(summary = "from an older proxy"))

        assertEquals("incomplete", outcome.status)
        assertEquals("incomplete", outcome.cls)
        assertEquals("", outcome.reason)
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

    companion object {
        @JvmStatic
        fun banners(): List<Arguments> =
            listOf(
                Arguments.of("a completed run", "completed", "completed", "completed"),
                Arguments.of("an incomplete run", "incomplete", "incomplete", "incomplete"),
                Arguments.of("a stopped run", "stopped", "stopped", "stopped"),
                Arguments.of("a timed-out run", "timed_out", "timed out", "timed_out"),
                Arguments.of("a failed run", "failed", "failed", "failed"),
                Arguments.of(
                    "a status this client has never seen",
                    """x" onclick="y""",
                    """x" onclick="y""",
                    "other",
                ),
            )
    }
}
