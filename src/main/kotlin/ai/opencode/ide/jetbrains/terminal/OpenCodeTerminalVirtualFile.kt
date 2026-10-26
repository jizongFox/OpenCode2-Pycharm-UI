package ai.opencode.ide.jetbrains.terminal

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.util.UUID

/** One identity per terminal process, independent of the editor tab position. */
class OpenCodeTerminalVirtualFile(title: String) : LightVirtualFile(title) {
    private val id = UUID.randomUUID()

    override fun getPath(): String = "opencode-terminal://$id"

    override fun getFileType(): FileType = TerminalFileType

    override fun isWritable(): Boolean = false

    object TerminalFileType : FakeFileType() {
        override fun getName(): String = "OpenCode Terminal"
        override fun getDescription(): String = "OpenCode Terminal"
        override fun isMyFileType(file: VirtualFile): Boolean = file is OpenCodeTerminalVirtualFile
    }
}
