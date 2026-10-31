package ai.opencode.ide.jetbrains.api

import ai.opencode.ide.jetbrains.api.models.fullFileDiff
import ai.opencode.ide.jetbrains.integration.FakeOpenCodeServer
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DiffProtocolTest {
    @Test
    fun metadataCanProveAnEmptyFileWasAddedOrDeleted() {
        val added = "diff --git a/empty b/empty\nnew file mode 100644\nindex 0000000..e69de29\n"
        val deleted = "diff --git a/empty b/empty\ndeleted file mode 100644\nindex e69de29..0000000\n"
        for ((status, patch) in listOf("added" to added, "deleted" to deleted)) {
            val diff = fullFileDiff("empty", patch, 0, 0, status)
            assertNotNull(diff)
            assertEquals("", diff?.before)
            assertEquals("", diff?.after)
        }
    }

    @Test
    fun binaryAndFallbackPatchesAreNotMistakenForEmptyFiles() {
        val fallback = "Index: file\n===================================================================\n--- file\t\n+++ file\t\n"
        for (patch in listOf("", fallback, "diff --git a/file b/file\nBinary files a/file and b/file differ\n")) {
            assertNull(fullFileDiff("file", patch, 0, 0, "deleted"))
        }
        assertNull(fullFileDiff("file", "diff --git a/file b/file\ndeleted file mode 100644\nindex 1234567..0000000\n",
            0, 0, "deleted"))
    }

    @Test
    fun truncatedHunksCannotSupplyPartialBeforeContentForReject() {
        assertNull(fullFileDiff("file", "@@ -1,3 +1 @@\n-old\n+new\n", 1, 3, "modified"))
        assertNull(fullFileDiff("file", "@@ -1 +1,2 @@\n-old\n+new\n", 2, 1, "modified"))
        val complete = fullFileDiff("file", "@@ -1,2 +1 @@\n-old\n-removed\n+new\n", 1, 2, "modified")
        assertEquals("old\nremoved\n", complete?.before)
        assertEquals("new\n", complete?.after)
    }

    @Test
    fun apiRejectsMissingUnknownOrIncompleteChangeMetadata() {
        val server = FakeOpenCodeServer()
        server.start()
        try {
            val client = OpenCodeApiClient("127.0.0.1", server.port)
            val gson = Gson()
            for (fields in listOf(emptyMap(), mapOf("status" to "renamed"),
                mapOf("status" to "modified", "more" to true))) {
                val diff = mapOf("file" to "file", "patch" to "@@ -1 +1 @@\n-old\n+new\n",
                    "additions" to 1, "deletions" to 1) + fields
                server.respondTo("msg_bad", gson.toJson(mapOf("data" to listOf(diff))))
                assertThrows(IOException::class.java) { client.getSessionDiff("ses_main", "msg_bad") }
            }
        } finally {
            server.stop()
        }
    }
}
