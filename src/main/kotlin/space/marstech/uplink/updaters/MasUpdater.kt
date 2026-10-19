package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.masUpdate() {
    section("macOS App Store updates")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: mas upgrade")
        bufPrint("[DRY-RUN] Outdated apps:")
        runProcess("mas", "outdated")
        summaryUpdated += "Mac App Store"
        return
    }

    runProcess("mas", "upgrade", timeoutSeconds = 600) // 10 min max — mas can hang waiting for App Store auth
    summaryUpdated += "Mac App Store"
}
