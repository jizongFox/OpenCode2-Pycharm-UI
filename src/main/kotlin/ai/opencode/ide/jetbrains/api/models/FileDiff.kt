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
    var lastPrefix = ' '
    for (line in patch.split('\n')) {
        when {
            line.startsWith("@@ ") -> {
                // The endpoint promises a full-file patch when `context` is omitted.
                if (++hunks != 1 || !line.matches(Regex("@@ -(?:0|1)(?:,\\d+)? \\+(?:0|1)(?:,\\d+)? @@.*"))) {
                    return null
                }
            }
            hunks == 0 -> Unit
            line.startsWith("\\ No newline at end of file") -> {
                if (lastPrefix != '+') before.removeFinalNewline()
                if (lastPrefix != '-') after.removeFinalNewline()
            }
            line.startsWith("+") -> { after.append(line.drop(1)).append('\n'); lastPrefix = '+' }
            line.startsWith("-") -> { before.append(line.drop(1)).append('\n'); lastPrefix = '-' }
            line.startsWith(" ") -> {
                before.append(line.drop(1)).append('\n')
                after.append(line.drop(1)).append('\n')
                lastPrefix = ' '
            }
            line.isNotEmpty() -> return null
        }
    }
    if (hunks != 1) return null
    return FileDiff(file, before.toString(), after.toString(), additions, deletions, status)
}

private fun StringBuilder.removeFinalNewline() {
    if (isNotEmpty() && this[length - 1] == '\n') deleteCharAt(length - 1)
}
