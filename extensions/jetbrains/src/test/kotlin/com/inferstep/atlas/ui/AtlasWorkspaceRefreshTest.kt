package com.inferstep.atlas.ui

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/**
 * The proxy writes through its bind mount; the plugin only asks the IDE to
 * look again. This exercises that adapter with a real file outside the test
 * project, which is the case a new file in the workspace is: the VFS has
 * never seen the path.
 */
class AtlasWorkspaceRefreshTest : BasePlatformTestCase() {
    fun testRefreshesANewFileIntoTheVfs() {
        val root = Files.createTempDirectory("atlas-vfs-refresh").toFile()
        try {
            val file =
                root.resolve("new.py").apply {
                    writeText("print(1)\n")
                }

            refreshWorkspacePath(root.path, "new.py")

            val virtual = LocalFileSystem.getInstance().findFileByPath(file.path)
            assertNotNull("the new file must be in the VFS after the refresh", virtual)
            assertEquals("print(1)\n", VfsUtilCore.loadText(virtual!!))
        } finally {
            root.deleteRecursively()
        }
    }
}
