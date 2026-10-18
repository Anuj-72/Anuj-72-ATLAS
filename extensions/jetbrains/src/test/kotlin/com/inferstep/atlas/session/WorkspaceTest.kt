// The local workspace: what a read and a stat return, that a path from tool
// args cannot escape the root, and the exact alignment check GET /workspace
// makes possible.
//
// The failure this exists for: the proxy was bound to ~/snake-game while the
// IDE had ~/demo2 open. Every tool call was truthful and useless, and it took
// several rounds of manual `atlas workspace align` to notice.
package com.inferstep.atlas.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

class WorkspaceTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `reads a file inside the root`() {
        File(dir, "a.py").writeText("x = 1\n")
        val workspace = LocalWorkspace(dir.absolutePath)

        assertEquals("x = 1\n", workspace.readText("a.py"))
    }

    @Test
    fun `reads a file in a subdirectory`() {
        val nested = File(dir, "src").apply { mkdirs() }
        File(nested, "a.py").writeText("nested")
        val workspace = LocalWorkspace(dir.absolutePath)

        assertEquals("nested", workspace.readText("src/a.py"))
    }

    @Test
    fun `an absent file reads as nothing`() {
        val workspace = LocalWorkspace(dir.absolutePath)

        assertNull(workspace.readText("missing.py"))
        assertNull(workspace.readText("src/missing.py"))
    }

    @Test
    fun `an absolute path is refused before any filesystem call`() {
        val outside = File(dir.parentFile, "outside-${dir.name}.py")
        outside.writeText("secret")
        try {
            val workspace = LocalWorkspace(dir.absolutePath)

            assertNull(workspace.readText(outside.absolutePath))
            assertEquals(PathStat(exists = false), workspace.stat(outside.absolutePath))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a relative path that climbs out of the root is refused`() {
        val outside = File(dir.parentFile, "outside-${dir.name}.py")
        outside.writeText("secret")
        try {
            val workspace = LocalWorkspace(dir.absolutePath)

            assertNull(workspace.readText("../${outside.name}"))
            assertEquals(PathStat(exists = false), workspace.stat("../${outside.name}"))
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a root that is null or empty reads nothing`() {
        val missingRoot = LocalWorkspace(null)
        val emptyRoot = LocalWorkspace("")

        assertNull(missingRoot.readText("a.py"))
        assertNull(emptyRoot.readText("a.py"))
        assertEquals(PathStat(exists = false), missingRoot.stat("a.py"))
    }

    @Test
    fun `stat reports existence and an mtime`() {
        val file = File(dir, "a.py")
        file.writeText("x")
        val workspace = LocalWorkspace(dir.absolutePath)

        val stat = workspace.stat("a.py")

        assertTrue(stat.exists)
        assertEquals(file.lastModified(), stat.mtimeMs)
        assertEquals(PathStat(exists = false), workspace.stat("missing.py"))
    }

    @Test
    fun `the refresh callback names the path the proxy changed`() {
        val changed = mutableListOf<String>()
        val workspace = LocalWorkspace(dir.absolutePath, onChanged = { changed += it })

        workspace.changed("src/new.py")

        assertEquals(listOf("src/new.py"), changed)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coverages")
    fun `covers is containment of a resolved path`(
        name: String,
        bound: String,
        target: String,
        want: Boolean,
    ) {
        assertEquals(want, covers(bound, target), name)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("alignments")
    fun `the proxy's host path is compared with the folder this window has open`(
        name: String,
        projectDir: String?,
        localRoot: String?,
        want: WorkspaceAlignment,
    ) {
        assertEquals(want, workspaceAlignment(projectDir, localRoot), name)
    }

    companion object {
        @JvmStatic
        fun coverages(): List<Arguments> =
            listOf(
                Arguments.of("the same path", "/home/isaac/monorepo", "/home/isaac/monorepo", true),
                Arguments.of(
                    "a folder nested inside the bound project",
                    "/home/isaac/monorepo",
                    "/home/isaac/monorepo/packages/api",
                    true,
                ),
                Arguments.of(
                    "a sibling directory",
                    "/home/isaac/sudoku-solver",
                    "/home/isaac/tic-tac-toe",
                    false,
                ),
                Arguments.of(
                    "the parent of the bound project",
                    "/home/isaac/monorepo",
                    "/home/isaac",
                    false,
                ),
                Arguments.of(
                    "a bind below the open folder",
                    "/home/isaac/monorepo/api",
                    "/home/isaac/monorepo",
                    false,
                ),
                Arguments.of(
                    "a prefix that is not a path boundary",
                    "/home/isaac/mono",
                    "/home/isaac/monorepo",
                    false,
                ),
                Arguments.of(
                    "no bound path at all",
                    "",
                    "/home/isaac/monorepo",
                    false,
                ),
            )

        @JvmStatic
        fun alignments(): List<Arguments> =
            listOf(
                Arguments.of(
                    "a host path that names another directory is a mismatch",
                    "/home/isaac/sudoku-solver",
                    "/home/isaac/tic-tac-toe",
                    WorkspaceAlignment.MISALIGNED,
                ),
                Arguments.of(
                    "the open folder covered by the proxy's project dir is aligned",
                    "/home/isaac/monorepo",
                    "/home/isaac/monorepo/packages/api",
                    WorkspaceAlignment.ALIGNED,
                ),
                Arguments.of(
                    "the same directory is aligned",
                    "/home/isaac/monorepo",
                    "/home/isaac/monorepo",
                    WorkspaceAlignment.ALIGNED,
                ),
                Arguments.of(
                    "a relative project dir is never trusted",
                    ".",
                    "/home/isaac/monorepo",
                    WorkspaceAlignment.UNKNOWN,
                ),
                Arguments.of(
                    "an empty project dir (the proxy cannot say) is unknown",
                    "",
                    "/home/isaac/monorepo",
                    WorkspaceAlignment.UNKNOWN,
                ),
                Arguments.of(
                    "a missing project dir is unknown",
                    null,
                    "/home/isaac/monorepo",
                    WorkspaceAlignment.UNKNOWN,
                ),
                Arguments.of(
                    "no folder open is unknown, not a mismatch",
                    "/home/isaac/monorepo",
                    null,
                    WorkspaceAlignment.UNKNOWN,
                ),
                Arguments.of(
                    "an empty local root is unknown",
                    "/home/isaac/monorepo",
                    "",
                    WorkspaceAlignment.UNKNOWN,
                ),
            )
    }
}
