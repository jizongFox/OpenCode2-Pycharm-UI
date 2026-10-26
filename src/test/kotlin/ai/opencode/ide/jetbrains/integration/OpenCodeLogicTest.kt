package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import ai.opencode.ide.jetbrains.api.models.SessionExecutionEvent
import ai.opencode.ide.jetbrains.api.models.SessionIdData
import ai.opencode.ide.jetbrains.api.models.fullFileDiff
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.review.ReviewCoordinator
import ai.opencode.ide.jetbrains.runtime.OpenCodeCli
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.openapi.project.Project
import com.google.gson.GsonBuilder
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import kotlin.io.path.createTempDirectory

class OpenCodeLogicTest {
    @Test
    fun parsesFullFilePatchAndMissingFinalNewline() {
        val patch = "@@ -1 +1 @@\n-old\n+new\n\\ No newline at end of file\n"
        val diff = fullFileDiff("src/a.kt", patch, 1, 1)
        assertEquals("old\n", diff?.before)
        assertEquals("new", diff?.after)
        assertEquals(null, fullFileDiff("a.kt", "@@ -3 +3 @@\n-a\n+b\n", 1, 1))
        val added = fullFileDiff("new.kt", "@@ -0,0 +1 @@\n+hello\n", 1, 0, "added")
        assertEquals("", added?.before)
        assertEquals("hello\n", added?.after)
        assertTrue(DiffEntry("new.kt", added ?: error("Missing diff")).isNewFile)
        val gson = GsonBuilder().registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer()).create()
        val event = gson.fromJson(
            """{"type":"session.execution.succeeded","data":{"sessionID":"ses_one"}}""",
            OpenCodeEvent::class.java
        )
        assertEquals("ses_one", (event as SessionExecutionEvent).data.sessionID)
    }

    @Test
    fun reviewsOnlyTheMatchingV2TurnAndLocation() {
        val server = FakeOpenCodeServer()
        server.start()
        val root = createTempDirectory(prefix = "opencode-v2-").toFile()
        val file = File(root, "edited.kt")
        file.writeText("new\n")
        val project = Proxy.newProxyInstance(Project::class.java.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> root.path
                "isDisposed" -> false
                "toString" -> "TestProject"
                "hashCode" -> 12345
                "equals" -> false
                else -> null
            }
        } as Project
        val manager = SessionManager(project)
        val viewer = object : DiffViewerService(project) {
            val shown = CopyOnWriteArrayList<List<DiffEntry>>()
            override fun showMultiFileDiff(entries: List<DiffEntry>, initialIndex: Int?) { shown.add(entries) }
        }
        val service = OpenCodeService(project)
        val client = OpenCodeApiClient("127.0.0.1", server.port)
        try {
            assertTrue(client.checkHealth())
            service.setTestDeps(manager, viewer, client)
            service.configureWorkspaceForTest(root.path)
            service.invokeLater = { it.run() }
            service.connectEventStreamForTest()
            assertTrue(server.waitForSubscriber())

            val patch = "@@ -1 +1 @@\n-old\n+new\n"
            val diff = """{"data":[{"file":"edited.kt","patch":${jsonString(patch)},"additions":1,"deletions":1,"status":"modified"}]}"""
            server.respondTo("msg_first", diff)
            val dir = jsonString(root.path)
            server.broadcast("""{"type":"session.status","location":{"directory":"/elsewhere"},"data":{"sessionID":"ses_one","status":{"type":"busy"}}}""")
            server.broadcast("""{"type":"session.inbox.enqueued","location":{"directory":$dir},"data":{"sessionID":"ses_one","inboxID":"msg_first","item":{"type":"user"}}}""")
            server.broadcast("""{"type":"session.status","location":{"directory":$dir},"data":{"sessionID":"ses_one","status":{"type":"busy"}}}""")
            server.broadcast("""{"type":"session.status","location":{"directory":$dir},"data":{"sessionID":"ses_one","status":{"type":"idle"}}}""")

            val deadline = System.currentTimeMillis() + 4000
            while (viewer.shown.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertEquals("msg_first", server.requestedTurns.single())
            assertEquals("old\n", viewer.shown.single().single().beforeContent)
            assertEquals("new\n", viewer.shown.single().single().diff.after)
        } finally {
            service.dispose()
            manager.dispose()
            server.stop()
            file.delete()
            root.delete()
        }
    }

    @Test
    fun reviewContentAndPendingIdentityAreBoundToTheShownTurn() {
        val original = DiffEntry("edited.kt", ai.opencode.ide.jetbrains.api.models.FileDiff(
            "edited.kt", "old", "agent", 1, 1, "modified"
        ))
        assertTrue(original.matchesCurrentContent("agent", "agent"))
        assertFalse(original.matchesCurrentContent("new user edit", "new user edit"))
        assertFalse(original.matchesCurrentContent("agent", "unsaved edit"))
        assertFalse(original.matchesCurrentContent(null, null))

        val root = createTempDirectory(prefix = "opencode-review-").toFile()
        val project = fakeProject(root)
        val manager = SessionManager(project)
        try {
            val first = manager.onTurnStart().also { assertTrue(it) }
            val old = manager.getProcessedDiffs(listOf(original.diff), manager.onTurnEnd()!!).single()
            assertTrue(first && manager.isPending(old))
            manager.onTurnStart()
            val newer = manager.getProcessedDiffs(listOf(original.diff.copy(after = "newer")), manager.onTurnEnd()!!).single()
            assertFalse(manager.isPending(old))
            assertTrue(manager.isPending(newer))

            val coordinator = ReviewCoordinator(project, { manager }, { DiffViewerService(project) },
                { null }, { root.path }, { it.run() })
            manager.onTurnStart()
            coordinator.reset()
            assertTrue("Reset must release the busy turn", manager.onTurnStart())
        } finally {
            manager.dispose()
            root.delete()
        }
    }

    @Test
    fun v2DiffRequestFailureIsNotReportedAsAnEmptyDiff() {
        val server = FakeOpenCodeServer()
        server.start()
        try {
            server.respondErrorTo("msg_failure")
            val client = OpenCodeApiClient("127.0.0.1", server.port)
            try {
                client.getSessionDiff("ses_one", "msg_failure")
                fail("A failed HTTP request must be distinguishable from an empty turn")
            } catch (_: java.io.IOException) {
                // A retry or warning can now handle the failure.
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun eventFromAnOldConnectionCannotStartATurnAfterReset() {
        val root = createTempDirectory(prefix = "opencode-stale-event-").toFile()
        val project = fakeProject(root)
        val manager = SessionManager(project)
        val coordinator = ReviewCoordinator(project, { manager }, { DiffViewerService(project) },
            { null }, { root.path }, { it.run() })
        try {
            val oldToken = coordinator.currentGeneration()
            coordinator.reset()
            coordinator.handle(SessionExecutionEvent("session.execution.started", SessionIdData("old")), oldToken)

            assertTrue("An obsolete busy event must not block the next turn", manager.onTurnStart())
        } finally {
            manager.dispose()
            root.delete()
        }
    }

    @Test
    fun oldIdleEventAndReconciliationCannotFinishTheNewConnectionsTurn() {
        val root = createTempDirectory(prefix = "opencode-stale-idle-").toFile()
        val project = fakeProject(root)
        val manager = SessionManager(project)
        val coordinator = ReviewCoordinator(project, { manager }, { DiffViewerService(project) },
            { null }, { root.path }, { it.run() })
        try {
            val oldToken = coordinator.currentGeneration()
            coordinator.reset()
            val token = coordinator.currentGeneration()
            coordinator.handle(SessionExecutionEvent("session.execution.started", SessionIdData("same-id")), token)

            coordinator.handle(SessionExecutionEvent("session.execution.succeeded", SessionIdData("same-id")), oldToken)
            coordinator.reconcileActiveSessions(emptySet(), oldToken)

            assertFalse("Old completion callbacks must leave the current turn busy", manager.onTurnStart())
            assertTrue(manager.onTurnEnd() != null)
        } finally {
            manager.dispose()
            root.delete()
        }
    }

    @Test
    fun localTuiCommandReplacesTheShell() {
        if (System.getProperty("os.name").lowercase().contains("windows")) return
        val command = OpenCodeCli().buildCommand("/tmp/opencode v2", "127.0.0.1", 4096, "secret", true)
        assertTrue(command.startsWith("exec env OPENCODE_PASSWORD='secret' '/tmp/opencode v2'"))
    }

    private fun fakeProject(root: File): Project = Proxy.newProxyInstance(
        Project::class.java.classLoader, arrayOf(Project::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getBasePath" -> root.path
            "isDisposed" -> false
            "toString" -> "TestProject"
            "hashCode" -> 12345
            "equals" -> false
            else -> null
        }
    } as Project

    private fun jsonString(value: String) = com.google.gson.Gson().toJson(value)
}
