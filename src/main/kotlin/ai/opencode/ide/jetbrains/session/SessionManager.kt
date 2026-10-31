package ai.opencode.ide.jetbrains.session

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.util.PathUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.nio.file.Paths
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/** Stores review entries for the current workspace; OpenCode V2 supplies each turn's file contents. */
open class SessionManager(private val project: Project) : Disposable {
    private val logger = Logger.getInstance(SessionManager::class.java)
    @Volatile private var workspaceRoot: String? = project.basePath
    @Volatile private var turnNumber = 0
    @Volatile private var isBusy = false
    private val userEditedFiles = ConcurrentHashMap.newKeySet<String>()
    private val pendingDiffs = Collections.synchronizedMap(LinkedHashMap<String, DiffEntry>())

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (!isBusy || CommandProcessor.getInstance().currentCommandName.isNullOrEmpty()) return
            val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
            PathUtil.relativizeWithinRoot(workspaceRoot, file.path)?.let(userEditedFiles::add)
        }
    }

    init {
        if (ApplicationManager.getApplication() != null) {
            EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
        }
    }

    @Synchronized
    open fun onTurnStart(): Boolean {
        if (isBusy) return false
        isBusy = true
        turnNumber++
        userEditedFiles.clear()
        return true
    }

    @Synchronized
    open fun onTurnEnd(): TurnSnapshot? {
        if (!isBusy) return null
        isBusy = false
        return TurnSnapshot(turnNumber, userEditedFiles.toSet())
    }

    @Synchronized
    fun getProcessedDiffs(serverDiffs: List<FileDiff>, snapshot: TurnSnapshot): List<DiffEntry> {
        if (snapshot.turnNumber != turnNumber || isBusy) return emptyList()
        val entries = serverDiffs.mapNotNull { diff ->
            val relative = PathUtil.relativizeWithinRoot(workspaceRoot, diff.file) ?: return@mapNotNull null
            if (relative in snapshot.userEditedFiles) return@mapNotNull null
            val normalized = diff.copy(file = relative)
            if (normalized.before == normalized.after && normalized.status !in listOf("added", "deleted")) {
                return@mapNotNull null
            }
            DiffEntry(relative, normalized)
        }
        entries.forEach { pendingDiffs[it.file] = it }
        return entries
    }

    fun acceptDiff(entry: DiffEntry, onComplete: ((ReviewResult) -> Unit)? = null) {
        AppExecutorUtil.getAppExecutorService().submit {
            val result = try {
                val root = workspaceRoot ?: error("Workspace is unavailable")
                val absPath = toAbsolutePath(entry.file) ?: error("Path is outside workspace")
                if (!isPending(entry) || !isStillCurrent(entry, absPath)) {
                    ReviewResult.Stale
                } else {
                    val cmd = GeneralCommandLine("git", "add", "--", entry.file).withWorkDirectory(root)
                    val output = CapturingProcessHandler(cmd).runProcess(5000)
                    when {
                        output.isTimeout -> ReviewResult.Failed(
                            "Git staging timed out. Check the repository and try again."
                        )
                        output.exitCode != 0 -> ReviewResult.Failed(
                            output.stderr.trim().ifEmpty {
                                "Git could not stage the file. Check the repository and file permissions."
                            }
                        )
                        else -> {
                            removePending(entry)
                            ReviewResult.Applied
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("[OpenCode] Could not stage ${entry.file}", e)
                ReviewResult.Failed(e.message ?: "Could not stage the file.")
            }
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater { cb(result) } }
        }
    }

    fun rejectDiff(entry: DiffEntry, onComplete: ((ReviewResult) -> Unit)? = null) {
        val absPath = toAbsolutePath(entry.file)
        if (absPath == null) {
            onComplete?.let { cb ->
                ApplicationManager.getApplication().invokeLater {
                    cb(ReviewResult.Failed("The file is outside the current workspace."))
                }
            }
            return
        }
        ApplicationManager.getApplication().invokeLater {
            val result = runWriteAction {
                try {
                    if (!isPending(entry) || !isStillCurrent(entry, absPath)) {
                        return@runWriteAction ReviewResult.Stale
                    }
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                    when {
                        vf != null && entry.isNewFile -> vf.delete(this@SessionManager)
                        vf != null -> VfsUtil.saveText(vf, entry.beforeContent)
                        entry.diff.status == "deleted" || entry.beforeContent.isNotEmpty() -> {
                            val file = File(absPath)
                            file.parentFile?.mkdirs()
                            file.writeText(entry.beforeContent)
                            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
                        }
                    }
                    removePending(entry)
                    ReviewResult.Applied
                } catch (e: Exception) {
                    logger.warn("[OpenCode] Could not restore ${entry.file}", e)
                    ReviewResult.Failed(e.message ?: "Could not restore the file.")
                }
            }
            onComplete?.invoke(result)
        }
    }

    fun getCurrentTurnNumber(): Int = turnNumber

    fun getDiffForFile(path: String): DiffEntry? =
        PathUtil.relativizeWithinRoot(workspaceRoot, path)?.let(pendingDiffs::get)

    fun getAllDiffEntries(): List<DiffEntry> = synchronized(pendingDiffs) { pendingDiffs.values.toList() }

    fun isPending(entry: DiffEntry): Boolean = synchronized(pendingDiffs) {
        pendingDiffs[entry.file] === entry
    }

    private fun removePending(entry: DiffEntry) = synchronized(pendingDiffs) {
        if (pendingDiffs[entry.file] === entry) pendingDiffs.remove(entry.file)
    }

    private fun isStillCurrent(entry: DiffEntry, absolutePath: String): Boolean {
        val disk = File(absolutePath).takeIf { it.isFile }?.readText()
        val document = runReadAction {
            LocalFileSystem.getInstance().findFileByPath(absolutePath)
                ?.takeIf { it.isValid }
                ?.let { FileDocumentManager.getInstance().getCachedDocument(it)?.text }
        }
        return entry.matchesCurrentContent(disk, document)
    }

    @Synchronized
    fun clear() {
        isBusy = false
        userEditedFiles.clear()
        pendingDiffs.clear()
    }

    @Synchronized
    fun configureWorkspaceRoot(path: String?) {
        val normalized = path?.let {
            try {
                PathUtil.toSystemIndependentPath(Paths.get(it).normalize().toAbsolutePath().toString())
            } catch (_: Exception) {
                null
            }
        }
        if (workspaceRoot == normalized) return
        clear()
        workspaceRoot = normalized
    }

    private fun toAbsolutePath(path: String): String? = PathUtil.resolveWithinRoot(workspaceRoot, path)

    override fun dispose() = clear()
}

data class TurnSnapshot(val turnNumber: Int, val userEditedFiles: Set<String>)
