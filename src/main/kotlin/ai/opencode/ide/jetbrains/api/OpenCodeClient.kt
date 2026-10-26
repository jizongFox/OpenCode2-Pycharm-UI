package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.FileDiff
import ai.opencode.ide.jetbrains.api.models.OpenCodeEvent

/**
 * The OpenCode capabilities used by the IDE integration.
 *
 * Keeping the rest of the plugin behind this small boundary makes a future
 * protocol adapter possible without leaking transport details into the UI.
 */
interface OpenCodeClient {
    fun checkHealth(): Boolean

    fun getActiveSessionIds(): Set<String>

    fun getSessionDiff(sessionId: String, fromMessageId: String): List<FileDiff>

    fun createEventStream(
        directory: String,
        onEvent: (OpenCodeEvent) -> Unit,
        onError: (Throwable) -> Unit,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {}
    ): OpenCodeEventStream

}

interface OpenCodeEventStream {
    fun connect()

    fun disconnect()
}
