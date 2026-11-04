package ai.opencode.ide.jetbrains.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import javax.swing.JComponent
import javax.swing.JRadioButton

class OpenCodeConnectDialogTest : BasePlatformTestCase() {
    fun testFreshDialogStartsLocallyWithoutAnAddress() {
        val dialog = OpenCodeConnectDialog(project)
        try {
            val local = dialog.preferredFocusedComponent as JRadioButton
            assertEquals("Start a local server", local.text)
            assertTrue(local.isSelected)
            val panel = invokeDialogHook(dialog, "createCenterPanel") as JComponent
            val localHeight = panel.preferredSize.height
            val existing = UIUtil.findComponentsOfType(panel, JRadioButton::class.java)
                .single { it.text == "Connect to an existing server" }
            existing.doClick()
            val address = dialog.preferredFocusedComponent as JBTextField
            assertTrue(panel.preferredSize.height > localHeight)
            address.text = ""
            local.doClick()
            assertEquals(localHeight, panel.preferredSize.height)

            invokeDialogHook(dialog, "doOKAction")

            assertTrue(dialog.choice is OpenCodeConnectDialog.ConnectionInfo.StartLocal)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun testRetryStillConnectsToTheExistingLoopbackServer() {
        val retry = OpenCodeConnectDialog.ConnectionInfo.ExistingServer("::1", 48001, null, null)
        val dialog = OpenCodeConnectDialog(project, retry)
        try {
            val address = dialog.preferredFocusedComponent as JBTextField
            assertEquals("[::1]:48001", address.text)

            invokeDialogHook(dialog, "doOKAction")

            assertEquals(retry, dialog.choice)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    private fun invokeDialogHook(dialog: OpenCodeConnectDialog, name: String): Any? {
        // The headless peer does not build the center panel until the dialog is shown.
        val hook = OpenCodeConnectDialog::class.java.getDeclaredMethod(name)
        check(hook.trySetAccessible())
        return hook.invoke(dialog)
    }
}
