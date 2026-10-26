package ai.opencode.ide.jetbrains.util

object NetworkAddress {
    fun httpBaseUrl(hostname: String, port: Int): String {
        val host = hostname.trim().removePrefix("[").removeSuffix("]")
        val authorityHost = if (host.contains(':')) "[$host]" else host
        return "http://$authorityHost:$port"
    }
}
