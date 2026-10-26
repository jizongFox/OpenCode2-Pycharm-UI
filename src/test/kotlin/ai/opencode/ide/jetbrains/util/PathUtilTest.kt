package ai.opencode.ide.jetbrains.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class PathUtilTest {

    @Test
    fun `context reference is relative inside workspace`() {
        val root = Files.createTempDirectory("opencode-reference-root")
        val file = root.resolve("src/Main.kt")

        assertEquals("src/Main.kt", PathUtil.contextReferencePath(root.toString(), file.toString()))
    }

    @Test
    fun `context reference stays absolute outside workspace`() {
        val root = Files.createTempDirectory("opencode-reference-root")
        val external = Files.createTempDirectory("opencode-reference-external").resolve("Main.kt")

        assertEquals(
            PathUtil.toSystemIndependentPath(external.toAbsolutePath().toString()),
            PathUtil.contextReferencePath(root.toString(), external.toString())
        )
    }

    @Test
    fun resolvesAndRelativizesPathsInsideWorkspace() {
        val root = Files.createTempDirectory("opencode-root")
        val file = Files.createDirectories(root.resolve("src")).resolve("main.kt")
        Files.writeString(file, "fun main() = Unit")

        assertEquals(
            PathUtil.toSystemIndependentPath(file.toString()),
            PathUtil.resolveWithinRoot(root.toString(), "src/main.kt")
        )
        assertEquals("src/main.kt", PathUtil.relativizeWithinRoot(root.toString(), file.toString()))
    }

    @Test
    fun rejectsTraversalAndAbsolutePathsOutsideWorkspace() {
        val root = Files.createTempDirectory("opencode-root")
        val outside = Files.createTempFile("opencode-outside", ".kt")

        assertNull(PathUtil.resolveWithinRoot(root.toString(), "../${outside.fileName}"))
        assertNull(PathUtil.resolveWithinRoot(root.toString(), outside.toString()))
        assertFalse(PathUtil.isWithinRoot(root.toString(), outside.toString()))
    }

    @Test
    fun rejectsSymlinkEscapes() {
        val root = Files.createTempDirectory("opencode-root")
        val outside = Files.createTempDirectory("opencode-outside")
        val link = root.resolve("linked")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: Exception) {
            assumeTrue("Symbolic links are not available", false)
        }

        assertNull(PathUtil.resolveWithinRoot(root.toString(), "linked/new.kt"))
        assertTrue(PathUtil.isWithinRoot(root.toString(), root.resolve("safe.kt").toString()))
    }
}
