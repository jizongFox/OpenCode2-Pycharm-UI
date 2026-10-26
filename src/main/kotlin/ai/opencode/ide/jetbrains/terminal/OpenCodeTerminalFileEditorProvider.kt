package ai.opencode.ide.jetbrains.terminal

import ai.opencode.ide.jetbrains.OpenCodeService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class OpenCodeTerminalFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is OpenCodeTerminalVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(file is OpenCodeTerminalVirtualFile)
        return project.service<OpenCodeService>().createTerminalEditor(file)
    }

    override fun getEditorTypeId(): String = "opencode-terminal-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
