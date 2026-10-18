// The predicted diff a permission prompt can show: which tools name a path,
// and what the post-state is for each of them.
//
// One table per tool, because every case in a table is the same question asked
// with other arguments: given this tool, these args and this local file, what
// diff — or none, which is what an unpredictable call gets. A port of
// extensions/vscode/test/editPreview.test.ts.
package com.inferstep.atlas.session

import com.inferstep.atlas.protocol.AtlasJson
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

private fun args(json: String): JsonElement = AtlasJson.parseToJsonElement(json)

private fun pred(
    path: String,
    kind: EditPredictionKind,
    left: String,
    right: String,
    approximate: Boolean,
    note: String? = null,
): EditPrediction = EditPrediction(path, kind, left, right, approximate, note)

private const val SERVER_SIDE_NOTE = "predicted client-side — tree-sitter runs server-side"

/** The python fixture: a decorated function with a sibling after it. */
private val PY =
    listOf(
        "import os",
        "",
        "@app.route('/dash')",
        "@login_required",
        "def dashboard():",
        "    users = get_users()",
        "",
        "    return render(users)",
        "",
        "def other():",
        "    pass",
    ).joinToString("\n")

/** The html fixture: doctype, head, and a body holding two nested divs. */
private val HTML =
    listOf(
        "<!DOCTYPE html>",
        "<html>",
        "<head><title>t</title></head>",
        "<body>",
        "  <div class=\"outer\"><div>inner</div></div>",
        "</body>",
        "</html>",
    ).joinToString("\n")

class EditPreviewTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    fun `names the path of a file-edit tool, and null for anything else`(
        name: String,
        tool: String,
        argsJson: String,
        want: String?,
    ) {
        assertEquals(want, editTargetPath(tool, args(argsJson)), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeFile")
    fun `write_file shows the current file against the new content`(
        name: String,
        argsJson: String,
        current: String?,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("write_file", args(argsJson), current), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editFile")
    fun `edit_file splices old_str, or falls back to a snippet`(
        name: String,
        argsJson: String,
        current: String?,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("edit_file", args(argsJson), current), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralPython")
    fun `structural_edit splices a named python node, or says it could not`(
        name: String,
        argsJson: String,
        current: String,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("structural_edit", args(argsJson), current), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralHtml")
    fun `structural_edit splices a top-level html element, or says it could not`(
        name: String,
        argsJson: String,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("structural_edit", args(argsJson), HTML), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("insertAfter")
    fun `insert_after splices after a line number, exactly`(
        name: String,
        argsJson: String,
        current: String?,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("insert_after", args(argsJson), current), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("replaceLines")
    fun `replace_lines replaces an inclusive range, exactly`(
        name: String,
        argsJson: String,
        current: String?,
        want: EditPrediction?,
    ) {
        assertEquals(want, predictEdit("replace_lines", args(argsJson), current), name)
    }

    companion object {
        @JvmStatic
        fun targets(): List<Arguments> =
            listOf(
                Arguments.of(
                    "write_file",
                    "write_file",
                    """{"path":"a.py","content":"x"}""",
                    "a.py",
                ),
                Arguments.of("edit_file", "edit_file", """{"path":"a.py"}""", "a.py"),
                Arguments.of("structural_edit", "structural_edit", """{"path":"a.py"}""", "a.py"),
                Arguments.of("insert_after", "insert_after", """{"path":"f.py"}""", "f.py"),
                Arguments.of("replace_lines", "replace_lines", """{"path":"f.py"}""", "f.py"),
                Arguments.of(
                    "run_command is not a file edit",
                    "run_command",
                    """{"command":"ls"}""",
                    null,
                ),
                Arguments.of(
                    "move_file is not a file edit",
                    "move_file",
                    """{"source":"a"}""",
                    null,
                ),
                Arguments.of(
                    "delete_file is not a file edit",
                    "delete_file",
                    """{"path":"a.py"}""",
                    null,
                ),
                Arguments.of("no args at all", "write_file", "null", null),
                Arguments.of("a path that is not a string", "write_file", """{"path":42}""", null),
            )

        @JvmStatic
        fun writeFile(): List<Arguments> =
            listOf(
                Arguments.of(
                    "a new file shows empty on the left and its content on the right",
                    """{"path":"new.py","content":"x = 1\n"}""",
                    null,
                    pred("new.py", EditPredictionKind.FILE, "", "x = 1\n", false, "new file"),
                ),
                Arguments.of(
                    "an existing file shows the old content against the new",
                    """{"path":"a.py","content":"new"}""",
                    "old",
                    pred("a.py", EditPredictionKind.FILE, "old", "new", false),
                ),
                Arguments.of(
                    "a missing content field is not previewable",
                    """{"path":"a.py"}""",
                    "old",
                    null,
                ),
            )

        @JvmStatic
        fun editFile(): List<Arguments> =
            listOf(
                Arguments.of(
                    "a unique old_str is an exact single-occurrence replacement",
                    """{"path":"a.txt","old_str":"aa","new_str":"bb"}""",
                    "aa x cc",
                    pred("a.txt", EditPredictionKind.FILE, "aa x cc", "bb x cc", false),
                ),
                Arguments.of(
                    "two matches without replace_all is approximate — the proxy rejects it",
                    """{"path":"a.txt","old_str":"aa","new_str":"bb"}""",
                    "aa x aa",
                    pred(
                        "a.txt",
                        EditPredictionKind.FILE,
                        "aa x aa",
                        "bb x aa",
                        true,
                        "old_str matches 2 locations — the proxy requires a unique match and " +
                            "will reject this edit (showing the first occurrence)",
                    ),
                ),
                Arguments.of(
                    "replace_all replaces every occurrence",
                    """{"path":"a.txt","old_str":"aa","new_str":"bb","replace_all":true}""",
                    "aa x aa",
                    pred("a.txt", EditPredictionKind.FILE, "aa x aa", "bb x bb", false),
                ),
                Arguments.of(
                    "replace_all as the string \"true\" is no boolean: one replacement, approximate",
                    """{"path":"a.txt","old_str":"aa","new_str":"bb","replace_all":"true"}""",
                    "aa x aa",
                    pred(
                        "a.txt",
                        EditPredictionKind.FILE,
                        "aa x aa",
                        "bb x aa",
                        true,
                        "old_str matches 2 locations — the proxy requires a unique match and " +
                            "will reject this edit (showing the first occurrence)",
                    ),
                ),
                Arguments.of(
                    "new_str is spliced literally, with no \$-pattern semantics",
                    """{"path":"a.txt","old_str":"X","new_str":"${'$'}& ${'$'}' ${'$'}1"}""",
                    "a X b",
                    pred("a.txt", EditPredictionKind.FILE, "a X b", "a \$& \$' \$1 b", false),
                ),
                Arguments.of(
                    "old_str not found: a snippet diff plus the note",
                    """{"path":"a.txt","old_str":"zz","new_str":"bb"}""",
                    "aa x aa",
                    pred(
                        "a.txt",
                        EditPredictionKind.SNIPPET,
                        "zz",
                        "bb",
                        true,
                        "old_str not found in the file — showing old_str vs new_str " +
                            "(the proxy will likely reject this edit)",
                    ),
                ),
                Arguments.of(
                    "file missing locally: a snippet diff plus the workspace note",
                    """{"path":"a.txt","old_str":"zz","new_str":"bb"}""",
                    null,
                    pred(
                        "a.txt",
                        EditPredictionKind.SNIPPET,
                        "zz",
                        "bb",
                        true,
                        "target file not found in this workspace — showing old_str vs new_str",
                    ),
                ),
            )

        @JvmStatic
        fun structuralPython(): List<Arguments> =
            listOf(
                Arguments.of(
                    "function: replaces the def block including its decorators",
                    """{"path":"app.py","selector":"function:dashboard",""" +
                        """"content":"@app.route('/dash')\ndef dashboard():\n    return quick()"}""",
                    PY,
                    pred(
                        "app.py",
                        EditPredictionKind.FILE,
                        PY,
                        listOf(
                            "import os",
                            "",
                            "@app.route('/dash')",
                            "def dashboard():",
                            "    return quick()",
                            "",
                            "def other():",
                            "    pass",
                        ).joinToString("\n"),
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "function: matches async def too",
                    """{"path":"a.py","selector":"function:fetch",""" +
                        """"content":"async def fetch():\n    return 2"}""",
                    "async def fetch():\n    return await go()\n\nprint(1)",
                    pred(
                        "a.py",
                        EditPredictionKind.FILE,
                        "async def fetch():\n    return await go()\n\nprint(1)",
                        "async def fetch():\n    return 2\n\nprint(1)",
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "class: replaces the class body and keeps its surroundings",
                    """{"path":"a.py","selector":"class:B","content":"class B(Base):\n    x = 9"}""",
                    "class A:\n    def m(self):\n        pass\n\nclass B(Base):\n    x = 1\n\ntail = 2",
                    pred(
                        "a.py",
                        EditPredictionKind.FILE,
                        "class A:\n    def m(self):\n        pass\n\nclass B(Base):\n    x = 1\n\ntail = 2",
                        "class A:\n    def m(self):\n        pass\n\nclass B(Base):\n    x = 9\n\ntail = 2",
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "no match: the whole file against the content, labelled approximate",
                    """{"path":"app.py","selector":"function:missing",""" +
                        """"content":"def missing():\n    pass"}""",
                    PY,
                    pred(
                        "app.py",
                        EditPredictionKind.FILE,
                        PY,
                        "def missing():\n    pass",
                        true,
                        "selector 'function:missing' not matched locally — showing the proposed " +
                            "content against the whole file",
                    ),
                ),
                Arguments.of(
                    "two definitions with the name: the first is replaced, and the note says so",
                    """{"path":"a.py","selector":"function:f","content":"def f():\n    return 9"}""",
                    "def f():\n    pass\n\ndef f():\n    return 1",
                    pred(
                        "a.py",
                        EditPredictionKind.FILE,
                        "def f():\n    pass\n\ndef f():\n    return 1",
                        "def f():\n    return 9\n\ndef f():\n    return 1",
                        true,
                        "2 definitions named 'f' — the first one is shown replaced",
                    ),
                ),
            )

        @JvmStatic
        fun structuralHtml(): List<Arguments> =
            listOf(
                Arguments.of(
                    "<body>: the whole element is replaced, nesting ignored",
                    """{"path":"i.html","selector":"<body>","content":"<body><p>new</p></body>"}""",
                    pred(
                        "i.html",
                        EditPredictionKind.FILE,
                        HTML,
                        listOf(
                            "<!DOCTYPE html>",
                            "<html>",
                            "<head><title>t</title></head>",
                            "<body><p>new</p></body>",
                            "</html>",
                        ).joinToString("\n"),
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "<div>: the matching close is found past the nested same-tag",
                    """{"path":"i.html","selector":"<div>","content":"<div>x</div>"}""",
                    pred(
                        "i.html",
                        EditPredictionKind.FILE,
                        HTML,
                        listOf(
                            "<!DOCTYPE html>",
                            "<html>",
                            "<head><title>t</title></head>",
                            "<body>",
                            "  <div>x</div>",
                            "</body>",
                            "</html>",
                        ).joinToString("\n"),
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "<html>: a leading doctype in the content is stripped (the proxy's quirk)",
                    """{"path":"i.html","selector":"<html>",""" +
                        """"content":"<!DOCTYPE html>\n<html><body>n</body></html>"}""",
                    pred(
                        "i.html",
                        EditPredictionKind.FILE,
                        HTML,
                        "<!DOCTYPE html>\n<html><body>n</body></html>",
                        true,
                        SERVER_SIDE_NOTE,
                    ),
                ),
                Arguments.of(
                    "tag absent: the whole-file approximate fallback",
                    """{"path":"i.html","selector":"<table>","content":"<table></table>"}""",
                    pred(
                        "i.html",
                        EditPredictionKind.FILE,
                        HTML,
                        "<table></table>",
                        true,
                        "selector '<table>' not matched locally — showing the proposed content " +
                            "against the whole file",
                    ),
                ),
            )

        @JvmStatic
        fun insertAfter(): List<Arguments> =
            listOf(
                Arguments.of(
                    "inserts after a 1-based line number, and is exact",
                    """{"path":"f.py","line":2,"content":"X"}""",
                    "a\nb\nc\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\n",
                        "a\nb\nX\nc\n",
                        false,
                        "inserted after line 2",
                    ),
                ),
                Arguments.of(
                    "line 0 is the top of the file",
                    """{"path":"f.py","line":0,"content":"X"}""",
                    "a\nb\nc\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\n",
                        "X\na\nb\nc\n",
                        false,
                        "inserted at top of file",
                    ),
                ),
                Arguments.of(
                    "a line past the end appends",
                    """{"path":"f.py","line":99,"content":"X"}""",
                    "a\nb\nc\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\n",
                        "a\nb\nc\nX\n",
                        false,
                        "inserted after line 99",
                    ),
                ),
                Arguments.of(
                    "preserves a missing trailing newline",
                    """{"path":"f.py","line":1,"content":"X"}""",
                    "a\nb",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb",
                        "a\nX\nb",
                        false,
                        "inserted after line 1",
                    ),
                ),
                Arguments.of(
                    "does not double a newline the content already carries",
                    """{"path":"f.py","line":1,"content":"X\n"}""",
                    "a\nb\nc\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\n",
                        "a\nX\nb\nc\n",
                        false,
                        "inserted after line 1",
                    ),
                ),
                Arguments.of(
                    "handles multi-line content",
                    """{"path":"f.py","line":1,"content":"X\nY"}""",
                    "a\nb\nc\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\n",
                        "a\nX\nY\nb\nc\n",
                        false,
                        "inserted after line 1",
                    ),
                ),
                Arguments.of(
                    "no line at all is not previewable",
                    """{"path":"f.py","content":"X"}""",
                    "a\n",
                    null,
                ),
                Arguments.of(
                    "a line sent as a string is not a number",
                    """{"path":"f.py","line":"2","content":"X"}""",
                    "a\n",
                    null,
                ),
                Arguments.of(
                    "a fractional line is not a line",
                    """{"path":"f.py","line":1.5,"content":"X"}""",
                    "a\n",
                    null,
                ),
                Arguments.of(
                    "a negative line is refused",
                    """{"path":"f.py","line":-1,"content":"X"}""",
                    "a\n",
                    null,
                ),
                Arguments.of(
                    "a file that is not readable locally",
                    """{"path":"f.py","line":1,"content":"X"}""",
                    null,
                    null,
                ),
            )

        @JvmStatic
        fun replaceLines(): List<Arguments> =
            listOf(
                Arguments.of(
                    "replaces an inclusive range, exactly",
                    """{"path":"f.py","start_line":2,"end_line":3,"content":"X\nY"}""",
                    "a\nb\nc\nd\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\nd\n",
                        "a\nX\nY\nd\n",
                        false,
                        "replaced lines 2-3",
                    ),
                ),
                Arguments.of(
                    "replaces a single line",
                    """{"path":"f.py","start_line":1,"end_line":1,"content":"Z"}""",
                    "a\nb\nc\nd\n",
                    pred(
                        "f.py",
                        EditPredictionKind.FILE,
                        "a\nb\nc\nd\n",
                        "Z\nb\nc\nd\n",
                        false,
                        "replaced line 1",
                    ),
                ),
                Arguments.of(
                    "preserves a missing trailing newline",
                    """{"path":"f.py","start_line":2,"end_line":2,"content":"X"}""",
                    "a\nb",
                    pred("f.py", EditPredictionKind.FILE, "a\nb", "a\nX", false, "replaced line 2"),
                ),
                Arguments.of(
                    "declines a range that starts before the file",
                    """{"path":"f.py","start_line":0,"end_line":1,"content":"X"}""",
                    "a\nb\nc\nd\n",
                    null,
                ),
                Arguments.of(
                    "declines an inverted range",
                    """{"path":"f.py","start_line":3,"end_line":2,"content":"X"}""",
                    "a\nb\nc\nd\n",
                    null,
                ),
                Arguments.of(
                    "declines a range past the end",
                    """{"path":"f.py","start_line":2,"end_line":99,"content":"X"}""",
                    "a\nb\nc\nd\n",
                    null,
                ),
                Arguments.of(
                    "declines a missing end line",
                    """{"path":"f.py","start_line":1,"content":"X"}""",
                    "a\nb\nc\nd\n",
                    null,
                ),
                Arguments.of(
                    "declines a line number sent as a string",
                    """{"path":"f.py","start_line":"1","end_line":2,"content":"X"}""",
                    "a\nb\nc\nd\n",
                    null,
                ),
                Arguments.of(
                    "declines a file that is not readable locally",
                    """{"path":"f.py","start_line":1,"end_line":2,"content":"X"}""",
                    null,
                    null,
                ),
            )
    }
}
