package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.RunContext
import space.marstech.uplink.captureOutput
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

private const val CARGO_TOOLS = "Cargo packages"

fun RunContext.cargoUpdate() {
    section("Cargo installed packages update")

    if (!toolPresent("cargo")) {
        bufPrint("cargo not found, skipping")
        summarySkipped += "Cargo (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: cargo install-update -a")
        bufPrint("[DRY-RUN] Note: requires cargo-update — install with: cargo install cargo-update")
        summaryUpdated += CARGO_TOOLS
        return
    }

    // 'cargo install-update' is a subcommand provided by the cargo-update crate.
    // Its binary is registered as 'cargo-install-update' — check for it explicitly.
    if (!toolPresent("cargo-install-update")) {
        bufPrint("${YELLOW}cargo-update not installed — cannot update cargo packages")
        bufPrint("  Install with: cargo install cargo-update$RESET")
        summaryWarnings += "Cargo: install cargo-update to enable package updates — cargo install cargo-update"
        return
    }

    // List installed packages for context (lines not starting with whitespace are package headers)
    val installedCount = captureOutput("cargo", "install", "--list")
        ?.lines()
        ?.count { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("\t") }
        ?: 0

    if (installedCount == 0) {
        bufPrint("No cargo packages installed, skipping")
        summarySkipped += "Cargo (no packages installed)"
        return
    }

    bufPrint("Installed cargo packages: $installedCount package(s)")

    if (runProcess("cargo", "install-update", "-a") != 0) {
        bufPrint("${YELLOW}Warning: cargo install-update -a reported errors$RESET")
        summaryWarnings += "Some cargo packages failed to update"
    } else {
        summaryUpdated += CARGO_TOOLS
    }
}
