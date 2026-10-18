// The mismatch heuristic: which calls are watched, what each kind of change
// proves, and when the detector stays quiet.
//
// The verdicts are a table because every row is the same question asked with
// other stats; the detector cases are separate, because each one drives a
// sequence of calls against a scripted filesystem and the sequence is the test.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AtlasJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private fun args(json: String): JsonElement = AtlasJson.parseToJsonElement(json)

private val ABSENT = PathStat(exists = false)

private fun at(mtimeMs: Long): PathStat = PathStat(exists = true, mtimeMs = mtimeMs)

/** A detector over a scripted stat table: tests mutate the table between the
 * call and the result, which is what the real filesystem does. */
private class Harness {
    val stats = mutableMapOf<String, PathStat>()
    var fired = 0

    val detector =
        MismatchDetector(
            stat = { path -> stats[path] ?: ABSENT },
            onMismatch = { fired += 1 },
            settleMs = 0,
        )
}

class WorkspaceMismatchTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("paths")
    fun `names the paths a file op touches, and null for anything else`(
        name: String,
        tool: String,
        argsJson: String,
        want: FileOpPaths?,
    ) {
        assertEquals(want, fileOpPaths(tool, args(argsJson)), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verdicts")
    fun `reads a pre-op and post-op stat as a verdict`(
        name: String,
        kind: FileOpKind,
        pre: PathStat,
        post: PathStat,
        preDest: PathStat?,
        postDest: PathStat?,
        want: Verdict,
    ) {
        assertEquals(want, fileOpVerdict(kind, pre, post, preDest, postDest), name)
    }

    @Test
    fun `does not fire when the write shows up locally`() =
        runBlocking {
            val harness = Harness()

            harness.detector.recordToolCall("write_file", args("""{"path":"a.py","content":"x"}"""))
            harness.stats["a.py"] = at(1) // the file appeared
            harness.detector.recordToolResult("write_file", success = true)

            assertEquals(0, harness.fired)
        }

    @Test
    fun `fires once when a successful write never lands locally`() =
        runBlocking {
            val harness = Harness()

            harness.detector.recordToolCall("write_file", args("""{"path":"a.py","content":"x"}"""))
            harness.detector.recordToolResult("write_file", success = true)
            assertEquals(1, harness.fired)

            // A second contradiction stays silent — the warning is one-shot.
            harness.detector.recordToolCall("write_file", args("""{"path":"b.py","content":"y"}"""))
            harness.detector.recordToolResult("write_file", success = true)

            assertEquals(1, harness.fired)
        }

    @Test
    fun `ignores a failed result, which applied nothing to verify`() =
        runBlocking {
            val harness = Harness()

            harness.detector.recordToolCall("write_file", args("""{"path":"a.py","content":"x"}"""))
            harness.detector.recordToolResult("write_file", success = false)

            assertEquals(0, harness.fired)
        }

    @Test
    fun `matches results to calls FIFO per tool name`() =
        runBlocking {
            val harness = Harness()
            harness.stats["a.py"] = at(1)
            harness.stats["b.py"] = at(1)

            harness.detector.recordToolCall("edit_file", args("""{"path":"a.py"}"""))
            harness.detector.recordToolCall("edit_file", args("""{"path":"b.py"}"""))
            harness.stats["a.py"] = at(2) // the first edit landed
            harness.stats.remove("b.py") // the second target vanished — a real contradiction

            harness.detector.recordToolResult("edit_file", success = true) // a.py -> match
            assertEquals(0, harness.fired)

            harness.detector.recordToolResult("edit_file", success = true) // b.py gone -> mismatch
            assertEquals(1, harness.fired)
        }

    @Test
    fun `stays silent when an edited file keeps an equal mtime`() =
        runBlocking {
            val harness = Harness()
            harness.stats["a.py"] = at(1)

            harness.detector.recordToolCall("edit_file", args("""{"path":"a.py"}"""))
            // The stat is unchanged: same mtime, which a coarse filesystem can
            // stamp on a real change. Never a warning.
            harness.detector.recordToolResult("edit_file", success = true)

            assertEquals(0, harness.fired)
        }

    @Test
    fun `stays silent on an inconclusive verdict`() =
        runBlocking {
            val harness = Harness()

            // An edit of a file this workspace never had.
            harness.detector.recordToolCall("edit_file", args("""{"path":"ghost.py"}"""))
            harness.detector.recordToolResult("edit_file", success = true)

            assertEquals(0, harness.fired)
        }

    @Test
    fun `verifies both ends of a move`() =
        runBlocking {
            val harness = Harness()
            harness.stats["a.py"] = at(1)

            harness.detector.recordToolCall(
                "move_file",
                args("""{"source":"a.py","destination":"lib/a.py"}"""),
            )
            harness.stats.remove("a.py")
            harness.stats["lib/a.py"] = at(2)
            harness.detector.recordToolResult("move_file", success = true)
            assertEquals(0, harness.fired)

            harness.detector.recordToolCall(
                "move_file",
                args("""{"source":"lib/a.py","destination":"lib/b.py"}"""),
            )
            // Nothing moved locally.
            harness.detector.recordToolResult("move_file", success = true)
            assertEquals(1, harness.fired)
        }

    @Test
    fun `a denied call consumes its pending op, so the next allowed one pairs correctly`() =
        runBlocking {
            val harness = Harness()
            harness.stats["a.py"] = at(1)
            harness.stats["b.py"] = at(1)

            // A denied edit captured pre-op state, but the proxy emits
            // permission_denied and NO tool_result.
            harness.detector.recordToolCall("edit_file", args("""{"path":"a.py"}"""))
            harness.detector.recordDenied("edit_file")

            // The allowed edit of b.py must pair with ITS pre-op state, not a.py's.
            harness.detector.recordToolCall("edit_file", args("""{"path":"b.py"}"""))
            harness.stats["b.py"] = at(2)
            harness.detector.recordToolResult("edit_file", success = true)

            assertEquals(0, harness.fired)
        }

    @Test
    fun `a denial with nothing pending is a no-op`() {
        val harness = Harness()

        harness.detector.recordDenied("edit_file")

        assertEquals(0, harness.fired)
    }

    @Test
    fun `reset drops pending pre-op state`() =
        runBlocking {
            val harness = Harness()

            harness.detector.recordToolCall("write_file", args("""{"path":"a.py","content":"x"}"""))
            harness.detector.reset()
            harness.detector.recordToolResult("write_file", success = true) // nothing to check

            assertEquals(0, harness.fired)
        }

    @Test
    fun `a tool that is not a file op is never watched`() =
        runBlocking {
            val harness = Harness()

            harness.detector.recordToolCall("read_file", args("""{"path":"a.py"}"""))
            harness.detector.recordToolResult("read_file", success = true)

            assertEquals(0, harness.fired)
        }

    companion object {
        @JvmStatic
        fun paths(): List<Arguments> =
            listOf(
                Arguments.of(
                    "write_file carries one path",
                    "write_file",
                    """{"path":"a.py","content":"x"}""",
                    FileOpPaths("a.py"),
                ),
                Arguments.of(
                    "edit_file carries one path",
                    "edit_file",
                    """{"path":"b.py"}""",
                    FileOpPaths("b.py"),
                ),
                Arguments.of(
                    "structural_edit carries one path",
                    "structural_edit",
                    """{"path":"c.py"}""",
                    FileOpPaths("c.py"),
                ),
                Arguments.of(
                    "insert_after carries one path",
                    "insert_after",
                    """{"path":"g.py"}""",
                    FileOpPaths("g.py"),
                ),
                Arguments.of(
                    "replace_lines carries one path",
                    "replace_lines",
                    """{"path":"h.py"}""",
                    FileOpPaths("h.py"),
                ),
                Arguments.of(
                    "delete_file carries one path",
                    "delete_file",
                    """{"path":"d.py"}""",
                    FileOpPaths("d.py"),
                ),
                Arguments.of(
                    "move_file carries a source and a destination",
                    "move_file",
                    """{"source":"a.py","destination":"lib/a.py"}""",
                    FileOpPaths("a.py", "lib/a.py"),
                ),
                Arguments.of(
                    "run_command touches no file",
                    "run_command",
                    """{"command":"ls"}""",
                    null,
                ),
                Arguments.of(
                    "read_file touches no file",
                    "read_file",
                    """{"path":"a.py"}""",
                    null,
                ),
                Arguments.of(
                    "a path that is not a string",
                    "write_file",
                    """{"path":42}""",
                    null,
                ),
                Arguments.of(
                    "a move with no destination",
                    "move_file",
                    """{"source":"a.py"}""",
                    null,
                ),
                Arguments.of(
                    "no args at all",
                    "write_file",
                    "null",
                    null,
                ),
            )

        @JvmStatic
        fun verdicts(): List<Arguments> =
            listOf(
                Arguments.of(
                    "write: a new file appearing is a match",
                    FileOpKind.WRITE,
                    ABSENT,
                    at(2),
                    null,
                    null,
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "write: staying absent is a mismatch",
                    FileOpKind.WRITE,
                    ABSENT,
                    ABSENT,
                    null,
                    null,
                    Verdict.MISMATCH,
                ),
                Arguments.of(
                    "write: an overwrite with a bumped mtime is a match",
                    FileOpKind.WRITE,
                    at(1),
                    at(2),
                    null,
                    null,
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "write: an equal mtime is inconclusive, never a wrong mount",
                    FileOpKind.WRITE,
                    at(1),
                    at(1),
                    null,
                    null,
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "edit: a changed mtime is a match",
                    FileOpKind.EDIT,
                    at(1),
                    at(2),
                    null,
                    null,
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "edit: an equal mtime is inconclusive",
                    FileOpKind.EDIT,
                    at(1),
                    at(1),
                    null,
                    null,
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "edit: a vanished file is a mismatch",
                    FileOpKind.EDIT,
                    at(1),
                    ABSENT,
                    null,
                    null,
                    Verdict.MISMATCH,
                ),
                Arguments.of(
                    "edit: a file this workspace never had is inconclusive",
                    FileOpKind.EDIT,
                    ABSENT,
                    at(2),
                    null,
                    null,
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "delete: disappearance is a match",
                    FileOpKind.DELETE,
                    at(1),
                    ABSENT,
                    null,
                    null,
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "delete: the file still being there is a mismatch",
                    FileOpKind.DELETE,
                    at(1),
                    at(1),
                    null,
                    null,
                    Verdict.MISMATCH,
                ),
                Arguments.of(
                    "delete: a file that was already absent is inconclusive",
                    FileOpKind.DELETE,
                    ABSENT,
                    ABSENT,
                    null,
                    null,
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "move: source gone and destination present is a match",
                    FileOpKind.MOVE,
                    at(1),
                    ABSENT,
                    ABSENT,
                    at(2),
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "move: source unmoved is a mismatch",
                    FileOpKind.MOVE,
                    at(1),
                    at(1),
                    ABSENT,
                    ABSENT,
                    Verdict.MISMATCH,
                ),
                Arguments.of(
                    "move: a source that was never here is inconclusive",
                    FileOpKind.MOVE,
                    ABSENT,
                    ABSENT,
                    ABSENT,
                    ABSENT,
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "move: both present (copy-like state) is inconclusive",
                    FileOpKind.MOVE,
                    at(1),
                    at(1),
                    ABSENT,
                    at(2),
                    Verdict.INCONCLUSIVE,
                ),
                Arguments.of(
                    "edit: a missing mtime is read as changed, conservatively",
                    FileOpKind.EDIT,
                    PathStat(exists = true),
                    at(2),
                    null,
                    null,
                    Verdict.MATCH,
                ),
                Arguments.of(
                    "write: a missing post-op mtime is read as changed too",
                    FileOpKind.WRITE,
                    at(1),
                    PathStat(exists = true),
                    null,
                    null,
                    Verdict.MATCH,
                ),
            )
    }
}
