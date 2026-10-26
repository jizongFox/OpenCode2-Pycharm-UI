package ai.opencode.ide.jetbrains.api

/** Result of checking both V2 identity and the authenticated active-session endpoint. */
sealed interface ConnectionProbe {
    data object Ready : ConnectionProbe
    data object AuthenticationRequired : ConnectionProbe
    data class Unsupported(val reason: String) : ConnectionProbe
    data class Unavailable(val reason: String) : ConnectionProbe
}
