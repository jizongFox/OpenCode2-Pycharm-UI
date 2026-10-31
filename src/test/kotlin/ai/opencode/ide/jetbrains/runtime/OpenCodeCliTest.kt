package ai.opencode.ide.jetbrains.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCliTest {
    @Test
    fun explicitSessionAndFreshLaunchWorkOnBothCommandPlatforms() {
        val original = System.getProperty("os.name")
        try {
            for (platform in listOf("Linux", "Windows 11")) {
                System.setProperty("os.name", platform)
                val cli = OpenCodeCli()
                val fresh = cli.buildCommand("opencode", "0.0.0.0", 4096, null)
                assertTrue(fresh.contains("http://127.0.0.1:4096"))
                assertFalse(fresh.contains("--continue"))
                assertFalse(fresh.contains("--session"))
                val restored = cli.buildCommand("opencode", "0.0.0.0", 4096, "secret", "ses_existing")
                assertTrue(restored.contains("OPENCODE_PASSWORD="))
                assertTrue(restored.endsWith(" --session ses_existing") ||
                    restored.endsWith(" --session ses_existing\""))
                assertFalse(restored.contains("--continue"))
            }
        } finally {
            System.setProperty("os.name", original)
        }
    }

    @Test
    fun callersCannotLaunchPlaceholdersOrInjectShellArgumentsAsASession() {
        for (id in listOf("dummy", "", "opencode:ses_existing", "ses_bad;echo injected", "ses_bad\nvalue",
            "ses_bad'quote", "ses_bad&value", "ses_bad$(echo injected)")) {
            assertThrows(IllegalArgumentException::class.java) {
                OpenCodeCli().buildCommand("opencode", "127.0.0.1", 4096, null, id)
            }
        }
    }
}
