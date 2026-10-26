package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/** A view of the project-owned terminal. Closing or moving a tab does not stop the TUI. */
class OpenCodeTerminalFileEditor(
    private val file: OpenCodeTerminalVirtualFile,
    private val host: TerminalHost
) : UserDataHolderBase(), FileEditor {
    private val panel = JPanel(BorderLayout())
    private var disposed = false

    init {
        host.attach(file, panel)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = host.preferredFocusComponent()

    override fun getName(): String = "OpenCode Terminal"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !disposed && file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        if (!disposed) {
            disposed = true
            host.detach(panel)
        }
    }
}
