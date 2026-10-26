package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.util.PortFinder
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProbeTest {
    private class Server(
        infoStatus: Int = 200,
        info: String = """{"version":"2.0.18"}""",
        activeStatus: Int = 200,
        active: String = """{"data":{}}"""
    ) : AutoCloseable {
        val authorization = mutableListOf<String?>()
        var activeRequests = 0
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port: Int get() = server.address.port

        init {
            for ((path, response) in mapOf("/api/info" to (infoStatus to info),
                "/api/session/active" to (activeStatus to active))) {
                server.createContext(path) { request ->
                    authorization.add(request.requestHeaders.getFirst("Authorization"))
                    if (path == "/api/session/active") activeRequests++
                    val bytes = response.second.toByteArray()
                    request.sendResponseHeaders(response.first, bytes.size.toLong())
                    request.responseBody.use { it.write(bytes) }
                }
            }
            server.start()
        }

        fun client() = OpenCodeApiClient("127.0.0.1", port, "opencode", "secret")
        override fun close() = server.stop(0)
    }

    @Test
    fun readyRequiresBothEndpointsAndUsesTheSameCredentials() {
        Server().use { server ->
            assertEquals(ConnectionProbe.Ready, server.client().probe())
            assertEquals(1, server.activeRequests)
            val expected = okhttp3.Credentials.basic("opencode", "secret")
            assertEquals(listOf(expected, expected), server.authorization)
        }
    }

    @Test
    fun authenticationFailureFromEitherEndpointIsRecognized() {
        for (status in listOf(401, 403)) {
            Server(infoStatus = status).use {
                assertEquals(ConnectionProbe.AuthenticationRequired, it.client().probe())
            }
            Server(activeStatus = status).use {
                assertEquals(ConnectionProbe.AuthenticationRequired, it.client().probe())
            }
        }
    }

    @Test
    fun missingCapabilityIsNotReportedAsAWrongPassword() {
        Server(activeStatus = 404).use {
            assertTrue(it.client().probe() is ConnectionProbe.Unsupported)
        }
    }

    @Test
    fun serverErrorIsNotReportedAsAWrongPassword() {
        Server(activeStatus = 503).use {
            val result = it.client().probe()
            assertTrue(result is ConnectionProbe.Unavailable)
            assertTrue((result as ConnectionProbe.Unavailable).reason.contains("503"))
        }
    }

    @Test
    fun identityAndPayloadValidationArePreserved() {
        Server(info = """{"version":"1.0.0"}""").use {
            assertTrue(it.client().probe() is ConnectionProbe.Unsupported)
            assertEquals(0, it.activeRequests)
        }
        Server(info = "not json").use { assertTrue(it.client().probe() is ConnectionProbe.Unsupported) }
        Server(active = """{"data":null}""").use {
            assertTrue(it.client().probe() is ConnectionProbe.Unsupported)
        }
    }

    @Test
    fun closedPortIsUnavailable() {
        val port = ServerSocket(0).use { it.localPort }
        assertTrue(OpenCodeApiClient("127.0.0.1", port).probe() is ConnectionProbe.Unavailable)
    }

    @Test
    fun localReadinessAlsoRequiresTheActiveSessionCapability() {
        Server(activeStatus = 401).use {
            assertFalse(PortFinder.isOpenCodeRunningOnPort(it.port, "127.0.0.1", "opencode", "secret"))
        }
    }
}
