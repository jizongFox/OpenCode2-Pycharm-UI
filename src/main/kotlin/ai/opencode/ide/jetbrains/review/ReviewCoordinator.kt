package ai.opencode.ide.jetbrains.review

import ai.opencode.ide.jetbrains.api.OpenCodeClient
import ai.opencode.ide.jetbrains.api.models.InboxCancelledEvent
import ai.opencode.ide.jetbrains.api.models.InboxDeliveredEvent
import ai.opencode.ide.jetbrains.api.models.InboxEnqueuedEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.SessionExecutionEvent
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
import java.util.concurrent.Future

/** Matches V2's busy/idle cycle to its per-turn diff endpoint. */
class ReviewCoordinator(
    private val project: Project,
    private val sessionManager: () -> SessionManager,
    private val diffViewerService: () -> DiffViewerService,
    private val client: () -> OpenCodeClient?,
    private val workspaceRoot: () -> String?,
    private val invokeLater: (Runnable) -> Unit
) {
    private data class Turn(val sessionId: String, val messageId: String? = null, val startedAt: Long? = null)

    private val logger = Logger.getInstance(ReviewCoordinator::class.java)
    private val pendingUserMessages = mutableSetOf<Pair<String, String>>()
    private var activeTurn: Turn? = null
    private var lastIdleNotification: Notification? = null
    @Volatile private var generation = 0L

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun handle(event: OpenCodeEvent, token: Long) {
        if (token != generation) return
        when (event) {
            is InboxEnqueuedEvent -> {
                if (event.data.item.type == "user") {
                    pendingUserMessages.add(event.data.sessionID to event.data.inboxID)
                }
            }
            is InboxCancelledEvent -> pendingUserMessages.remove(event.data.sessionID to event.data.inboxID)
            is InboxDeliveredEvent -> {
                val id = event.data.sessionID
                // Delivery carries only IDs. The enqueue event establishes whether it is user input.
                if (!pendingUserMessages.remove(id to event.data.inboxID)) return
                start(id)
                val turn = activeTurn ?: return
                if (turn.sessionId == id && turn.messageId == null) {
                    activeTurn = turn.copy(messageId = event.data.inboxID)
                }
            }
            is SessionExecutionEvent -> when (event.type) {
                "session.execution.started" -> start(event.data.sessionID, event.created)
                "session.execution.interrupted" -> {
                    if (event.data.reason != "shutdown") finish(event.data.sessionID)
                }
                else -> finish(event.data.sessionID)
            }
            else -> Unit
        }
    }

    @Synchronized
    fun reset() {
        generation++
        pendingUserMessages.clear()
        activeTurn = null
        sessionManager().clear()
        lastIdleNotification?.expire()
        lastIdleNotification = null
    }

    /** Reconcile missed completion events without mistaking a recoverable shutdown for an idle turn. */
    fun reconcileActiveSessions(activeIds: Set<String>, token: Long): Future<*>? {
        val (turn, api) = synchronized(this) {
            if (token != generation) return null
            val turn = activeTurn?.takeIf { it.sessionId !in activeIds } ?: return null
            turn to (client() ?: return null)
        }
        return AppExecutorUtil.getAppExecutorService().submit {
            if (token != generation) return@submit
            try {
                val state = api.getSessionState(turn.sessionId)
                synchronized(this) {
                    if (token != generation || activeTurn !== turn) return@submit
                    val root = workspaceRoot() ?: return@submit
                    if (PathUtil.toOpenCodeServerPath(state.directory) == PathUtil.toOpenCodeServerPath(root) &&
                        state.completedAfter(turn.startedAt)) finish(turn.sessionId)
                }
            } catch (e: Exception) {
                logger.warn("[OpenCode] Could not confirm whether the disconnected turn completed", e)
            }
        }
    }

    private fun start(id: String, startedAt: Long? = null) {
        val current = activeTurn
        if (current != null) {
            if (current.sessionId == id && startedAt != null) activeTurn = current.copy(startedAt = startedAt)
            return
        }
        if (sessionManager().onTurnStart()) {
            activeTurn = Turn(id, startedAt = startedAt)
        }
    }

    private fun finish(id: String) {
        val turn = activeTurn?.takeIf { it.sessionId == id } ?: return
        val snapshot = sessionManager().onTurnEnd() ?: return
        activeTurn = null
        val token = generation
        notifyTaskCompleted(token)
        // V2 persists the turn diff at idle. A short delay lets the projection finish.
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { fetch(id, turn.messageId, snapshot, token, 0) }, 200, TimeUnit.MILLISECONDS
        )
    }

    private fun fetch(id: String, messageId: String?, snapshot: TurnSnapshot, token: Long, attempt: Int) {
        if (token != generation) return
        if (messageId == null) {
            notifyReviewUnavailable(token, "Cannot identify the completed OpenCode turn. Review the files manually.")
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
            invokeForReview(token) {
                val pending = entries.filter(manager::isPending)
                if (pending.isNotEmpty()) diffViewerService().showMultiFileDiff(pending)
            }
        } catch (e: Exception) {
            logger.warn("[OpenCode] Failed to review turn ${snapshot.turnNumber}", e)
            if (token != generation) return
            if (attempt == 0) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { fetch(id, messageId, snapshot, token, 1) }, 700, TimeUnit.MILLISECONDS
                )
            } else {
                notifyReviewUnavailable(token, "Cannot load the OpenCode turn diff. Review the files manually.")
            }
        }
    }

    private fun invokeForReview(token: Long, action: () -> Unit) {
        invokeLater(Runnable {
            if (!project.isDisposed && token == generation) action()
        })
    }

    private fun notifyReviewUnavailable(token: Long, message: String) {
        invokeForReview(token) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("OpenCode")
                .createNotification("OpenCode Review Unavailable", message, NotificationType.WARNING)
                .notify(project)
        }
    }

    private fun notifyTaskCompleted(token: Long) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val message = "[$timestamp] Session is now idle. Checking for changes..."
        invokeForReview(token) {
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
        }
    }
}
