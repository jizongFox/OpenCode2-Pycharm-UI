package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.OpenCodeService
import ai.opencode.ide.jetbrains.ui.OpenCodeConnectDialog.ConnectionInfo.ExistingServer
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionStartupTest : BasePlatformTestCase() {
    fun testLaunchAndCitationsShareThePendingConnectionProbe() {
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/info") { exchange ->
            exchange.use {
                probeStarted.countDown()
                if (releaseProbe.await(5, TimeUnit.SECONDS)) it.sendResponseHeaders(503, -1)
            }
        }
        server.start()

        val callbacks = ConcurrentLinkedQueue<Runnable>()
        fun drainUi() {
            while (true) (callbacks.poll() ?: break).run()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }

        val endpoint = ExistingServer("127.0.0.1", server.address.port, null, null)
        val choices = mutableListOf<ExistingServer?>()
        val service = OpenCodeService(project).apply {
            invokeLater = { callbacks.add(it) }
            chooseConnection = { retry ->
                choices.add(retry)
                endpoint.takeIf { choices.size == 1 }
            }
        }
        TestDialogManager.setTestDialog { Messages.OK }
        try {
            service.focusOrCreateTerminalAndPaste("@first.py ")
            drainUi()
            assertTrue("The server probe must be in flight", probeStarted.await(3, TimeUnit.SECONDS))

            service.focusOrCreateTerminal(interactive = true)
            service.focusOrCreateTerminalAndPaste("@second.py ")
            drainUi()
            assertEquals(listOf<ExistingServer?>(null), choices)

            releaseProbe.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (choices.size == 1 && System.nanoTime() < deadline) {
                drainUi()
                Thread.sleep(10)
            }
            assertEquals(listOf(null, endpoint), choices)

            service.focusOrCreateTerminal(interactive = true)
            drainUi()
            assertEquals(listOf(null, endpoint, null), choices)
        } finally {
            service.dispose()
            releaseProbe.countDown()
            server.stop(0)
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }
    }
}
