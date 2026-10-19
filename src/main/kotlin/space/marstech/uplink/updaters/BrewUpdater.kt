package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.Config
import space.marstech.uplink.ProcessResult
import space.marstech.uplink.RunContext
import space.marstech.uplink.captureOutput
import space.marstech.uplink.runCaptured
import space.marstech.uplink.section

fun RunContext.brewUpdate() {
    section("Homebrew update")
    if (dryRun) {
        brewDryRun(); return
    }

    // Run 'brew update' first, then 'brew outdated' sequentially to avoid lock contention
    // (both commands share the same lockfile; running them concurrently causes 'already locked')
    val updateResult = runCaptured("brew", "update")
    bufPrint(filterAndSummarizeTapImportErrors(updateResult.output))

    if (updateResult.exitCode != 0) {
        handleBrewUpdateFailure(updateResult)
        return
    }

    section("Homebrew — outdated packages")
    val outdated = captureOutput("brew", "outdated", "--verbose")
    bufPrint(outdated.takeUnless { it.isNullOrBlank() } ?: "All Homebrew packages are up to date")

    brewUpgrade()
    brewCleanupAndDoctor()
    brewLinkUnlinkedKegs()
    summaryUpdated += "Homebrew"
}

private fun RunContext.brewDryRun() {
    bufPrint("[DRY-RUN] Would run: brew update")
    bufPrint("[DRY-RUN] Would show: brew outdated --verbose")
    val bc = Config.appConfig.brew
    bufPrint("[DRY-RUN] Would run: brew upgrade --formula${if (bc.skipBuildFromSource) " --force-bottle" else ""}, then brew upgrade --cask --greedy (timeout ${bc.upgradeTimeoutMinutes}m total)")
    if (bc.excludeCasks.isNotEmpty()) bufPrint("[DRY-RUN] Would leave out casks: ${bc.excludeCasks.joinToString(", ")}")
    bufPrint("[DRY-RUN] Would run: brew cleanup -s --prune=all")
    bufPrint("[DRY-RUN] Would run: brew doctor")
    bufPrint("[DRY-RUN] Would link any unlinked kegs")
    summaryUpdated += "Homebrew"
}

/**
 * Collapses repeated "Error: Failed to import: <path>/Formula/<name>.rb" (and the matching
 * "syntax errors found" lines) that a broken third-party tap can spam on every 'brew update'
 * into a single one-line-per-tap summary, instead of flooding the log with dozens of lines.
 */
internal fun RunContext.filterAndSummarizeTapImportErrors(output: String): String {
    val importErrorPattern = Regex("""Error: Failed to import: (.+)/Formula/([^/]+)\.rb""")
    val syntaxErrorPattern = Regex("""^\S+: .*/Formula/[^/]+\.rb:\d+: syntax errors found$""")

    val counts = linkedMapOf<String, Int>()
    val kept = output.lines().filter { line ->
        val importMatch = importErrorPattern.find(line)
        if (importMatch != null) {
            val tapDir = importMatch.groupValues[1].substringAfterLast("/Taps/")
            counts[tapDir] = (counts[tapDir] ?: 0) + 1
            return@filter false
        }
        if (syntaxErrorPattern.matches(line.trim())) return@filter false
        true
    }.joinToString("\n")

    if (counts.isEmpty()) return kept

    val summaryLines = counts.map { (tap, n) -> "brew: tap '$tap' — $n formula(e) ignored due to import errors" }
    summaryLines.forEach { msg ->
        if (summaryWarnings.none { it.contains(tapNameFrom(msg)) }) summaryWarnings += msg
    }
    return (kept.trimEnd('\n') + "\n" + summaryLines.joinToString("\n")).trim()
}

private fun tapNameFrom(summaryMsg: String): String =
    summaryMsg.substringAfter("tap '").substringBefore("'")

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

/**
 * Formulae first, then casks, sharing the one [BrewConfig.upgradeTimeoutMinutes] budget.
 * Split so `exclude_casks` can leave out casks that would wait on an admin password.
 */
private fun RunContext.brewUpgrade(): Int {
    val brewConfig = Config.appConfig.brew
    val budgetSeconds = brewConfig.upgradeTimeoutMinutes * 60L
    val startedAt = System.nanoTime()

    val formulaArgs = buildList {
        add("brew"); add("upgrade"); add("--formula")
        if (brewConfig.skipBuildFromSource) add("--force-bottle")
    }
    section("Homebrew upgrade — formulae${if (brewConfig.skipBuildFromSource) " (--force-bottle)" else ""}")
    // Streamed: a long from-source build must show progress in the log, not one burst at the end.
    val formulaResult = runCaptured(*formulaArgs.toTypedArray(), timeoutSeconds = budgetSeconds, stream = true)
    bufPrint(formulaResult.output, log = false)
    if (formulaResult.exitCode == 124) return brewUpgradeTimedOut(formulaResult.output, casks = false)

    // Empty list = every outdated cask; null = nothing to run.
    val casks = if (brewConfig.excludeCasks.isEmpty()) emptyList() else brewCasksToUpgrade(brewConfig.excludeCasks)
    var result = formulaResult
    if (casks != null) {
        val remaining = budgetSeconds - (System.nanoTime() - startedAt) / 1_000_000_000
        if (remaining <= 0) return brewUpgradeTimedOut(formulaResult.output, casks = false)  // formulae spent the budget
        section("Homebrew upgrade — casks (--greedy)")
        val caskResult = runCaptured(
            "brew", "upgrade", "--cask", "--greedy", *casks.toTypedArray(),
            timeoutSeconds = remaining, stream = true,
        )
        bufPrint(caskResult.output, log = false)
        val combined = formulaResult.output + caskResult.output
        if (caskResult.exitCode == 124) return brewUpgradeTimedOut(combined, casks = true)
        result = ProcessResult(if (formulaResult.exitCode != 0) formulaResult.exitCode else caskResult.exitCode, combined)
    }

    if (result.exitCode != 0) {
        bufPrint("${YELLOW}Warning: Some packages failed to upgrade$RESET")
        val failedCasks = parseBrewFailedCasks(result.output)
        val pinBlocked = parseBrewPinBlocked(result.output)
        if (failedCasks.isNotEmpty())
            summaryWarnings += "Brew casks failed to upgrade: ${failedCasks.joinToString(", ")}"
        if (pinBlocked != null)
            summaryWarnings += "brew: blocked by pins: ${pinBlocked.first.joinToString(", ")} -> " +
                "${pinBlocked.second.size} formulae (${pinBlocked.second.joinToString(", ")})"
        if (failedCasks.isEmpty() && pinBlocked == null)
            summaryWarnings += "Some brew packages failed to upgrade"
    }
    // Surface casks that brew refuses to upgrade as-is (even on exit 0)
    parseBrewCannotUpgradeCasks(result.output).forEach { cask ->
        bufPrint("${YELLOW}Warning: cask '$cask' cannot be upgraded as-is — manual action required$RESET")
        summaryWarnings += "brew: '$cask' cannot be upgraded as-is — run: brew reinstall --cask $cask"
    }
    brewSurfacePostInstallFailures(result.output)
    brewSurfaceDeprecationWarnings(result.output)
    return result.exitCode
}

/**
 * Outdated casks minus [excluded], or null when the cask phase must not run: nothing left,
 * or the outdated list could not be read (running a bare 'brew upgrade --cask' would then
 * pull in the excluded casks too). Excluded casks that are outdated go to summarySkipped.
 */
private fun RunContext.brewCasksToUpgrade(excluded: List<String>): List<String>? {
    val outdated = runCaptured("brew", "outdated", "--cask", "--greedy", "--quiet", timeoutSeconds = 300)
    if (outdated.exitCode != 0) {
        bufPrint("${YELLOW}Warning: could not list outdated casks (exit ${outdated.exitCode}) — skipping cask upgrades$RESET")
        summaryWarnings += "brew: outdated casks could not be listed, cask upgrades skipped"
        return null
    }
    val (skipped, toUpgrade) = partitionExcludedCasks(outdated.output, excluded)
    skipped.forEach { summarySkipped += "brew cask $it (excluded in config — upgrade it by hand: brew upgrade --cask $it)" }
    if (skipped.isNotEmpty()) bufPrint("Excluded by config: ${skipped.joinToString(", ")}")
    if (toUpgrade.isEmpty()) bufPrint("No cask left to upgrade after exclusions")
    return toUpgrade.ifEmpty { null }
}

/**
 * Splits 'brew outdated --quiet' output into (excluded, to upgrade); names may carry a tap prefix.
 * stderr is merged in, so lines that are not a bare token (the Tier 3 platform notice) are dropped.
 */
internal fun partitionExcludedCasks(outdatedOutput: String, excluded: List<String>): Pair<List<String>, List<String>> {
    val token = Regex("""[a-z0-9][\w@.+/-]*""")
    return outdatedOutput.lines().map { it.trim() }.filter { token.matches(it) }
        .partition { it.substringAfterLast('/') in excluded }
}

private fun RunContext.brewUpgradeTimedOut(output: String, casks: Boolean): Int {
    val minutes = Config.appConfig.brew.upgradeTimeoutMinutes
    val last = lastBrewStep(output)
    val stuckOn = last?.let { " (stuck on: $it)" } ?: ""
    if (casks) {
        bufPrint("${RED}Warning: brew cask upgrade timed out after ${minutes}m$stuckOn — a cask install/uninstall may be waiting for an admin password$RESET")
        bufPrint("  Consider adding it to exclude_casks in the [brew] section of config.toml")
    } else {
        bufPrint("${RED}Warning: brew formula upgrade timed out after ${minutes}m$stuckOn — likely building from source (no bottle for this platform)$RESET")
        bufPrint("  Consider setting skip_build_from_source = true in the [brew] section of config.toml")
    }
    summaryWarnings += "brew upgrade timed out after ${minutes}m$stuckOn — some packages may be partially upgraded"
    brewSurfaceDeprecationWarnings(output)
    return 124
}

/** The package of the last "==> Upgrading X" / "==> Installing X" line brew printed. */
internal fun lastBrewStep(output: String): String? {
    val step = Regex("""^==> (?:Upgrading|Installing) (?!\d+ outdated)([A-Za-z0-9][\w@.+/-]*)""")
    return output.lines().asReversed().firstNotNullOfOrNull { step.find(it.trim())?.groupValues?.get(1) }
}

/**
 * Surfaces per-formula/cask failures such as:
 *   "Error: podman: An unsatisfied requirement failed this build."
 *   "Error: antigravity: It seems the App source '/Applications/Antigravity.app' is not there."
 * into summaryWarnings, so they aren't left buried in raw log output.
 */
internal fun RunContext.brewSurfacePostInstallFailures(output: String) {
    val pattern = Regex("""^Error: ([\w@.-]+): (.+)$""")
    output.lines()
        .mapNotNull { pattern.find(it.trim())?.groupValues }
        .filter { (_, name, _) -> name != "Problems with multiple casks" }
        .distinct()
        .forEach { (_, name, reason) ->
            bufPrint("${YELLOW}Warning: '$name' failed — $reason$RESET")
            summaryWarnings += "brew: '$name' failed — $reason"
        }
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
 * (pins, blocked formulae) from brew's refusals, null when there are none:
 *   "Error: You must `brew unpin libssh2 llvm` as installing vim requires the latest version of pinned dependencies."
 */
internal fun parseBrewPinBlocked(output: String): Pair<List<String>, List<String>>? {
    val refusals = Regex("""Error: You must `brew unpin ([^`]+)` as installing (\S+) requires""").findAll(output).toList()
    if (refusals.isEmpty()) return null
    return refusals.flatMap { it.groupValues[1].split(" ") }.distinct() to refusals.map { it.groupValues[2] }.distinct()
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

private fun RunContext.brewCleanupAndDoctor() {
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
    section("Homebrew doctor")
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
        if (result.exitCode == 0) {
            bufPrint("Linked $keg")
        } else {
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
    toolPresent("jq") -> captureOutput(
        "bash", "-c",
        "brew info --json=v1 --installed 2>/dev/null | jq -r '.[] | select(.keg_only == false) | select(.linked_keg == null) | .name' 2>/dev/null"
    )?.lines()?.filter { it.isNotBlank() } ?: emptyList()

    toolPresent("python3") -> captureOutput(
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
