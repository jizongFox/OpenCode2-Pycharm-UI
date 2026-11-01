package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import javax.swing.JPanel

class TerminalHostTest : FileEditorManagerTestCase() {
    private lateinit var host: TerminalHost

    override fun setUp() {
        super.setUp()
        host = TerminalHost(project) { null }
        // Use the real editor view with this test's host instead of the project service's host.
        val providers = FileEditorProvider.EP_FILE_EDITOR_PROVIDER.extensionList
            .filterNot { it is OpenCodeTerminalFileEditorProvider } + object : FileEditorProvider, DumbAware {
            override fun accept(project: Project, file: VirtualFile): Boolean = file is OpenCodeTerminalVirtualFile
            override fun createEditor(project: Project, file: VirtualFile): FileEditor =
                OpenCodeTerminalFileEditor(file as OpenCodeTerminalVirtualFile, host)
            override fun getEditorTypeId(): String = "opencode-terminal-editor"
            override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
        }
        ExtensionTestUtil.maskExtensions(FileEditorProvider.EP_FILE_EDITOR_PROVIDER, providers, testRootDisposable)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(host)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    fun testConstructionDoesNotOpenAnEditor() {
        assertEmpty(checkNotNull(manager).openFiles)
        assertFalse(host.isOpen())
    }

    fun testFirstLaunchPlacesTerminalToTheRightOfSource() {
        val editors = checkNotNull(manager)
        val source = myFixture.addFileToProject("source.txt", "source").virtualFile
        editors.openFile(source, false)

        val terminal = launchTerminal()
        assertNull("Editor launch must not initialize or activate a Terminal tool-window tab",
            TerminalToolWindowManager.getInstance(project).toolWindow)
        assertEquals(2, editors.windows.size)
        val split = checkNotNull(UIUtil.findComponentOfType(editors.mainSplitters, Splitter::class.java))
        assertFalse("A horizontal editor split places its second component on the right", split.isVertical)
        assertTrue(UIUtil.isAncestor(split.firstComponent, editors.getEditors(source).single().component))
        assertTrue(UIUtil.isAncestor(split.secondComponent, editors.getEditors(terminal).single().component))
    }

    fun testLaunchWithoutSourceUsesOneEditorWindow() {
        launchTerminal()

        val editors = checkNotNull(manager)
        assertEquals(1, editors.windows.size)
        assertEquals(1, editors.openFiles.filterIsInstance<OpenCodeTerminalVirtualFile>().size)
    }

    fun testRepeatedOpenReusesTheTerminalAndEditorSplit() {
        val editors = checkNotNull(manager)
        val source = myFixture.addFileToProject("source.txt", "source").virtualFile
        editors.openFile(source, false)
        val sourceWindow = checkNotNull(editors.currentWindow)
        val terminal = launchTerminal()
        val terminalEditor = editors.getEditors(terminal).single()
        val focusComponent = host.preferredFocusComponent()

        sourceWindow.setAsCurrentWindow(false)
        host.show()
        assertSame("Revealing the TUI must preserve the source window for citation insertion",
            sourceWindow, editors.currentWindow)
        host.focus()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(2, editors.windows.size)
        assertSame(terminalEditor, editors.getEditors(terminal).single())
        assertSame(focusComponent, host.preferredFocusComponent())
    }

    fun testClosingAndReopeningEditorKeepsTheTerminalWidget() {
        val editors = checkNotNull(manager)
        val terminal = launchTerminal()
        val focusComponent = host.preferredFocusComponent()

        editors.closeFile(terminal)
        host.focus()
        awaitTerminalEditor(terminal)

        assertTrue(editors.isFileOpen(terminal))
        assertSame(focusComponent, host.preferredFocusComponent())
    }

    fun testVisibleViewIsFocusedWithoutReopeningItsFile() {
        val editors = checkNotNull(manager)
        val source = myFixture.addFileToProject("source.txt", "source").virtualFile
        editors.openFile(source, false)
        val sourceWindow = checkNotNull(editors.currentWindow)
        val terminal = launchTerminal()
        val view = editors.getEditors(terminal).single().component
        // Model a showing editor view without requiring a native detached AWT window.
        val visibleContainer = object : JPanel() {
            override fun isShowing(): Boolean = true
        }
        visibleContainer.add(view)
        visibleContainer.addNotify()
        try {
            assertTrue(checkNotNull(host.preferredFocusComponent()).isShowing)
            sourceWindow.setAsCurrentWindow(false)
            host.focus()

            assertSame("A visible view must keep its location instead of reopening the file",
                sourceWindow, editors.currentWindow)
            assertEquals(2, editors.windows.size)
        } finally {
            visibleContainer.removeNotify()
            visibleContainer.removeAll()
        }
    }

    fun testResetClosesTheEditorAndDisposesTerminalResources() {
        val editors = checkNotNull(manager)
        val terminal = launchTerminal()
        val widget = checkNotNull(UIUtil.getParentOfType(
            ShellTerminalWidget::class.java, host.preferredFocusComponent()
        ))

        host.reset()

        assertFalse(editors.isFileOpen(terminal))
        assertTrue(Disposer.isDisposed(widget))
        assertNull(host.preferredFocusComponent())
        assertFalse(host.isOpen())
    }

    private fun launchTerminal(): OpenCodeTerminalVirtualFile {
        host.create(project.basePath, "OpenCode", "echo terminal-test")
        val terminal = checkNotNull(manager).openFiles.filterIsInstance<OpenCodeTerminalVirtualFile>().single()
        awaitTerminalEditor(terminal)
        return terminal
    }

    private fun awaitTerminalEditor(file: OpenCodeTerminalVirtualFile) {
        PlatformTestUtil.waitWithEventsDispatching(
            "OpenCode editor to finish loading", { checkNotNull(manager).getEditors(file).isNotEmpty() }, 10
        )
    }
}
