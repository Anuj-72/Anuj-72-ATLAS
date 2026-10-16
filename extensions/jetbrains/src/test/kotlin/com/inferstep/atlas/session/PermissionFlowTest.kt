// PermissionFlow: auto-allow for session-approved tools, prompt/settle round
// trips (once/session/deny), first-answer-wins, remote permission_denied
// dismissal, turn-end cleanup and advisory POST error reporting.
//
// The same cases as extensions/vscode/test/permissionFlow.test.ts.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.PermissionDecision
import com.inferstep.atlas.protocol.PermissionDecisionRequest
import com.inferstep.atlas.protocol.PermissionRequestPayload
import com.inferstep.atlas.protocol.PermissionScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private fun request(
    tool: String,
    id: String = "call-$tool",
    oneTimeOnly: Boolean = false,
): PermissionRequestPayload =
    PermissionRequestPayload(
        toolName = tool,
        args = JsonObject(mapOf("path" to JsonPrimitive("a.py"))),
        message = "run $tool?",
        toolCallId = id,
        oneTimeOnly = oneTimeOnly,
    )

private fun deletion(
    id: String,
    marked: Boolean,
): PermissionRequestPayload =
    PermissionRequestPayload(
        toolName = "delete_file",
        args = JsonObject(mapOf("path" to JsonPrimitive("important.db"))),
        message = "Allow this one deletion? important.db",
        toolCallId = id,
        oneTimeOnly = marked,
    )

private class FakePoster(
    private val failWith: Throwable? = null,
) : PermissionPoster {
    val decisions = mutableListOf<PermissionDecisionRequest>()

    override suspend fun postPermissionDecision(decision: PermissionDecisionRequest) {
        decisions += decision
        failWith?.let { throw it }
    }
}

private sealed interface UiEvent {
    data class Prompt(
        val pending: PendingPermission,
    ) : UiEvent

    data class Dismiss(
        val pending: PendingPermission,
        val reason: DismissReason,
    ) : UiEvent

    data class AutoAllow(
        val toolName: String,
    ) : UiEvent

    data class PostError(
        val toolName: String,
        val error: Throwable,
    ) : UiEvent
}

private class FakeUi : PermissionUi {
    val events = mutableListOf<UiEvent>()

    override fun onPrompt(pending: PendingPermission) {
        events += UiEvent.Prompt(pending)
    }

    override fun onDismiss(
        pending: PendingPermission,
        reason: DismissReason,
    ) {
        events += UiEvent.Dismiss(pending, reason)
    }

    override fun onAutoAllow(toolName: String) {
        events += UiEvent.AutoAllow(toolName)
    }

    override fun onPostError(
        toolName: String,
        error: Throwable,
    ) {
        events += UiEvent.PostError(toolName, error)
    }

    val prompts: List<PendingPermission>
        get() = events.filterIsInstance<UiEvent.Prompt>().map { it.pending }
}

class PermissionFlowTest {
    @Test
    fun `auto-answers allow-once for a session-allowed tool, with no prompt`() =
        runBlocking {
            val poster = FakePoster()
            val ui = FakeUi()
            val allowed = linkedSetOf("edit_file")
            val flow = PermissionFlow(allowed, ui, this)

            flow.handleRequest(poster, "sess-1", request("edit_file"))
            settle()

            // TUI convention: auto-allow posts scope "once", not "session".
            assertEquals(
                listOf(
                    PermissionDecisionRequest(
                        "sess-1",
                        "call-edit_file",
                        PermissionDecision.ALLOW,
                        PermissionScope.ONCE,
                    ),
                ),
                poster.decisions,
            )
            assertEquals(listOf(UiEvent.AutoAllow("edit_file")), ui.events)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("answers")
    fun `posts what the user picked, and only allow-for-session touches the list`(
        name: String,
        tool: String,
        choice: PermissionChoice,
        decision: PermissionDecision,
        scope: PermissionScope,
        onTheList: Boolean,
    ) = runBlocking {
        val poster = FakePoster()
        val ui = FakeUi()
        val allowed = linkedSetOf<String>()
        val flow = PermissionFlow(allowed, ui, this)

        flow.handleRequest(poster, "sess-1", request(tool))
        val pending = ui.prompts.single()
        assertEquals(tool, pending.request.toolName, name)

        assertTrue(pending.settle(choice), name)
        settle()

        assertEquals(
            listOf(PermissionDecisionRequest("sess-1", "call-$tool", decision, scope)),
            poster.decisions,
            name,
        )
        assertEquals(choice, pending.choice, name)
        assertEquals(DismissReason.ANSWERED, (ui.events.last() as UiEvent.Dismiss).reason, name)
        assertEquals(onTheList, allowed.contains(tool), name)
    }

    @Test
    fun `first answer wins — the second settle is a no-op`() =
        runBlocking {
            val poster = FakePoster()
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleRequest(poster, "sess-4", request("write_file"))
            val pending = ui.prompts.single()

            assertTrue(pending.settle(PermissionChoice.ALLOW_ONCE))
            assertFalse(pending.settle(PermissionChoice.DENY))
            settle()

            assertEquals(1, poster.decisions.size)
            assertEquals(PermissionDecision.ALLOW, poster.decisions.single().decision)
            assertEquals(PermissionChoice.ALLOW_ONCE, pending.choice)
        }

    @Test
    fun `a remote permission_denied dismisses the matching prompt`() =
        runBlocking {
            val poster = FakePoster()
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleRequest(poster, "sess-5", request("write_file"))
            val pending = ui.prompts.single()

            flow.handleDenied("write_file")
            assertEquals(DismissReason.DENIED_REMOTE, (ui.events.last() as UiEvent.Dismiss).reason)

            assertFalse(pending.settle(PermissionChoice.ALLOW_ONCE))
            settle()
            assertEquals(0, poster.decisions.size)
        }

    @Test
    fun `a permission_denied for a tool with no open prompt is a no-op`() =
        runBlocking {
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleDenied("write_file")

            assertEquals(0, ui.events.size)
        }

    @Test
    fun `endTurn dismisses every open prompt and blocks late answers`() =
        runBlocking {
            val poster = FakePoster()
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleRequest(poster, "sess-6", request("write_file", "call-1"))
            flow.handleRequest(poster, "sess-6", request("edit_file", "call-2"))
            val first = ui.prompts.first()

            flow.endTurn()

            val dismissals = ui.events.filterIsInstance<UiEvent.Dismiss>()
            assertEquals(2, dismissals.size)
            assertTrue(dismissals.all { it.reason == DismissReason.TURN_ENDED })

            assertFalse(first.settle(PermissionChoice.ALLOW_ONCE))
            settle()
            assertEquals(0, poster.decisions.size)
        }

    @Test
    fun `settleById routes to the right prompt and an unknown id is a no-op`() =
        runBlocking {
            val poster = FakePoster()
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleRequest(poster, "sess-7", request("write_file", "call-1"))
            flow.handleRequest(poster, "sess-7", request("edit_file", "call-2"))
            val second = ui.prompts[1]

            assertFalse(flow.settleById(9999, PermissionChoice.DENY))
            assertTrue(flow.settleById(second.id, PermissionChoice.ALLOW_ONCE))
            settle()

            assertEquals(
                listOf(
                    PermissionDecisionRequest(
                        "sess-7",
                        "call-2",
                        PermissionDecision.ALLOW,
                        PermissionScope.ONCE,
                    ),
                ),
                poster.decisions,
            )
        }

    @Test
    fun `reports a POST failure through onPostError instead of throwing`() =
        runBlocking {
            val boom = IllegalStateException("proxy 500")
            val poster = FakePoster(boom)
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf(), ui, this)

            flow.handleRequest(poster, "sess-8", request("write_file"))
            ui.prompts.single().settle(PermissionChoice.ALLOW_ONCE)
            settle()

            assertEquals(UiEvent.PostError("write_file", boom), ui.events.last())
        }

    @Test
    fun `an auto-allow POST failure also routes to onPostError`() =
        runBlocking {
            val boom = IllegalStateException("proxy 500")
            val poster = FakePoster(boom)
            val ui = FakeUi()
            val flow = PermissionFlow(linkedSetOf("edit_file"), ui, this)

            flow.handleRequest(poster, "sess-9", request("edit_file"))
            settle()

            assertEquals(UiEvent.PostError("edit_file", boom), ui.events.last())
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("deletions")
    fun `never approves a deletion for the session, marked or not`(
        name: String,
        marked: Boolean,
    ) = runBlocking {
        val allowed = linkedSetOf<String>()
        val ui = FakeUi()
        val poster = FakePoster()
        val flow = PermissionFlow(allowed, ui, this)

        flow.handleRequest(poster, "s", deletion("c1", marked))
        ui.prompts.single().settle(PermissionChoice.ALLOW_SESSION)
        settle()

        assertEquals(PermissionDecision.ALLOW, poster.decisions[0].decision, name)
        assertEquals(PermissionScope.ONCE, poster.decisions[0].scope, name)
        assertFalse(allowed.contains("delete_file"), name)

        // Even an allowlist entry from elsewhere answers nothing.
        allowed += "delete_file"
        flow.handleRequest(poster, "s", deletion("c2", marked))
        assertEquals(2, ui.prompts.size, name)
        assertTrue(ui.events.none { it is UiEvent.AutoAllow }, name)
    }

    @Test
    fun `commandOf returns the whole command and nothing for a call that runs none`() {
        val long =
            "cd tests && python3 -m pytest -q test_api.py test_models.py test_views.py " +
                "2>&1 | tail -n 40 && cd .. && rm -rf src/legacy data/ && git checkout -- ."

        assertEquals(long, commandOf(JsonObject(mapOf("command" to JsonPrimitive(long)))))
        assertNull(commandOf(JsonObject(mapOf("path" to JsonPrimitive("a.py")))))
        assertNull(commandOf(null))
    }

    companion object {
        @JvmStatic
        fun answers(): List<Arguments> =
            listOf(
                Arguments.of(
                    "allow once answers the prompt and posts allow once",
                    "write_file",
                    PermissionChoice.ALLOW_ONCE,
                    PermissionDecision.ALLOW,
                    PermissionScope.ONCE,
                    false,
                ),
                Arguments.of(
                    "allow for session adds the tool to the session list",
                    "structural_edit",
                    PermissionChoice.ALLOW_SESSION,
                    PermissionDecision.ALLOW,
                    PermissionScope.SESSION,
                    true,
                ),
                Arguments.of(
                    "deny answers with a deny and leaves the list alone",
                    "run_command",
                    PermissionChoice.DENY,
                    PermissionDecision.DENY,
                    PermissionScope.ONCE,
                    false,
                ),
            )

        @JvmStatic
        fun deletions(): List<Arguments> =
            listOf(
                Arguments.of("a deletion the proxy marks one_time_only", true),
                Arguments.of("a delete_file the proxy did not mark", false),
            )
    }
}
