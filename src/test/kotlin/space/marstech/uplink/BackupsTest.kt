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
    fun `backupKeewebDb always records an outcome in summary`() {
        val ctx = RunContext(dryRun = false)
        // The configured keewebSource may or may not exist on the current machine;
        // the backup dir may or may not be writable (e.g. /Sync not mounted in CI).
        // The function must always record its terminal state in one of the summary lists.
        ctx.backupKeewebDb()
        val handled = ctx.summarySkipped.any { it.contains("KeeWeb") } ||
                      ctx.summaryUpdated.any  { it.contains("KeeWeb") } ||
                      ctx.summaryFailed.any   { it.contains("KeeWeb") }
        assertTrue(handled, "Expected KeeWeb backup to produce a summary entry in skipped, updated, or failed")
    }

    @Test
    fun `backupKeewebDb in dry-run does not throw`() {
        val ctx = RunContext(dryRun = true)
        assertDoesNotThrow { ctx.backupKeewebDb() }
    }
}
