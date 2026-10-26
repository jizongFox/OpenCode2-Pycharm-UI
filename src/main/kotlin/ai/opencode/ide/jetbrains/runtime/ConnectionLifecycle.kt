package ai.opencode.ide.jetbrains.runtime

/** Serializes connection publication with reset, so a late worker cannot revive an old connection. */
internal class ConnectionLifecycle(private val projectDisposed: () -> Boolean = { false }) {
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun isCurrent(token: Long): Boolean = !closed && !projectDisposed() && token == generation

    @Synchronized
    fun update(token: Long, action: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        action()
        return true
    }

    @Synchronized
    fun reset(close: Boolean = false, clear: () -> Unit): Long {
        generation++
        closed = closed || close
        clear()
        return generation
    }
}
