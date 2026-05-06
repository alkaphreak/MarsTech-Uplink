package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Smoke-level tests for all updater functions.
 * Updaters are called in dry-run mode with toolsPresent pre-populated so no
 * real processes are executed. Each test verifies that the function produces
 * exactly one entry in either summaryUpdated or summarySkipped.
 */
class UpdatersTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun dryCtx(vararg tools: Pair<String, Boolean>) = RunContext(dryRun = true).also { ctx ->
        ctx.toolsPresent = mapOf(*tools)
    }

    // -------------------------------------------------------------------------
    // brewUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `brewUpdate in dry-run adds Homebrew to summaryUpdated`() {
        val ctx = dryCtx("brew" to true)
        ctx.brewUpdate()
        assertTrue(ctx.summaryUpdated.contains("Homebrew"))
    }

    // -------------------------------------------------------------------------
    // codexUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `codexUpdate in dry-run adds Codex CLI to summaryUpdated when present`() {
        val ctx = dryCtx("codex" to true)
        ctx.codexUpdate()
        assertTrue(ctx.summaryUpdated.any { it.contains("Codex") })
    }

    @Test
    fun `codexUpdate skips when codex not installed`() {
        val ctx = dryCtx("codex" to false)
        ctx.codexUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("Codex") })
    }

    // -------------------------------------------------------------------------
    // sdkmanUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `sdkmanUpdate in dry-run produces a summary entry`() {
        val ctx = dryCtx()
        ctx.sdkmanUpdate()
        val handled = ctx.summaryUpdated.any { it.contains("SDKMAN") } ||
                      ctx.summarySkipped.any { it.contains("SDKMAN") }
        assertTrue(handled, "Expected SDKMAN summary entry")
    }

    // -------------------------------------------------------------------------
    // npmUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `npmUpdate in dry-run adds NPM to summaryUpdated when node present`() {
        val ctx = dryCtx("node" to true)
        ctx.npmUpdate()
        assertTrue(ctx.summaryUpdated.contains("NPM"))
    }

    @Test
    fun `npmUpdate skips when node not present`() {
        val ctx = dryCtx("node" to false)
        ctx.npmUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("NPM") })
    }

    // -------------------------------------------------------------------------
    // uvUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `uvUpdate in dry-run adds UV to summaryUpdated when present`() {
        val ctx = dryCtx("uv" to true)
        ctx.uvUpdate()
        assertTrue(ctx.summaryUpdated.contains("UV"))
    }

    @Test
    fun `uvUpdate skips when uv not installed`() {
        val ctx = dryCtx("uv" to false)
        ctx.uvUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("UV") })
    }

    // -------------------------------------------------------------------------
    // rustupUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `rustupUpdate in dry-run adds rustup to summaryUpdated when present`() {
        val ctx = dryCtx("rustup" to true)
        ctx.rustupUpdate()
        assertTrue(ctx.summaryUpdated.contains("rustup"))
    }

    @Test
    fun `rustupUpdate skips when rustup not installed`() {
        val ctx = dryCtx("rustup" to false)
        ctx.rustupUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("rustup") })
    }

    // -------------------------------------------------------------------------
    // pipxUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `pipxUpdate in dry-run adds pipx to summaryUpdated when present`() {
        val ctx = dryCtx("pipx" to true)
        ctx.pipxUpdate()
        assertTrue(ctx.summaryUpdated.contains("pipx"))
    }

    @Test
    fun `pipxUpdate skips when pipx not installed`() {
        val ctx = dryCtx("pipx" to false)
        ctx.pipxUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("pipx") })
    }

    // -------------------------------------------------------------------------
    // ghExtUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `ghExtUpdate in dry-run adds gh extensions to summaryUpdated when present`() {
        val ctx = dryCtx("gh" to true)
        ctx.ghExtUpdate()
        assertTrue(ctx.summaryUpdated.any { it.contains("gh") })
    }

    @Test
    fun `ghExtUpdate skips when gh not installed`() {
        val ctx = dryCtx("gh" to false)
        ctx.ghExtUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("gh") })
    }

    // -------------------------------------------------------------------------
    // macosUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `macosUpdate in dry-run adds macOS to summaryUpdated`() {
        val ctx = dryCtx()
        ctx.macosUpdate()
        assertTrue(ctx.summaryUpdated.contains("macOS"))
    }

    // -------------------------------------------------------------------------
    // masUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `masUpdate in dry-run adds Mac App Store to summaryUpdated`() {
        val ctx = dryCtx()
        ctx.masUpdate()
        assertTrue(ctx.summaryUpdated.contains("Mac App Store"))
    }

    // -------------------------------------------------------------------------
    // ohmyzshUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `ohmyzshUpdate in dry-run produces a summary entry`() {
        val ctx = dryCtx("omz" to true)
        ctx.ohmyzshUpdate()
        val handled = ctx.summaryUpdated.any { it.contains("Oh My Zsh") } ||
                      ctx.summarySkipped.any { it.contains("Oh My Zsh") }
        assertTrue(handled, "Expected Oh My Zsh summary entry")
    }
}
