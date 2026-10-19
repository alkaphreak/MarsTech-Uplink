package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.pipxUpdate() {
    section("pipx managed tools update")

    if (!toolPresent("pipx")) {
        bufPrint("pipx not found, skipping")
        summarySkipped += "pipx (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: pipx upgrade-all")
        summaryUpdated += "pipx"
        return
    }

    if (runProcess("pipx", "upgrade-all") != 0) {
        bufPrint("Warning: pipx upgrade-all failed")
        summaryWarnings += "pipx upgrade-all failed"
    } else {
        summaryUpdated += "pipx"
    }
}
