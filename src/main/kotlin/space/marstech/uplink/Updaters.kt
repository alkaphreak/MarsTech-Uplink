package space.marstech.uplink

import space.marstech.uplink.Colors.GREEN
import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import java.io.File

fun RunContext.brewUpdate() {
    section("Homebrew update")
    if (dryRun) {
        brewDryRun(); return
    }

    // Run 'brew update' first, then 'brew outdated' sequentially to avoid lock contention
    // (both commands share the same lockfile; running them concurrently causes 'already locked')
    val updateResult = runCaptured("brew", "update")
    bufPrint(updateResult.output)

    if (updateResult.exitCode != 0) {
        handleBrewUpdateFailure(updateResult)
        return
    }

    section("Homebrew — outdated packages")
    val outdated = captureOutput("brew", "outdated", "--verbose")
    bufPrint(outdated.takeUnless { it.isNullOrBlank() } ?: "All Homebrew packages are up to date")

    val upgradeExit = brewUpgrade()
    brewCleanupAndDoctor(upgradeExit)
    brewLinkUnlinkedKegs()
    summaryUpdated += "Homebrew"
}

private fun RunContext.brewDryRun() {
    bufPrint("[DRY-RUN] Would run: brew update")
    bufPrint("[DRY-RUN] Would show: brew outdated --verbose")
    bufPrint("[DRY-RUN] Would run: brew upgrade --greedy")
    bufPrint("[DRY-RUN] Would run: brew cleanup -s --prune=all")
    bufPrint("[DRY-RUN] Would run: brew doctor")
    bufPrint("[DRY-RUN] Would link any unlinked kegs")
    summaryUpdated += "Homebrew"
}

private fun RunContext.handleBrewUpdateFailure(result: ProcessResult) {
    val isLockContention = result.output.contains("already locked", ignoreCase = true)
            || result.output.contains("Another `brew update` process", ignoreCase = true)
            || result.output.contains("Another brew update", ignoreCase = true)
    if (isLockContention) {
        bufPrint("${YELLOW}Homebrew: another update process is already running — skipping.$RESET")
        bufPrint("Wait for it to finish, or remove the lock:")
        bufPrint("  rm -f $(brew --prefix)/var/homebrew/locks/update")
        summaryWarnings += "Homebrew skipped: lock contention (another brew update was running)"
    } else {
        bufPrint("${RED}Warning: Homebrew update failed (exit ${result.exitCode})$RESET")
        summaryFailed += "Homebrew"
    }
}

private fun RunContext.brewUpgrade(): Int {
    section("Homebrew upgrade (--greedy)")
    val result = runCaptured("brew", "upgrade", "--greedy")
    bufPrint(result.output)
    if (result.exitCode != 0) {
        bufPrint("${YELLOW}Warning: Some packages failed to upgrade$RESET")
        val failedCasks = parseBrewFailedCasks(result.output)
        if (failedCasks.isNotEmpty())
            summaryWarnings += "Brew casks failed to upgrade: ${failedCasks.joinToString(", ")}"
        else
            summaryWarnings += "Some brew packages failed to upgrade"
    }
    // Surface casks that brew refuses to upgrade as-is (even on exit 0)
    parseBrewCannotUpgradeCasks(result.output).forEach { cask ->
        bufPrint("${YELLOW}Warning: cask '$cask' cannot be upgraded as-is — manual action required$RESET")
        summaryWarnings += "brew: '$cask' cannot be upgraded as-is — run: brew reinstall --cask $cask"
    }
    brewSurfaceDeprecationWarnings(result.output)
    return result.exitCode
}

/**
 * Extracts cask names from a "Error: Problems with multiple casks:" block
 * that brew appends when some cask upgrades fail.
 */
internal fun parseBrewFailedCasks(output: String): List<String> {
    val section = output.substringAfter("Error: Problems with multiple casks:", "")
    if (section.isBlank()) return emptyList()
    return section.lines()
        .filter { it.contains(": It seems") || it.contains(": Failed") }
        .map { it.substringBefore(":").trim() }
        .filter { it.isNotBlank() }
}

/**
 * Extracts cask names from brew's "Warning: The cask '<name>' cannot be upgraded as-is" lines.
 * These appear in the output even when the exit code is 0, so they are easy to miss.
 */
internal fun parseBrewCannotUpgradeCasks(output: String): List<String> =
    Regex("""Warning: The cask '([^']+)' cannot be upgraded as-is""", RegexOption.IGNORE_CASE)
        .findAll(output)
        .map { it.groupValues[1] }
        .toList()

private fun RunContext.brewSurfaceDeprecationWarnings(output: String) {
    val deprecatedPattern = Regex("""Warning:\s+\S+\s+(is deprecated|has been deprecated)""", RegexOption.IGNORE_CASE)
    deprecatedPattern.findAll(output).map { it.value.trim() }.toSet().forEach { msg ->
        bufPrint("${YELLOW}$msg$RESET")
        summaryWarnings += "brew: $msg"
    }
    val eolPattern = Regex("""(\w[\w@.]+)\s+\S+\s+is deprecated because""", RegexOption.IGNORE_CASE)
    eolPattern.findAll(output).map { it.groupValues[1] }.toSet()
        .filter { formula -> summaryWarnings.none { it.contains(formula) } }
        .forEach { formula -> summaryWarnings += "brew deprecated: $formula — consider: brew uninstall $formula" }
}

private fun RunContext.brewCleanupAndDoctor(upgradeExit: Int) {
    section("Homebrew cleanup")
    val result = runCaptured("brew", "cleanup", "-s", "--prune=all")
    // Filter out individual "Removing:" lines — only keep summary and warnings
    val filtered = result.output.lines().filter { line ->
        val t = line.trim()
        !t.startsWith("Removing:") && !t.startsWith("Pruned ")
    }.joinToString("\n")
    if (filtered.isNotBlank()) bufPrint(filtered)
    // Always surface the freed-space summary if present
    result.output.lines()
        .firstOrNull { it.contains("freed approximately") }
        ?.trim()
        ?.let { bufPrint(it) }

    // Always run 'brew doctor' — not just on upgrade failures — to surface deprecated packages
    val doctorSection = if (upgradeExit != 0) "Homebrew doctor (triggered by upgrade failure)" else "Homebrew doctor"
    section(doctorSection)
    val doctorResult = runCaptured("brew", "doctor")
    val doctorOut = doctorResult.output
    // Suppress the "Your system is ready to brew." success line to avoid noise
    val doctorFiltered = doctorOut.lines()
        .filter { !it.trim().equals("Your system is ready to brew.", ignoreCase = true) }
        .joinToString("\n").trim()
    if (doctorFiltered.isNotBlank()) bufPrint(doctorFiltered)

    // Surface deprecated packages found by brew doctor into summaryWarnings
    extractDeprecatedFromDoctorOutput(doctorOut).forEach { name ->
        if (summaryWarnings.none { it.contains(name) })
            summaryWarnings += "brew doctor: '$name' is deprecated — consider: brew uninstall $name"
    }
}

/**
 * Extracts formula/cask names that brew doctor reports as deprecated.
 * Matches lines like: "angry-ip-scanner (cask): Deprecated because ..."
 *                  or "python@3.9: Deprecated because ..."
 */
internal fun extractDeprecatedFromDoctorOutput(output: String): List<String> =
    output.lines()
        .filter { line ->
            val t = line.trim()
            t.isNotBlank()
                && t.contains(": Deprecated", ignoreCase = false)
                && !t.startsWith("Warning:")
                && !t.startsWith("Run ")
        }
        .map { line ->
            line.trim()
                .substringBefore(":")
                .replace(Regex("""\s*\(cask\)\s*"""), "")
                .trim()
                .split(" ").first()
        }
        .filter { it.isNotBlank() }
        .distinct()

private fun RunContext.brewLinkUnlinkedKegs() {
    section("Linking unlinked kegs")
    val kegs = findUnlinkedKegs()
    if (kegs.isEmpty()) {
        bufPrint("No unlinked kegs found")
        return
    }
    bufPrint("Found unlinked kegs: ${kegs.joinToString(", ")}")
    kegs.forEach { keg ->
        bufPrint("Linking $keg...")
        val result = runCaptured("brew", "link", "--overwrite", keg)
        if (result.exitCode != 0) {
            // Detect "is not writable" — typically caused by Docker Desktop owning /usr/local/lib/docker
            val notWritableLine = result.output.lines()
                .firstOrNull { it.contains("is not writable", ignoreCase = true) }
            if (notWritableLine != null) {
                // Extract the offending directory from the error line
                val dir = notWritableLine.trim()
                    .substringBefore(" is not writable").trim()
                    .split(" ").lastOrNull() ?: "the directory"
                bufPrint("${YELLOW}Warning: Cannot link $keg — $dir is not writable$RESET")
                summaryWarnings += "brew link $keg failed: $dir is not writable — fix: sudo chown -R \$(whoami) $dir && brew link --overwrite $keg"
            } else {
                bufPrint("${YELLOW}Warning: Failed to link $keg$RESET")
                summaryWarnings += "Failed to link keg: $keg"
            }
        }
    }
}

private fun RunContext.findUnlinkedKegs(): List<String> = when {
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

private const val CODEX_CLI = "Codex CLI"

fun RunContext.codexUpdate() {
    section("Codex CLI update")

    if (!toolPresent("codex")) {
        bufPrint("Codex CLI not found, skipping")
        summarySkipped += "Codex CLI (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would check if codex is outdated and upgrade")
        summaryUpdated += CODEX_CLI
        return
    }

    val isBrewManaged = runProcess("brew", "list", "--formula", "codex") == 0
            || runProcess("brew", "list", "--cask", "codex") == 0

    if (!isBrewManaged) {
        bufPrint("Codex CLI is not managed by Homebrew, skipping brew upgrade")
        summarySkipped += "Codex CLI (not brew-managed)"
        return
    }

    // codex is a cask, not a formula — must use --cask to avoid "No such keg" error
    if (runProcess("brew", "outdated", "--cask", "codex") == 0) {
        bufPrint("Codex CLI is outdated, upgrading...")
        if (runProcess("brew", "upgrade", "--cask", "codex") != 0) {
            bufPrint("Warning: Failed to upgrade Codex CLI")
            summaryWarnings += "Failed to upgrade Codex CLI"
        } else {
            summaryUpdated += CODEX_CLI
        }
    } else {
        bufPrint("Codex CLI is up to date")
        summaryUpdated += CODEX_CLI
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

private const val CARGO_TOOLS = "Cargo packages"

fun RunContext.cargoUpdate() {
    section("Cargo installed packages update")

    if (!toolPresent("cargo")) {
        bufPrint("cargo not found, skipping")
        summarySkipped += "Cargo (not installed)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: cargo install-update -a")
        bufPrint("[DRY-RUN] Note: requires cargo-update — install with: cargo install cargo-update")
        summaryUpdated += CARGO_TOOLS
        return
    }

    // 'cargo install-update' is a subcommand provided by the cargo-update crate.
    // Its binary is registered as 'cargo-install-update' — check for it explicitly.
    if (!commandExists("cargo-install-update")) {
        bufPrint("${YELLOW}cargo-update not installed — cannot update cargo packages")
        bufPrint("  Install with: cargo install cargo-update$RESET")
        summaryWarnings += "Cargo: install cargo-update to enable package updates — cargo install cargo-update"
        return
    }

    // List installed packages for context (lines not starting with whitespace are package headers)
    val installedCount = captureOutput("cargo", "install", "--list")
        ?.lines()
        ?.count { it.isNotBlank() && !it.startsWith(" ") && !it.startsWith("\t") }
        ?: 0

    if (installedCount == 0) {
        bufPrint("No cargo packages installed, skipping")
        summarySkipped += "Cargo (no packages installed)"
        return
    }

    bufPrint("Installed cargo packages: $installedCount package(s)")

    if (runProcess("cargo", "install-update", "-a") != 0) {
        bufPrint("${YELLOW}Warning: cargo install-update -a reported errors$RESET")
        summaryWarnings += "Some cargo packages failed to update"
    } else {
        summaryUpdated += CARGO_TOOLS
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

fun RunContext.pipUpdate() {
    section("pip upgrade")

    val pipCmd = when {
        toolPresent("pip")  -> "pip"
        toolPresent("pip3") -> "pip3"
        else -> {
            bufPrint("pip / pip3 not found, skipping")
            summarySkipped += "pip (not installed)"
            return
        }
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: $pipCmd install --upgrade pip")
        summaryUpdated += "pip"
        return
    }

    if (runProcess(pipCmd, "install", "--upgrade", "pip") != 0) {
        bufPrint("Warning: pip upgrade failed")
        summaryWarnings += "pip upgrade failed"
    } else {
        summaryUpdated += "pip"
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

// =============================================================================
// Self-update
// =============================================================================

private const val GITHUB_OWNER = "Alkaphreak"
private const val GITHUB_REPO  = "marstech-uplink"

fun RunContext.selfUpdate() {
    section("Self-update check")

    if (dryRun) {
        bufPrint("[DRY-RUN] Current version : ${Config.UPLINK_VERSION}")
        bufPrint("[DRY-RUN] Would check GitHub ($GITHUB_OWNER/$GITHUB_REPO) for a newer version")
        summaryUpdated += "marstech-uplink (self-update — dry-run)"
        return
    }

    val latestTag = fetchLatestReleaseTag()
    if (latestTag == null) {
        bufPrint("${YELLOW}Could not reach GitHub to check for updates$RESET")
        summaryWarnings += "marstech-uplink: could not check for updates (no network or rate limited)"
        return
    }

    val latestVersion = latestTag.trimStart('v')
    bufPrint("Current version : ${Config.UPLINK_VERSION}")
    bufPrint("Latest release  : $latestVersion ($latestTag)")

    if (compareVersions(latestVersion, Config.UPLINK_VERSION) <= 0) {
        bufPrint("marstech-uplink is already at the latest version")
        summarySkipped += "marstech-uplink self-update (already at ${Config.UPLINK_VERSION})"
        return
    }

    val arch = captureOutput("uname", "-m")?.trim() ?: "x86_64"
    val assetSuffix = if (arch == "arm64") "aarch_64" else "x86_64"
    val assetName   = "marstech-uplink-osx-$assetSuffix.zip"
    val downloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$latestTag/$assetName"

    val installPath = detectInstallPath()
    if (installPath == null) {
        bufPrint("${YELLOW}marstech-uplink not found in PATH or ~/.local/bin — skipping self-update$RESET")
        summarySkipped += "marstech-uplink self-update (install path not found)"
        return
    }

    bufPrint("Updating ${Config.UPLINK_VERSION} → $latestVersion  [$arch / $assetName]")
    bufPrint("Install target  : $installPath")

    val tmpZip = File(System.getProperty("java.io.tmpdir"), "marstech-uplink-update-$latestTag.zip")
    val tmpDir = File(System.getProperty("java.io.tmpdir"), "marstech-uplink-update-$latestTag")
    try {
        bufPrint("Downloading $assetName...")
        val dlResult = runCaptured(
            "curl", "-fsSL", "--max-time", "60", "-o", tmpZip.absolutePath, downloadUrl,
            timeoutSeconds = 70
        )
        if (dlResult.exitCode != 0) {
            bufPrint("${RED}Download failed (exit ${dlResult.exitCode})$RESET")
            if (dlResult.output.isNotBlank()) bufPrint(dlResult.output)
            summaryFailed += "marstech-uplink self-update (download failed)"
            return
        }

        tmpDir.mkdirs()
        val unzipResult = runCaptured("unzip", "-o", "-d", tmpDir.absolutePath, tmpZip.absolutePath)
        if (unzipResult.exitCode != 0) {
            bufPrint("${RED}Unzip failed$RESET")
            if (unzipResult.output.isNotBlank()) bufPrint(unzipResult.output)
            summaryFailed += "marstech-uplink self-update (unzip failed)"
            return
        }

        val binaryName = "marstech-uplink-osx-$assetSuffix"
        val newBinary  = tmpDir.walkTopDown().firstOrNull { it.name == binaryName && it.isFile }
        if (newBinary == null) {
            bufPrint("${RED}Binary '$binaryName' not found in downloaded archive$RESET")
            summaryFailed += "marstech-uplink self-update (binary not found in archive)"
            return
        }

        newBinary.setExecutable(true)
        val mvResult = runCaptured("mv", newBinary.absolutePath, installPath)
        if (mvResult.exitCode != 0) {
            bufPrint("${RED}Failed to install new binary at $installPath$RESET")
            bufPrint("Hint: check write permissions — try: sudo chown \$(whoami) $installPath")
            summaryFailed += "marstech-uplink self-update (install failed — permission denied?)"
            return
        }

        bufPrint("${GREEN}Updated: ${Config.UPLINK_VERSION} → $latestVersion$RESET")
        bufPrint("Restart your terminal for the new version to take effect.")
        summaryUpdated += "marstech-uplink ${Config.UPLINK_VERSION} → $latestVersion"
    } finally {
        runCatching { tmpZip.delete() }
        runCatching { tmpDir.deleteRecursively() }
    }
}

private fun RunContext.detectInstallPath(): String? {
    val fromWhich = captureOutput("which", "marstech-uplink")?.trim()
    if (!fromWhich.isNullOrBlank()) return fromWhich
    val fallback = "${Config.HOME}/.local/bin/marstech-uplink"
    return if (File(fallback).exists()) fallback else null
}

private fun RunContext.fetchLatestReleaseTag(): String? {
    // Prefer gh CLI if available — may be authenticated for higher rate limits
    if (toolPresent("gh")) {
        val tag = captureOutput(
            "gh", "release", "view",
            "--repo", "$GITHUB_OWNER/$GITHUB_REPO",
            "--json", "tagName",
            "--jq", ".tagName",
            timeoutSeconds = 10
        )?.trim()
        if (!tag.isNullOrBlank()) return tag
    }
    // Fallback: GitHub REST API via curl (public repo, no auth required)
    val json = captureOutput(
        "curl", "-fsSL", "--max-time", "10",
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest",
        timeoutSeconds = 15
    )
    if (!json.isNullOrBlank()) {
        return Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
    }
    return null
}

/**
 * Compares two dotted version strings (e.g. "1.2.3" vs "1.3.0").
 * Returns positive when [a] > [b], negative when [a] < [b], 0 when equal.
 */
internal fun compareVersions(a: String, b: String): Int {
    val aParts = a.split(".").mapNotNull { it.toIntOrNull() }
    val bParts = b.split(".").mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(aParts.size, bParts.size)
    for (i in 0 until maxLen) {
        val diff = aParts.getOrElse(i) { 0 } - bParts.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}
