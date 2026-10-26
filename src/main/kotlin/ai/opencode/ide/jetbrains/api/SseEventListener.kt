package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.OpenCodeEventDeserializer
import ai.opencode.ide.jetbrains.api.models.SessionExecutionEvent
import ai.opencode.ide.jetbrains.util.PathUtil
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE (Server-Sent Events) listener for OpenCode server.
 */
class SseEventListener(
    private val baseUrl: String,
    private val projectPath: String,
    private val onEvent: (OpenCodeEvent) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val username: String? = null,
    private val password: String? = null
) : OpenCodeEventStream {
    private val logger = Logger.getInstance(SseEventListener::class.java)

    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .apply {
            if (username != null && password != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password))
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    private val gson = GsonBuilder()
        .registerTypeAdapter(OpenCodeEvent::class.java, OpenCodeEventDeserializer())
        .create()

    @Volatile
    private var call: Call? = null
    private val isActive = AtomicBoolean(false)
    private val workspaceSessions = mutableSetOf<String>()

    override fun connect() {
        if (!isActive.compareAndSet(false, true)) return
        doConnect()
    }

    private fun doConnect() {
        val url = "$baseUrl/api/event"

        logger.info("[SSE] Connecting to $url")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        call = client.newCall(request)
        call?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isActive.compareAndSet(true, false)) return
                logger.warn("[SSE] Connection failure: ${e.message}")
                onError(e)
                onDisconnected()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            val body = try {
                                response.peekBody(1024).string()
                            } catch (_: Exception) {
                                "no body"
                            }
                            throw IOException("SSE failed: ${response.code}. Response: $body")
                        }

                        val source = response.body?.source()
                            ?: throw IOException("SSE response has no body")
                        onConnected()
                        logger.info("[SSE] Connection established")

                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                val json = line.substring(5).trim()
                                if (json.isNotEmpty()) parseAndDispatchEvent(json)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            logger.warn("[SSE] Stream interrupted: ${e.message}")
                            onError(e)
                        }
                    } finally {
                        if (isActive.compareAndSet(true, false)) {
                            onDisconnected()
                        }
                    }
                }
            }
        })
    }

    private fun parseAndDispatchEvent(json: String) {
        try {
            val payload = JsonParser.parseString(json).asJsonObject
            val sessionId = payload.getAsJsonObject("data")?.get("sessionID")?.asString
            val location = payload.getAsJsonObject("location")?.get("directory")?.asString
            if (location != null) {
                val matches = PathUtil.toOpenCodeServerPath(location) == PathUtil.toOpenCodeServerPath(projectPath)
                if (sessionId != null) {
                    if (matches) workspaceSessions.add(sessionId) else workspaceSessions.remove(sessionId)
                }
                if (!matches) return
            }
            val event = gson.fromJson(payload, OpenCodeEvent::class.java)
            // V2 execution events may have no location. Scope them by a previously located session.
            if (location == null && (event !is SessionExecutionEvent || sessionId !in workspaceSessions)) return
            if (event != null) onEvent(event)
        } catch (e: Exception) {
            logger.debug("[SSE] Parse error: ${e.message}")
        }
    }

    override fun disconnect() {
        call?.cancel()
        call = null
        isActive.set(false)
    }
}
