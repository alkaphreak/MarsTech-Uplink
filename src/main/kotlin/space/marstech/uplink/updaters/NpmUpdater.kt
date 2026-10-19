package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.RunContext
import space.marstech.uplink.captureOutput
import space.marstech.uplink.commandExists
import space.marstech.uplink.runProcess
import space.marstech.uplink.section
import java.util.concurrent.Future

/**
 * Runs [npmUpdate], or re-probes after brew when npm is missing at tool-detection time:
 * brew relinks unlinked kegs (node included) at the end of its task, so the early probe can
 * miss an installed npm (MARSTECH-747). A still-missing npm is a summary warning, not a silent skip.
 */
internal fun RunContext.npmUpdateAfterBrewIfMissing(brewDone: Future<*>?) {
    if (!toolPresent("npm") && brewDone != null) {
        brewDone.get()  // already done when Main.kt deferred this task with Executor.after(brew)
        bufPrint("npm was not found at startup — re-checking now that Homebrew is done (it may have relinked the node keg)")
        if (commandExists("npm") && commandExists("node"))
            toolsPresent = toolsPresent + mapOf("npm" to true, "node" to true)
    }
    if (toolPresent("npm")) return npmUpdate()
    bufPrint("${YELLOW}Warning: npm not found in PATH, skipping$RESET")
    summarySkipped += "NPM (not installed)"
    summaryWarnings += "NPM skipped: npm not found in PATH although enabled in config (node keg unlinked?)"
}

fun RunContext.npmUpdate() {
    section("NPM global packages update")

    if (!toolPresent("node")) {
        bufPrint("Warning: Node.js not found or not linked properly")
        summarySkipped += "NPM (Node.js not found)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: npm install -g npm@latest")
        bufPrint("[DRY-RUN] Would run: npm outdated -g (filtered, no dot-prefixed packages)")
        bufPrint("[DRY-RUN] Would check/install @github/copilot")
        summaryUpdated += "NPM"
        return
    }

    if (runProcess("npm", "install", "-g", "npm@latest") != 0) {
        bufPrint("Warning: Failed to update npm")
        summaryWarnings += "Failed to update npm"
    }

    val outdatedPkgs = captureOutput(
        "bash", "-c",
        """npm outdated -g --depth=0 2>/dev/null | awk 'NR>1 && $1 !~ /^\./ {print $1"@latest"}'"""
    )?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    if (outdatedPkgs.isEmpty()) {
        bufPrint("All global npm packages are up to date")
    } else {
        if (runProcess("npm", "install", "-g", *outdatedPkgs.toTypedArray()) != 0) {
            bufPrint("Warning: Failed to update some global npm packages")
            summaryWarnings += "Failed to update some global npm packages"
        }
    }

    val copilotInstalled = captureOutput("npm", "list", "-g", "--depth=0")
        ?.contains("@github/copilot") == true
    if (!copilotInstalled) {
        if (runProcess("npm", "install", "-g", "@github/copilot@latest") != 0) {
            bufPrint("Warning: Failed to install Copilot CLI")
            summaryWarnings += "Failed to install Copilot CLI"
        }
    } else {
        bufPrint("Copilot already installed globally")
    }
    summaryUpdated += "NPM"
}
