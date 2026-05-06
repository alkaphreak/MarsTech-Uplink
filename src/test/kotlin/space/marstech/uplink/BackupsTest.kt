package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BackupsTest {

    @Test
    fun `backupShellConfigs in dry-run adds to summaryUpdated`() {
        val ctx = RunContext(dryRun = true)
        ctx.backupShellConfigs()
        assertTrue(
            ctx.summaryUpdated.contains("Shell config backup"),
            "Expected 'Shell config backup' in summaryUpdated"
        )
    }

    @Test
    fun `backupShellConfigs in dry-run does not modify summaryFailed`() {
        val ctx = RunContext(dryRun = true)
        ctx.backupShellConfigs()
        assertTrue(ctx.summaryFailed.isEmpty())
    }

    @Test
    fun `backupKeewebDb skips when Dropbox source does not exist`() {
        val ctx = RunContext(dryRun = false)
        // The Dropbox source won't exist on a CI machine or a clean environment
        ctx.backupKeewebDb()
        // Either skipped (source missing) or updated (source exists on dev machine)
        val handled = ctx.summarySkipped.any { it.contains("KeeWeb") } ||
                      ctx.summaryUpdated.contains("KeeWeb backup")
        assertTrue(handled, "Expected KeeWeb backup to produce a summary entry")
    }

    @Test
    fun `backupKeewebDb in dry-run does not throw`() {
        val ctx = RunContext(dryRun = true)
        assertDoesNotThrow { ctx.backupKeewebDb() }
    }
}
