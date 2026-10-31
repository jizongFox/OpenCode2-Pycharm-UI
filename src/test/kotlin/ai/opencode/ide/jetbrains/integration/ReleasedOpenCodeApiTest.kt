package ai.opencode.ide.jetbrains.integration

import ai.opencode.ide.jetbrains.api.ConnectionProbe
import ai.opencode.ide.jetbrains.api.OpenCodeApiClient
import ai.opencode.ide.jetbrains.api.models.InboxCancelledEvent
import ai.opencode.ide.jetbrains.api.models.InboxEnqueuedEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.runtime.OpenCodeCli
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pty4j.PtyProcessBuilder
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
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory

/** Optional real-release check. No model requests, provider credentials, or shared service are used. */
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
            assertNull(api.getLatestSessionId(workspace.canonicalPath))
            assertTuiStartup(executable, workspace, port, password, process.environment(), null)
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
            assertEquals(id, api.getLatestSessionId(workspace.canonicalPath))
            assertTuiStartup(executable, workspace, port, password, process.environment(), id)
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
                assertNull(api.getLatestSessionId(workspace.canonicalPath))
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

    /** Exercise the same generated command as the IDE terminal without submitting a model request. */
    private fun assertTuiStartup(
        executable: File,
        workspace: File,
        port: Int,
        password: String,
        environment: Map<String, String>,
        sessionId: String?
    ) {
        if (!System.getProperty("os.name").startsWith("Linux")) return
        val command = OpenCodeCli().buildCommand(executable.path, "127.0.0.1", port, password, sessionId)
        val terminalEnvironment = environment + mapOf(
            "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "OPENCODE_DISABLE_AUTOUPDATE" to "1"
        )
        val tui = PtyProcessBuilder(arrayOf("/bin/bash", "--noprofile", "--norc", "-c", command))
            .setDirectory(workspace.path).setEnvironment(terminalEnvironment)
            .setInitialColumns(160).setInitialRows(35).setRedirectErrorStream(true).start()
        val output = StringBuffer()
        // Pty4j's available() always returns zero; consume its blocking stream on an owned reader.
        val reader = thread(isDaemon = true, name = "opencode-tui-contract-reader") {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = tui.inputStream.read(buffer)
                    if (count < 0) break
                    val chunk = String(buffer, 0, count, Charsets.UTF_8)
                    output.append(chunk)
                    if (chunk.contains("\u001b[6n")) tui.outputStream.write("\u001b[1;1R".toByteArray())
                    if (chunk.contains("\u001b[c") || chunk.contains("\u001b[0c")) {
                        tui.outputStream.write("\u001b[?1;2c".toByteArray())
                    }
                    if (chunk.contains("\u001b]11;?")) {
                        tui.outputStream.write("\u001b]11;rgb:0000/0000/0000\u001b\\".toByteArray())
                    }
                    tui.outputStream.flush()
                }
            } catch (_: IOException) {
                // Terminating the owned TUI closes the PTY.
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7)
            while (tui.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            val text = output.toString().replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
            assertTrue("The released TUI must remain open after startup", tui.isAlive)
            assertTrue("The released TUI must render its interface: ${text.takeLast(1200)}", text.contains("OpenCode"))
            assertFalse("Startup must not route through an invalid placeholder session",
                text.contains("Expected a string starting with"))
        } finally {
            tui.destroy()
            if (!tui.waitFor(5, TimeUnit.SECONDS)) {
                tui.destroyForcibly()
                tui.waitFor(5, TimeUnit.SECONDS)
            }
            tui.inputStream.close()
            tui.outputStream.close()
            reader.join(1000)
        }
    }
}
