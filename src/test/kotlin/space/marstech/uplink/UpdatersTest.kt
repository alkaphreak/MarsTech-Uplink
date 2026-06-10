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
    // cargoUpdate
    // -------------------------------------------------------------------------

    @Test
    fun `cargoUpdate in dry-run adds Cargo packages to summaryUpdated when cargo present`() {
        val ctx = dryCtx("cargo" to true)
        ctx.cargoUpdate()
        assertTrue(ctx.summaryUpdated.any { it.contains("Cargo") })
    }

    @Test
    fun `cargoUpdate skips when cargo not installed`() {
        val ctx = dryCtx("cargo" to false)
        ctx.cargoUpdate()
        assertTrue(ctx.summarySkipped.any { it.contains("Cargo") })
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

    // -------------------------------------------------------------------------
    // parseBrewFailedCasks (jalon 25 — internal, unit-testable)
    // -------------------------------------------------------------------------

    @Test
    fun `parseBrewFailedCasks extracts cask names from Problems block`() {
        val output = """
            Some packages upgraded successfully.
            Error: Problems with multiple casks:
            docker-desktop: It seems there is already an App at '/Applications/Docker.app'.
            another-cask: Failed to install
        """.trimIndent()
        val result = parseBrewFailedCasks(output)
        assertTrue(result.contains("docker-desktop"), "Expected docker-desktop in $result")
        assertTrue(result.contains("another-cask"), "Expected another-cask in $result")
    }

    @Test
    fun `parseBrewFailedCasks returns empty list when no Problems block`() {
        val result = parseBrewFailedCasks("brew upgrade --greedy\nAll good.")
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // parseBrewCannotUpgradeCasks (jalon 25 — internal, unit-testable)
    // -------------------------------------------------------------------------

    @Test
    fun `parseBrewCannotUpgradeCasks extracts cask name from cannot be upgraded warning`() {
        val output = """
            ==> Upgrading 3 outdated packages:
            Warning: The cask 'docker-desktop' cannot be upgraded as-is
            Some other line
        """.trimIndent()
        val result = parseBrewCannotUpgradeCasks(output)
        assertEquals(listOf("docker-desktop"), result)
    }

    @Test
    fun `parseBrewCannotUpgradeCasks returns empty list when no such warning`() {
        val result = parseBrewCannotUpgradeCasks("==> Upgrading 1 outdated package")
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // extractDeprecatedFromDoctorOutput (jalon 27 — internal, unit-testable)
    // -------------------------------------------------------------------------

    @Test
    fun `extractDeprecatedFromDoctorOutput extracts formula and cask names`() {
        val output = """
            Warning: Some installed formulae are deprecated and should be uninstalled.
            Run `brew cleanup` to uninstall these formulae:
            angry-ip-scanner (cask): Deprecated because it has been discontinued upstream!
            python@3.9: Deprecated because it is end-of-life upstream! Use python@3.13 instead.
        """.trimIndent()
        val result = extractDeprecatedFromDoctorOutput(output)
        assertTrue(result.contains("angry-ip-scanner"), "Expected angry-ip-scanner in $result")
        assertTrue(result.contains("python@3.9"), "Expected python@3.9 in $result")
    }

    @Test
    fun `extractDeprecatedFromDoctorOutput returns empty list when no deprecated packages`() {
        val result = extractDeprecatedFromDoctorOutput("Your system is ready to brew.")
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // selfUpdate (jalon 29)
    // -------------------------------------------------------------------------

    @Test
    fun `selfUpdate in dry-run adds marstech-uplink to summaryUpdated`() {
        val ctx = dryCtx()
        ctx.selfUpdate()
        assertTrue(ctx.summaryUpdated.any { it.contains("marstech-uplink") })
    }

    // -------------------------------------------------------------------------
    // compareVersions (jalon 29 — internal, unit-testable)
    // -------------------------------------------------------------------------

    @Test
    fun `compareVersions returns positive when a is greater`() {
        assertTrue(compareVersions("1.1.0", "1.0.0") > 0)
        assertTrue(compareVersions("2.0.0", "1.9.9") > 0)
        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
    }

    @Test
    fun `compareVersions returns negative when a is lesser`() {
        assertTrue(compareVersions("1.0.0", "1.1.0") < 0)
        assertTrue(compareVersions("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun `compareVersions returns zero for equal versions`() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, compareVersions("2.3.4", "2.3.4"))
    }

    @Test
    fun `compareVersions handles different segment counts`() {
        assertTrue(compareVersions("1.1", "1.0.0") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }
}
