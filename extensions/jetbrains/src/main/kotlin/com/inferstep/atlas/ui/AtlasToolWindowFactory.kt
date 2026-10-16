package com.inferstep.atlas.ui

import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme

/**
 * Mounts the ATLAS chat panel.
 *
 * The panel is the only content: the tool window itself holds no state, so it
 * can be closed and reopened without losing anything the plugin cares about
 * (the transcript lives in the panel's [com.inferstep.atlas.session.ChatSession]
 * for as long as the panel does).
 *
 * `project.basePath` is sent as the turn's `working_dir`, the same value the
 * TUI sends as its cwd: on Docker installs the proxy uses it for
 * host-to-container path translation, and on bare metal file operations
 * resolve against it.
 */
class AtlasToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val panel = ComposePanel()
        panel.setContent {
            SwingBridgeTheme {
                atlasChatPanel(project, project.basePath.orEmpty())
            }
        }
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
