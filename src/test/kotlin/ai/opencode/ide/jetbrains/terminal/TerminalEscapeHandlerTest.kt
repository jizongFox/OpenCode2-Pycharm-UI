package ai.opencode.ide.jetbrains.terminal

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEscapeHandlerTest {

    @Test
    fun `plain Escape is sent to the terminal and consumed`() {
        val event = keyEvent(KeyEvent.KEY_PRESSED)
        var sent = 0

        val handled = handleTerminalEscapeKey(event) { sent++ }

        assertTrue(handled)
        assertTrue(event.isConsumed)
        assertEquals(1, sent)
    }

    @Test
    fun `Escape release is consumed without sending twice`() {
        val event = keyEvent(KeyEvent.KEY_RELEASED)
        var sent = 0

        val handled = handleTerminalEscapeKey(event) { sent++ }

        assertTrue(handled)
        assertTrue(event.isConsumed)
        assertEquals(0, sent)
    }

    @Test
    fun `modified Escape remains available to the IDE`() {
        val event = keyEvent(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK)

        val handled = handleTerminalEscapeKey(event) {
            throw AssertionError("Modified Escape must not be sent")
        }

        assertFalse(handled)
        assertFalse(event.isConsumed)
    }

    private fun keyEvent(id: Int, modifiers: Int = 0): KeyEvent {
        return KeyEvent(
            JPanel(),
            id,
            System.currentTimeMillis(),
            modifiers,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED
        )
    }
}
