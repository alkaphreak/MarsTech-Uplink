package space.marstech.uplink.updaters

import space.marstech.uplink.Config
import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section
import java.io.File

fun RunContext.ohmyzshUpdate() {
    section("Oh My Zsh update")

    val zshDir = System.getenv("ZSH")?.let { File(it) }
        ?: File("${Config.HOME}/.oh-my-zsh")

    if (!zshDir.isDirectory) {
        bufPrint("Oh My Zsh not found (checked: ${zshDir.absolutePath}), skipping")
        summarySkipped += "Oh My Zsh (not installed)"
        return
    }

    if (dryRun) {
        bufPrint($$"[DRY-RUN] Would run: omz update  (or zsh $ZSH/tools/upgrade.sh)")
        summaryUpdated += "Oh My Zsh"
        return
    }

    val exitCode = if (toolPresent("omz")) {
        bufPrint("Using: omz update")
        runProcess("zsh", "-c", "omz update --unattended", timeoutSeconds = 300) // 5 min max
    } else {
        val upgradeScript = File(zshDir, "tools/upgrade.sh")
        if (!upgradeScript.exists()) {
            bufPrint("Warning: Oh My Zsh upgrade script not found at ${upgradeScript.absolutePath}")
            summaryWarnings += "Oh My Zsh upgrade script missing"
            return
        }
        bufPrint("Using: zsh ${upgradeScript.absolutePath}")
        runProcess("zsh", upgradeScript.absolutePath, timeoutSeconds = 300) // 5 min max
    }

    if (exitCode != 0) {
        bufPrint("Warning: Oh My Zsh update failed (exit $exitCode)")
        summaryWarnings += "Oh My Zsh update failed"
    } else {
        summaryUpdated += "Oh My Zsh"
    }
}
