package ai.opencode.ide.jetbrains.integration

import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Wire-compatible subset of the released V2 API. */
class FakeOpenCodeServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val streams = CopyOnWriteArrayList<OutputStream>()
    private val responses = ConcurrentHashMap<String, String>()
    private val errors = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newCachedThreadPool()
    val requestedTurns = CopyOnWriteArrayList<String>()
    val port: Int get() = server.address.port

    init {
        server.createContext("/api/info") { ex ->
            val body = """{"version":"2.0.18","pid":123,"urls":[],"paths":{"tmp":"/tmp"}}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/api/session/active") { ex ->
            val body = """{"data":{}}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/api/session/") { ex ->
            val from = ex.requestURI.rawQuery?.substringAfter("from=", "")
                ?.substringBefore('&')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            requestedTurns.add(from)
            val body = responses[from] ?: """{"data":[]}"""
            ex.sendResponseHeaders(if (from in errors) 503 else 200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/api/event") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            val stream = ex.responseBody
            streams.add(stream)
            try {
                stream.write(": connected\n\n".toByteArray())
                stream.flush()
                while (!Thread.currentThread().isInterrupted) Thread.sleep(1000)
            } catch (_: Exception) {
                streams.remove(stream)
            }
        }
        server.executor = executor
    }

    fun start() = server.start()
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }
    fun respondTo(from: String, json: String) { responses[from] = json }
    fun respondErrorTo(from: String) { errors.add(from) }

    fun broadcast(json: String) {
        streams.forEach {
            try { it.write("data: $json\n\n".toByteArray()); it.flush() }
            catch (_: Exception) { streams.remove(it) }
        }
    }

    fun waitForSubscriber(): Boolean {
        val deadline = System.currentTimeMillis() + 3000
        while (streams.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return streams.isNotEmpty()
    }
}
