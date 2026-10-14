// The failure: "Please list the files in the directory you are in." returned a
// green chip, a duration, and no files. The proxy had sent the listing in
// `data` and the client dropped it.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AtlasJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

/** tool_result as the proxy sends it: the tool's own output nested in `data`. */
private fun payload(inner: String): JsonElement =
    element("""{"tool":"list_directory","success":true,"data":$inner}""")

class ToolSummaryTest {
    @Test
    fun `answers a directory listing with the entries`() {
        val summary =
            summarizeToolResult(
                "list_directory",
                payload(
                    """{"entries":[{"name":"app.py","type":"file"},{"name":"src","type":"dir"}]}""",
                ),
            )

        assertNotNull(summary)
        assertTrue(summary!!.contains("2 entries"), summary)
        assertTrue(summary.contains("app.py"), summary)
        assertTrue(summary.contains("src/"), summary)
    }

    @Test
    fun `caps a large listing instead of flooding the chip`() {
        val entries = (0 until 40).joinToString(",") { """{"name":"f$it.py","type":"file"}""" }
        val summary = summarizeToolResult("list_directory", payload("""{"entries":[$entries]}"""))

        assertNotNull(summary)
        assertTrue(summary!!.contains("40 entries"), summary)
        assertTrue(summary.contains("+28 more"), summary)
        assertEquals(1, summary.split("\n").size)
    }

    @Test
    fun `says so when a directory is empty rather than rendering nothing`() {
        assertEquals(
            "empty directory",
            summarizeToolResult("list_directory", payload("""{"entries":[]}""")),
        )
    }

    @Test
    fun `counts search matches`() {
        assertEquals(
            "3 matches",
            summarizeToolResult("search_files", payload("""{"matches":[1,2,3]}""")),
        )
        assertEquals("1 match", summarizeToolResult("search_files", payload("""{"matches":[1]}""")))
        assertEquals(
            "no matches",
            summarizeToolResult("search_files", payload("""{"matches":[]}""")),
        )
    }

    @Test
    fun `falls back to the generic keys the TUI already uses`() {
        assertEquals("ok", summarizeToolResult("run_command", payload("""{"stdout":"ok"}""")))
        assertEquals(
            "3 lines",
            summarizeToolResult("read_file", payload("""{"content":"a\nb\nc"}""")),
        )
    }

    @Test
    fun `returns null for a shape it does not know`() {
        // Degrades to the old chip rather than inventing a summary.
        assertNull(summarizeToolResult("future_tool", payload("""{"weird":true}""")))
        assertNull(summarizeToolResult("x", null))
        assertNull(summarizeToolResult("x", payload("""{"stdout":"   "}""")))
    }

    @Test
    fun `reads a payload that is not nested, as an older proxy sends it`() {
        // The reference client falls back to the outer object when `data` is
        // absent at the top level.
        assertEquals(
            "4 lines",
            summarizeToolResult(
                "read_file",
                AtlasJson.parseToJsonElement("""{"content":"a\nb\nc\nd"}"""),
            ),
        )
    }
}
