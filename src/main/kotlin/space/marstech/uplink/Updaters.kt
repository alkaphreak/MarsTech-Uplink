package space.marstech.uplink

import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import java.io.File
import java.util.concurrent.CompletableFuture

fun RunContext.brewUpdate() {
    section("Homebrew update")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: brew update")
        bufPrint("[DRY-RUN] Would show: brew outdated --verbose")
        bufPrint("[DRY-RUN] Would run: brew upgrade --greedy")
        bufPrint("[DRY-RUN] Would run: brew cleanup -s --prune=all")
        bufPrint("[DRY-RUN] Would run: brew doctor")
        bufPrint("[DRY-RUN] Would link any unlinked kegs")
        summaryUpdated += "Homebrew"
        return
    }

    // Run 'brew outdated' concurrently with 'brew update' — both are read-only
    val outdatedFuture = CompletableFuture.supplyAsync {
        captureOutput("brew", "outdated", "--verbose")
    }

    val updateResult = runCaptured("brew", "update")
    bufPrint(updateResult.output)

    if (updateResult.exitCode != 0) {
        outdatedFuture.cancel(true)
        if (updateResult.output.contains("already locked", ignoreCase = true) ||
            updateResult.output.contains("Another `brew update` process", ignoreCase = true) ||
            updateResult.output.contains("Another brew update", ignoreCase = true)) {
            bufPrint("${YELLOW}Homebrew: another update process is already running — skipping.$RESET")
            bufPrint("Wait for it to finish, or remove the lock:")
            bufPrint("  rm -f \$(brew --prefix)/var/homebrew/locks/update")
            summaryWarnings += "Homebrew skipped: lock contention (another brew update was running)"
        } else {
            bufPrint("${RED}Warning: Homebrew update failed (exit ${updateResult.exitCode})$RESET")
            summaryFailed += "Homebrew"
        }
        return
    }

    section("Homebrew — outdated packages")
    val outdated = runCatching { outdatedFuture.get() }.getOrNull()
    if (outdated.isNullOrBlank()) bufPrint("All Homebrew packages are up to date")
    else bufPrint(outdated)

    section("Homebrew upgrade (--greedy)")
    val upgradeResult = runCaptured("brew", "upgrade", "--greedy")
    bufPrint(upgradeResult.output)
    val upgradeExit = upgradeResult.exitCode

    if (upgradeExit != 0) {
        bufPrint("${YELLOW}Warning: Some packages failed to upgrade$RESET")
        summaryWarnings += "Some brew packages failed to upgrade"
    }

    // Surface deprecated formula warnings
    val deprecatedPattern = Regex("""Warning:\s+\S+\s+(is deprecated|has been deprecated)""", RegexOption.IGNORE_CASE)
    deprecatedPattern.findAll(upgradeResult.output).map { it.value.trim() }.toSet().forEach { msg ->
        bufPrint("${YELLOW}$msg$RESET")
        summaryWarnings += "brew: $msg"
    }
    val eolPattern = Regex("""([\w][\w@.]+)\s+\S+\s+is deprecated because""", RegexOption.IGNORE_CASE)
    eolPattern.findAll(upgradeResult.output).map { it.groupValues[1] }.toSet().forEach { formula ->
        if (summaryWarnings.none { it.contains(formula) })
            summaryWarnings += "brew deprecated: $formula — consider: brew uninstall $formula"
    }

    section("Homebrew cleanup")
    runProcess("brew", "cleanup", "-s", "--prune=all")

    if (upgradeExit != 0) {
        section("Homebrew doctor (triggered by upgrade failure)")
        runProcess("brew", "doctor")
    }

    section("Linking unlinked kegs")
    val unlinkedKegs: List<String> = when {
        commandExists("jq") -> captureOutput(
            "bash", "-c",
            "brew info --json=v1 --installed 2>/dev/null | jq -r '.[] | select(.keg_only == false) | select(.linked_keg == null) | .name' 2>/dev/null"
        )?.lines()?.filter { it.isNotBlank() } ?: emptyList()

        commandExists("python3") -> captureOutput(
            "bash", "-c",
            """brew info --json=v1 --installed 2>/dev/null | python3 -c "
import sys, json
for f in json.load(sys.stdin):
    if not f.get('keg_only') and f.get('linked_keg') is None and f.get('installed'):
        print(f['name'])
" 2>/dev/null"""
        )?.lines()?.filter { it.isNotBlank() } ?: emptyList()

        else -> {
            bufPrint("Warning: neither jq nor python3 found; skipping unlinked keg detection")
            summaryWarnings += "Install jq or python3 to enable unlinked keg detection"
            emptyList()
        }
    }

    if (unlinkedKegs.isEmpty()) {
        bufPrint("No unlinked kegs found")
    } else {
        bufPrint("Found unlinked kegs: ${unlinkedKegs.joinToString(", ")}")
        unlinkedKegs.forEach { keg ->
            bufPrint("Linking $keg...")
            if (runProcess("brew", "link", "--overwrite", keg) != 0) {
                bufPrint("Warning: Failed to link $keg")
                summaryWarnings += "Failed to link keg: $keg"
            }
        }
    }
    summaryUpdated += "Homebrew"
}

fun RunContext.codexUpdate() {
    section("Codex CLI update")

    if (!toolPresent("codex")) {
        bufPrint("Codex CLI not found, skipping")
        summarySkipped += "Codex CLI (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would check if codex is outdated and upgrade")
        summaryUpdated += "Codex CLI"
        return
    }

    val isBrewManaged = runProcess("brew", "list", "--formula", "codex") == 0
            || runProcess("brew", "list", "--cask", "codex") == 0

    if (!isBrewManaged) {
        bufPrint("Codex CLI is not managed by Homebrew, skipping brew upgrade")
        summarySkipped += "Codex CLI (not brew-managed)"
        return
    }

    if (runProcess("brew", "outdated", "codex") == 0) {
        bufPrint("Codex CLI is outdated, upgrading...")
        if (runProcess("brew", "upgrade", "codex") != 0) {
            bufPrint("Warning: Failed to upgrade Codex CLI")
            summaryWarnings += "Failed to upgrade Codex CLI"
        } else {
            summaryUpdated += "Codex CLI"
        }
    } else {
        bufPrint("Codex CLI is up to date")
        summaryUpdated += "Codex CLI"
    }
}

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
        commandExists("zsh")                    -> "zsh"
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

fun RunContext.macosUpdate() {
    section("macOS software update")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: softwareupdate --install --all")
        bufPrint("[DRY-RUN] Available updates:")
        runProcess("softwareupdate", "--list")
        summaryUpdated += "macOS"
        return
    }

    val result = runCaptured("softwareupdate", "--install", "--all")
    bufPrint(result.output)

    if (result.exitCode != 0) {
        bufPrint("${RED}Warning: macOS update failed (exit ${result.exitCode})$RESET")
        summaryFailed += "macOS"
        return
    }

    if (result.output.contains("restart", ignoreCase = true) ||
        result.output.contains("A restart is required", ignoreCase = true)) {
        bufPrint("${YELLOW}Warning: A system restart is required to complete the macOS update$RESET")
        summaryWarnings += "macOS update requires restart"
        restartRequired.set(true)
    }
    summaryUpdated += "macOS"
}

fun RunContext.masUpdate() {
    section("macOS App Store updates")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: mas upgrade")
        bufPrint("[DRY-RUN] Outdated apps:")
        runProcess("mas", "outdated")
        summaryUpdated += "Mac App Store"
        return
    }

    runProcess("mas", "upgrade") // non-zero is non-fatal
    summaryUpdated += "Mac App Store"
}

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
        bufPrint("[DRY-RUN] Would run: omz update  (or zsh \$ZSH/tools/upgrade.sh)")
        summaryUpdated += "Oh My Zsh"
        return
    }

    val exitCode = if (toolPresent("omz")) {
        bufPrint("Using: omz update")
        runProcess("zsh", "-c", "omz update --unattended")
    } else {
        val upgradeScript = File(zshDir, "tools/upgrade.sh")
        if (!upgradeScript.exists()) {
            bufPrint("Warning: Oh My Zsh upgrade script not found at ${upgradeScript.absolutePath}")
            summaryWarnings += "Oh My Zsh upgrade script missing"
            return
        }
        bufPrint("Using: zsh ${upgradeScript.absolutePath}")
        runProcess("zsh", upgradeScript.absolutePath)
    }

    if (exitCode != 0) {
        bufPrint("Warning: Oh My Zsh update failed (exit $exitCode)")
        summaryWarnings += "Oh My Zsh update failed"
    } else {
        summaryUpdated += "Oh My Zsh"
    }
}
