package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.OpenCodeEventStream
import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.review.ReviewCoordinator
import ai.opencode.ide.jetbrains.session.SessionManager
import com.google.gson.Gson
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

/** Exercises the actual HTTP/SSE client, including V2 events without a location or item payload. */
class ReviewEventFlowTest : BasePlatformTestCase() {
    private lateinit var root: File
    private lateinit var server: FakeOpenCodeServer
    private lateinit var stream: OpenCodeEventStream
    private lateinit var coordinator: ReviewCoordinator
    private val gson = Gson()
    private val uiCallbacks = ConcurrentLinkedQueue<Runnable>()
    private val notifications = CopyOnWriteArrayList<Notification>()
    private val reviews = CopyOnWriteArrayList<List<DiffEntry>>()
    private val errors = CopyOnWriteArrayList<Throwable>()

    override fun setUp() {
        super.setUp()
        root = createTempDirectory("opencode-event-flow-").toFile()
        File(root, "review.txt").writeText("new\n")
        server = FakeOpenCodeServer()
        server.start()
        val response = gson.toJson(mapOf("data" to listOf(mapOf(
            "file" to "review.txt", "patch" to "@@ -1 +1 @@\n-old\n+new\n",
            "additions" to 1, "deletions" to 1, "status" to "modified"
        ))))
        server.respondTo("msg_first", response)
        server.respondTo("msg_second", response)
        server.respondErrorTo("msg_cancelled")
        val manager = project.service<SessionManager>()
        manager.configureWorkspaceRoot(root.path)
        val viewer = object : DiffViewerService(project) {
            override fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int?) {
                reviews.add(entries)
            }
        }
        val client = OpenCodeApiClient("127.0.0.1", server.port)
        coordinator = ReviewCoordinator(project, { manager }, { viewer }, { client }, { root.path }) {
            uiCallbacks.add(it)
        }
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                if (notification.groupId == "OpenCode") notifications.add(notification)
            }
        })
        val token = coordinator.currentGeneration()
        stream = client.createEventStream(root.path, { coordinator.handle(it, token) }, { errors.add(it) })
        stream.connect()
        assertTrue("The SSE subscription must be ready", server.waitForSubscriber())
    }

    override fun tearDown() {
        try {
            stream.disconnect()
            coordinator.reset()
            notifications.forEach(Notification::expire)
            server.stop()
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testCancelledInputCannotBecomeTheReviewedTurn() {
        enqueue("msg_cancelled")
        enqueue("msg_second")
        event("session.inbox.cancelled", mapOf("sessionID" to "ses_main", "inboxID" to "msg_cancelled"))
        start()
        deliver("msg_second")
        finish()

        awaitReview()

        assertEquals(listOf("msg_second"), server.requestedTurns)
        assertEquals("new\n", reviews.single().single().diff.after)
    }

    fun testDeliveryOrderSelectsTheFirstUserMessageWithinOneBusyCycle() {
        enqueue("msg_first")
        enqueue("msg_second")
        enqueue("msg_system", type = "synthetic")
        start()
        deliver("msg_system")
        deliver("msg_second")
        deliver("msg_first")
        finish()

        awaitReview()

        assertEquals(listOf("msg_second"), server.requestedTurns)
        assertEquals(1, reviews.size)
    }

    fun testQueuedInputSurvivesThePreviousTurnEnding() {
        enqueue("msg_first")
        start()
        deliver("msg_first")
        enqueue("msg_second")
        finish()
        awaitReview()

        start()
        deliver("msg_second")
        finish()
        awaitReview(count = 2)

        assertEquals(listOf("msg_first", "msg_second"), server.requestedTurns)
    }

    fun testDeliveryCanIdentifyATurnBeforeTheBusyNotification() {
        enqueue("msg_first")
        deliver("msg_first")
        start()
        finish()

        awaitReview()

        assertEquals(listOf("msg_first"), server.requestedTurns)
    }

    fun testUnscopedAndOtherWorkspaceExecutionsCannotCaptureTheTurn() {
        start("ses_unknown")
        enqueue("msg_foreign", session = "ses_foreign", directory = root.path + "-other")
        start("ses_foreign")
        enqueue("msg_first")
        start()
        deliver("msg_first")
        finish("ses_foreign")
        finish()

        awaitReview()

        assertEquals(listOf("msg_first"), server.requestedTurns)
    }

    fun testResetDiscardsAQueuedCompletionAndDiff() {
        enqueue("msg_first")
        start()
        deliver("msg_first")
        finish()
        waitUntil { uiCallbacks.size >= 2 }

        coordinator.reset()
        drainUi()

        assertTrue(notifications.isEmpty())
        assertTrue(reviews.isEmpty())
    }

    fun testResetDiscardsAQueuedMissingTurnWarning() {
        enqueue("msg_system", type = "synthetic")
        start()
        finish()
        waitUntil { uiCallbacks.size >= 2 }

        coordinator.reset()
        drainUi()

        assertTrue(notifications.isEmpty())
        assertTrue(server.requestedTurns.isEmpty())
    }

    fun testCurrentCompletionAndMissingTurnWarningsRemainVisible() {
        enqueue("msg_system", type = "synthetic")
        start()
        finish()
        waitUntil { uiCallbacks.size >= 2 }
        drainUi()

        assertEquals(setOf("OpenCode Task Completed", "OpenCode Review Unavailable"),
            notifications.map { it.title }.toSet())
    }

    private fun enqueue(
        id: String, type: String = "user", session: String = "ses_main", directory: String = root.path
    ) {
        event("session.inbox.enqueued", mapOf(
            "sessionID" to session, "inboxID" to id, "item" to mapOf("type" to type)
        ), directory)
    }

    private fun deliver(id: String) {
        event("session.inbox.delivered", mapOf("sessionID" to "ses_main", "inboxID" to id))
    }

    private fun start(session: String = "ses_main") {
        event("session.execution.started", mapOf("sessionID" to session), directory = null)
    }

    private fun finish(session: String = "ses_main") {
        event("session.execution.succeeded", mapOf("sessionID" to session), directory = null)
    }

    private fun event(type: String, data: Map<String, Any>, directory: String? = root.path) {
        val payload = mutableMapOf<String, Any>("type" to type, "data" to data)
        if (directory != null) payload["location"] = mapOf("directory" to directory)
        server.broadcast(gson.toJson(payload))
    }

    private fun awaitReview(count: Int = 1) {
        waitUntil {
            drainUi()
            reviews.size >= count
        }
        assertTrue("The stream must remain healthy", errors.isEmpty())
        assertTrue(notifications.none { it.title == "OpenCode Review Unavailable" })
    }

    private fun drainUi() {
        while (true) (uiCallbacks.poll() ?: break).run()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("The expected event flow did not complete", condition())
    }
}
