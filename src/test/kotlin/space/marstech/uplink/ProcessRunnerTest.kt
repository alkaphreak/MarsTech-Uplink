package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import space.marstech.uplink.updaters.*

/**
 * Exercises the updaters' real (non dry-run) paths through a [FakeProcessRunner]:
 * no external command is started, so failure, timeout and restart branches are testable.
 */
class ProcessRunnerTest {

    private fun ctx(
        vararg present: String,
        installed: Set<String> = emptySet(),
        responses: List<Pair<List<String>, ProcessResult>> = emptyList(),
    ): Pair<RunContext, FakeProcessRunner> {
        val fake = FakeProcessRunner(installed, responses)
        val ctx = RunContext(processRunner = fake)
        ctx.toolsPresent = present.associateWith { true }
        return ctx to fake
    }

    // -------------------------------------------------------------------------
    // ProcessUtils through the seam
    // -------------------------------------------------------------------------

    @Test
    fun `runProcess returns the fake exit code`() {
        val (ctx, fake) = ctx(responses = listOf(listOf("false") to ProcessResult(3, "")))
        assertEquals(3, ctx.runProcess("false"))
        assertEquals(listOf(listOf("false")), fake.calls)
    }

    @Test
    fun `runProcess turns a start failure into exit 1`() {
        val ctx = RunContext(processRunner = { _, _, _ -> throw java.io.IOException("No such file") })
        assertEquals(1, ctx.runProcess("ghost"))
    }

    @Test
    fun `captureOutput returns null on timeout`() {
        val (ctx, _) = ctx(responses = listOf(listOf("slow") to ProcessResult(124, "partial\n")))
        assertNull(ctx.captureOutput("slow", timeoutSeconds = 1))
    }

    @Test
    fun `toolPresent falls back to which through the runner`() {
        val (ctx, fake) = ctx(installed = setOf("jq"))
        assertTrue(ctx.toolPresent("jq"))
        assertFalse(ctx.toolPresent("python3"))
        assertEquals(listOf(listOf("which", "jq"), listOf("which", "python3")), fake.calls)
    }

    // -------------------------------------------------------------------------
    // Updaters — real paths
    // -------------------------------------------------------------------------

    @Test
    fun `pipxUpdate failure is a summary warning`() {
        val (ctx, fake) = ctx("pipx", responses = listOf(listOf("pipx") to ProcessResult(1, "boom\n")))
        ctx.pipxUpdate()
        assertTrue(listOf("pipx", "upgrade-all") in fake.calls)
        assertTrue(ctx.summaryWarnings.contains("pipx upgrade-all failed"))
        assertFalse(ctx.summaryUpdated.contains("pipx"))
    }

    @Test
    fun `uvUpdate success adds UV to summaryUpdated`() {
        val (ctx, fake) = ctx("uv")
        ctx.uvUpdate()
        assertEquals(listOf(listOf("uv", "self", "update")), fake.calls)
        assertTrue(ctx.summaryUpdated.contains("UV"))
    }

    @Test
    fun `ghExtUpdate failure is a summary warning`() {
        val (ctx, _) = ctx("gh", responses = listOf(listOf("gh") to ProcessResult(1, "")))
        ctx.ghExtUpdate()
        assertTrue(ctx.summaryWarnings.contains("gh extension upgrade failed"))
    }

    @Test
    fun `macosUpdate flags a required restart`() {
        val (ctx, _) = ctx(
            responses = listOf(listOf("softwareupdate") to ProcessResult(0, "Done.\nA restart is required\n")),
        )
        ctx.macosUpdate()
        assertTrue(ctx.restartRequired.get())
        assertTrue(ctx.summaryWarnings.contains("macOS update requires restart"))
        assertTrue(ctx.summaryUpdated.contains("macOS"))
    }

    @Test
    fun `macosUpdate timeout is a failure`() {
        val (ctx, _) = ctx(responses = listOf(listOf("softwareupdate") to ProcessResult(124, "")))
        ctx.macosUpdate()
        assertTrue(ctx.summaryFailed.contains("macOS"))
        assertFalse(ctx.restartRequired.get())
    }

    @Test
    fun `cargoUpdate without cargo-update plugin is a summary warning`() {
        val (ctx, fake) = ctx("cargo")
        ctx.cargoUpdate()
        assertTrue(ctx.summaryWarnings.any { it.contains("install cargo-update") })
        assertFalse(fake.calls.any { it.take(2) == listOf("cargo", "install-update") })
    }

    @Test
    fun `cargoUpdate failure is a summary warning`() {
        val (ctx, _) = ctx(
            "cargo",
            installed = setOf("cargo-install-update"),
            responses = listOf(
                listOf("cargo", "install", "--list") to ProcessResult(0, "ripgrep v14.1.0:\n    rg\n"),
                listOf("cargo", "install-update") to ProcessResult(1, ""),
            ),
        )
        ctx.cargoUpdate()
        assertTrue(ctx.summaryWarnings.contains("Some cargo packages failed to update"))
    }
}
