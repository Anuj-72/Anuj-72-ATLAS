// The failure: "Please list the files in the directory you are in." returned a
// green chip, a duration, and no files. The proxy had sent the listing in
// `data` and the client dropped it.
//
// One table, because every case here is the same question asked with other
// values: given a tool_result payload, what single line does the chip show —
// or does it say nothing, which is what an unknown shape gets.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AtlasJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

/** tool_result as the proxy sends it: the tool's own output nested in `data`. */
private fun payload(
    tool: String,
    inner: String,
): JsonElement = element("""{"tool":"$tool","success":true,"data":$inner}""")

/** [count] file entries, as `list_directory` returns them. */
private fun fileEntries(count: Int): String =
    (0 until count).joinToString(",") { """{"name":"f$it.py","type":"file"}""" }

class ToolSummaryTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("summaries")
    fun `summarizes a tool result, and says nothing for a shape it does not know`(
        name: String,
        tool: String,
        result: JsonElement?,
        want: String?,
    ) {
        assertEquals(want, summarizeToolResult(tool, result), name)
    }

    companion object {
        @JvmStatic
        fun summaries(): List<Arguments> =
            listOf(
                Arguments.of(
                    "an empty directory says so rather than rendering nothing",
                    "list_directory",
                    payload("list_directory", """{"entries":[]}"""),
                    "empty directory",
                ),
                Arguments.of(
                    "a listing counts the entries and marks a directory",
                    "list_directory",
                    payload(
                        "list_directory",
                        """{"entries":[{"name":"app.py","type":"file"},""" +
                            """{"name":"src","type":"dir"}]}""",
                    ),
                    "2 entries: app.py, src/",
                ),
                Arguments.of(
                    "a listing past the cap is cut, and the cut is counted",
                    "list_directory",
                    payload("list_directory", """{"entries":[${fileEntries(40)}]}"""),
                    "40 entries: f0.py, f1.py, f2.py, f3.py, f4.py, f5.py, f6.py, " +
                        "f7.py, f8.py, f9.py, f10.py, f11.py (+28 more)",
                ),
                Arguments.of(
                    "three matches",
                    "search_files",
                    payload("search_files", """{"matches":[1,2,3]}"""),
                    "3 matches",
                ),
                Arguments.of(
                    "one match",
                    "search_files",
                    payload("search_files", """{"matches":[1]}"""),
                    "1 match",
                ),
                Arguments.of(
                    "no matches",
                    "search_files",
                    payload("search_files", """{"matches":[]}"""),
                    "no matches",
                ),
                Arguments.of(
                    "the generic stdout key the TUI reads",
                    "run_command",
                    payload("run_command", """{"stdout":"ok"}"""),
                    "ok",
                ),
                Arguments.of(
                    "content of several lines becomes its count",
                    "read_file",
                    payload("read_file", """{"content":"a\nb\nc"}"""),
                    "3 lines",
                ),
                Arguments.of(
                    "a shape this client does not know",
                    "future_tool",
                    payload("future_tool", """{"weird":true}"""),
                    null,
                ),
                Arguments.of(
                    "a result that never arrived",
                    "read_file",
                    null,
                    null,
                ),
                Arguments.of(
                    "a generic key holding only space",
                    "run_command",
                    payload("run_command", """{"stdout":"   "}"""),
                    null,
                ),
                Arguments.of(
                    "a payload that is not nested, as an older proxy sends it",
                    "read_file",
                    AtlasJson.parseToJsonElement("""{"content":"a\nb\nc\nd"}"""),
                    "4 lines",
                ),
            )
    }
}
