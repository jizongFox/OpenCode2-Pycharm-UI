package ai.opencode.ide.jetbrains

import ai.opencode.ide.jetbrains.util.PathUtil

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

/**
 * Sends file path references to the OpenCode terminal.
 * 
 * - Editor selection: @path#start-end (NO content, just the reference)
 * - Project View selection: @path for each file/directory
 *
 * If the OpenCode terminal is not open yet, the plugin will create/focus it
 * when the IDE can safely create a terminal, then append the reference.
 */
class SendSelectionToTerminalAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val openCodeService = project.service<OpenCodeService>()

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val textToSend = StringBuilder()

        if (editor != null) {
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val relativePath = file?.let { openCodeService.toReferencePath(it.path) }

            if (relativePath != null) {
                val referencePath = formatPathReference(relativePath)
                if (editor.selectionModel.hasSelection()) {
                    // Editor selection: only @path#start-end (NO content)
                    val selectionModel = editor.selectionModel
                    val document = editor.document
                    val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
                    val endLine = selectionEndLine(
                        document,
                        selectionModel.selectionStart,
                        selectionModel.selectionEnd
                    )

                    textToSend.append("@$referencePath#$startLine-$endLine")
                } else {
                    // No selection: share current file reference
                    textToSend.append("@$referencePath")
                }
            }
        } else if (virtualFiles != null && virtualFiles.isNotEmpty()) {
            // Project View selection: @path per file/directory
            val focusedFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val normalizedSelection = normalizeProjectSelection(virtualFiles, focusedFile)
            for (file in normalizedSelection) {
                val relativePath = openCodeService.toReferencePath(file.path) ?: continue
                val referencePath = formatPathReference(relativePath)
                textToSend.append("@$referencePath ")
            }
        }

        if (textToSend.isNotEmpty()) {
            val payload = textToSend.toString().trimEnd() + " "
            openCodeService.focusOrCreateTerminalAndPaste(payload)
        } else {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification(
                    "Cannot add context",
                    "The current editor or Project View selection has no saved file.",
                    NotificationType.WARNING
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val editor = e.getData(CommonDataKeys.EDITOR)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)

        val hasEditorContext = editor != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
        val hasFileSelection = virtualFiles != null && virtualFiles.isNotEmpty()

        // Always available even if terminal isn't created yet
        e.presentation.isEnabledAndVisible = hasEditorContext || hasFileSelection
    }

    private fun formatPathReference(path: String): String {
        return if (path.contains(" ")) {
            "\"$path\""
        } else {
            path
        }
    }

    private fun normalizeProjectSelection(
        selectedFiles: Array<VirtualFile>,
        focusedFile: VirtualFile?
    ): List<VirtualFile> {
        val all = LinkedHashMap<String, VirtualFile>()
        selectedFiles.forEach { all[it.path] = it }

        // Some IDE versions may provide descendants in VIRTUAL_FILE_ARRAY when a directory is selected.
        // If focused item is a directory but missing from array, add it to preserve directory semantics.
        if (focusedFile != null && focusedFile.isDirectory) {
            all.putIfAbsent(focusedFile.path, focusedFile)
        }

        val sorted = all.values.sortedBy { it.path.length }
        val kept = mutableListOf<VirtualFile>()
        for (candidate in sorted) {
            val candidatePath = PathUtil.toSystemIndependentPath(candidate.path)
            val hasParentSelected = kept.any { parent ->
                val parentPath = PathUtil.toSystemIndependentPath(parent.path)
                candidatePath == parentPath || candidatePath.startsWith("$parentPath/")
            }
            if (!hasParentSelected) {
                kept.add(candidate)
            }
        }
        return kept
    }
}

internal fun selectionEndLine(document: Document, selectionStart: Int, selectionEnd: Int): Int {
    val inclusiveEnd = if (selectionEnd > selectionStart) selectionEnd - 1 else selectionEnd
    return document.getLineNumber(inclusiveEnd.coerceAtLeast(0)) + 1
}
