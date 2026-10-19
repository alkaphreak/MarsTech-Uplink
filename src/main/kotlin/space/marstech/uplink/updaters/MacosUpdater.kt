package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.RunContext
import space.marstech.uplink.runCaptured
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.macosUpdate() {
    section("macOS software update")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: softwareupdate --install --all")
        bufPrint("[DRY-RUN] Available updates:")
        runProcess("softwareupdate", "--list")
        summaryUpdated += "macOS"
        return
    }

    val result = runCaptured("softwareupdate", "--install", "--all", timeoutSeconds = 1800) // 30 min max
    bufPrint(result.output)

    if (result.exitCode != 0) {
        bufPrint("${RED}Warning: macOS update failed (exit ${result.exitCode})$RESET")
        summaryFailed += "macOS"
        return
    }

    if (result.output.contains("restart", ignoreCase = true) ||
        result.output.contains("A restart is required", ignoreCase = true)
    ) {
        bufPrint("${YELLOW}Warning: A system restart is required to complete the macOS update$RESET")
        summaryWarnings += "macOS update requires restart"
        restartRequired.set(true)
    }
    summaryUpdated += "macOS"
}
