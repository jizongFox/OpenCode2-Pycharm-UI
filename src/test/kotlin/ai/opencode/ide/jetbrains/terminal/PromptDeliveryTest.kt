package ai.opencode.ide.jetbrains.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptDeliveryTest {
    private class Scheduler {
        val tasks = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val delays = mutableListOf<Long>()

        fun schedule(delay: Long, action: () -> Unit) {
            delays.add(delay)
            tasks.addLast(action)
        }

        fun next() = tasks.removeFirst().invoke()

        fun finish() {
            while (tasks.isNotEmpty()) next()
            while (ui.isNotEmpty()) ui.removeFirst().invoke()
        }
    }

    @Test
    fun waitsWithoutRetryingUntilTheTerminalIsCreated() {
        val scheduler = Scheduler()
        val received = mutableListOf<String>()
        val delivery = PromptDelivery(
            paste = { received.add(it); true },
            onDelivered = {},
            onFailure = { error("Unexpected failure: $it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) }
        )
        delivery.enqueue("@cold-start.kt ")

        scheduler.finish()
        assertTrue("Waiting for a connection must not schedule retries", scheduler.delays.isEmpty())
        assertTrue(received.isEmpty())

        delivery.resume()
        scheduler.finish()
        assertEquals(listOf("@cold-start.kt "), received)
    }

    @Test
    fun retriesBeforeFocusingAndPreservesCitationOrder() {
        val scheduler = Scheduler()
        val events = mutableListOf<String>()
        var attempts = 0
        val delivery = PromptDelivery(
            paste = { text -> events.add(text); ++attempts > 2 },
            onDelivered = { events.add("focus") },
            onFailure = { error("Unexpected failure: $it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) }
        )
        delivery.enqueue("first")
        delivery.enqueue("second")
        delivery.resume()
        delivery.resume()

        scheduler.next()
        scheduler.next()
        assertTrue("Failures must not move keyboard focus", scheduler.ui.isEmpty())
        scheduler.finish()

        assertEquals(listOf("first", "first", "first", "second", "focus", "focus"), events)
        assertEquals(listOf(0L, 250L, 250L, 0L), scheduler.delays)
    }

    @Test
    fun restartPausesDeliveryAndGivesRetainedCitationsAFreshRetryBudget() {
        val scheduler = Scheduler()
        var attempts = 0
        var focused = false
        val delivery = PromptDelivery(
            paste = { ++attempts >= 3 },
            onDelivered = { focused = true },
            onFailure = { error("Restart must preserve the reference: $it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) },
            maxAttempts = 2
        )
        delivery.enqueue("retained")
        delivery.resume()
        scheduler.next()
        delivery.pause(clearPending = false)
        scheduler.finish()
        assertEquals(1, attempts)

        delivery.resume()
        scheduler.finish()
        assertEquals(3, attempts)
        assertTrue(focused)
    }

    @Test
    fun resetInvalidatesQueuedFocusAndFailureNotifications() {
        val scheduler = Scheduler()
        val events = mutableListOf<String>()
        val delivery = PromptDelivery(
            paste = { it == "sent" },
            onDelivered = { events.add("focus") },
            onFailure = { events.add("failure:$it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) },
            maxAttempts = 1
        )
        delivery.enqueue("sent")
        delivery.enqueue("failed")
        delivery.resume()
        scheduler.next()
        scheduler.next()
        delivery.pause(clearPending = true)
        scheduler.finish()
        assertTrue(events.isEmpty())
    }

    @Test
    fun exhaustedCitationDoesNotBlockTheNextCitation() {
        val scheduler = Scheduler()
        val events = mutableListOf<String>()
        val delivery = PromptDelivery(
            paste = { events.add("paste:$it"); it == "next" },
            onDelivered = { events.add("focus") },
            onFailure = { events.add("failure:$it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) },
            maxAttempts = 2
        )
        delivery.enqueue("failed")
        delivery.enqueue("next")
        delivery.resume()
        scheduler.finish()
        assertEquals(listOf("paste:failed", "paste:failed", "paste:next", "failure:failed", "focus"), events)
    }

    @Test
    fun resetDuringPasteCannotConsumeACitationFromTheNewConnection() {
        val scheduler = Scheduler()
        val received = mutableListOf<String>()
        var focused = 0
        lateinit var delivery: PromptDelivery
        delivery = PromptDelivery(
            paste = { text ->
                received.add(text)
                if (text == "old") {
                    delivery.pause(clearPending = true)
                    delivery.enqueue("new")
                    delivery.resume()
                }
                true
            },
            onDelivered = { focused++ },
            onFailure = { error("Unexpected failure: $it") },
            schedule = scheduler::schedule,
            postToUi = { scheduler.ui.addLast(it) }
        )
        delivery.enqueue("old")
        delivery.resume()
        scheduler.finish()
        assertEquals(listOf("old", "new"), received)
        assertEquals(1, focused)
    }
}
