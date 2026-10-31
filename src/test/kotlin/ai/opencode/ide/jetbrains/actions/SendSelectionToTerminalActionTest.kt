package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.SendSelectionToTerminalAction
import ai.opencode.ide.jetbrains.api.OpenCodeClient
import ai.opencode.ide.jetbrains.api.OpenCodeEventStream
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.SessionState
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.diff.DiffViewerService
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SendSelectionToTerminalActionTest : BasePlatformTestCase() {

    fun testDirectorySelectionSendsOnlyDirectoryReference() {
        val client = configureClient()
        val dir = myFixture.tempDirFixture.findOrCreateDir("root/mydir")
        myFixture.tempDirFixture.createFile("root/mydir/file1.txt")
        myFixture.tempDirFixture.createFile("root/mydir/file2.txt")
        project.service<OpenCodeService>().configureWorkspaceForTest(dir.parent.path)

        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(dir))
        dataContext.put(CommonDataKeys.EDITOR, null)

        performAction(dataContext)

        val prompt = waitForPrompt(client)
        assertEquals("@mydir ", prompt)
    }

    fun testSelectionEndingAtNextLineStartDoesNotIncludeNextLine() {
        val client = configureClient()
        val file = myFixture.addFileToProject("sample.kt", "first\nsecond\nthird\n").virtualFile
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor
        project.service<OpenCodeService>().configureWorkspaceForTest(file.parent.path)
        editor.selectionModel.setSelection(0, editor.document.getLineStartOffset(1))

        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE, file)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
        dataContext.put(CommonDataKeys.EDITOR, editor)

        performAction(dataContext)

        assertEquals("@sample.kt#1-1 ", waitForPrompt(client))
    }

    fun testTerminalPasteIsRetriedAfterTransportFailure() {
        val client = configureClient(failuresBeforeSuccess = 2)
        val file = myFixture.addFileToProject("retry.kt", "val retry = true\n").virtualFile
        myFixture.openFileInEditor(file)
        val editor = myFixture.editor
        project.service<OpenCodeService>().configureWorkspaceForTest(file.parent.path)

        val dataContext = MapDataContext()
        dataContext.put(CommonDataKeys.PROJECT, project)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE, file)
        dataContext.put(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(file))
        dataContext.put(CommonDataKeys.EDITOR, editor)

        performAction(dataContext)

        assertEquals("@retry.kt ", waitForPrompt(client))
        waitForFocus(client)
        assertEquals(3, client.attempts.get())
        assertEquals(listOf("paste:1", "paste:2", "paste:3", "focus"), client.events.toList())
    }

    private fun configureClient(failuresBeforeSuccess: Int = 0): RecordingClient {
        val service = project.service<OpenCodeService>()
        val client = RecordingClient(failuresBeforeSuccess)
        service.setTestDeps(
            project.service<SessionManager>(),
            project.service<DiffViewerService>(),
            client
        )
        service.pasteToTerminal = client::paste
        service.focusAfterPaste = client::focus
        service.hasRunningTerminal = { true }
        return client
    }

    private fun performAction(dataContext: MapDataContext) {
        val event = AnActionEvent.createFromDataContext("test", null, dataContext)
        SendSelectionToTerminalAction().actionPerformed(event)
    }

    private fun waitForPrompt(client: RecordingClient): String {
        val deadline = System.currentTimeMillis() + 3000
        while (client.prompts.isEmpty() && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(25)
        }
        assertFalse("The client should have received a prompt", client.prompts.isEmpty())
        return client.prompts.last()
    }

    private fun waitForFocus(client: RecordingClient) {
        val deadline = System.currentTimeMillis() + 3000
        while ("focus" !in client.events && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(25)
        }
        assertTrue("Focus should follow a successful paste", "focus" in client.events)
    }

    private class RecordingClient(private val failuresBeforeSuccess: Int) : OpenCodeClient {
        val attempts = AtomicInteger()
        val prompts = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<String>()

        override fun checkHealth(): Boolean = true

        override fun getSessionState(sessionId: String): SessionState = error("No review in this test")

        override fun getActiveSessionIds(): Set<String> = emptySet()

        override fun getSessionDiff(sessionId: String, fromMessageId: String): List<FileDiff> = emptyList()

        override fun createEventStream(
            directory: String,
            onEvent: (OpenCodeEvent) -> Unit,
            onError: (Throwable) -> Unit,
            onConnected: () -> Unit,
            onDisconnected: () -> Unit
        ): OpenCodeEventStream {
            return object : OpenCodeEventStream {
                override fun connect() = onConnected()

                override fun disconnect() = Unit
            }
        }

        fun paste(text: String): Boolean {
            val attempt = attempts.incrementAndGet()
            events.add("paste:$attempt")
            if (attempt <= failuresBeforeSuccess) return false
            prompts.add(text)
            return true
        }

        fun focus() { events.add("focus") }
    }
}
