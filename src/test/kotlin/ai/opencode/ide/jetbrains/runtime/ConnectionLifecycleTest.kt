package ai.opencode.ide.jetbrains.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLifecycleTest {
    @Test
    fun lateConnectionResultCannotReplaceTheNewConnection() {
        val lifecycle = ConnectionLifecycle()
        val old = lifecycle.current()
        var connection: String? = null
        val next = lifecycle.reset { connection = null }
        assertTrue(lifecycle.update(next) { connection = "new" })
        assertFalse(lifecycle.update(old) { connection = "old" })
        assertEquals("new", connection)
    }

    @Test
    fun projectDisposalBlocksPublicationBeforeTheServiceIsDisposed() {
        var disposed = false
        val lifecycle = ConnectionLifecycle { disposed }
        val token = lifecycle.current()
        disposed = true
        assertFalse(lifecycle.update(token) { error("A disposed project cannot adopt a process") })
    }

    @Test
    fun closingTheServiceCannotBeUndoneByAnotherReset() {
        val lifecycle = ConnectionLifecycle()
        val old = lifecycle.current()
        lifecycle.reset(close = true) {}
        val newer = lifecycle.reset {}
        assertFalse(lifecycle.update(old) { error("Old connection revived") })
        assertFalse(lifecycle.update(newer) { error("Disposed service revived") })
    }

    @Test
    fun cleanupAndPublicationCannotPassEachOther() {
        val lifecycle = ConnectionLifecycle()
        val token = lifecycle.current()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetting = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val connection = AtomicReference<String?>()
        val worker = Thread {
            lifecycle.update(token) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                connection.set("published")
            }
        }
        val resetter = Thread {
            resetting.countDown()
            lifecycle.reset { connection.set(null) }
            cleaned.countDown()
        }
        try {
            worker.start()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            resetter.start()
            assertTrue(resetting.await(3, TimeUnit.SECONDS))
            assertFalse("Reset must wait for in-flight publication", cleaned.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            worker.join(3000)
            resetter.join(3000)
        }
        assertTrue(cleaned.await(1, TimeUnit.SECONDS))
        assertEquals(null, connection.get())
        assertFalse(lifecycle.isCurrent(token))
    }
}
