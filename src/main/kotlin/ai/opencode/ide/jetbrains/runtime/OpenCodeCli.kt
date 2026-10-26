package ai.opencode.ide.jetbrains.runtime

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import ai.opencode.ide.jetbrains.util.NetworkAddress
import java.io.File

/** Resolves and launches the installed OpenCode CLI without involving UI state. */
class OpenCodeCli {

    private val logger = Logger.getInstance(OpenCodeCli::class.java)

    @Volatile
    private var cachedBinary: String? = null

    /** Full detection, including PATH lookup. Call from a background thread. */
    fun detect(): String? {
        cachedBinary?.let { return it }
        val candidates = candidates().filter { it.exists() && it.canExecute() }.map { it.absolutePath } +
            listOfNotNull(locateOnPath())
        candidates.distinct().firstOrNull(::isV2)?.let { return cache(it) }
        return null
    }

    /** Fast resolution for an already-known installation. Safe to call on the EDT. */
    fun command(): String {
        cachedBinary?.let { return it }
        return findInstalledBinary()?.let(::cache) ?: "opencode"
    }

    fun buildCommand(
        command: String,
        hostname: String,
        port: Int,
        password: String?,
        continueSession: Boolean
    ): String {
        val continueFlag = if (continueSession) " --continue" else ""
        val host = if (hostname == "0.0.0.0") "127.0.0.1" else hostname
        val url = NetworkAddress.httpBaseUrl(host, port)
        if (isWindows()) {
            val executable = if (command.contains(' ')) "\"$command\"" else command
            val base = "$executable --server $url$continueFlag"
            if (password.isNullOrBlank()) return base
            val escaped = password.replace("\"", "\\\"")
            return "cmd /c \"set \"OPENCODE_PASSWORD=$escaped\" && $base\""
        }

        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val environment = if (password.isNullOrBlank()) "" else "env OPENCODE_PASSWORD=${quote(password)} "
        // Replace the shell so the PTY process ends when the OpenCode TUI exits.
        return "exec $environment${quote(command)} --server ${quote(url)}$continueFlag"
    }

    /** The V2 TUI attaches to an already-running server. Own this process separately. */
    fun startServer(binary: String, hostname: String, port: Int, directory: String?, password: String): Process {
        val process = ProcessBuilder(binary, "serve", "--hostname", hostname, "--port", port.toString())
        if (directory != null) process.directory(File(directory))
        process.environment()["OPENCODE_PASSWORD"] = password
        process.redirectErrorStream(true)
        process.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return process.start()
    }

    private fun findInstalledBinary(): String? {
        return candidates().firstOrNull { it.exists() && it.canExecute() }?.absolutePath
    }

    private fun isV2(path: String): Boolean = try {
        val output = CapturingProcessHandler(GeneralCommandLine(path, "--version")).runProcess(5000)
        output.exitCode == 0 && Regex("\\bv?2\\.\\d+").containsMatchIn(output.stdout)
    } catch (_: Exception) {
        false
    }

    private fun candidates(): List<File> {
        val userHome = System.getProperty("user.home")
        return if (isWindows()) {
            listOf(
                File(userHome, ".opencode/bin/opencode.exe"),
                File("C:\\Program Files\\opencode\\opencode.exe"),
                File("C:\\Program Files (x86)\\opencode\\opencode.exe"),
                File(System.getenv("LOCALAPPDATA").orEmpty(), "opencode\\opencode.exe")
            )
        } else {
            listOf(
                File(userHome, ".opencode/bin/opencode"),
                File("/opt/homebrew/bin/opencode"),
                File("/usr/local/bin/opencode"),
                File("/home/linuxbrew/.linuxbrew/bin/opencode"),
                File(userHome, ".linuxbrew/bin/opencode"),
                File("/usr/bin/opencode"),
                File("/snap/bin/opencode")
            )
        }
    }

    private fun locateOnPath(): String? {
        val commands = if (isWindows()) {
            listOf(
                listOf("cmd", "/c", "where", "opencode"),
                listOf("powershell", "-Command", "Get-Command opencode")
            )
        } else {
            listOf(
                listOf("which", "opencode"),
                listOf("sh", "-lc", "command -v opencode")
            )
        }
        for (command in commands) {
            try {
                val output = CapturingProcessHandler(GeneralCommandLine(command)).runProcess(5000)
                if (output.exitCode == 0) {
                    val path = output.stdoutLines.firstOrNull { it.isNotBlank() }?.trim()
                    if (path != null && File(path).canExecute()) return path
                }
            } catch (_: Exception) {
                // Try the next search command.
            }
        }
        return null
    }

    private fun cache(path: String): String {
        logger.info("[OpenCode] Resolved CLI to: $path")
        cachedBinary = path
        return path
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("windows")
    }
}
