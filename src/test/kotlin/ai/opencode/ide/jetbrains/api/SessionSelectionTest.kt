package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.runtime.OpenCodeCli
import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSelectionTest {
    private val directory = "/tmp/project with spaces+suffix"
    private val gson = Gson()

    private class Server(body: String, status: Int = 200) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var query = emptyMap<String, String>()
        var authorization: String? = null
        val port: Int get() = server.address.port

        init {
            server.createContext("/api/session") { request ->
                query = request.requestURI.rawQuery.split('&').associate { part ->
                    val (key, value) = part.split('=', limit = 2)
                    URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
                }
                authorization = request.requestHeaders.getFirst("Authorization")
                val bytes = body.toByteArray()
                request.sendResponseHeaders(status, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun client() = OpenCodeApiClient("127.0.0.1", port, "opencode", "secret")
        override fun close() = server.stop(0)
    }

    private fun response(id: Any?, path: String = directory, parent: String? = null): String = gson.toJson(
        mapOf("data" to listOf(mapOf("id" to id, "location" to mapOf("directory" to path), "parentID" to parent)))
    )

    @Test
    fun aProjectWithoutHistoryLaunchesWithoutContinueOrSession() {
        Server("""{"data":[],"cursor":{}}""").use { server ->
            val id = server.client().getLatestSessionId(directory)
            assertNull(id)
            val command = OpenCodeCli().buildCommand("opencode", "127.0.0.1", server.port, "secret", id)
            assertFalse(command.contains("--continue"))
            assertFalse(command.contains("--session"))
        }
    }

    @Test
    fun historyLookupRequestsTheNewestRootInTheConfiguredDirectory() {
        Server(response("ses_existing")).use { server ->
            val id = server.client().getLatestSessionId(directory)
            assertEquals("ses_existing", id)
            assertEquals(mapOf("directory" to directory, "parentID" to "null", "order" to "desc", "limit" to "1"),
                server.query)
            assertEquals(okhttp3.Credentials.basic("opencode", "secret"), server.authorization)
            val command = OpenCodeCli().buildCommand("opencode", "127.0.0.1", server.port, "secret", id)
            assertTrue(command.endsWith(" --session ses_existing"))
            assertFalse(command.contains("--continue"))
        }
    }

    @Test
    fun anotherWorkspaceOrAChildSessionCannotBecomeTheStartupTarget() {
        for (body in listOf(response("ses_other", path = "/tmp/another-project"),
            response("ses_child", parent = "ses_parent"))) {
            Server(body).use { assertNull(it.client().getLatestSessionId(directory)) }
        }
    }

    @Test
    fun placeholdersUrlsAndMalformedIdentifiersCannotBecomeTheStartupTarget() {
        for (id in listOf(null, 123, listOf("ses_array"), "", "dummy", "pending", "opencode:ses_existing",
            "94807773-1228-4079-ab53-a2de32e6a697", "ses_bad;echo injected", "ses_bad\"value")) {
            Server(response(id)).use { assertNull(it.client().getLatestSessionId(directory)) }
        }
    }

    @Test
    fun failedAndMalformedListsDoNotReturnASession() {
        Server("unavailable", status = 503).use {
            assertThrows(IOException::class.java) { it.client().getLatestSessionId(directory) }
        }
        Server("""{"unexpected":[]}""").use {
            assertThrows(IOException::class.java) { it.client().getLatestSessionId(directory) }
        }
    }
}
