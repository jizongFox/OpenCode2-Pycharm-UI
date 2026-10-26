package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent
import ai.opencode.ide.jetbrains.api.models.fullFileDiff
import ai.opencode.ide.jetbrains.util.NetworkAddress
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Small V2 HTTP surface used for readiness, events and turn review. */
open class OpenCodeApiClient(
    hostname: String,
    port: Int,
    private val username: String? = null,
    private val password: String? = null
) : OpenCodeClient {
    private val logger = Logger.getInstance(OpenCodeApiClient::class.java)
    private val baseUrl = NetworkAddress.httpBaseUrl(hostname, port)
    private val client = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (username != null && password != null) {
                addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .header("Authorization", Credentials.basic(username, password)).build())
                }
            }
        }
        .build()

    private val probeClient = client.newBuilder().readTimeout(5, TimeUnit.SECONDS).build()

    override fun checkHealth(): Boolean = probe() == ConnectionProbe.Ready

    fun probe(): ConnectionProbe = try {
        val info = get("$baseUrl/api/info", probeClient)
        if (info.asJsonObject.get("version")?.asString?.startsWith("2.") != true) {
            ConnectionProbe.Unsupported("The server does not identify itself as OpenCode V2.")
        } else {
            val active = get("$baseUrl/api/session/active", probeClient)
            if (active.asJsonObject.get("data")?.isJsonObject == true) ConnectionProbe.Ready
            else ConnectionProbe.Unsupported("The V2 active-session response is missing its data object.")
        }
    } catch (e: HttpFailure) {
        when (e.status) {
            401, 403 -> ConnectionProbe.AuthenticationRequired
            404 -> ConnectionProbe.Unsupported("A required OpenCode V2 endpoint is unavailable (HTTP 404).")
            else -> ConnectionProbe.Unavailable(e.message ?: "The server returned an HTTP error.")
        }
    } catch (e: IOException) {
        ConnectionProbe.Unavailable(e.message ?: "Cannot reach the server.")
    } catch (_: RuntimeException) {
        ConnectionProbe.Unsupported("The server returned invalid OpenCode V2 data.")
    }

    override fun getActiveSessionIds(): Set<String> =
        get("$baseUrl/api/session/active").asJsonObject.getAsJsonObject("data")?.keySet()
            ?: throw IOException("Missing V2 active sessions data")

    override fun getSessionDiff(sessionId: String, fromMessageId: String): List<FileDiff> {
        val response = get("$baseUrl/api/session/${encode(sessionId)}/diff?from=${encode(fromMessageId)}")
        return try {
            val data = response.asJsonObject.getAsJsonArray("data")
                ?: throw IOException("Missing V2 turn diff data for $sessionId")
            data.map { element ->
                val diff = element.asJsonObject
                val file = diff.get("file")?.asString ?: throw IOException("Missing V2 diff path")
                val patch = diff.get("patch")?.asString ?: throw IOException("Missing V2 patch for $file")
                fullFileDiff(
                    file, patch,
                    diff.get("additions")?.asInt ?: 0,
                    diff.get("deletions")?.asInt ?: 0,
                    diff.get("status")?.asString
                ) ?: throw IOException("Unsupported V2 patch for $file")
            }
        } catch (e: Exception) {
            logger.warn("[OpenCode] Invalid V2 diff response for $sessionId", e)
            throw e
        }
    }

    override fun createEventStream(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit,
        onDisconnected: () -> Unit
    ): OpenCodeEventStream = SseEventListener(
        baseUrl, directory, onEvent, onError, onConnected, onDisconnected, username, password
    )

    private class HttpFailure(val status: Int) : IOException("V2 request failed: HTTP $status")

    private fun get(url: String, http: OkHttpClient = client): com.google.gson.JsonElement {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpFailure(response.code)
            val body = response.body?.string() ?: throw IOException("Empty V2 response")
            return JsonParser.parseString(body)
        }
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
}
