package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.diagnostic.Logger
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import java.io.IOException

/**
 * Utility for finding available ports for OpenCode server.
 * 
 * Port allocation strategy:
 * - Starts from port 4096 and increments until an available port is found
 * - Once a port is allocated, it is fixed for the lifetime of that session
 * - No scanning for existing servers - each project gets its own server instance
 */
object PortFinder {
    private val logger = Logger.getInstance(PortFinder::class.java)
    private const val START_PORT = 4096
    private const val MAX_ATTEMPTS = 100

    /**
     * Finds an available port starting from START_PORT.
     * Checks both that the port is not bound AND that no OpenCode server is running on it.
     * @return An available port number
     * @throws IOException if no available port is found within MAX_ATTEMPTS
     */
    fun findAvailablePort(): Int {
        logger.info("Searching for available port starting from $START_PORT")
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            if (isPortAvailable(port)) {
                logger.info("Found available port: $port")
                return port
            }
            logger.debug("Port $port is not available")
        }
        throw IOException("No available port found in range $START_PORT-${START_PORT + MAX_ATTEMPTS - 1}")
    }

    /**
     * Checks if a specific port is available for use (not bound by any process).
     * Checks availability by:
     * 1. Attempting to connect to 127.0.0.1 (If successful, it's occupied)
     * 2. Attempting to bind specifically to 127.0.0.1 (Explicit IPv4 check)
     */
    fun isPortAvailable(port: Int): Boolean {
        // Check 0: Try to connect to 127.0.0.1. If we can connect, someone is listening.
        if (isPortOpen("127.0.0.1", port, timeoutMs = 100)) {
            return false
        }

        // Check 1: Explicit IPv4 localhost bind check
        return try {
            java.net.ServerSocket().use { s ->
                s.reuseAddress = false
                s.bind(java.net.InetSocketAddress("127.0.0.1", port))
                true
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Checks if OpenCode server is running and healthy on the specified port.
     * Uses Proxy.NO_PROXY to bypass system proxy settings.
     * Supports HTTP Basic Authentication if credentials are provided.
     */
    fun isOpenCodeRunningOnPort(
        port: Int, 
        hostname: String = "127.0.0.1",
        username: String? = null,
        password: String? = null
    ): Boolean {
        return OpenCodeApiClient(hostname, port, username, password).checkHealth()
    }

    /**
     * Scans for a running OpenCode server in the port range.
     * @return The port number if a running server is found, null otherwise
     */
    fun findRunningOpenCodeServer(): Int? {
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            if (isOpenCodeRunningOnPort(port)) {
                return port
            }
        }
        return null
    }

    /**
     * Waits for the specified port to be bound and accepting connections.
     * @param port The port to wait for
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return true if port became available, false if timed out
     */
    fun waitForPort(
        port: Int,
        host: String = "127.0.0.1",
        timeoutMs: Long = 10000,
        requireHealth: Boolean = true,
        username: String? = null,
        password: String? = null
    ): Boolean {
        logger.info("Waiting for port $host:$port (timeout=${timeoutMs}ms, requireHealth=$requireHealth)")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val ready = if (requireHealth) {
                // Important: Health check implies HTTP connectivity and OpenCode readiness.
                isOpenCodeRunningOnPort(port, host, username, password)
            } else {
                isPortOpen(host, port)
            }

            if (ready) {
                logger.info("Port $host:$port is ready.")
                return true
            }
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        logger.warn("Timed out waiting for port $host:$port after ${timeoutMs}ms")
        return false
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int = 1000): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
