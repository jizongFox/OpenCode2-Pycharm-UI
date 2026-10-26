package ai.opencode.ide.jetbrains.terminal

/** Holds citations until a terminal exists, then owns their ordered, bounded retries. */
internal class PromptDelivery(
    private val paste: (String) -> Boolean,
    private val onDelivered: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val postToUi: (() -> Unit) -> Unit,
    private val maxAttempts: Int = 120,
    private val retryDelayMs: Long = 250
) {
    private data class Pending(val text: String, var attempts: Int = 0)

    private val pending = ArrayDeque<Pending>()
    private var scheduled = false
    private var generation = 0L

    @Synchronized
    fun enqueue(text: String) {
        pending.addLast(Pending(text))
    }

    /** Called only after creating or finding the running terminal. */
    @Synchronized
    fun resume() = scheduleDrain(0)

    @Synchronized
    fun pause(clearPending: Boolean) {
        generation++
        scheduled = false
        if (clearPending) pending.clear()
        else pending.forEach { it.attempts = 0 }
    }

    private fun scheduleDrain(delayMs: Long) {
        if (scheduled || pending.isEmpty()) return
        scheduled = true
        val token = generation
        schedule(delayMs) { drain(token) }
    }

    private fun drain(token: Long) {
        val next = synchronized(this) {
            if (token != generation) return
            pending.first()
        }
        val sent = paste(next.text)
        val exhausted = synchronized(this) {
            if (token != generation) return
            val failed = !sent && ++next.attempts >= maxAttempts
            if (sent || failed) pending.removeFirst()
            scheduled = false
            scheduleDrain(if (sent) 0 else retryDelayMs)
            failed
        }
        if (sent || exhausted) postToUi {
            // A reset also invalidates focus and failure notifications already queued on the EDT.
            if (synchronized(this) { token == generation }) {
                if (sent) onDelivered() else onFailure(next.text)
            }
        }
    }
}
