package ai.opencode.ide.jetbrains.api.models

/** One reviewable V2 file change. */
data class DiffEntry(val file: String, val diff: FileDiff) {
    val isNewFile: Boolean get() = diff.status == "added"
    val beforeContent: String get() = diff.before

    fun matchesCurrentContent(diskContent: String?, documentContent: String?): Boolean {
        if (diff.status == "deleted" || (diskContent == null && diff.after.isEmpty())) {
            return diskContent == null && documentContent == null
        }
        return diskContent == diff.after && (documentContent == null || documentContent == diff.after)
    }
}
