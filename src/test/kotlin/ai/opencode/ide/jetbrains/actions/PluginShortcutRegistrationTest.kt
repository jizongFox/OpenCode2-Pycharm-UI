package ai.opencode.ide.jetbrains.actions

import ai.opencode.ide.jetbrains.QuickLaunchAction
import ai.opencode.ide.jetbrains.SendSelectionToTerminalAction
import com.intellij.openapi.project.DumbAware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class PluginShortcutRegistrationTest {

    @Test
    fun `tool window does not activate when the IDE starts`() {
        val descriptor = File("src/main/resources/META-INF/plugin.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(descriptor)

        val toolWindow = document.getElementsByTagName("toolWindow").asElements()
            .single { it.getAttribute("id") == "OpenCode" }

        assertEquals("true", toolWindow.getAttribute("doNotActivateOnStart"))
    }

    @Test
    fun `plugin actions remain available during indexing`() {
        assertTrue(SendSelectionToTerminalAction() is DumbAware)
        assertTrue(QuickLaunchAction() is DumbAware)
    }

    @Test
    fun `context shortcut is registered once and shared by both menus`() {
        val descriptor = File("src/main/resources/META-INF/plugin.xml")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(descriptor)

        val actions = document.getElementsByTagName("action").asElements()
        val contextAction = actions.single {
            it.getAttribute("class") ==
                "ai.opencode.ide.jetbrains.SendSelectionToTerminalAction"
        }

        val defaultShortcuts = contextAction
            .getElementsByTagName("keyboard-shortcut")
            .asElements()
            .filter { it.getAttribute("keymap") == "\$default" }
            .map { it.getAttribute("first-keystroke") }

        assertEquals(listOf("control alt K"), defaultShortcuts)

        val menuGroups = contextAction
            .getElementsByTagName("add-to-group")
            .asElements()
            .map { it.getAttribute("group-id") }
            .toSet()

        assertEquals(setOf("EditorPopupMenu", "ProjectViewPopupMenu"), menuGroups)
    }

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).map { item(it) as Element }
}
