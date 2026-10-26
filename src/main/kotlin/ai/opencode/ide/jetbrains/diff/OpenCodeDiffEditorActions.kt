package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.ReviewResult
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

class AcceptFromDiffEditorAction(private val entry: DiffEntry, private val onApplied: () -> Unit) : AnAction() {
    init {
        templatePresentation.text = "Accept"
        templatePresentation.description = "Accept this change (git add)"
        templatePresentation.icon = AllIcons.Actions.Checked
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sessionManager = project.service<SessionManager>()

        if (sessionManager.isPending(entry)) {
            // Use the callback to navigate after accept completes
            sessionManager.acceptDiff(entry) { result ->
                if (project.isDisposed) return@acceptDiff
                when (result) {
                    ReviewResult.Applied -> onApplied()
                    ReviewResult.Stale -> Messages.showWarningDialog(
                        project, "Review is no longer current for ${entry.file}. Refresh the review.", "Accept Failed"
                    )
                    is ReviewResult.Failed -> Messages.showWarningDialog(
                        project, "Could not stage ${entry.file}.\n${result.message}", "Accept Failed"
                    )
                }
            }
        } else {
            Messages.showWarningDialog(project, "Review is no longer current for ${entry.file}.", "Accept Failed")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null && project.service<SessionManager>().isPending(entry)
        e.presentation.isEnabled = enabled
    }
}

class RejectFromDiffEditorAction(private val entry: DiffEntry, private val onApplied: () -> Unit) : AnAction() {
    init {
        templatePresentation.text = "Reject"
        templatePresentation.description = "Reject this change (restore original)"
        templatePresentation.icon = AllIcons.Actions.Rollback
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val sessionManager = project.service<SessionManager>()
        if (!sessionManager.isPending(entry)) return

        // Build confirmation message based on file state
        val isNewFile = entry.isNewFile
        
        val message = buildString {
            if (isNewFile) {
                append("Delete new file '${entry.file}'?\n\n")
                append("This file was created by OpenCode and will be removed.")
            } else {
                append("Reject changes to '${entry.file}'?\n\n")
                append("The file will be restored to its state before OpenCode modified it.")
            }
            
        }

        val confirm = Messages.showYesNoDialog(
            project,
            message,
            "Confirm Reject",
            Messages.getQuestionIcon()
        )

        if (confirm != Messages.YES) return

        // Use the callback to navigate after reject completes (no more ProgressManager race)
        sessionManager.rejectDiff(entry) { result ->
            if (project.isDisposed) return@rejectDiff
            when (result) {
                ReviewResult.Applied -> onApplied()
                ReviewResult.Stale -> Messages.showWarningDialog(
                    project, "Review is no longer current for ${entry.file}. Refresh the review.", "Reject Failed"
                )
                is ReviewResult.Failed -> Messages.showWarningDialog(
                    project, "Could not restore ${entry.file}.\n${result.message}", "Reject Failed"
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && project.service<SessionManager>().isPending(entry)
    }
}
