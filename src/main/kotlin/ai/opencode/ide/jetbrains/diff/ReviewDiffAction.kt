package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.ReviewResult
import ai.opencode.ide.jetbrains.session.SessionManager

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

import javax.swing.Icon

internal enum class ReviewOperation(
    val label: String,
    val description: String,
    val icon: Icon,
    val failureVerb: String
) {
    ACCEPT("Accept", "Accept this change (git add)", AllIcons.Actions.Checked, "stage"),
    REJECT("Reject", "Reject this change (restore original)", AllIcons.Actions.Rollback, "restore")
}

/** Shares review availability and completion handling; file operations stay in SessionManager. */
internal class ReviewDiffAction(
    private val entry: DiffEntry,
    private val operation: ReviewOperation,
    private val onApplied: () -> Unit
) : AnAction(operation.label, operation.description, operation.icon) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val manager = project.service<SessionManager>()
        if (!manager.isPending(entry)) {
            if (operation == ReviewOperation.ACCEPT) {
                showFailure(project, "Review is no longer current for ${entry.file}.")
            }
            return
        }
        if (operation == ReviewOperation.REJECT && !confirmReject(project)) return

        val onComplete: (ReviewResult) -> Unit = { result ->
            if (!project.isDisposed) {
                when (result) {
                    ReviewResult.Applied -> onApplied()
                    ReviewResult.Stale -> showFailure(
                        project, "Review is no longer current for ${entry.file}. Refresh the review."
                    )
                    is ReviewResult.Failed -> showFailure(
                        project, "Could not ${operation.failureVerb} ${entry.file}.\n${result.message}"
                    )
                }
            }
        }
        when (operation) {
            ReviewOperation.ACCEPT -> manager.acceptDiff(entry, onComplete)
            ReviewOperation.REJECT -> manager.rejectDiff(entry, onComplete)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && project.service<SessionManager>().isPending(entry)
    }

    private fun confirmReject(project: Project): Boolean {
        val message = if (entry.isNewFile) {
            "Delete new file '${entry.file}'?\n\nThis file was created by OpenCode and will be removed."
        } else {
            "Reject changes to '${entry.file}'?\n\n" +
                "The file will be restored to its state before OpenCode modified it."
        }
        return Messages.showYesNoDialog(
            project, message, "Confirm Reject", Messages.getQuestionIcon()
        ) == Messages.YES
    }

    private fun showFailure(project: Project, message: String) {
        Messages.showWarningDialog(project, message, "${operation.label} Failed")
    }
}
