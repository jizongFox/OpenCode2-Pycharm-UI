package ai.opencode.ide.jetbrains

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

/**
 * OpenCode sidebar icon.
 *
 * The sidebar icon opens the OpenCode terminal in the editor area.
 */
class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(JPanel(), "", false)
        toolWindow.contentManager.addContent(content)

        // An IDE-restored visible tool window must not start a connection on startup.
        if (toolWindow.isVisible) toolWindow.hide()

        // Listen for when this tool window is shown (sidebar icon clicked)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id == "OpenCode") {
                        tw.hide()
                        project.service<OpenCodeService>().focusOrCreateTerminal(interactive = true)
                    }
                }
            }
        )
    }
}
