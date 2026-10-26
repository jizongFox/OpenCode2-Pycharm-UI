package ai.opencode.ide.jetbrains.review

import ai.opencode.ide.jetbrains.api.OpenCodeClient
import ai.opencode.ide.jetbrains.api.models.InboxEnqueuedEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.SessionIdleEvent
import ai.opencode.ide.jetbrains.api.models.SessionExecutionEvent
import ai.opencode.ide.jetbrains.api.models.SessionStatusEvent
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.TurnSnapshot
import ai.opencode.ide.jetbrains.util.PathUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SystemNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Matches V2's busy/idle cycle to its per-turn diff endpoint. */
class ReviewCoordinator(
    private val project: Project,
    private val sessionManager: () -> SessionManager,
    private val diffViewerService: () -> DiffViewerService,
    private val client: () -> OpenCodeClient?,
    private val workspaceRoot: () -> String?,
    private val invokeLater: (Runnable) -> Unit
) {
    private data class Turn(val messageId: String?)

    private val logger = Logger.getInstance(ReviewCoordinator::class.java)
    private val turns = mutableMapOf<String, Turn>()
    private val queuedMessages = mutableMapOf<String, String>()
    private var activeSessionId: String? = null
    private var lastIdleNotification: Notification? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun handle(event: OpenCodeEvent, token: Long) {
        if (token != generation) return
        when (event) {
            is InboxEnqueuedEvent -> {
                if (event.data.item.type != "user") return
                val id = event.data.sessionID
                val turn = turns[id]
                if (turn != null) {
                    if (turn.messageId == null) turns[id] = turn.copy(messageId = event.data.inboxID)
                } else {
                    queuedMessages.putIfAbsent(id, event.data.inboxID)
                }
            }
            is SessionStatusEvent -> when (event.data.status.type) {
                "busy" -> start(event.data.sessionID)
                "idle" -> finish(event.data.sessionID)
                else -> Unit
            }
            is SessionIdleEvent -> finish(event.data.sessionID)
            is SessionExecutionEvent -> when (event.type) {
                "session.execution.started" -> start(event.data.sessionID)
                else -> finish(event.data.sessionID)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun reset() {
        generation++
        turns.clear()
        queuedMessages.clear()
        activeSessionId = null
        sessionManager().clear()
        lastIdleNotification?.expire()
        lastIdleNotification = null
    }

    /** The V2 event stream does not replay events missed while disconnected. */
    @Synchronized
    fun reconcileActiveSessions(activeIds: Set<String>, token: Long) {
        if (token != generation) return
        activeSessionId?.takeIf { it !in activeIds }?.let(::finish)
    }

    private fun start(id: String) {
        if (activeSessionId != null && activeSessionId != id) return
        if (sessionManager().onTurnStart()) {
            activeSessionId = id
            turns[id] = Turn(queuedMessages.remove(id))
        }
    }

    private fun finish(id: String) {
        if (activeSessionId != id) return
        val snapshot = sessionManager().onTurnEnd() ?: return
        activeSessionId = null
        val turn = turns.remove(id) ?: Turn(null)
        notifyTaskCompleted()
        // V2 persists the turn diff at idle. A short delay lets the projection finish.
        val token = generation
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { fetch(id, turn.messageId, snapshot, token, 0) }, 200, TimeUnit.MILLISECONDS
        )
    }

    private fun fetch(id: String, messageId: String?, snapshot: TurnSnapshot, token: Long, attempt: Int) {
        if (token != generation) return
        if (messageId == null) {
            notifyReviewUnavailable("Cannot identify the completed OpenCode turn. Review the files manually.")
            return
        }
        val api = client() ?: return
        val root = workspaceRoot() ?: return
        try {
            val diffs = api.getSessionDiff(id, messageId)
            if (token != generation) return
            val files = diffs.mapNotNull { PathUtil.resolveWithinRoot(root, it.file)?.let(::File) }
            if (files.isNotEmpty()) LocalFileSystem.getInstance().refreshIoFiles(files, false, false, null)
            val manager = sessionManager()
            val entries = synchronized(this) {
                if (token != generation || snapshot.turnNumber != manager.getCurrentTurnNumber()) return
                manager.getProcessedDiffs(diffs, snapshot)
            }
            if (entries.isEmpty()) return
            invokeLater(Runnable {
                if (!project.isDisposed && token == generation && entries.any(manager::isPending)) {
                    diffViewerService().showMultiFileDiff(entries.filter(manager::isPending))
                }
            })
        } catch (e: Exception) {
            logger.warn("[OpenCode] Failed to review turn ${snapshot.turnNumber}", e)
            if (token != generation) return
            if (attempt == 0) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { fetch(id, messageId, snapshot, token, 1) }, 700, TimeUnit.MILLISECONDS
                )
            } else {
                notifyReviewUnavailable("Cannot load the OpenCode turn diff. Review the files manually.")
            }
        }
    }

    private fun notifyReviewUnavailable(message: String) {
        invokeLater(Runnable {
            if (!project.isDisposed) NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification("OpenCode Review Unavailable", message, NotificationType.WARNING)
                .notify(project)
        })
    }

    private fun notifyTaskCompleted() {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val message = "[$timestamp] Session is now idle. Checking for changes..."
        invokeLater(Runnable {
            if (project.isDisposed) return@Runnable
            try {
                lastIdleNotification?.expire()
                val notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup("OpenCode")
                    .createNotification("OpenCode Task Completed", message, NotificationType.INFORMATION)
                    .setImportant(true)
                lastIdleNotification = notification
                notification.notify(project)
                SystemNotifications.getInstance().notify("OpenCode", "OpenCode Task Completed", message)
            } catch (e: Throwable) {
                logger.debug("[OpenCode] Task notification failed: ${e.message}")
            }
        })
    }
}
