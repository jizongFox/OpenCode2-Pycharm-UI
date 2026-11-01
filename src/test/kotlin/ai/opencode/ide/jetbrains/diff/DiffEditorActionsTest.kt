package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.session.SessionManager

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

class DiffEditorActionsTest : BasePlatformTestCase() {
    private lateinit var root: File
    private val dialogs = mutableListOf<String>()
    private var answer = Messages.YES
    private var applied = 0

    override fun setUp() {
        super.setUp()
        root = createTempDirectory("opencode-review-actions-").toFile()
        project.service<SessionManager>().configureWorkspaceRoot(root.path)
        TestDialogManager.setTestDialog { message ->
            dialogs.add(message)
            answer
        }
    }

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAcceptStagesBeforeNavigatingAndDoesNotAskForConfirmation() {
        git("init", "--quiet")
        val entry = reviewedFile()
        val action = action(entry, accept = true) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(project.service<SessionManager>().isPending(entry))
            assertEquals("after", git("show", ":review.txt"))
        }

        action.actionPerformed(event())
        waitUntil { applied == 1 }

        assertTrue(dialogs.isEmpty())
        assertEquals("after", File(root, entry.file).readText())
    }

    fun testCancellingRejectKeepsTheFilePendingAndDoesNotNavigate() {
        val entry = reviewedFile()
        answer = Messages.NO

        action(entry, accept = false).actionPerformed(event())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals("after", File(root, entry.file).readText())
        assertTrue(project.service<SessionManager>().isPending(entry))
        assertEquals(0, applied)
        assertEquals(1, dialogs.size)
        assertTrue(dialogs.single().startsWith("Reject changes to 'review.txt'?"))
    }

    fun testRejectingANewFileConfirmsDeletionBeforeNavigating() {
        val entry = reviewedFile(status = "added")

        action(entry, accept = false) {
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(File(root, entry.file).exists())
            assertFalse(project.service<SessionManager>().isPending(entry))
        }.actionPerformed(event())
        waitUntil { applied == 1 }

        assertEquals(1, dialogs.size)
        assertTrue(dialogs.single().startsWith("Delete new file 'review.txt'?"))
    }

    fun testAcceptFailureKeepsTheEntryAvailableForRetry() {
        val entry = reviewedFile()
        val action = action(entry, accept = true)
        action.actionPerformed(event())
        waitUntil { dialogs.isNotEmpty() }

        assertTrue(dialogs.single().startsWith("Could not stage review.txt.\n"))
        assertTrue(project.service<SessionManager>().isPending(entry))
        assertEquals(0, applied)
        git("init", "--quiet")
        action.actionPerformed(event())
        waitUntil { applied == 1 }
        assertEquals("after", git("show", ":review.txt"))
        assertEquals(1, dialogs.size)
    }

    fun testBothActionsRefuseAFileEditedAfterReview() {
        for (accept in listOf(true, false)) {
            dialogs.clear()
            val entry = reviewedFile()
            File(root, entry.file).writeText("human edit")

            action(entry, accept).actionPerformed(event())
            waitUntil { dialogs.any { it.contains("Refresh the review.") } }

            assertEquals("human edit", File(root, entry.file).readText())
            assertTrue(project.service<SessionManager>().isPending(entry))
            assertEquals(0, applied)
        }
    }

    fun testSupersededEntriesDisableBothActionsAndCannotBeApplied() {
        val old = reviewedFile()
        val accept = action(old, accept = true)
        val reject = action(old, accept = false)
        val current = reviewedFile()

        for (action in listOf(accept, reject)) {
            val event = event()
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(project.service<SessionManager>().isPending(current))
        assertEquals("after", File(root, old.file).readText())
        assertEquals(0, applied)
        // Accept reports an expired entry; Reject silently ignores it before confirmation.
        assertEquals(listOf("Review is no longer current for review.txt."), dialogs)
    }

    private fun action(entry: DiffEntry, accept: Boolean, afterApplied: () -> Unit = {}): AnAction {
        val onApplied = { afterApplied(); applied++; Unit }
        return ReviewDiffAction(entry, if (accept) ReviewOperation.ACCEPT else ReviewOperation.REJECT, onApplied)
    }

    private fun reviewedFile(status: String = "modified"): DiffEntry {
        File(root, "review.txt").writeText("after")
        val manager = project.service<SessionManager>()
        assertTrue(manager.onTurnStart())
        return manager.getProcessedDiffs(
            listOf(FileDiff("review.txt", if (status == "added") "" else "before", "after", 1, 1, status)),
            checkNotNull(manager.onTurnEnd())
        ).single()
    }

    private fun event(): AnActionEvent {
        val context = MapDataContext()
        context.put(CommonDataKeys.PROJECT, project)
        return AnActionEvent.createFromDataContext("test", null, context)
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", *arguments)).directory(root).redirectErrorStream(true).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        return output
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("The review action did not complete", condition())
    }
}
