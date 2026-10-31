package ai.opencode.ide.jetbrains.api.models

private val sessionIdPattern = Regex("ses[A-Za-z0-9_-]*")

/** Only forward session IDs in their shell-safe wire form, never placeholders or URLs. */
internal fun isSessionId(value: String): Boolean = sessionIdPattern.matches(value)
