package ai.opencode.ide.jetbrains.api.models

data class FileDiff(
    val file: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int,
    val status: String? = null
)

/** V2 returns a full-file unified patch instead of before/after content. */
internal fun fullFileDiff(
    file: String, patch: String, additions: Int, deletions: Int, status: String? = null
): FileDiff? {
    val before = StringBuilder()
    val after = StringBuilder()
    var hunks = 0
    var lastPrefix: Char? = null
    var expectedBefore = 0
    var expectedAfter = 0
    var beforeLines = 0
    var afterLines = 0
    for (line in patch.split('\n')) {
        when {
            line.startsWith("@@ ") -> {
                // The endpoint promises a full-file patch when `context` is omitted.
                val header = Regex("@@ -(?:0|1)(?:,(\\d+))? \\+(?:0|1)(?:,(\\d+))? @@.*").matchEntire(line)
                    ?: return null
                if (++hunks != 1) return null
                expectedBefore = header.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return null
                expectedAfter = header.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
            }
            hunks == 0 -> if (!isPatchHeader(line)) return null
            line.startsWith("\\ No newline at end of file") -> {
                if (lastPrefix == null) return null
                if (lastPrefix != '+') before.removeFinalNewline()
                if (lastPrefix != '-') after.removeFinalNewline()
                lastPrefix = null
            }
            line.startsWith("+") -> {
                after.append(line.drop(1)).append('\n'); afterLines++; lastPrefix = '+'
            }
            line.startsWith("-") -> {
                before.append(line.drop(1)).append('\n'); beforeLines++; lastPrefix = '-'
            }
            line.startsWith(" ") -> {
                before.append(line.drop(1)).append('\n')
                after.append(line.drop(1)).append('\n')
                beforeLines++
                afterLines++
                lastPrefix = ' '
            }
            line.isNotEmpty() -> return null
        }
    }
    if (hunks == 0) {
        // Empty strings also represent binary patches; fallback headers can represent truncation.
        // Only Git metadata identifying the empty blob proves that there is no content to restore.
        if (additions != 0 || deletions != 0 || !isEmptyFileChange(patch, status)) return null
    } else if (beforeLines != expectedBefore || afterLines != expectedAfter) return null
    return FileDiff(file, before.toString(), after.toString(), additions, deletions, status)
}

private fun isEmptyFileChange(patch: String, status: String?): Boolean {
    val index = Regex("^index ([0-9a-f]+)\\.\\.([0-9a-f]+)(?: [0-7]+)?$", RegexOption.MULTILINE)
        .find(patch) ?: return false
    val before = index.groupValues[1]
    val after = index.groupValues[2]
    val emptyBlob = "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391"
    fun empty(hash: String) = hash.length >= 7 && emptyBlob.startsWith(hash)
    return when (status) {
        "added" -> patch.lineSequence().any { it.startsWith("new file mode ") } &&
            before.all { it == '0' } && empty(after)
        "deleted" -> patch.lineSequence().any { it.startsWith("deleted file mode ") } &&
            empty(before) && after.all { it == '0' }
        else -> false
    }
}

private fun isPatchHeader(line: String): Boolean =
    line.isEmpty() || line.startsWith("diff --git ") || line.startsWith("index ") ||
        line.startsWith("new file mode ") || line.startsWith("deleted file mode ") ||
        line.startsWith("old mode ") || line.startsWith("new mode ") ||
        line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("Index: ") ||
        line.all { it == '=' }

private fun StringBuilder.removeFinalNewline() {
    if (isNotEmpty() && this[length - 1] == '\n') deleteCharAt(length - 1)
}
