package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView
import java.awt.BorderLayout
import java.awt.event.KeyEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/** Owns the terminal; editor tabs are disposable views of the same widget. */
class TerminalHost(
    private val project: Project,
    private val resolvePath: (String) -> String?
) : Disposable {

    private val logger = Logger.getInstance(TerminalHost::class.java)
    private val views = LinkedHashMap<JPanel, OpenCodeTerminalVirtualFile>()
    private var file: OpenCodeTerminalVirtualFile? = null
    private var widget: ShellTerminalWidget? = null
    private var readyAt: Long = 0
    private var launchDeadline: Long = 0

    fun attach(viewFile: OpenCodeTerminalVirtualFile, panel: JPanel) {
        views[panel] = viewFile
        render()
    }

    fun detach(panel: JPanel) {
        views.remove(panel)
        render()
    }

    fun preferredFocusComponent(): JComponent? = widget?.preferredFocusableComponent

    fun isOpen(): Boolean {
        val current = widget ?: return false
        val process = current.processTtyConnector?.process
        return process?.isAlive ?: (System.currentTimeMillis() < launchDeadline)
    }

    fun create(workingDirectory: String?, title: String, command: String) {
        disposeWidget()

        val newWidget = TerminalView.getInstance(project)
            .createLocalShellWidget(workingDirectory, title)
        OpenCodeTerminalLinkFilter.install(project, newWidget, resolvePath)
        newWidget.terminalPanel.addPreKeyEventHandler { event ->
            handleTerminalEscapeKey(event) {
                try {
                    newWidget.executeWithTtyConnector { connector ->
                        connector.write(ESCAPE_SEQUENCE)
                    }
                } catch (e: Exception) {
                    logger.warn("[OpenCode] Failed to send Escape to terminal", e)
                }
            }
        }
        widget = newWidget
        file = OpenCodeTerminalVirtualFile(title)
        readyAt = System.currentTimeMillis() + STARTUP_GRACE_MS
        launchDeadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        focus()
        newWidget.executeCommand(command)
    }

    /** V2 has no prompt-append HTTP route. Write a bracketed paste to the owned TUI. */
    fun appendPrompt(text: String): Boolean {
        val current = widget ?: return false
        if (System.currentTimeMillis() < readyAt || !isOpen()) return false
        return try {
            if (current.processTtyConnector?.process?.isAlive != true) return false
            current.executeWithTtyConnector { connector ->
                if (widget === current) connector.write("\u001B[200~$text\u001B[201~")
            }
            true
        } catch (e: Exception) {
            logger.debug("[OpenCode] Terminal paste unavailable: ${e.message}")
            false
        }
    }

    fun focus() {
        showEditor(requestFocus = true)
        val current = widget ?: return
        ApplicationManager.getApplication().invokeLater {
            if (widget === current) current.preferredFocusableComponent?.requestFocusInWindow()
        }
    }

    /** Reveals the editor tab without moving keyboard focus away from the source editor. */
    fun show() {
        showEditor(requestFocus = false)
    }

    private fun showEditor(requestFocus: Boolean) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { showEditor(requestFocus) }
            return
        }
        val terminalComponent = widget?.component
        if (terminalComponent?.isShowing == true) {
            // An already visible tab may live in a detached editor window. Opening
            // its file again can make the IDE reposition that window.
            if (requestFocus) {
                SwingUtilities.getWindowAncestor(terminalComponent)?.toFront()
                widget?.preferredFocusableComponent?.requestFocusInWindow()
            }
            return
        }

        val terminalFile = file ?: return
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val existing = manager.currentWindow?.takeIf { terminalFile in it.fileList }
            ?: manager.windows.firstOrNull { terminalFile in it.fileList }
        if (existing != null) {
            val previous = manager.currentWindow
            if (existing !== previous) existing.setAsCurrentWindow(requestFocus)
            manager.openFile(terminalFile, requestFocus)
            if (!requestFocus && previous != null && previous !== existing) {
                previous.setAsCurrentWindow(false)
            }
            return
        }

        val source = manager.currentWindow ?: manager.windows.firstOrNull { it.fileList.isNotEmpty() }
        if (source != null && source.fileList.isNotEmpty()) {
            // JSplitPane.HORIZONTAL_SPLIT puts the new editor to the right.
            source.split(JSplitPane.HORIZONTAL_SPLIT, true, terminalFile, requestFocus, true)
        } else {
            manager.openFile(terminalFile, requestFocus)
        }
    }

    fun reset(): CompletableFuture<Void> {
        val process = try {
            widget?.processTtyConnector?.process
        } catch (_: Exception) {
            null
        }

        disposeWidget()
        return CompletableFuture.runAsync(
            { terminateProcess(process) },
            AppExecutorUtil.getAppExecutorService()
        )
    }

    override fun dispose() {
        reset()
    }

    private fun terminateProcess(process: Process?) {
        try {
            if (process?.isAlive == true) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            }
        } catch (_: Exception) {
            // The widget may already have been disposed by the IDE.
        }
    }

    private fun disposeWidget() {
        val oldFile = file
        file = null
        try {
            widget?.dispose()
        } catch (_: Exception) {
            // Disposal is idempotent from the host's perspective.
        } finally {
            widget = null
            readyAt = 0
            launchDeadline = 0
        }
        if (oldFile != null && !project.isDisposed) {
            val close = Runnable {
                if (!project.isDisposed) FileEditorManagerEx.getInstanceEx(project).closeFile(oldFile)
            }
            if (ApplicationManager.getApplication().isDispatchThread) close.run()
            else ApplicationManager.getApplication().invokeLater(close)
        }
    }

    private fun render() {
        val update = Runnable {
            val active = views.entries.lastOrNull { it.value === file }?.key
            for ((panel, _) in views) {
                val component = if (panel === active) widget?.component else null
                val matches = panel.componentCount == 1 &&
                    (if (component == null) panel.getComponent(0) is JLabel else panel.getComponent(0) === component)
                if (!matches) {
                    panel.removeAll()
                    panel.add(component ?: JLabel(PLACEHOLDER_TEXT, SwingConstants.CENTER), BorderLayout.CENTER)
                    panel.revalidate()
                    panel.repaint()
                }
            }
        }

        if (ApplicationManager.getApplication().isDispatchThread) {
            update.run()
        } else {
            ApplicationManager.getApplication().invokeLater(update)
        }
    }

    companion object {
        const val PLACEHOLDER_TEXT = "OpenCode is open in another editor tab."
        private const val ESCAPE_SEQUENCE = "\u001B"
        private const val STARTUP_GRACE_MS = 1500L
        private const val STARTUP_TIMEOUT_MS = 30_000L
    }
}

/**
 * Gives a focused terminal ownership of plain Escape before the IDE can move
 * focus back to the editor. Modified Escape shortcuts remain available to the IDE.
 */
internal fun handleTerminalEscapeKey(event: KeyEvent, sendEscape: () -> Unit): Boolean {
    if (event.keyCode != KeyEvent.VK_ESCAPE || event.modifiersEx != 0) return false

    if (event.id == KeyEvent.KEY_PRESSED) {
        sendEscape()
    }
    event.consume()
    return true
}
