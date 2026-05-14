package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

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

    @Test
    fun `pruneKeewebBackups keeps only 5 latest backups for the same device`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val deleted = mutableListOf<String>()

        (1..7).forEach { day ->
            File(dir, "2026-05-0$day-macbook-pro-Alkaphreak.kdbx").writeText("x")
        }

        pruneKeewebBackups(
            destDir = dir,
            deviceName = "macbook-pro",
            sourceName = "Alkaphreak.kdbx",
            retention = 5,
            onDelete = { deleted += it.name },
        )

        val remaining = dir.listFiles { f -> f.isFile && f.name.endsWith("-macbook-pro-Alkaphreak.kdbx") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

        assertEquals(5, remaining.size)
        assertEquals(
            listOf(
                "2026-05-03-macbook-pro-Alkaphreak.kdbx",
                "2026-05-04-macbook-pro-Alkaphreak.kdbx",
                "2026-05-05-macbook-pro-Alkaphreak.kdbx",
                "2026-05-06-macbook-pro-Alkaphreak.kdbx",
                "2026-05-07-macbook-pro-Alkaphreak.kdbx",
            ),
            remaining,
        )
        assertEquals(
            listOf(
                "2026-05-01-macbook-pro-Alkaphreak.kdbx",
                "2026-05-02-macbook-pro-Alkaphreak.kdbx",
            ),
            deleted.sorted(),
        )
    }

    // -------------------------------------------------------------------------
    // pruneShellSnapshots
    // -------------------------------------------------------------------------

    @Test
    fun `pruneShellSnapshots keeps only N latest snapshots for device`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val device = "macbook-pro"
        val deleted = mutableListOf<String>()

        (1..5).forEach { day ->
            File(dir, "2026-05-0${day}-$device").mkdirs()
        }

        pruneShellSnapshots(dir, device, retention = 3) { deleted += it.name }

        val remaining = dir.listFiles { f -> f.isDirectory && f.name.endsWith("-$device") }
            ?.map { it.name }?.sorted() ?: emptyList()

        assertEquals(3, remaining.size)
        assertEquals(
            listOf(
                "2026-05-03-$device",
                "2026-05-04-$device",
                "2026-05-05-$device",
            ),
            remaining,
        )
        assertEquals(
            listOf("2026-05-01-$device", "2026-05-02-$device"),
            deleted.sorted(),
        )
    }

    @Test
    fun `pruneShellSnapshots ignores snapshots from other devices`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val device = "macbook-pro"

        (1..4).forEach { day ->
            File(dir, "2026-05-0${day}-$device").mkdirs()
        }
        // Another device — must never be touched
        File(dir, "2026-05-01-mac-mini").mkdirs()

        pruneShellSnapshots(dir, device, retention = 3)

        val remaining = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertTrue("2026-05-01-mac-mini" in remaining, "Other device snapshot must remain untouched")
        assertFalse("2026-05-01-$device" in remaining, "Oldest snapshot for target device should be pruned")
        assertEquals(4, remaining.size, "3 kept for device + 1 other device")
    }

    @Test
    fun `pruneShellSnapshots does nothing when count is within retention`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val device = "macbook-pro"

        (1..2).forEach { day ->
            File(dir, "2026-05-0${day}-$device").mkdirs()
        }

        val deleted = mutableListOf<String>()
        pruneShellSnapshots(dir, device, retention = 3) { deleted += it.name }

        val remaining = dir.listFiles { f -> f.isDirectory && f.name.endsWith("-$device") }
            ?.map { it.name } ?: emptyList()
        assertEquals(2, remaining.size)
        assertTrue(deleted.isEmpty(), "Nothing should be deleted when under retention limit")
    }

    @Test
    fun `pruneShellSnapshots deletes directory contents recursively`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val device = "macbook-pro"

        (1..4).forEach { day ->
            val snap = File(dir, "2026-05-0${day}-$device")
            snap.mkdirs()
            File(snap, "zshrc.sh").writeText("# snapshot content")
        }

        pruneShellSnapshots(dir, device, retention = 3)

        val oldest = File(dir, "2026-05-01-$device")
        assertFalse(oldest.exists(), "Oldest snapshot directory and its contents must be deleted")
        assertEquals(
            3,
            dir.listFiles { f -> f.isDirectory && f.name.endsWith("-$device") }?.size,
        )
    }

    @Test
    fun `pruneShellSnapshots is a no-op when snapshotsDir does not exist`(@TempDir tempDir: Path) {
        val nonExistent = File(tempDir.toFile(), "does-not-exist")
        // Must not throw
        assertDoesNotThrow { pruneShellSnapshots(nonExistent, "macbook-pro", retention = 3) }
    }

    // -------------------------------------------------------------------------
    // pruneKeewebBackups
    // -------------------------------------------------------------------------

    @Test
    fun `pruneKeewebBackups ignores other devices and legacy names`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()

        (1..6).forEach { day ->
            File(dir, "2026-05-0$day-macbook-pro-Alkaphreak.kdbx").writeText("x")
        }
        File(dir, "2026-05-01-mac-mini-Alkaphreak.kdbx").writeText("x")
        File(dir, "2026-05-01-Alkaphreak.kdbx").writeText("x")

        pruneKeewebBackups(
            destDir = dir,
            deviceName = "macbook-pro",
            sourceName = "Alkaphreak.kdbx",
            retention = 5,
        )

        val remaining = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertTrue("2026-05-01-Alkaphreak.kdbx" in remaining, "Legacy backup name should remain untouched")
        assertTrue("2026-05-01-mac-mini-Alkaphreak.kdbx" in remaining, "Backups from other devices should remain untouched")
        assertFalse("2026-05-01-macbook-pro-Alkaphreak.kdbx" in remaining, "Oldest backup for target device should be pruned")
    }
}
