// ChatSession: what the panel would render, as values. Streaming text, tool
// chips with their summaries, the done banner, live permission prompts,
// auto-allowed tools, cancellation, the two mode controls and reset.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AgentRequest
import com.inferstep.atlas.protocol.AtlasJson
import com.inferstep.atlas.protocol.ChatEvent
import com.inferstep.atlas.protocol.PermissionDecision
import com.inferstep.atlas.protocol.PermissionDecisionRequest
import com.inferstep.atlas.protocol.PermissionMode
import com.inferstep.atlas.protocol.PermissionScope
import com.inferstep.atlas.protocol.TaskContract
import com.inferstep.atlas.protocol.TaskMode
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private fun event(frame: String): ChatEvent =
    AtlasJson.decodeFromString(ChatEvent.serializer(), frame)

private fun textFrame(content: String): ChatEvent =
    event("""{"type":"text","data":{"content":"$content"}}""")

private fun doneFrame(json: String): ChatEvent = event("""{"type":"done","data":$json}""")

private fun permissionFrame(
    tool: String,
    id: String,
): ChatEvent =
    event(
        """{"type":"permission_request","data":{"tool_name":"$tool",""" +
            """"args":{"path":"a.py"},"message":"$tool?","tool_call_id":"$id"}}""",
    )

private class RecordingClient(
    private val frames: List<ChatEvent>,
) : TurnClient {
    val requests = mutableListOf<AgentRequest>()
    var hangAfter: Int? = null

    /** Makes the stream fail the way a non-2xx response does. */
    var failWith: Throwable? = null

    override fun agentTurn(request: AgentRequest): Flow<ChatEvent> =
        flow {
            requests += request
            failWith?.let { error -> throw error }
            frames.forEachIndexed { index, event ->
                emit(event)
                if (hangAfter == index) {
                    awaitCancellation()
                }
            }
        }

    override suspend fun cancelTurn(sessionId: String): Boolean = true
}

private class RecordingPoster : PermissionPoster {
    val decisions = mutableListOf<PermissionDecisionRequest>()

    override suspend fun postPermissionDecision(decision: PermissionDecisionRequest) {
        decisions += decision
    }
}

private class SessionFixture(
    frames: List<ChatEvent>,
) {
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    val client = RecordingClient(frames)
    val poster = RecordingPoster()
    val session = ChatSession(client, poster, scope, "/tmp/ws")

    val entries: List<TranscriptEntry>
        get() = session.state.value.entries
}

class ChatSessionTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    fun `reads a stream into the transcript and ends the turn`(
        name: String,
        message: String,
        frames: List<ChatEvent>,
        want: List<TranscriptEntry>,
    ) = runBlocking {
        val fixture = SessionFixture(frames)

        fixture.session.send(message)

        assertEquals(want, fixture.entries, name)
        assertFalse(fixture.session.state.value.running, name)
    }

    @Test
    fun `shows tool calls with the output summary and the call's outcome`() =
        runBlocking {
            val fixture =
                SessionFixture(
                    listOf(
                        event(
                            """{"type":"tool_call","data":{"name":"list_directory",""" +
                                """"args":{},"turn":1}}""",
                        ),
                        event(
                            """{"type":"tool_result","data":{"tool":"list_directory",""" +
                                """"success":true,"data":{"entries":""" +
                                """[{"name":"app.py","type":"file"}]}}}""",
                        ),
                    ),
                )

            fixture.session.send("list the files")

            assertEquals(TranscriptEntry.Tool(2, "list_directory", null, null), fixture.entries[1])
            val result = fixture.entries[2] as TranscriptEntry.Tool
            assertEquals("list_directory", result.name)
            assertEquals(true, result.success)
            val summary = result.summary.orEmpty()
            assertTrue(summary.contains("app.py"), summary)
        }

    @Test
    fun `shows a prompt for the request and posts the answer`() =
        runBlocking {
            val fixture =
                SessionFixture(
                    listOf(
                        event(
                            """{"type":"permission_request","data":{"tool_name":"write_file",""" +
                                """"args":{"path":"app.py"},"message":"write app.py?",""" +
                                """"tool_call_id":"c1"}}""",
                        ),
                        doneFrame("""{"summary":"stopped","status":"incomplete"}"""),
                    ),
                )
            // The proxy pauses the turn while a permission is pending, which
            // is the only time a prompt is on screen.
            fixture.client.hangAfter = 0

            val running = launch { fixture.session.send("write it") }
            settle()
            val pending =
                fixture.session.state.value.pending
                    .single()
            assertEquals("write_file", pending.request.toolName)

            fixture.session.answer(pending.id, PermissionChoice.ALLOW_SESSION)
            settle()

            // The decision has to go back on the turn's own session id, or the
            // proxy has no pending request to route it to.
            val decision = fixture.poster.decisions.single()
            assertEquals("c1", decision.toolCallId)
            assertEquals(PermissionDecision.ALLOW, decision.decision)
            assertEquals(PermissionScope.SESSION, decision.scope)
            val turn = fixture.client.requests.single()
            assertEquals(turn.sessionId, decision.sessionId)
            assertTrue(
                fixture.session.state.value.pending
                    .isEmpty(),
            )
            assertTrue(fixture.session.state.value.running)

            fixture.session.cancel()
            running.join()
            assertFalse(fixture.session.state.value.running)
        }

    @Test
    fun `auto-answers a tool the user allowed for the session, on the next turn`() =
        runBlocking {
            val fixture =
                SessionFixture(
                    listOf(
                        permissionFrame("edit_file", "c2"),
                        doneFrame("""{"summary":"ok"}"""),
                    ),
                )

            fixture.client.hangAfter = 0

            // Turn 1: the user allows edit_file for the session.
            val first = launch { fixture.session.send("edit it") }
            settle()
            val pending =
                fixture.session.state.value.pending
                    .single()
            fixture.session.answer(pending.id, PermissionChoice.ALLOW_SESSION)
            settle()
            fixture.session.cancel()
            first.join()

            // Turn 2: the same tool is now on the allowlist, so there is no
            // prompt and the proxy is told the answer instead.
            val second = launch { fixture.session.send("edit it again") }
            settle()

            assertEquals(
                listOf(PermissionScope.SESSION, PermissionScope.ONCE),
                fixture.poster.decisions.map { it.scope },
            )
            assertTrue(
                fixture.session.state.value.pending
                    .isEmpty(),
            )
            assertTrue(
                fixture.entries.any {
                    it is TranscriptEntry.Notice &&
                        it.text == "edit_file is allowed for this session."
                },
                fixture.entries.toString(),
            )

            fixture.session.cancel()
            second.join()
        }

    @Test
    fun `reports a failed turn instead of leaving the message unanswered`() =
        runBlocking {
            val fixture = SessionFixture(listOf(textFrame("never reached")))
            fixture.client.failWith = IllegalStateException("unauthorized: no token")

            fixture.session.send("go")

            val last = fixture.entries.last()
            assertTrue(last is TranscriptEntry.Notice, fixture.entries.toString())
            assertEquals(true, (last as TranscriptEntry.Notice).isError)
            assertTrue(last.text.contains("unauthorized: no token"), last.text)
            assertFalse(fixture.session.state.value.running)
        }

    @Test
    fun `declares the mode the user picked`() =
        runBlocking {
            val fixture = SessionFixture(listOf(doneFrame("""{"summary":"answered"}""")))

            fixture.session.setTaskMode(TaskMode.QUESTION)
            fixture.session.setPermissionMode(PermissionMode.ACCEPT_EDITS)
            fixture.session.send("what does solve.py do?")

            val request = fixture.client.requests.single()
            assertEquals(TaskContract(TaskMode.QUESTION), request.taskContract)
            assertEquals(PermissionMode.ACCEPT_EDITS, request.mode)
        }

    @Test
    fun `ignores an empty message and a second turn while one is running`() =
        runBlocking {
            val fixture = SessionFixture(listOf(textFrame("hi")))
            fixture.client.hangAfter = 0

            fixture.session.send("   ")
            assertEquals(0, fixture.entries.size)

            val running = launch { fixture.session.send("first") }
            settle()
            assertTrue(fixture.session.state.value.running)
            fixture.session.send("second")

            assertEquals(1, fixture.client.requests.size)
            assertEquals(0, fixture.session.state.value.pending.size)

            fixture.session.cancel()
            running.join()
        }

    @Test
    fun `cancel stops the turn, closes its prompts and says so`() =
        runBlocking {
            val fixture =
                SessionFixture(
                    listOf(
                        event(
                            """{"type":"permission_request","data":{"tool_name":"delete_file",""" +
                                """"args":{"path":"important.db"},"tool_call_id":"c3",""" +
                                """"one_time_only":true}}""",
                        ),
                    ),
                )
            fixture.client.hangAfter = 0

            val running = launch { fixture.session.send("delete it") }
            settle()
            assertEquals(1, fixture.session.state.value.pending.size)

            fixture.session.cancel()
            running.join()
            settle()

            assertFalse(fixture.session.state.value.running)
            assertEquals(emptyList<PendingPermission>(), fixture.session.state.value.pending)
            val last = fixture.entries.last()
            assertTrue(last is TranscriptEntry.Notice, fixture.entries.toString())
            assertEquals("Turn cancelled.", (last as TranscriptEntry.Notice).text)
            assertFalse(last.isError)
        }

    @Test
    fun `reset clears the transcript and the history, and keeps the chosen modes`() =
        runBlocking {
            val fixture =
                SessionFixture(listOf(textFrame("hi"), doneFrame("""{"summary":"done"}""")))
            fixture.session.setTaskMode(TaskMode.QUESTION)
            fixture.session.send("go")

            fixture.session.reset()

            assertEquals(emptyList<TranscriptEntry>(), fixture.entries)
            assertEquals(TaskMode.QUESTION, fixture.session.state.value.taskMode)

            // The turn after the reset is the one that shows the history is
            // gone: it would carry turn 1's entries back to the proxy.
            fixture.session.send("again")

            assertEquals(2, fixture.client.requests.size)
            val second = fixture.client.requests[1]
            assertEquals("again", second.message)
            assertNull(second.history)
        }

    companion object {
        @JvmStatic
        fun streams(): List<Arguments> =
            listOf(
                Arguments.of(
                    "deltas collect into one assistant entry, then the banner",
                    "plan it",
                    listOf(
                        textFrame("Here is "),
                        textFrame("the plan."),
                        doneFrame("""{"summary":"Plan ready.","status":"completed"}"""),
                    ),
                    listOf(
                        TranscriptEntry.User(1, "plan it"),
                        TranscriptEntry.Assistant(2, "Here is the plan."),
                        TranscriptEntry.Done(
                            3,
                            DoneOutcome("Plan ready.", "completed", "completed", "", "completed"),
                        ),
                    ),
                ),
                Arguments.of(
                    "an error event becomes an error notice",
                    "go",
                    listOf(event("""{"type":"error","data":{"error":"lens down"}}""")),
                    listOf(
                        TranscriptEntry.User(1, "go"),
                        TranscriptEntry.Notice(2, "lens down", isError = true),
                    ),
                ),
                Arguments.of(
                    "progress events never reach the transcript",
                    "go",
                    listOf(
                        event("""{"type":"llm_token","data":{"text":"thinking"}}"""),
                        event(
                            """{"type":"v3_phase","data":{"stage":"phase2","detail":"allocating"}}""",
                        ),
                        event(
                            """{"type":"plan_loaded","data":{"steps":[],"verify_step":"s1"}}""",
                        ),
                        doneFrame("""{"summary":"done"}"""),
                    ),
                    listOf(
                        TranscriptEntry.User(1, "go"),
                        TranscriptEntry.Done(
                            2,
                            DoneOutcome("done", "incomplete", "incomplete", "", "incomplete"),
                        ),
                    ),
                ),
            )
    }
}
