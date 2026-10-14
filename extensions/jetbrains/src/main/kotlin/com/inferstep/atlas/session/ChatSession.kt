// The chat, as state: the transcript, the open permission prompts, the two
// controls the user operates (permission mode and Work/Question), and the
// send/cancel/reset actions over [TurnManager] and [PermissionFlow].
//
// Free of com.intellij and of Compose, so the whole conversation — streaming
// text, tool chips, the done banner, answered prompts — is exercised under
// plain JUnit. The tool window is a renderer of [ChatUiState] and nothing else.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.ChatEvent
import com.inferstep.atlas.protocol.DonePayload
import com.inferstep.atlas.protocol.ErrorPayload
import com.inferstep.atlas.protocol.PermissionDeniedPayload
import com.inferstep.atlas.protocol.PermissionMode
import com.inferstep.atlas.protocol.PermissionRequestPayload
import com.inferstep.atlas.protocol.TaskMode
import com.inferstep.atlas.protocol.TextPayload
import com.inferstep.atlas.protocol.ToolCallPayload
import com.inferstep.atlas.protocol.ToolResultPayload
import com.inferstep.atlas.protocol.payloadOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TEXT_EVENT = "text"
private const val TOOL_CALL_EVENT = "tool_call"
private const val TOOL_RESULT_EVENT = "tool_result"
private const val PERMISSION_REQUEST_EVENT = "permission_request"
private const val PERMISSION_DENIED_EVENT = "permission_denied"
private const val DONE_EVENT = "done"
private const val ERROR_EVENT = "error"

/**
 * One line of the conversation.
 *
 * Every entry carries an id so Compose can key a list without re-creating rows
 * as text streams in.
 */
sealed interface TranscriptEntry {
    val id: Long

    /** What the user asked. */
    data class User(
        override val id: Long,
        val text: String,
    ) : TranscriptEntry

    /** Assistant prose, updated in place as deltas arrive. */
    data class Assistant(
        override val id: Long,
        val text: String,
    ) : TranscriptEntry

    /** A tool call, with the one-line summary of what it returned. */
    data class Tool(
        override val id: Long,
        val name: String,
        val summary: String?,
        /** Null while the call is still running. */
        val success: Boolean?,
    ) : TranscriptEntry

    /** Something the client says about itself, never the model's words. */
    data class Notice(
        override val id: Long,
        val text: String,
        val isError: Boolean = false,
    ) : TranscriptEntry

    /** How the run ended: the summary, the status and the reason (#236). */
    data class Done(
        override val id: Long,
        val outcome: DoneOutcome,
    ) : TranscriptEntry
}

/** Everything the panel renders. */
data class ChatUiState(
    val entries: List<TranscriptEntry> = emptyList(),
    val pending: List<PendingPermission> = emptyList(),
    val running: Boolean = false,
    /** What the next turn declares: work (default) or question. */
    val taskMode: TaskMode = TaskMode.WORK,
    val permissionMode: PermissionMode = PermissionMode.DEFAULT,
)

/**
 * One conversation against one proxy.
 *
 * [send] suspends for the length of the turn; the caller launches it in the
 * scope it wants the turn's lifetime tied to, and [cancel] stops it from
 * anywhere. The posted permission decisions run on [scope].
 */
class ChatSession(
    private val client: TurnClient,
    private val poster: PermissionPoster,
    private val scope: CoroutineScope,
    /** The client's workspace root, sent as `working_dir`. */
    private val workingDir: String,
) : PermissionUi {
    private val turns = TurnManager(scope)
    private val permissions = PermissionFlow(turns.sessionAllowedTools, this, scope)

    private val mutableState = MutableStateFlow(ChatUiState())

    /** The transcript and controls, as the panel renders them. */
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var nextId = 1L
    private var cancelRequested = false

    /** The next turn's declaration. Nothing reads the message text. */
    fun setTaskMode(mode: TaskMode) {
        mutableState.update { it.copy(taskMode = mode) }
    }

    /** The permission mode the proxy enforces for the turn. */
    fun setPermissionMode(mode: PermissionMode) {
        mutableState.update { it.copy(permissionMode = mode) }
    }

    /**
     * Answer an open prompt. Unknown and already-settled ids are no-ops, which
     * is what a card left over from a finished turn looks like.
     */
    fun answer(
        id: Long,
        choice: PermissionChoice,
    ) {
        permissions.settleById(id, choice)
    }

    /**
     * Run one turn: append the user's message, stream the events into the
     * transcript, and end the turn whatever happens.
     *
     * Returns immediately for an empty message or while a turn is in flight.
     */
    suspend fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty() || turns.busy) {
            return
        }

        val mode = mutableState.value.permissionMode
        val taskMode = mutableState.value.taskMode
        cancelRequested = false
        var sawDone = false

        append(TranscriptEntry.User(nextId++, text))
        mutableState.update { it.copy(running = true) }

        try {
            turns
                .runTurn(client, text, mode, taskMode, workingDir)
                .collect { event ->
                    if (event.type == DONE_EVENT) {
                        sawDone = true
                    }
                    apply(event)
                }
        } catch (e: CancellationException) {
            // The scope went away mid-turn (the panel was disposed); the
            // finally below still closes the turn's prompts.
            throw e
        } catch (e: Throwable) {
            // The turn failed before or during the stream: a 401 when the
            // proxy has a token and this client does not, a 503 while the lens
            // cannot score, a connection that never opened. The panel has to
            // say so — an escaping failure here would leave the user's message
            // on screen with nothing after it, which reads as a hang.
            //
            // AtlasApiError's message is its stable code plus the detail
            // (`unauthorized: ...`), so reporting it verbatim names the cause.
            append(
                TranscriptEntry.Notice(
                    id = nextId++,
                    text = "The turn failed: ${e.message.orEmpty()}",
                    isError = true,
                ),
            )
        } finally {
            permissions.endTurn()
            if (cancelRequested && !sawDone) {
                append(TranscriptEntry.Notice(nextId++, "Turn cancelled."))
            }
            mutableState.update { it.copy(running = false, pending = emptyList()) }
        }
    }

    /** Stop the in-flight turn. A no-op when nothing is running. */
    fun cancel() {
        if (!turns.busy) {
            return
        }
        cancelRequested = true
        turns.cancel()
    }

    /** Start a fresh conversation: drop the transcript, the history and the
     * session-approved tools. */
    fun reset() {
        if (turns.busy) {
            return
        }
        turns.reset()
        mutableState.update {
            it.copy(entries = emptyList(), pending = emptyList(), running = false)
        }
    }

    // ------------------------------------------------------------------
    // PermissionUi — the panel is a renderer, so the prompts are state too.
    // ------------------------------------------------------------------

    override fun onPrompt(pending: PendingPermission) {
        mutableState.update { it.copy(pending = it.pending + pending) }
    }

    override fun onDismiss(
        pending: PendingPermission,
        reason: DismissReason,
    ) {
        mutableState.update { state ->
            state.copy(pending = state.pending.filterNot { it === pending })
        }
    }

    override fun onAutoAllow(toolName: String) {
        append(TranscriptEntry.Notice(nextId++, "$toolName is allowed for this session."))
    }

    override fun onPostError(
        toolName: String,
        error: Throwable,
    ) {
        append(
            TranscriptEntry.Notice(
                id = nextId++,
                text = "Could not answer the $toolName prompt: ${error.message.orEmpty()}",
                isError = true,
            ),
        )
    }

    /** Apply one stream event to the transcript. */
    private fun apply(event: ChatEvent) {
        when (event.type) {
            TEXT_EVENT -> {
                appendText(event.payloadOrNull<TextPayload>()?.content.orEmpty())
            }

            TOOL_CALL_EVENT -> {
                val call = event.payloadOrNull<ToolCallPayload>()
                append(
                    TranscriptEntry.Tool(
                        id = nextId++,
                        name = call?.name.orEmpty(),
                        summary = null,
                        success = null,
                    ),
                )
            }

            TOOL_RESULT_EVENT -> {
                val result = event.payloadOrNull<ToolResultPayload>()
                append(
                    TranscriptEntry.Tool(
                        id = nextId++,
                        name = result?.tool.orEmpty(),
                        summary = summarizeToolResult(result?.tool.orEmpty(), result?.data),
                        success = result?.success,
                    ),
                )
            }

            PERMISSION_REQUEST_EVENT -> {
                event
                    .payloadOrNull<PermissionRequestPayload>()
                    ?.let { permissions.handleRequest(poster, turns.sessionId, it) }
            }

            PERMISSION_DENIED_EVENT -> {
                permissions.handleDenied(
                    event.payloadOrNull<PermissionDeniedPayload>()?.tool.orEmpty(),
                )
            }

            DONE_EVENT -> {
                append(
                    TranscriptEntry.Done(
                        id = nextId++,
                        outcome = doneOutcome(event.payloadOrNull<DonePayload>()),
                    ),
                )
            }

            ERROR_EVENT -> {
                append(
                    TranscriptEntry.Notice(
                        id = nextId++,
                        text = event.payloadOrNull<ErrorPayload>()?.error.orEmpty(),
                        isError = true,
                    ),
                )
            }

            // llm_*, v3_*, plan_* and the intervention events are progress,
            // not conversation. A later stage renders them; ignoring them here
            // is what keeps the transcript readable.
            else -> {
                Unit
            }
        }
    }

    /** Trail a delta onto the streaming assistant entry, or start one. */
    private fun appendText(delta: String) {
        if (delta.isEmpty()) {
            return
        }
        val last = mutableState.value.entries.lastOrNull() as? TranscriptEntry.Assistant
        if (last == null) {
            append(TranscriptEntry.Assistant(nextId++, delta))
        } else {
            mutableState.update { state ->
                state.copy(
                    entries = state.entries.dropLast(1) + last.copy(text = last.text + delta),
                )
            }
        }
    }

    private fun append(entry: TranscriptEntry) {
        mutableState.update { it.copy(entries = it.entries + entry) }
    }
}
