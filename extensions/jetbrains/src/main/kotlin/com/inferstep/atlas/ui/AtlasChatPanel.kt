// The ATLAS chat panel: a renderer of ChatUiState and nothing else.
//
// Everything the panel shows is decided in session/ChatSession, which is free
// of Compose and of com.intellij, so the conversation semantics are tested
// under plain JUnit and this file only has to lay them out.
//
// Drawn with Compose foundation plus Jewel's Text and Markdown renderer. There
// is no Material dependency: the IDE provides Jewel, and the tool window is
// expected to look like the IDE's own panels.
package com.inferstep.atlas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.inferstep.atlas.client.AtlasClient
import com.inferstep.atlas.client.AtlasConnection
import com.inferstep.atlas.protocol.PermissionMode
import com.inferstep.atlas.protocol.TaskMode
import com.inferstep.atlas.session.AtlasTurnClient
import com.inferstep.atlas.session.ChatSession
import com.inferstep.atlas.session.ChatUiState
import com.inferstep.atlas.session.PendingPermission
import com.inferstep.atlas.session.PermissionChoice
import com.inferstep.atlas.session.TranscriptEntry
import com.inferstep.atlas.session.commandOf
import com.inferstep.atlas.session.isOneTimeOnly
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.jewel.intui.markdown.bridge.ProvideMarkdownStyling
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.ui.component.Text
import javax.swing.UIManager

private val ERROR_COLOR = Color(0xFFCC3333)

/**
 * The chat panel for one project.
 *
 * The connection comes from the environment (see [AtlasConnection]): the
 * settings UI and secure token storage are Stage 5, so this stage reads what
 * every other ATLAS client reads, and shows the URL it is talking to.
 */
@Composable
fun atlasChatPanel(
    project: Project,
    workingDir: String,
) {
    val scope = rememberCoroutineScope()
    val connection = remember { AtlasConnection.fromEnvironment() }
    val client = remember { AtlasClient(connection.url, connection.token) }
    val adapter = remember { AtlasTurnClient(client) }
    val session = remember { ChatSession(adapter, adapter, scope, workingDir) }
    val state by session.state.collectAsState()

    // The Ktor client owns a connection pool; close it with the panel.
    DisposableEffect(client) {
        onDispose { client.close() }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("ATLAS — ${connection.url}")
        modeControls(state, session)
        transcript(state, Modifier.fillMaxWidth().weight(1f))
        pendingPrompts(state, session)
        composer(state, session, scope)
    }
}

/** The two controls the user operates. Nothing here reads the message text. */
@Composable
private fun modeControls(
    state: ChatUiState,
    session: ChatSession,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Task:")
            choiceChip("Work", state.taskMode == TaskMode.WORK) {
                session.setTaskMode(TaskMode.WORK)
            }
            choiceChip("Question", state.taskMode == TaskMode.QUESTION) {
                session.setTaskMode(TaskMode.QUESTION)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Permissions:")
            PermissionMode.entries.forEach { mode ->
                choiceChip(modeLabel(mode), state.permissionMode == mode) {
                    session.setPermissionMode(mode)
                }
            }
        }
    }
}

/** The wire name of a mode, which is also the clearest label for it. */
private fun modeLabel(mode: PermissionMode): String =
    when (mode) {
        PermissionMode.DEFAULT -> {
            "default"
        }

        PermissionMode.ACCEPT_EDITS -> {
            "accept-edits"
        }

        PermissionMode.YOLO -> {
            "yolo"
        }
    }

@Composable
private fun choiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val text = if (selected) "[$label]" else label
    Text(text, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun transcript(
    state: ChatUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.entries.forEach { entry ->
            transcriptRow(entry)
        }
    }
}

@Composable
private fun transcriptRow(entry: TranscriptEntry) {
    when (entry) {
        is TranscriptEntry.User -> {
            Text("You: ${entry.text}")
        }

        is TranscriptEntry.Assistant -> {
            Markdown(entry.text)
        }

        is TranscriptEntry.Tool -> {
            Text(toolLine(entry))
        }

        is TranscriptEntry.Notice -> {
            noticeLine(entry)
        }

        is TranscriptEntry.Done -> {
            Text(doneLine(entry))
        }
    }
}

/** An error is the client's own words about a failure, so it is marked. */
@Composable
private fun noticeLine(entry: TranscriptEntry.Notice) {
    if (entry.isError) {
        Text("ATLAS error: ${entry.text}", color = ERROR_COLOR)
    } else {
        Text(entry.text)
    }
}

/** A tool chip: what ran, how it went, and the one-line answer it produced. */
private fun toolLine(entry: TranscriptEntry.Tool): String {
    val mark =
        when (entry.success) {
            true -> {
                "done"
            }

            false -> {
                "failed"
            }

            null -> {
                "running"
            }
        }
    val summary = entry.summary?.let { " — $it" }.orEmpty()
    return "tool ${entry.name} ($mark)$summary"
}

/** The banner that says how the run ended — never just the summary (#236). */
private fun doneLine(entry: TranscriptEntry.Done): String {
    val outcome = entry.outcome
    val reason = if (outcome.reason.isEmpty()) "" else " (${outcome.reason})"
    return "Done — ${outcome.label}$reason: ${outcome.text}"
}

@Composable
private fun pendingPrompts(
    state: ChatUiState,
    session: ChatSession,
) {
    state.pending.forEach { prompt ->
        permissionCard(prompt, session)
    }
}

/**
 * The approval card.
 *
 * The whole command is shown: approval is the only per-command control, and a
 * truncated detail hides the end of a chain. A deletion gets no "allow for
 * session" button, which is the UI half of the rule the flow enforces.
 */
@Composable
private fun permissionCard(
    prompt: PendingPermission,
    session: ChatSession,
) {
    val request = prompt.request
    val heading = request.message.ifEmpty { "${request.toolName} needs approval" }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading)
        request.canonicalPath?.let { path ->
            Text(path)
        }
        commandOf(request.args)?.let { command ->
            Text(command)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            actionText("Allow once") {
                session.answer(prompt.id, PermissionChoice.ALLOW_ONCE)
            }
            if (!isOneTimeOnly(request)) {
                actionText("Allow for session") {
                    session.answer(prompt.id, PermissionChoice.ALLOW_SESSION)
                }
            }
            actionText("Deny") {
                session.answer(prompt.id, PermissionChoice.DENY)
            }
        }
    }
}

@Composable
private fun actionText(
    label: String,
    onClick: () -> Unit,
) {
    Text(label, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun composer(
    state: ChatUiState,
    session: ChatSession,
    scope: CoroutineScope,
) {
    var draft by remember { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicTextField(
            value = draft,
            onValueChange = { typed -> draft = typed },
            modifier = Modifier.fillMaxWidth(0.8f),
            // The IDE's own field foreground, so the text is readable in light
            // and dark themes alike.
            textStyle = TextStyle(color = ideColor("TextField.foreground")),
        )
        if (state.running) {
            actionText("Cancel") { session.cancel() }
        } else {
            sendAction(draft = draft, scope = scope, session = session) { draft = "" }
        }
    }
}

@Composable
private fun sendAction(
    draft: String,
    scope: CoroutineScope,
    session: ChatSession,
    onSent: () -> Unit,
) {
    actionText("Send") {
        onSent()
        scope.launch { session.send(draft) }
    }
}

/** A Swing UI-default colour as a Compose one; the IDE theme owns the value. */
private fun ideColor(key: String): Color {
    val awt = UIManager.getColor(key) ?: return Color.Unspecified
    return Color(awt.red, awt.green, awt.blue, awt.alpha)
}
