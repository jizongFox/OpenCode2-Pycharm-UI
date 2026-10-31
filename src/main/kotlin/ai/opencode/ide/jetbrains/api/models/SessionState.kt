package ai.opencode.ide.jetbrains.api.models

/** Authoritative session projection; active-session listings describe process ownership only. */
data class SessionState(
    val id: String,
    val directory: String,
    val busyAt: Long?,
    val idleAt: Long?
) {
    fun completedAfter(observedStart: Long?): Boolean {
        val start = listOfNotNull(busyAt, observedStart).maxOrNull() ?: return false
        return idleAt?.let { it >= start } == true
    }
}
