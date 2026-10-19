package space.marstech.uplink.updaters

import space.marstech.uplink.Colors.GREEN
import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import space.marstech.uplink.Config
import space.marstech.uplink.RunContext
import space.marstech.uplink.captureOutput
import space.marstech.uplink.runCaptured
import space.marstech.uplink.section
import java.io.File

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

    // An unstamped build has no comparable version: any release would look newer and be reinstalled.
    if (Config.UPLINK_VERSION == Config.DEV_VERSION) {
        bufPrint("${YELLOW}Unversioned build (not packaged by Maven) — skipping self-update$RESET")
        summarySkipped += "marstech-uplink self-update (unversioned dev build)"
        return
    }

    val latestTag = fetchLatestReleaseTag()
    if (latestTag == null) {
        bufPrint("${YELLOW}Could not reach GitHub to check for updates$RESET")
        summaryWarnings += "marstech-uplink: could not check for updates (no published release, no network or rate limited)"
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
        // captureOutput merges stderr: on a repo without releases gh prints "release not found".
        if (tag != null && Regex("""v?\d+(\.\d+)*""").matches(tag)) return tag
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
