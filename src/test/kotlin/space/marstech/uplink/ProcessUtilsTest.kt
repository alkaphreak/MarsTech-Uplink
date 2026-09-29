package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class ProcessUtilsTest {

    @Test
    fun `commandExists returns true for known system command`() {
        assertTrue(commandExists("ls"))
    }

    @Test
    fun `commandExists returns false for nonexistent command`() {
        assertFalse(commandExists("zzz-this-command-does-not-exist-xxx"))
    }

    @Test
    fun `runProcess returns zero exit code for successful command`() {
        val ctx = RunContext(dryRun = true)
        val code = ctx.runProcess("true")
        assertEquals(0, code)
    }

    @Test
    fun `runProcess returns non-zero exit code for failing command`() {
        val ctx = RunContext(dryRun = true)
        val code = ctx.runProcess("false")
        assertNotEquals(0, code)
    }

    @Test
    fun `captureOutput returns command output`() {
        val ctx = RunContext(dryRun = true)
        val output = ctx.captureOutput("echo", "hello-uplink")
        assertEquals("hello-uplink", output)
    }

    @Test
    fun `captureOutput returns null for unknown command`() {
        val ctx = RunContext(dryRun = true)
        val output = ctx.captureOutput("zzz-nonexistent-cmd-xxx")
        assertNull(output)
    }

    @Test
    fun `runCaptured captures output and exit code`() {
        val ctx = RunContext(dryRun = true)
        val result = ctx.runCaptured("echo", "uplink-test")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("uplink-test"))
    }

    @Test
    fun `runCaptured returns non-zero exit code for failing command`() {
        val ctx = RunContext(dryRun = true)
        val result = ctx.runCaptured("false")
        assertNotEquals(0, result.exitCode)
    }

    @Test
    fun `shouldRun returns true when no filter is set`() {
        val ctx = RunContext()
        assertTrue(ctx.shouldRun("brew"))
        assertTrue(ctx.shouldRun("npm"))
    }

    @Test
    fun `shouldRun returns true only for matching tool`() {
        val ctx = RunContext(onlyTool = "brew")
        assertTrue(ctx.shouldRun("brew"))
        assertFalse(ctx.shouldRun("npm"))
    }

    @Test
    fun `toolPresent reads from toolsPresent map`() {
        val ctx = RunContext()
        ctx.toolsPresent = mapOf("brew" to true, "nonexistent-tool" to false)
        assertTrue(ctx.toolPresent("brew"))
        assertFalse(ctx.toolPresent("nonexistent-tool"))
    }

    @Test
    fun `runCaptured enforces timeout while the process is still writing`() {
        val ctx = RunContext(dryRun = true)
        val start = System.currentTimeMillis()
        val result = ctx.runCaptured("sh", "-c", "echo started; sleep 30", timeoutSeconds = 1, stream = true)
        assertEquals(124, result.exitCode)
        assertTrue(result.output.contains("started"))
        assertTrue(System.currentTimeMillis() - start < 10_000, "timeout must not wait for the process to exit")
    }

    @Test
    fun `npm missing without brew is a summary warning, not a silent skip`() {
        val ctx = RunContext(dryRun = true)
        ctx.toolsPresent = mapOf("npm" to false)
        ctx.npmUpdateAfterBrewIfMissing(null)
        assertTrue(ctx.summaryWarnings.any { it.startsWith("NPM skipped") })
    }

    @Test
    fun `timeout sends SIGTERM before SIGKILL so the process can clean up`() {
        val marker = File.createTempFile("uplink-sigterm", ".txt").apply { delete() }
        val ctx = RunContext(dryRun = true)
        val result = ctx.runCaptured("sh", "-c", "trap 'touch ${marker.absolutePath}; exit 143' TERM; sleep 30 & wait", timeoutSeconds = 1)
        assertEquals(124, result.exitCode)
        assertTrue(marker.delete(), "the TERM trap must have run")
    }

    @Test
    fun `captureOutput returns null when the timeout fires`() {
        val ctx = RunContext(dryRun = true)
        assertNull(ctx.captureOutput("sh", "-c", "echo started; sleep 30", timeoutSeconds = 1))
    }

    @Test
    fun `a command that cannot start is reported with the Failed to run warning`() {
        val ctx = RunContext(dryRun = true)
        val result = ctx.runCaptured("zzz-nonexistent-cmd-xxx")
        assertEquals(1, result.exitCode)
        assertTrue(result.output.startsWith("Warning: Failed to run 'zzz-nonexistent-cmd-xxx'"))
    }
}
