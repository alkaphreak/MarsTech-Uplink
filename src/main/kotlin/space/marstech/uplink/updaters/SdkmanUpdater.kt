package space.marstech.uplink.updaters

import space.marstech.uplink.Config
import space.marstech.uplink.RunContext
import space.marstech.uplink.commandExists
import space.marstech.uplink.runShell
import space.marstech.uplink.section
import java.io.File

fun RunContext.sdkmanUpdate() {
    section("SDKMAN update & upgrade")
    val initScript = "${Config.HOME}/.sdkman/bin/sdkman-init.sh"

    if (!File(initScript).exists()) {
        bufPrint("SDKMAN not found, skipping SDK updates")
        summarySkipped += "SDKMAN (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: sdk selfupdate")
        bufPrint("[DRY-RUN] Would run: sdk update")
        bufPrint("[DRY-RUN] Would run: sdk upgrade")
        bufPrint("[DRY-RUN] Would run: sdk flush archives && sdk flush temp")
        summaryUpdated += "SDKMAN"
        return
    }

    // SDKMAN helper scripts use ${var^^} (bash 4+ syntax); run in zsh 5.9+ or Homebrew bash 4+
    val sdkmanShell = when {
        commandExists("zsh") -> "zsh"
        File("/opt/homebrew/bin/bash").exists() -> "/opt/homebrew/bin/bash"
        else -> {
            bufPrint("Warning: SDKMAN requires zsh 5.9+ or bash 4+ (brew install bash)")
            summaryWarnings += "SDKMAN skipped: install bash 4+ via 'brew install bash'"
            return
        }
    }

    val init = """. "${Config.HOME}/.sdkman/bin/sdkman-init.sh""""

    section("SDKMAN selfupdate")
    if (runShell("$init && sdk selfupdate", sdkmanShell) != 0) {
        bufPrint("Warning: sdk selfupdate failed")
        summaryWarnings += "sdk selfupdate failed"
    }

    section("SDKMAN update")
    if (runShell("$init && sdk update", sdkmanShell) != 0) {
        bufPrint("Warning: sdk update failed")
        summaryWarnings += "sdk update failed"
    }

    section("SDKMAN upgrade")
    if (runShell("$init && (printf 'y\\n' | sdk upgrade || true)", sdkmanShell) != 0) {
        bufPrint("Warning: sdk upgrade failed")
        summaryWarnings += "sdk upgrade failed"
    }

    section("SDKMAN flush")
    runShell("$init && sdk flush archives && sdk flush temp", sdkmanShell)

    summaryUpdated += "SDKMAN"
}
