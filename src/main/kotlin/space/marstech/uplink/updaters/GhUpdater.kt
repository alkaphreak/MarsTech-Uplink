package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.ghExtUpdate() {
    section("GitHub CLI extensions update")

    if (!toolPresent("gh")) {
        bufPrint("gh not found, skipping")
        summarySkipped += "gh extensions (gh not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: gh extension upgrade --all")
        summaryUpdated += "gh extensions"
        return
    }

    if (runProcess("gh", "extension", "upgrade", "--all") != 0) {
        bufPrint("Warning: gh extension upgrade failed")
        summaryWarnings += "gh extension upgrade failed"
    } else {
        summaryUpdated += "gh extensions"
    }
}
