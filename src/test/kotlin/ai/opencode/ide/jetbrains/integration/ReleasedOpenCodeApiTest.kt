package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.ConnectionProbe
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.InboxCancelledEvent
import ai.opencode.ide.jetbrains.api.models.InboxEnqueuedEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/** Optional real-release contract check. No model requests, credentials, or shared service are used. */
class ReleasedOpenCodeApiTest {
    @Test
    fun privateReleasedServerMatchesTheClientContract() {
        val binary = System.getenv("OPENCODE_TEST_BINARY")?.let(::File)
        assumeTrue("Set OPENCODE_TEST_BINARY to an official V2 release", binary?.canExecute() == true)
        val executable = checkNotNull(binary)
        val root = createTempDirectory("opencode-release-contract-").toFile()
        val workspace = File(root, "project").apply { mkdir() }
        val password = UUID.randomUUID().toString()
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(executable.path, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
            .directory(workspace).redirectErrorStream(true).redirectOutput(File(root, "server.log"))
        process.environment().apply {
            for (name in listOf("CONFIG", "DATA", "CACHE", "STATE")) {
                put("XDG_${name}_HOME", File(root, name.lowercase()).path)
            }
            for (name in listOf("OPENCODE_CONFIG", "OPENCODE_CONFIG_CONTENT", "OPENCODE_CONFIG_DIR", "OPENCODE_DB")) {
                remove(name)
            }
            put("OPENCODE_PASSWORD", password)
        }
        val server = process.start()
        try {
            val api = OpenCodeApiClient("127.0.0.1", port, "opencode", password)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (api.probe() != ConnectionProbe.Ready && server.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(100)
            }
            assertEquals(ConnectionProbe.Ready, api.probe())
            assertEquals(ConnectionProbe.AuthenticationRequired,
                OpenCodeApiClient("127.0.0.1", port, "opencode", "incorrect").probe())
            assertTrue(api.getActiveSessionIds().isEmpty())
            val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(5, TimeUnit.SECONDS).build()
            fun request(path: String, method: String = "POST", body: JsonObject? = null): JsonObject? {
                val payload = if (method == "DELETE") null else
                    checkNotNull(body).toString().toRequestBody("application/json".toMediaType())
                val call = Request.Builder().url("http://127.0.0.1:$port$path")
                    .header("Authorization", Credentials.basic("opencode", password)).method(method, payload).build()
                return http.newCall(call).execute().use { response ->
                    assertTrue("$method $path returned HTTP ${response.code}", response.isSuccessful)
                    response.body?.string()?.takeIf { it.isNotEmpty() }?.let { JsonParser.parseString(it).asJsonObject }
                }
            }
            val create = JsonObject().apply {
                add("location", JsonObject().apply { addProperty("directory", workspace.path) })
            }
            val id = checkNotNull(request("/api/session", body = create)).getAsJsonObject("data").get("id").asString
            val state = api.getSessionState(id)
            assertEquals(workspace.canonicalPath, state.directory)
            assertNull(state.busyAt)
            assertNull(state.idleAt)
            assertFalse(state.completedAfter(null))
            val connected = CountDownLatch(1)
            val events = CopyOnWriteArrayList<OpenCodeEvent>()
            val errors = CopyOnWriteArrayList<Throwable>()
            val stream = api.createEventStream(workspace.path, { events.add(it) }, { errors.add(it) },
                { connected.countDown() }, {})
            try {
                stream.connect()
                assertTrue(connected.await(5, TimeUnit.SECONDS))
                val input = JsonObject().apply {
                    addProperty("text", "Offline contract test: input is admitted without execution.")
                    addProperty("resume", false)
                }
                val prompt = checkNotNull(request("/api/session/$id/prompt", body = input))
                val message = prompt.getAsJsonObject("data").get("id").asString
                request("/api/session/$id/inbox/$message", method = "DELETE")
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (events.none { it is InboxCancelledEvent } && System.nanoTime() < until) Thread.sleep(10)
                assertTrue(events.any { it is InboxEnqueuedEvent && it.data.inboxID == message })
                assertTrue(events.any { it is InboxCancelledEvent && it.data.inboxID == message })
                assertTrue(errors.isEmpty())
                assertThrows(IOException::class.java) { api.getSessionDiff(id, message) }
                request("/api/session/$id", method = "DELETE")
            } finally {
                stream.disconnect()
            }
        } finally {
            server.destroy()
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly()
                server.waitFor(5, TimeUnit.SECONDS)
            }
            root.deleteRecursively()
        }
    }
}
