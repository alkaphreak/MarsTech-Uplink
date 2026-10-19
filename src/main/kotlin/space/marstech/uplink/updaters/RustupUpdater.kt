package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.rustupUpdate() {
    section("Rust toolchain update (rustup)")

    if (!toolPresent("rustup")) {
        bufPrint("rustup not found, skipping")
        summarySkipped += "rustup (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: rustup update")
        summaryUpdated += "rustup"
        return
    }

    if (runProcess("rustup", "update") != 0) {
        bufPrint("Warning: rustup update failed")
        summaryWarnings += "rustup update failed"
    } else {
        summaryUpdated += "rustup"
    }
}
