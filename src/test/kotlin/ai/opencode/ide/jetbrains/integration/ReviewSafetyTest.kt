package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.models.DiffEntry
import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.session.SessionManager
import ai.opencode.ide.jetbrains.session.ReviewResult
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

class ReviewSafetyTest : BasePlatformTestCase() {
    private var reviewRoot: File? = null

    override fun tearDown() {
        try {
            reviewRoot?.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRejectDoesNotOverwriteAnEditAfterReview() {
        val (entry, file) = reviewedFile()
        runWriteAction { VfsUtil.saveText(file, "human edit") }

        var result: ReviewResult? = null
        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Stale, result)
        assertEquals("human edit", File(file.path).readText())
        assertTrue(project.service<SessionManager>().isPending(entry))
    }

    fun testAcceptDoesNotStageAnEditAfterReview() {
        val (entry, file) = reviewedFile()
        runWriteAction { VfsUtil.saveText(file, "human edit") }

        var result: ReviewResult? = null
        project.service<SessionManager>().acceptDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Stale, result)
        assertEquals("human edit", File(file.path).readText())
        assertTrue(project.service<SessionManager>().isPending(entry))
    }

    fun testStageFailureIsDistinguishableFromAStaleReview() {
        val (entry, _) = reviewedFile()
        var result: ReviewResult? = null
        project.service<SessionManager>().acceptDiff(entry) { result = it }
        waitForResult { result }

        assertTrue("A folder without a Git repository must report an operation failure", result is ReviewResult.Failed)
        assertTrue(project.service<SessionManager>().isPending(entry))
    }

    fun testRejectRestoresTheFileAndCompletesTheReview() {
        val (entry, file) = reviewedFile()
        var result: ReviewResult? = null
        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Applied, result)
        assertEquals("before", File(file.path).readText())
        assertFalse(project.service<SessionManager>().isPending(entry))
    }

    fun testAcceptStagesTheReviewedContent() {
        val (entry, file) = reviewedFile()
        val init = ProcessBuilder("git", "init", "--quiet").directory(File(file.parent.path)).start()
        assertTrue(init.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, init.exitValue())
        var result: ReviewResult? = null
        project.service<SessionManager>().acceptDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Applied, result)
        val show = ProcessBuilder("git", "show", ":review.txt").directory(File(file.parent.path)).start()
        assertTrue(show.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, show.exitValue())
        assertEquals("agent", show.inputStream.bufferedReader().readText())
        assertFalse(project.service<SessionManager>().isPending(entry))
    }

    fun testRejectDoesNotRecreateAnEmptiedFileDeletedAfterReview() {
        val (entry, file) = reviewedFile(after = "")
        val path = file.path
        runWriteAction { file.delete(this) }
        var result: ReviewResult? = null

        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Stale, result)
        assertFalse(File(path).exists())
        assertTrue(project.service<SessionManager>().isPending(entry))
    }

    fun testAcceptRefusesAnEmptiedFileDeletedAfterReview() {
        val (entry, file) = reviewedFile(after = "")
        runWriteAction { file.delete(this) }
        var result: ReviewResult? = null

        project.service<SessionManager>().acceptDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Stale, result)
        assertTrue(project.service<SessionManager>().isPending(entry))
    }

    fun testRejectRestoresAnEmptiedFileThatStillExists() {
        val (entry, file) = reviewedFile(after = "")
        var result: ReviewResult? = null

        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Applied, result)
        assertEquals("before", File(file.path).readText())
    }

    fun testRejectKeepsAnOriginallyEmptyFile() {
        val (entry, file) = reviewedFile(before = "")
        var result: ReviewResult? = null

        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Applied, result)
        assertTrue(File(file.path).isFile)
        assertEquals("", File(file.path).readText())
    }

    fun testRejectRecreatesAReviewedDeletion() {
        val (entry, file) = reviewedFile(after = "", status = "deleted")
        val path = file.path
        runWriteAction { file.delete(this) }
        var result: ReviewResult? = null

        project.service<SessionManager>().rejectDiff(entry) { result = it }
        waitForResult { result }

        assertEquals(ReviewResult.Applied, result)
        assertEquals("before", File(path).readText())
        assertFalse(project.service<SessionManager>().isPending(entry))
    }

    private fun reviewedFile(
        before: String = "before", after: String = "agent", status: String = "modified"
    ): Pair<DiffEntry, VirtualFile> {
        val root = createTempDirectory("opencode-review-").toFile()
        reviewRoot = root
        val file = File(root, "review.txt").apply { writeText(after) }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("Review file is unavailable")
        val manager = project.service<SessionManager>()
        manager.configureWorkspaceRoot(root.path)
        assertTrue(manager.onTurnStart())
        val entry = manager.getProcessedDiffs(
            listOf(FileDiff("review.txt", before, after, 1, 1, status)),
            manager.onTurnEnd() ?: error("Missing review turn")
        ).single()
        return entry to virtualFile
    }

    private fun waitForResult(result: () -> ReviewResult?) {
        val deadline = System.currentTimeMillis() + 5_000
        while (result() == null && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertNotNull("Review action did not complete", result())
    }
}
