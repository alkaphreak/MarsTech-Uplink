package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.uvUpdate() {
    section("UV (Python package manager) update")

    if (!toolPresent("uv")) {
        bufPrint("UV not found, skipping")
        summarySkipped += "UV (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: uv self update")
        summaryUpdated += "UV"
        return
    }

    if (runProcess("uv", "self", "update") != 0) {
        bufPrint("Warning: Failed to update UV")
        summaryWarnings += "Failed to update UV"
    } else {
        summaryUpdated += "UV"
    }
}
