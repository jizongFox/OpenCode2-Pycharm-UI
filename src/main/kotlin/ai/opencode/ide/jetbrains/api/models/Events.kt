package ai.opencode.ide.jetbrains.api.models

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type

/** Public V2 events used for turn review. Other events need no model here. */
sealed class OpenCodeEvent(open val type: String)

data class SessionExecutionData(val sessionID: String, val reason: String? = null)
data class SessionExecutionEvent(
    override val type: String, val data: SessionExecutionData, val created: Long? = null
) : OpenCodeEvent(type)

data class InboxEnqueuedEvent(val data: InboxEnqueuedData) : OpenCodeEvent("session.inbox.enqueued")
data class InboxEnqueuedData(val sessionID: String, val inboxID: String, val item: InboxItem)
data class InboxItem(val type: String)

data class InboxDeliveredEvent(val data: InboxReference) : OpenCodeEvent("session.inbox.delivered")
data class InboxCancelledEvent(val data: InboxReference) : OpenCodeEvent("session.inbox.cancelled")
data class InboxReference(val sessionID: String, val inboxID: String)

data class UnknownEvent(override val type: String) : OpenCodeEvent(type)

class OpenCodeEventDeserializer : JsonDeserializer<OpenCodeEvent> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): OpenCodeEvent {
        val value = json.asJsonObject
        val type = value.get("type")?.asString ?: return UnknownEvent("unknown")
        return when (type) {
            "session.execution.started", "session.execution.succeeded",
            "session.execution.failed", "session.execution.interrupted" ->
                SessionExecutionEvent(type, context.deserialize(value.get("data"), SessionExecutionData::class.java),
                    value.get("created")?.asLong)
            "session.inbox.enqueued" -> context.deserialize(json, InboxEnqueuedEvent::class.java)
            "session.inbox.delivered" -> context.deserialize(json, InboxDeliveredEvent::class.java)
            "session.inbox.cancelled" -> context.deserialize(json, InboxCancelledEvent::class.java)
            else -> UnknownEvent(type)
        }
    }
}
