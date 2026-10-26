package ai.opencode.ide.jetbrains.util

import com.intellij.openapi.util.io.FileUtil

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Centralizes path conversion and keeps mutation paths inside one workspace root. */
object PathUtil {
    fun toSystemIndependentPath(path: String): String {
        return FileUtil.toSystemIndependentName(path)
    }

    fun toOpenCodeServerPath(path: String): String {
        val normalized = FileUtil.toSystemIndependentName(path)
        return if (isWindows()) normalized.replace("/", "\\") else normalized
    }

    fun resolveWithinRoot(basePath: String?, filePath: String): String? {
        val root = normalizedRoot(basePath) ?: return null
        val raw = unquote(filePath) ?: return null

        return try {
            val input = Paths.get(FileUtil.toSystemDependentName(raw))
            val resolved = (if (input.isAbsolute) input else root.resolve(input)).normalize().toAbsolutePath()
            if (!resolved.startsWith(root) || escapesThroughSymlink(root, resolved)) return null
            toSystemIndependentPath(resolved.toString())
        } catch (_: Exception) {
            null
        }
    }

    fun relativizeWithinRoot(basePath: String?, filePath: String): String? {
        val root = normalizedRoot(basePath) ?: return null
        val resolved = resolveWithinRoot(basePath, filePath) ?: return null
        return try {
            val relative = toSystemIndependentPath(root.relativize(Paths.get(resolved)).toString())
            relative.ifBlank { "." }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Formats a path explicitly selected by the user as OpenCode context.
     *
     * Context references are intentionally less restrictive than mutation paths: files inside
     * the active workspace use compact relative paths, while explicitly selected external files
     * remain usable through normalized absolute paths. File writes must continue to use
     * [resolveWithinRoot].
     */
    fun contextReferencePath(basePath: String?, filePath: String): String? {
        val raw = unquote(filePath) ?: return null
        return try {
            val root = normalizedRoot(basePath)
            val input = Paths.get(FileUtil.toSystemDependentName(raw))
            val resolved = (if (input.isAbsolute) input else root?.resolve(input) ?: input)
                .normalize()
                .toAbsolutePath()

            if (root != null && resolved.startsWith(root)) {
                val relative = toSystemIndependentPath(root.relativize(resolved).toString())
                relative.ifBlank { "." }
            } else {
                toSystemIndependentPath(resolved.toString())
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isWithinRoot(basePath: String?, filePath: String): Boolean {
        return resolveWithinRoot(basePath, filePath) != null
    }

    private fun normalizedRoot(basePath: String?): Path? {
        if (basePath.isNullOrBlank()) return null
        return try {
            Paths.get(FileUtil.toSystemDependentName(basePath)).normalize().toAbsolutePath()
        } catch (_: Exception) {
            null
        }
    }

    private fun unquote(filePath: String): String? {
        val raw = filePath.trim()
        if (raw.isEmpty()) return null
        return if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun escapesThroughSymlink(root: Path, resolved: Path): Boolean {
        if (!Files.exists(root)) return false

        val realRoot = try {
            root.toRealPath()
        } catch (_: Exception) {
            return false
        }

        var existing: Path? = resolved
        while (existing != null && !Files.exists(existing)) {
            existing = existing.parent
        }
        if (existing == null) return true

        return try {
            !existing.toRealPath().startsWith(realRoot)
        } catch (_: Exception) {
            true
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase().contains("windows")
    }
}
