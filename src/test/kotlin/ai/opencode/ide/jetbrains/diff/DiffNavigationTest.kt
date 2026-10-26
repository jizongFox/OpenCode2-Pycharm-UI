package ai.opencode.ide.jetbrains.diff

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.session.SessionManager
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.ui.WindowWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.MapDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Image
import java.awt.Window
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.io.path.createTempDirectory

class DiffNavigationTest : BasePlatformTestCase() {
    private lateinit var root: File
    private lateinit var viewer: CapturingViewer

    private class ReviewWindow(private val project: Project) : WindowWrapper {
        var closed = false
        override fun close() { closed = true }
        override fun dispose() { closed = true }
        override fun isDisposed(): Boolean = closed
        override fun show() = Unit
        override fun getProject(): Project = project
        override fun getComponent(): JComponent = JPanel()
        override fun getMode(): WindowWrapper.Mode = WindowWrapper.Mode.NON_MODAL
        override fun getWindow(): Window = error("The headless test does not create an AWT window")
        override fun setTitle(title: String?) = Unit
        override fun setImages(images: List<Image>?) = Unit
    }

    private class CapturingViewer(private val project: Project) : DiffViewerService(project) {
        data class Opened(val chain: SimpleDiffRequestChain, val hints: DiffDialogHints, val window: ReviewWindow)
        val opened = mutableListOf<Opened>()
        var delayWindowCallback = false
        override fun displayDiff(chain: SimpleDiffRequestChain, hints: DiffDialogHints) {
            val window = ReviewWindow(project)
            opened.add(Opened(chain, hints, window))
            if (!delayWindowCallback) hints.windowConsumer?.consume(window)
        }
    }

    override fun setUp() {
        super.setUp()
        root = createTempDirectory("opencode-navigation-").toFile()
        project.service<SessionManager>().configureWorkspaceRoot(root.path)
        viewer = CapturingViewer(project)
        Registry.get("show.diff.as.frame").setValue(true, testRootDisposable)
        TestDialogManager.setTestDialog(TestDialog.YES)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(viewer)
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testAppliedEntryAdvancesTheCurrentReview() {
        val entries = reviewedFiles("first.kt", "second.kt")
        viewer.showMultiFileDiff(entries)
        rejectFirst(viewer.opened.single().chain)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(2, viewer.opened.size)
        assertTrue(viewer.opened.first().window.closed)
        assertFalse(viewer.opened.last().window.closed)
        assertEquals(1, viewer.opened.last().chain.listSelection.list.size)
        assertEquals("before", File(root, "first.kt").readText())
    }

    fun testDefaultEditorHintsDoNotForceAWindow() {
        Registry.get("show.diff.as.frame").setValue(false, testRootDisposable)
        viewer.showMultiFileDiff(reviewedFiles("first.kt"))
        assertNull("An ownership callback must not change the IDE's editor-tab preference",
            viewer.opened.single().hints.windowConsumer)
    }

    fun testOldCompletionCannotCloseOrNavigateTheNewReview() {
        val entries = reviewedFiles("first.kt", "second.kt")
        viewer.showMultiFileDiff(entries)
        rejectFirst(viewer.opened.single().chain)
        viewer.showMultiFileDiff(listOf(entries.last()))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(2, viewer.opened.size)
        assertFalse(viewer.opened.last().window.closed)
        assertFalse(project.service<SessionManager>().isPending(entries.first()))
        assertTrue(project.service<SessionManager>().isPending(entries.last()))
    }

    fun testCompletingTheLastEntryKeepsTheSourceEditorOpen() {
        val source = myFixture.addFileToProject("human.kt", "val human = true").virtualFile
        myFixture.openFileInEditor(source)
        val entries = reviewedFiles("first.kt")
        viewer.showMultiFileDiff(entries)
        rejectFirst(viewer.opened.single().chain)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, viewer.opened.size)
        assertTrue(viewer.opened.single().window.closed)
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(source))
    }

    fun testManuallyClosedReviewIsNotReopenedByCompletion() {
        val entries = reviewedFiles("first.kt", "second.kt")
        viewer.showMultiFileDiff(entries)
        viewer.opened.single().window.close()
        rejectFirst(viewer.opened.single().chain)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(1, viewer.opened.size)
        assertFalse(project.service<SessionManager>().isPending(entries.first()))
    }

    fun testLateWindowCallbackCannotReplaceTheNewView() {
        val entries = reviewedFiles("first.kt", "second.kt")
        viewer.delayWindowCallback = true
        viewer.showMultiFileDiff(entries)
        viewer.showMultiFileDiff(listOf(entries.last()))
        val old = viewer.opened.first()
        val current = viewer.opened.last()
        current.hints.windowConsumer?.consume(current.window)
        old.hints.windowConsumer?.consume(old.window)

        assertTrue(old.window.closed)
        assertFalse(current.window.closed)
    }

    private fun reviewedFiles(vararg names: String): List<DiffEntry> {
        val manager = project.service<SessionManager>()
        val diffs = names.map {
            File(root, it).writeText("after")
            FileDiff(it, "before", "after", 1, 1, "modified")
        }
        assertTrue(manager.onTurnStart())
        return manager.getProcessedDiffs(diffs, checkNotNull(manager.onTurnEnd()))
    }

    private fun rejectFirst(chain: SimpleDiffRequestChain) {
        val request = chain.listSelection.list.first().process(UserDataHolderBase(), EmptyProgressIndicator())
        val action = checkNotNull(request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS))
            .filterIsInstance<RejectFromDiffEditorAction>().single()
        val context = MapDataContext()
        context.put(CommonDataKeys.PROJECT, project)
        // This key can refer to a source file. Navigation must never use it to close a review.
        context.put(CommonDataKeys.VIRTUAL_FILE, FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
        action.actionPerformed(AnActionEvent.createFromDataContext("test", null, context))
    }
}
