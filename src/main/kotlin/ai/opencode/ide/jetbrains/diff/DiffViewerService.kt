package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import java.lang.ref.WeakReference

/** Owns the current review view and its navigation, whether the IDE shows a tab or a window. */
@Service(Service.Level.PROJECT)
open class DiffViewerService(private val project: Project) : Disposable {
    private class ReviewView(val entries: List<DiffEntry>) {
        var close: () -> Unit = {}
        var isOpen: () -> Boolean = { true }
    }

    private var currentReview: ReviewView? = null

    open fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int? = null) {
        if (project.isDisposed) return
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater { showMultiFileDiff(entries, initialIndex) }
            return
        }
        closeCurrentReview()
        if (entries.isEmpty()) return
        val view = ReviewView(entries.toList())
        currentReview = view
        val requests = view.entries.mapIndexed { index, entry ->
            val type = FileTypeManager.getInstance().getFileTypeByFileName(entry.file)
            SimpleDiffRequest(
                "[${index + 1}/${entries.size}] ${entry.file}",
                DiffContentFactory.getInstance().create(project, entry.beforeContent, type),
                DiffContentFactory.getInstance().create(project, entry.diff.after, type),
                "Original", "Modified (OpenCode)"
            ).apply {
                putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(
                    AcceptFromDiffEditorAction(entry) { onEntryApplied(view, entry) },
                    RejectFromDiffEditorAction(entry) { onEntryApplied(view, entry) }
                ))
            }
        }
        val chain = SimpleDiffRequestChain(requests, (initialIndex ?: 0).coerceIn(0, requests.lastIndex))

        // Match the request chain so another plugin's Diff tab is never owned by this view.
        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                if (file is ChainDiffVirtualFile && file.chain === chain) {
                    val reference = WeakReference(file)
                    attachView(view,
                        close = { reference.get()?.let { FileEditorManager.getInstance(project).closeFile(it) } },
                        isOpen = { reference.get()?.let { FileEditorManager.getInstance(project).isFileOpen(it) } == true }
                    )
                    connection.disconnect()
                }
            }
        })
        val defaults = DiffDialogHints.DEFAULT
        // A non-null window consumer forces a dialog in DiffManager. Add it only when
        // the IDE would already choose a window for DEFAULT hints.
        val fromDialog = DialogWrapper.findInstance(IdeFocusManager.getInstance(project).focusOwner) != null
        val usesWindow = Registry.`is`("show.diff.as.frame") || fromDialog ||
            DiffUtil.getWindowMode(defaults) != WindowWrapper.Mode.FRAME
        val hints = if (!usesWindow) defaults else DiffDialogHints(defaults.mode, defaults.parent) { window ->
            val reference = WeakReference<WindowWrapper>(window)
            attachView(view,
                close = { reference.get()?.close() },
                isOpen = { reference.get()?.isDisposed == false }
            )
        }
        try {
            displayDiff(chain, hints)
        } finally {
            ApplicationManager.getApplication().invokeLater { connection.disconnect() }
        }
    }

    protected open fun displayDiff(chain: SimpleDiffRequestChain, hints: DiffDialogHints) {
        DiffManager.getInstance().showDiff(project, chain, hints)
    }

    private fun attachView(view: ReviewView, close: () -> Unit, isOpen: () -> Boolean) {
        if (currentReview === view) {
            view.close = close
            view.isOpen = isOpen
        } else close()
    }

    private fun onEntryApplied(view: ReviewView, entry: DiffEntry) {
        if (project.isDisposed || currentReview !== view || !view.isOpen()) return
        val index = view.entries.indexOfFirst { it === entry }.coerceAtLeast(0)
        val remaining = project.service<SessionManager>().getAllDiffEntries()
        showMultiFileDiff(remaining, index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)))
    }

    private fun closeCurrentReview() {
        val previous = currentReview
        currentReview = null
        previous?.close?.invoke()
    }

    override fun dispose() {
        if (ApplicationManager.getApplication().isDispatchThread) closeCurrentReview()
        else ApplicationManager.getApplication().invokeLater { closeCurrentReview() }
    }
}
