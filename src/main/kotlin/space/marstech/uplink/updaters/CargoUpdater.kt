package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.RunContext
import space.marstech.uplink.captureOutput
import space.marstech.uplink.runProcess
import space.marstech.uplink.section
import java.util.concurrent.Future

private const val CARGO_TOOLS = "Cargo packages"

/**
 * Runs [cargoUpdate], re-probing cargo after brew when it was missing at tool-detection time:
 * brew relinks an unlinked rust keg at the end of its task (MARSTECH-793, same race as MARSTECH-747).
 */
internal fun RunContext.cargoUpdateAfterBrewIfMissing(brewDone: Future<*>?) {
    if (!toolPresent("cargo") && brewDone != null) {
        brewDone.get()  // already done when Main.kt deferred this task with Executor.after(brew)
        bufPrint("cargo was not found at startup — re-checking now that Homebrew is done (it may have relinked the rust keg)")
        recheckTool("cargo")
    }
    cargoUpdate()
}

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

    // 'cargo install-update' is a subcommand provided by the cargo-update crate. Ask cargo, not
    // 'which': cargo also finds subcommands in ~/.cargo/bin, which launchd leaves off the PATH
    // (false "not installed" warning under Dixie, MARSTECH-793).
    val hasInstallUpdate = captureOutput("cargo", "--list")?.lines()?.any { it.trim() == "install-update" } == true
    if (!hasInstallUpdate) {
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
