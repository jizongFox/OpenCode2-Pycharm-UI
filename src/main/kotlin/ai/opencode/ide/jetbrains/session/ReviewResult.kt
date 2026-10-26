package ai.opencode.ide.jetbrains.session

/** Distinguishes a stale review from an operation that failed and can be retried. */
sealed interface ReviewResult {
    data object Applied : ReviewResult
    data object Stale : ReviewResult
    data class Failed(val message: String) : ReviewResult
}
