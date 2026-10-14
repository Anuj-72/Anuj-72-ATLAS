// TurnManager: session_id minting, request shape, rolling history (40-cap,
// assistant envelope re-wrap), session_allowed_tools re-send, the busy guard,
// the task contract and cancel.
//
// The same cases as extensions/vscode/test/turnManager.test.ts, so the two
// clients cannot drift apart on what a turn looks like on the wire.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AgentRequest
import com.inferstep.atlas.protocol.AtlasJson
import com.inferstep.atlas.protocol.ChatEvent
import com.inferstep.atlas.protocol.PermissionMode
import com.inferstep.atlas.protocol.TaskContract
import com.inferstep.atlas.protocol.TaskMode
import com.inferstep.atlas.protocol.assistantHistoryContent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WORKSPACE = "/tmp/ws"

/** The `text` event, as the proxy sends it. */
private fun text(content: String): ChatEvent =
    decode("""{"type":"text","data":{"content":${quote(content)}}}""")

/** The `done` event with the given summary. */
private fun done(summary: String): ChatEvent =
    decode("""{"type":"done","data":{"summary":${quote(summary)}}}""")

private fun decode(frame: String): ChatEvent =
    AtlasJson.decodeFromString(ChatEvent.serializer(), frame)

private fun quote(value: String): String = JsonPrimitive(value).toString()

/**
 * A fake [TurnClient]: records the requests it is given and replays canned
 * frames. [hangAfter] makes it stop after that frame and wait forever, which
 * is what an in-flight turn looks like while a turn is being cancelled or a
 * second one is rejected.
 */
private class FakeTurnClient(
    private val frames: List<ChatEvent> = emptyList(),
) : TurnClient {
    val requests = mutableListOf<AgentRequest>()
    val cancelled = mutableListOf<String>()
    var hangAfter: Int? = null

    override fun agentTurn(request: AgentRequest): Flow<ChatEvent> =
        flow {
            requests += request
            frames.forEachIndexed { index, event ->
                emit(event)
                if (hangAfter == index) {
                    awaitCancellation()
                }
            }
        }

    override suspend fun cancelTurn(sessionId: String): Boolean {
        cancelled += sessionId
        return true
    }
}

class TurnManagerTest {
    @Test
    fun `mints 24-character hex ids, unique per call`() {
        val first = mintSessionId()
        val second = mintSessionId()

        assertEquals(24, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{24}")), first)
        assertNotEquals(first, second)
    }

    @Test
    fun `sends the conventional request shape and yields every event`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("hi"), done("")))
            val turns = TurnManager(scope = this)

            val events = mutableListOf<ChatEvent>()
            turns
                .runTurn(client, "hello", PermissionMode.DEFAULT, TaskMode.WORK, WORKSPACE)
                .collect { events += it }

            assertEquals(listOf("text", "done"), events.map { it.type })
            val request = client.requests.single()
            assertEquals("hello", request.message)
            // The caller's workspace root goes on the wire verbatim — the proxy
            // uses it for host-to-container path translation.
            assertEquals(WORKSPACE, request.workingDir)
            assertEquals(PermissionMode.DEFAULT, request.mode)
            assertTrue(request.sessionId.matches(Regex("[0-9a-f]{24}")), request.sessionId)
            // Absent, not empty: the proxy treats an empty history as a history.
            assertNull(request.history)
            assertNull(request.sessionAllowedTools)
            assertEquals(request.sessionId, turns.sessionId)
        }

    @Test
    fun `declares the task mode and nothing it does not know`() =
        runBlocking {
            val client = FakeTurnClient(listOf(done("")))
            val turns = TurnManager(scope = this)

            turns
                .runTurn(
                    client,
                    "what does solve.py do?",
                    PermissionMode.DEFAULT,
                    TaskMode.QUESTION,
                    WORKSPACE,
                ).collect {}

            assertEquals(TaskContract(TaskMode.QUESTION), client.requests.single().taskContract)
        }

    @Test
    fun `mints a fresh session id per turn`() =
        runBlocking {
            val client = FakeTurnClient(listOf(done("")))
            val turns = TurnManager(scope = this)

            turns
                .runTurn(
                    client,
                    "one",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns
                .runTurn(
                    client,
                    "two",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertNotEquals(client.requests[0].sessionId, client.requests[1].sessionId)
        }

    @Test
    fun `replays history with the assistant text re-wrapped in the text envelope`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("Hello "), text("world"), done("")))
            val turns = TurnManager(scope = this)

            turns
                .runTurn(
                    client,
                    "greet me",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns
                .runTurn(
                    client,
                    "again",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertEquals(
                listOf(
                    "user|greet me",
                    "assistant|${assistantHistoryContent("Hello world")}",
                ),
                client.requests[1].history?.map { "${it.role}|${it.content}" },
            )
        }

    @Test
    fun `records no assistant entry for a text-free turn`() =
        runBlocking {
            val client =
                FakeTurnClient(listOf(decode("""{"type":"error","data":{"error":"boom"}}""")))
            val turns = TurnManager(scope = this)

            turns
                .runTurn(
                    client,
                    "do it",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns
                .runTurn(
                    client,
                    "retry",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            // History sent on turn N covers turns before it; turn 1 produced no text.
            assertEquals(
                listOf("user|do it"),
                client.requests[1].history?.map { "${it.role}|${it.content}" },
            )
        }

    @Test
    fun `uses the done summary as the assistant entry on a tool-shaped turn`() =
        runBlocking {
            val client =
                FakeTurnClient(
                    listOf(
                        decode(
                            """{"type":"tool_call","data":{"name":"edit_file","args":{},"turn":1}}""",
                        ),
                        done("Fixed the off-by-one in pagination."),
                    ),
                )
            val turns = TurnManager(scope = this)

            turns
                .runTurn(
                    client,
                    "fix it",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns
                .runTurn(
                    client,
                    "next",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertEquals(
                assistantHistoryContent("Fixed the off-by-one in pagination."),
                client.requests[1]
                    .history
                    ?.last()
                    ?.content,
            )
        }

    @Test
    fun `prefers streamed text over the done summary`() =
        runBlocking {
            val client =
                FakeTurnClient(listOf(text("the streamed answer"), done("a trailing summary")))
            val turns = TurnManager(scope = this)

            turns.runTurn(client, "go", PermissionMode.DEFAULT, TaskMode.WORK, WORKSPACE).collect {}
            turns
                .runTurn(
                    client,
                    "next",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertEquals(
                assistantHistoryContent("the streamed answer"),
                client.requests[1]
                    .history
                    ?.last()
                    ?.content,
            )
        }

    @Test
    fun `caps history at the most recent $HISTORY_LIMIT entries`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("r"), done("")))
            val turns = TurnManager(scope = this)

            // 25 turns x 2 entries = 50 recorded, which is over the cap.
            repeat(25) { index ->
                turns
                    .runTurn(client, "msg $index", PermissionMode.DEFAULT, TaskMode.WORK, WORKSPACE)
                    .collect {}
            }
            turns
                .runTurn(
                    client,
                    "final",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            val history =
                client.requests
                    .last()
                    .history
                    .orEmpty()
            assertEquals(HISTORY_LIMIT, history.size)
            // Oldest surviving entries come from the tail of the run, not turn 0.
            assertFalse(history.any { it.content.contains("msg 0") })
            assertEquals("msg 24", history[history.size - 2].content)
            assertEquals(assistantHistoryContent("r"), history.last().content)
        }

    @Test
    fun `re-sends sessionAllowedTools on every turn once populated`() =
        runBlocking {
            val client = FakeTurnClient(listOf(done("")))
            val turns = TurnManager(scope = this)
            turns.sessionAllowedTools += "edit_file"
            turns.sessionAllowedTools += "write_file"

            turns
                .runTurn(
                    client,
                    "one",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns
                .runTurn(
                    client,
                    "two",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertEquals(listOf("edit_file", "write_file"), client.requests[0].sessionAllowedTools)
            assertEquals(listOf("edit_file", "write_file"), client.requests[1].sessionAllowedTools)
        }

    @Test
    fun `rejects a second concurrent turn`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("first")))
            client.hangAfter = 0
            val turns = TurnManager(scope = this)

            val first =
                launch {
                    turns
                        .runTurn(
                            client,
                            "slow",
                            PermissionMode.DEFAULT,
                            TaskMode.WORK,
                            WORKSPACE,
                        ).collect {}
                }
            // Let the first turn reach its first suspension point.
            delay(50)
            assertTrue(turns.busy)

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    turns
                        .runTurn(client, "second", PermissionMode.DEFAULT, TaskMode.WORK, WORKSPACE)
                        .collect {}
                }
            }

            turns.cancel()
            first.cancelAndJoin()
        }

    @Test
    fun `cancel aborts the stream, posts cancel, and keeps partial text`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("half an ans")))
            client.hangAfter = 0
            val turns = TurnManager(scope = this)

            val seen = mutableListOf<ChatEvent>()
            val turn =
                launch {
                    turns
                        .runTurn(client, "go", PermissionMode.DEFAULT, TaskMode.WORK, WORKSPACE)
                        .collect { event ->
                            seen += event
                            turns.cancel()
                        }
                }
            turn.join()
            // The POST /cancel is fire-and-forget; let the launched coroutine run.
            delay(50)

            assertEquals(listOf("text"), seen.map { it.type })
            assertEquals(listOf(client.requests.single().sessionId), client.cancelled)
            assertFalse(turns.busy)

            // The next turn replays the partial text: a cancelled turn still
            // counts as an assistant turn.
            client.hangAfter = null
            turns
                .runTurn(
                    client,
                    "next",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            assertEquals(
                listOf("user|go", "assistant|${assistantHistoryContent("half an ans")}"),
                client.requests[1].history?.map { "${it.role}|${it.content}" },
            )
        }

    @Test
    fun `cancel when idle is a no-op`() =
        runBlocking {
            val client = FakeTurnClient()
            val turns = TurnManager(scope = this)

            turns.cancel()
            delay(20)

            assertEquals(emptyList<String>(), client.cancelled)
        }

    @Test
    fun `reset clears the history and the session-approved tools`() =
        runBlocking {
            val client = FakeTurnClient(listOf(text("a"), done("")))
            val turns = TurnManager(scope = this)
            turns.sessionAllowedTools += "edit_file"

            turns
                .runTurn(
                    client,
                    "one",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}
            turns.reset()
            turns
                .runTurn(
                    client,
                    "two",
                    PermissionMode.DEFAULT,
                    TaskMode.WORK,
                    WORKSPACE,
                ).collect {}

            assertNull(client.requests[1].history)
            assertNull(client.requests[1].sessionAllowedTools)
        }
}
