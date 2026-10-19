package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

private const val CODEX_CLI = "Codex CLI"

fun RunContext.codexUpdate() {
    section("Codex CLI update")

    if (!toolPresent("codex")) {
        bufPrint("Codex CLI not found, skipping")
        summarySkipped += "Codex CLI (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would check if codex is outdated and upgrade")
        summaryUpdated += CODEX_CLI
        return
    }

    val isBrewManaged = runProcess("brew", "list", "--formula", "codex") == 0
            || runProcess("brew", "list", "--cask", "codex") == 0

    if (!isBrewManaged) {
        bufPrint("Codex CLI is not managed by Homebrew, skipping brew upgrade")
        summarySkipped += "Codex CLI (not brew-managed)"
        return
    }

    // codex is a cask, not a formula — must use --cask to avoid "No such keg" error
    if (runProcess("brew", "outdated", "--cask", "codex") == 0) {
        bufPrint("Codex CLI is outdated, upgrading...")
        if (runProcess("brew", "upgrade", "--cask", "codex") != 0) {
            bufPrint("Warning: Failed to upgrade Codex CLI")
            summaryWarnings += "Failed to upgrade Codex CLI"
        } else {
            summaryUpdated += CODEX_CLI
        }
    } else {
        bufPrint("Codex CLI is up to date")
        summaryUpdated += CODEX_CLI
    }
}
